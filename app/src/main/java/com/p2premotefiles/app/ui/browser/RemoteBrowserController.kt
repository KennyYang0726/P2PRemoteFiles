package com.p2premotefiles.app.ui.browser

import com.p2premotefiles.app.domain.model.FileAccessException
import com.p2premotefiles.app.domain.model.FileEntry
import com.p2premotefiles.app.domain.model.FileError
import com.p2premotefiles.app.domain.model.FileFilter
import com.p2premotefiles.app.domain.model.SortDirection
import com.p2premotefiles.app.domain.model.SortField
import com.p2premotefiles.app.domain.model.StorageVolumeInfo
import com.p2premotefiles.app.remote.RemoteFileClient
import com.p2premotefiles.app.remote.RemoteSessionDisconnectedException
import com.p2premotefiles.app.util.PathUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal enum class PaneId {
    LEFT,
    RIGHT,
}

internal enum class BrowserOperationType {
    COPY,
    MOVE,
    DELETE,
    RENAME,
    COMPRESS,
}

internal data class BrowserOperationUiState(
    val type: BrowserOperationType,
    val currentName: String? = null,
    val completedItems: Int = 0,
    val totalItems: Int = 0,
)

internal data class PaneUiState(
    val volumeId: String? = null,
    val volumeRoot: String = "/",
    val currentPath: String = "/",
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val accessError: FileError? = null,
    val selectedPaths: Set<String> = emptySet(),
    val selectionAnchorPath: String? = null,
    val query: String = "",
    val filter: FileFilter = FileFilter.ALL,
    val sortField: SortField = SortField.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
)

internal data class BrowserUiState(
    val initialized: Boolean = false,
    val volumes: List<StorageVolumeInfo> = emptyList(),
    val left: PaneUiState = PaneUiState(),
    val right: PaneUiState = PaneUiState(),
    val activePane: PaneId = PaneId.LEFT,
    val operationRunning: Boolean = false,
    val operation: BrowserOperationUiState? = null,
)

internal sealed interface BrowserEvent {
    data object OperationSuccess : BrowserEvent
    data class OperationFailure(val error: FileError) : BrowserEvent
    data class OperationFailureDetailed(val error: FileError, val detail: String) : BrowserEvent
    data class SessionDisconnected(val detail: String? = null) : BrowserEvent
}

internal class RemoteBrowserController(
    private val client: RemoteFileClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val eventChannel = Channel<BrowserEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    fun initialize() {
        if (_state.value.initialized) return
        scope.launch {
            try {
                val volumes = client.listVolumes()
                val first = volumes.firstOrNull { it.readable } ?: volumes.firstOrNull()
                if (first == null) {
                    _state.value = _state.value.copy(initialized = true, volumes = volumes)
                    return@launch
                }
                val pane = PaneUiState(
                    volumeId = first.id,
                    volumeRoot = PathUtils.normalize(first.rootPath),
                    currentPath = PathUtils.normalize(first.rootPath),
                )
                _state.value = _state.value.copy(
                    initialized = true,
                    volumes = volumes,
                    left = pane,
                    right = pane,
                )
                refresh(PaneId.LEFT)
                refresh(PaneId.RIGHT)
            } catch (error: Throwable) {
                _state.value = _state.value.copy(initialized = true)
                emitFailure(error)
            }
        }
    }

    fun setActivePane(pane: PaneId) {
        _state.value = _state.value.copy(activePane = pane)
    }

    fun refresh(pane: PaneId) {
        val current = paneState(pane)
        if (current.currentPath.isBlank()) return
        updatePane(pane) { it.copy(loading = true, accessError = null) }
        scope.launch {
            try {
                val entries = client.listDirectory(current.currentPath)
                updatePane(pane) { it.copy(entries = entries, loading = false, accessError = null) }
            } catch (error: RemoteSessionDisconnectedException) {
                updatePane(pane) { it.copy(loading = false) }
                eventChannel.send(BrowserEvent.SessionDisconnected(error.cause?.message ?: error.message))
            } catch (error: FileAccessException) {
                updatePane(pane) {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        accessError = error.error,
                        selectedPaths = emptySet(),
                        selectionAnchorPath = null,
                    )
                }
                // 畫面保留「存取失敗」狀態，同時仍用底部Snackbar提示實際錯誤。
                eventChannel.send(BrowserEvent.OperationFailure(error.error))
            } catch (error: Throwable) {
                updatePane(pane) {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        accessError = FileError.UNKNOWN,
                        selectedPaths = emptySet(),
                        selectionAnchorPath = null,
                    )
                }
                emitFailure(error)
            }
        }
    }

    fun openDirectory(pane: PaneId, entry: FileEntry) {
        if (!entry.isDirectory) return
        updatePane(pane) {
            it.copy(
                currentPath = PathUtils.normalize(entry.path),
                entries = emptyList(),
                accessError = null,
                selectedPaths = emptySet(),
                selectionAnchorPath = null,
                query = "",
            )
        }
        setActivePane(pane)
        refresh(pane)
    }


    fun navigateToPath(pane: PaneId, rawPath: String) {
        val normalized = PathUtils.normalize(rawPath.trim())
        if (!normalized.startsWith('/')) {
            scope.launch { eventChannel.send(BrowserEvent.OperationFailure(FileError.INVALID_PATH)) }
            return
        }
        scope.launch {
            try {
                val entry = client.stat(normalized)
                if (!entry.isDirectory) throw FileAccessException(FileError.INVALID_PATH)
                val volume = _state.value.volumes.firstOrNull { volume ->
                    val root = PathUtils.normalize(volume.rootPath)
                    normalized == root || normalized.startsWith("$root/")
                } ?: throw FileAccessException(FileError.INVALID_PATH)
                updatePane(pane) {
                    it.copy(
                        volumeId = volume.id,
                        volumeRoot = PathUtils.normalize(volume.rootPath),
                        currentPath = normalized,
                        entries = emptyList(),
                        accessError = null,
                        selectedPaths = emptySet(),
                        selectionAnchorPath = null,
                        query = "",
                    )
                }
                setActivePane(pane)
                refresh(pane)
            } catch (error: Throwable) {
                emitFailure(error)
            }
        }
    }

    fun navigateUp(pane: PaneId) {
        val current = paneState(pane)
        if (PathUtils.samePath(current.currentPath, current.volumeRoot)) return
        val parent = PathUtils.parent(current.currentPath) ?: return
        val normalizedRoot = PathUtils.normalize(current.volumeRoot)
        val normalizedParent = PathUtils.normalize(parent)
        if (normalizedParent != normalizedRoot && !normalizedParent.startsWith("$normalizedRoot/")) return
        updatePane(pane) {
            it.copy(
                currentPath = normalizedParent,
                entries = emptyList(),
                accessError = null,
                selectedPaths = emptySet(),
                selectionAnchorPath = null,
                query = "",
            )
        }
        setActivePane(pane)
        refresh(pane)
    }

    fun switchVolume(volume: StorageVolumeInfo) {
        val pane = _state.value.activePane
        updatePane(pane) {
            it.copy(
                volumeId = volume.id,
                volumeRoot = PathUtils.normalize(volume.rootPath),
                currentPath = PathUtils.normalize(volume.rootPath),
                selectedPaths = emptySet(),
                selectionAnchorPath = null,
                query = "",
                entries = emptyList(),
                accessError = null,
            )
        }
        refresh(pane)
    }

    fun swapPanes() {
        val current = _state.value
        _state.value = current.copy(left = current.right, right = current.left)
    }

    fun toggleSelection(pane: PaneId, path: String) {
        updatePane(pane) { current ->
            val next = current.selectedPaths.toMutableSet()
            if (!next.add(path)) next.remove(path)
            current.copy(
                selectedPaths = next,
                // 當畫面已沒有任何選取項目時，下一次水平拖曳必須重新建立Shift錨點。
                selectionAnchorPath = if (next.isEmpty()) null else current.selectionAnchorPath,
            )
        }
        setActivePane(pane)
    }

    /**
     * MT管理器式水平拖曳選取：
     * - 奇數次拖曳：建立Shift錨點，並選取該ITEM。
     * - 偶數次拖曳：選取錨點到本次ITEM的整段範圍，完成後立刻清除錨點。
     * - 若目前已沒有任何選取項目，不論先前拖曳次數，一律重新從本次建立錨點。
     * - 普通點擊只做Ctrl式單項增減，不會建立新的Shift錨點。
     */
    fun horizontalRangeSelect(pane: PaneId, path: String, visibleEntries: List<FileEntry>) {
        updatePane(pane) { current ->
            val targetIndex = visibleEntries.indexOfFirst { it.path == path }
            if (targetIndex < 0) return@updatePane current

            val visiblePaths = visibleEntries.asSequence().map { it.path }.toHashSet()
            val hasVisibleSelection = current.selectedPaths.any { it in visiblePaths }
            val effectiveAnchor = current.selectionAnchorPath.takeIf { hasVisibleSelection }
            val anchorIndex = effectiveAnchor
                ?.let { anchor -> visibleEntries.indexOfFirst { it.path == anchor } }
                ?.takeIf { it >= 0 }
            val next = if (hasVisibleSelection) {
                current.selectedPaths.toMutableSet()
            } else {
                // 畫面已沒有任何被選取ITEM時，丟棄舊範圍狀態並重新計算。
                linkedSetOf<String>()
            }

            if (anchorIndex == null) {
                next += path
                current.copy(
                    selectedPaths = next,
                    selectionAnchorPath = path,
                )
            } else {
                val range = if (anchorIndex <= targetIndex) anchorIndex..targetIndex else targetIndex..anchorIndex
                range.forEach { index -> next += visibleEntries[index].path }
                current.copy(
                    selectedPaths = next,
                    // 第二次拖曳完成一組Shift範圍後結束圈選模式。
                    selectionAnchorPath = null,
                )
            }
        }
        setActivePane(pane)
    }

    fun selectAll(pane: PaneId, visibleEntries: List<FileEntry>) {
        updatePane(pane) {
            it.copy(
                selectedPaths = visibleEntries.mapTo(linkedSetOf()) { entry -> entry.path },
                selectionAnchorPath = null,
            )
        }
        setActivePane(pane)
    }

    fun clearSelection(pane: PaneId) {
        updatePane(pane) { it.copy(selectedPaths = emptySet(), selectionAnchorPath = null) }
    }

    fun setQuery(pane: PaneId, query: String) {
        updatePane(pane) { it.copy(query = query) }
    }

    fun setFilter(pane: PaneId, filter: FileFilter) {
        updatePane(pane) { it.copy(filter = filter) }
    }

    fun setSort(pane: PaneId, field: SortField, direction: SortDirection) {
        updatePane(pane) { it.copy(sortField = field, sortDirection = direction) }
    }

    fun copyToOtherPane(sourcePane: PaneId, sourcePaths: List<String>) {
        executeMultiPathOperation(sourcePane, sourcePaths, move = false)
    }

    fun moveToOtherPane(sourcePane: PaneId, sourcePaths: List<String>) {
        executeMultiPathOperation(sourcePane, sourcePaths, move = true)
    }

    fun delete(pane: PaneId, paths: List<String>) {
        if (paths.isEmpty()) return
        beginOperation(BrowserOperationType.DELETE, paths.size)
        scope.launch {
            try {
                paths.forEachIndexed { index, path ->
                    updateOperation(
                        currentName = path.substringAfterLast('/'),
                        completedItems = index,
                    )
                    client.delete(path)
                    updateOperation(completedItems = index + 1)
                }
                clearSelection(pane)
                refresh(PaneId.LEFT)
                refresh(PaneId.RIGHT)
                eventChannel.send(BrowserEvent.OperationSuccess)
            } catch (error: Throwable) {
                emitFailure(error)
            } finally {
                endOperation()
            }
        }
    }

    fun rename(pane: PaneId, sourcePath: String, newName: String) {
        val sanitized = newName.trim()
        if (sanitized.isBlank() || sanitized.contains('/')) {
            scope.launch { eventChannel.send(BrowserEvent.OperationFailure(FileError.INVALID_PATH)) }
            return
        }
        val parent = PathUtils.parent(sourcePath) ?: return
        val destination = PathUtils.child(parent, sanitized)
        beginOperation(
            type = BrowserOperationType.RENAME,
            totalItems = 1,
            currentName = sourcePath.substringAfterLast('/'),
        )
        scope.launch {
            try {
                client.rename(sourcePath, destination)
                updateOperation(currentName = sanitized, completedItems = 1)
                clearSelection(pane)
                refresh(pane)
                eventChannel.send(BrowserEvent.OperationSuccess)
            } catch (error: Throwable) {
                emitFailure(error)
            } finally {
                endOperation()
            }
        }
    }

    fun compress(
        sourcePane: PaneId,
        sourcePaths: List<String>,
        destination: String,
        storeOnly: Boolean,
    ) {
        if (sourcePaths.isEmpty()) return
        beginOperation(
            type = BrowserOperationType.COMPRESS,
            totalItems = 0,
            currentName = destination.substringAfterLast('/'),
        )
        scope.launch {
            try {
                client.compress(
                    paths = sourcePaths,
                    destination = destination,
                    storeOnly = storeOnly,
                )
                clearSelection(sourcePane)
                refresh(PaneId.LEFT)
                refresh(PaneId.RIGHT)
                eventChannel.send(BrowserEvent.OperationSuccess)
            } catch (error: Throwable) {
                emitFailure(error)
            } finally {
                endOperation()
            }
        }
    }

    fun sameDirectory(): Boolean = PathUtils.samePath(_state.value.left.currentPath, _state.value.right.currentPath)

    private fun executeMultiPathOperation(sourcePane: PaneId, sourcePaths: List<String>, move: Boolean) {
        if (sourcePaths.isEmpty()) return
        val destinationPane = if (sourcePane == PaneId.LEFT) _state.value.right else _state.value.left
        if (move && sameDirectory()) return
        beginOperation(
            type = if (move) BrowserOperationType.MOVE else BrowserOperationType.COPY,
            totalItems = sourcePaths.size,
        )
        scope.launch {
            try {
                sourcePaths.forEachIndexed { index, source ->
                    val name = source.substringAfterLast('/')
                    val destination = PathUtils.child(destinationPane.currentPath, name)
                    updateOperation(
                        currentName = name,
                        completedItems = index,
                    )
                    if (move) client.move(source, destination) else client.copy(source, destination)
                    updateOperation(completedItems = index + 1)
                }
                clearSelection(sourcePane)
                refresh(PaneId.LEFT)
                refresh(PaneId.RIGHT)
                eventChannel.send(BrowserEvent.OperationSuccess)
            } catch (error: Throwable) {
                emitFailure(error)
            } finally {
                endOperation()
            }
        }
    }

    private fun beginOperation(
        type: BrowserOperationType,
        totalItems: Int,
        currentName: String? = null,
    ) {
        _state.value = _state.value.copy(
            operationRunning = true,
            operation = BrowserOperationUiState(
                type = type,
                currentName = currentName,
                completedItems = 0,
                totalItems = totalItems,
            ),
        )
    }

    private fun updateOperation(
        currentName: String? = null,
        completedItems: Int? = null,
    ) {
        val current = _state.value.operation ?: return
        _state.value = _state.value.copy(
            operation = current.copy(
                currentName = currentName ?: current.currentName,
                completedItems = completedItems ?: current.completedItems,
            ),
        )
    }

    private fun endOperation() {
        _state.value = _state.value.copy(
            operationRunning = false,
            operation = null,
        )
    }

    private suspend fun emitFailure(error: Throwable) {
        when (error) {
            is RemoteSessionDisconnectedException -> {
                eventChannel.send(BrowserEvent.SessionDisconnected(error.cause?.message ?: error.message))
            }
            is FileAccessException -> {
                val detail = error.detail?.takeIf { it.isNotBlank() }
                if (detail != null) {
                    eventChannel.send(BrowserEvent.OperationFailureDetailed(error.error, detail))
                } else {
                    eventChannel.send(BrowserEvent.OperationFailure(error.error))
                }
            }
            else -> eventChannel.send(BrowserEvent.OperationFailure(FileError.UNKNOWN))
        }
    }

    private fun paneState(pane: PaneId): PaneUiState = if (pane == PaneId.LEFT) _state.value.left else _state.value.right

    private fun updatePane(pane: PaneId, transform: (PaneUiState) -> PaneUiState) {
        val current = _state.value
        _state.value = if (pane == PaneId.LEFT) {
            current.copy(left = transform(current.left))
        } else {
            current.copy(right = transform(current.right))
        }
    }
}

internal fun PaneUiState.visibleEntries(): List<FileEntry> {
    val filtered = entries.asSequence()
        .filter { entry ->
            when (filter) {
                FileFilter.ALL -> true
                FileFilter.FOLDERS -> entry.isDirectory
                FileFilter.FILES -> !entry.isDirectory
            }
        }
        .filter { entry -> query.isBlank() || entry.name.contains(query, ignoreCase = true) }
        .toList()

    val baseComparator = when (sortField) {
        SortField.NAME -> compareBy<FileEntry> { it.name.lowercase() }
        SortField.MODIFIED -> compareBy { it.modifiedAt }
        SortField.SIZE -> compareBy { it.size }
        SortField.TYPE -> compareBy<FileEntry> { it.category.ordinal }.thenBy { it.name.lowercase() }
    }
    val orderedComparator = if (sortDirection == SortDirection.ASCENDING) baseComparator else baseComparator.reversed()
    return filtered.sortedWith(
        compareByDescending<FileEntry> { it.isDirectory }.then(orderedComparator),
    )
}
