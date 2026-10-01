package com.p2premotefiles.app.ui.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p2premotefiles.app.R
import com.p2premotefiles.app.data.filesystem.MimeClassifier
import com.p2premotefiles.app.domain.model.FileAccessException
import com.p2premotefiles.app.domain.model.FileCategory
import com.p2premotefiles.app.domain.model.FileEntry
import com.p2premotefiles.app.domain.model.FileError
import com.p2premotefiles.app.domain.model.FileFilter
import com.p2premotefiles.app.domain.model.FilePermissions
import com.p2premotefiles.app.domain.model.SortDirection
import com.p2premotefiles.app.domain.model.SortField
import com.p2premotefiles.app.domain.model.StorageKind
import com.p2premotefiles.app.domain.model.StorageVolumeInfo
import com.p2premotefiles.app.remote.RemoteFileClient
import com.p2premotefiles.app.remote.RemoteSessionDisconnectedException
import com.p2premotefiles.app.util.DisplayFormatters
import com.p2premotefiles.app.util.PathUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.abs
import kotlin.math.roundToInt

private data class ContextRequest(
    val pane: PaneId,
    val entry: FileEntry,
    val anchor: Offset,
)

private data class PendingTransfer(
    val pane: PaneId,
    val paths: List<String>,
    val move: Boolean,
    val destination: String,
)

private data class PendingDelete(
    val pane: PaneId,
    val paths: List<String>,
    val displayName: String,
)

private data class PendingCompression(
    val pane: PaneId,
    val paths: List<String>,
    val destinationDirectory: String,
    val defaultFileName: String,
)

private data class TransferProgress(
    val label: String,
    val current: Long,
    val total: Long,
)

private enum class TransferDirection {
    UPLOAD,
    DOWNLOAD,
}

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteBrowserScreen(
    client: RemoteFileClient,
    sessionLabel: String,
    thumbnailsEnabled: Boolean,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val controller = remember(client) { RemoteBrowserController(client, scope) }
    val state by controller.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var drawerOpen by remember { mutableStateOf(false) }
    val thumbnailCache = remember(client) { ThumbnailMemoryCache(client) }

    var overflowExpanded by remember { mutableStateOf(false) }
    var contextRequest by remember { mutableStateOf<ContextRequest?>(null) }
    var pendingTransfer by remember { mutableStateOf<PendingTransfer?>(null) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var pendingCompression by remember { mutableStateOf<PendingCompression?>(null) }
    var renameTarget by remember { mutableStateOf<Pair<PaneId, FileEntry>?>(null) }
    var propertiesTarget by remember { mutableStateOf<FileEntry?>(null) }
    var propertiesPermissions by remember { mutableStateOf<FilePermissions?>(null) }
    var permissionsEditorTarget by remember { mutableStateOf<Pair<FileEntry, FilePermissions>?>(null) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var pathEditPane by remember { mutableStateOf<PaneId?>(null) }
    var pendingDownloadPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var transferProgress by remember { mutableStateOf<TransferProgress?>(null) }
    var transferRunning by remember { mutableStateOf(false) }
    var transferDirection by remember { mutableStateOf<TransferDirection?>(null) }
    var operationError by remember { mutableStateOf<Pair<FileError, String?>?>(null) }
    var sessionDisconnected by remember { mutableStateOf(false) }
    var lastBackPressAt by remember { mutableLongStateOf(0L) }

    val activePaneState = if (state.activePane == PaneId.LEFT) state.left else state.right
    val activeVolume = state.volumes.firstOrNull { it.id == activePaneState.volumeId }
    val activeVisibleEntries = activePaneState.visibleEntries()

    fun markSessionDisconnected() {
        drawerOpen = false
        contextRequest = null
        pendingTransfer = null
        pendingDelete = null
        pendingCompression = null
        renameTarget = null
        propertiesTarget = null
        permissionsEditorTarget = null
        showSearchDialog = false
        showFilterDialog = false
        showSortDialog = false
        pathEditPane = null
        operationError = null
        sessionDisconnected = true
    }

    val copyPropertyValue: (String, String) -> Unit = { label, value ->
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    LaunchedEffect(propertiesTarget?.path) {
        val target = propertiesTarget
        propertiesPermissions = if (target == null) {
            null
        } else {
            runCatching { client.permissions(target.path) }.getOrNull()
        }
    }

    BackHandler(enabled = drawerOpen) {
        drawerOpen = false
    }

    BackHandler(
        enabled = !drawerOpen &&
            contextRequest == null && pendingTransfer == null && pendingDelete == null && pendingCompression == null &&
            renameTarget == null && propertiesTarget == null && permissionsEditorTarget == null &&
            !showSearchDialog && !showFilterDialog && !showSortDialog && pathEditPane == null &&
            !sessionDisconnected,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPressAt <= 1_000L) {
            onExit()
        } else {
            lastBackPressAt = now
            scope.launch {
                snackbar.showSnackbar(
                    message = context.getString(R.string.press_back_again_to_exit),
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    val downloadDirectoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val paths = pendingDownloadPaths
        pendingDownloadPaths = emptyList()
        if (treeUri != null && paths.isNotEmpty()) {
            scope.launch {
                transferDirection = TransferDirection.DOWNLOAD
                transferRunning = true
                try {
                    downloadPathsToTree(context, client, paths, treeUri) { label, current, total ->
                        transferProgress = TransferProgress(label, current, total)
                    }
                    snackbar.showSnackbar(context.getString(R.string.transfer_download_complete))
                } catch (_: Throwable) {
                    snackbar.showSnackbar(context.getString(R.string.transfer_failed))
                } finally {
                    transferRunning = false
                    transferProgress = null
                    transferDirection = null
                }
            }
        }
    }

    val uploadFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            val destination = activePaneState.currentPath
            scope.launch {
                transferDirection = TransferDirection.UPLOAD
                transferRunning = true
                try {
                    uploadUris(context, client, uris, destination) { label, current, total ->
                        transferProgress = TransferProgress(label, current, total)
                    }
                    controller.refresh(state.activePane)
                    snackbar.showSnackbar(context.getString(R.string.transfer_upload_complete))
                } catch (_: Throwable) {
                    snackbar.showSnackbar(context.getString(R.string.transfer_failed))
                } finally {
                    transferRunning = false
                    transferProgress = null
                    transferDirection = null
                }
            }
        }
    }

    LaunchedEffect(controller) {
        controller.initialize()
    }

    LaunchedEffect(controller, snackbar) {
        controller.events.collect { event ->
            when (event) {
                BrowserEvent.OperationSuccess -> snackbar.showSnackbar(
                    message = context.getString(R.string.operation_success),
                )
                is BrowserEvent.OperationFailure -> {
                    snackbar.showSnackbar(message = context.getString(errorMessageRes(event.error)))
                }
                is BrowserEvent.OperationFailureDetailed -> {
                    operationError = event.error to event.detail
                }
                is BrowserEvent.SessionDisconnected -> markSessionDisconnected()
            }
        }
    }

    LaunchedEffect(client) {
        while (isActive && !sessionDisconnected) {
            delay(4_000L)
            try {
                client.healthCheck()
            } catch (_: RemoteSessionDisconnectedException) {
                markSessionDisconnected()
                break
            } catch (_: Throwable) {
                // 暫時性非連線錯誤不應誤判Session中斷。
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pathEditPane = state.activePane }
                                .padding(vertical = 2.dp),
                        ) {
                            Text(
                                text = activePaneState.currentPath.ensureTrailingSlash(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = paneSummary(activePaneState, activeVolume),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { drawerOpen = true }) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_storage_drawer))
                        }
                    },
                    actions = {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.refresh)) },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                onClick = {
                                    overflowExpanded = false
                                    controller.refresh(state.activePane)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.search)) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                onClick = {
                                    overflowExpanded = false
                                    showSearchDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.select_all)) },
                                leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null) },
                                onClick = {
                                    overflowExpanded = false
                                    controller.selectAll(state.activePane, activeVisibleEntries)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.filter)) },
                                leadingIcon = { Icon(Icons.Default.FilterAlt, contentDescription = null) },
                                onClick = {
                                    overflowExpanded = false
                                    showFilterDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort)) },
                                leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null) },
                                onClick = {
                                    overflowExpanded = false
                                    showSortDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.upload_to_current_directory)) },
                                leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                                enabled = !transferRunning && !state.operationRunning,
                                onClick = {
                                    overflowExpanded = false
                                    uploadFilesLauncher.launch(arrayOf("*/*"))
                                },
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.swap_panes)) },
                                leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                                onClick = {
                                    overflowExpanded = false
                                    controller.swapPanes()
                                },
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    overflowExpanded = false
                                    onOpenSettings()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.exit_session)) },
                                leadingIcon = { Icon(Icons.Default.ExitToApp, contentDescription = null) },
                                onClick = {
                                    overflowExpanded = false
                                    onExit()
                                },
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    RemotePane(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        pane = state.left,
                        volume = state.volumes.firstOrNull { it.id == state.left.volumeId },
                        active = state.activePane == PaneId.LEFT,
                        operationRunning = state.operationRunning,
                        thumbnailsEnabled = thumbnailsEnabled,
                        thumbnailCache = thumbnailCache,
                        onActivate = { controller.setActivePane(PaneId.LEFT) },
                        onRefresh = { controller.refresh(PaneId.LEFT) },
                        onNavigateUp = { controller.navigateUp(PaneId.LEFT) },
                        onOpenDirectory = { controller.openDirectory(PaneId.LEFT, it) },
                        onToggleSelection = { controller.toggleSelection(PaneId.LEFT, it.path) },
                        onHorizontalSelect = { entry, entries ->
                            controller.horizontalRangeSelect(PaneId.LEFT, entry.path, entries)
                        },
                        onContext = { entry, anchor -> contextRequest = ContextRequest(PaneId.LEFT, entry, anchor) },
                    )
                    VerticalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                    RemotePane(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        pane = state.right,
                        volume = state.volumes.firstOrNull { it.id == state.right.volumeId },
                        active = state.activePane == PaneId.RIGHT,
                        operationRunning = state.operationRunning,
                        thumbnailsEnabled = thumbnailsEnabled,
                        thumbnailCache = thumbnailCache,
                        onActivate = { controller.setActivePane(PaneId.RIGHT) },
                        onRefresh = { controller.refresh(PaneId.RIGHT) },
                        onNavigateUp = { controller.navigateUp(PaneId.RIGHT) },
                        onOpenDirectory = { controller.openDirectory(PaneId.RIGHT, it) },
                        onToggleSelection = { controller.toggleSelection(PaneId.RIGHT, it.path) },
                        onHorizontalSelect = { entry, entries ->
                            controller.horizontalRangeSelect(PaneId.RIGHT, entry.path, entries)
                        },
                        onContext = { entry, anchor -> contextRequest = ContextRequest(PaneId.RIGHT, entry, anchor) },
                    )
                }
            }
        }


        AnimatedVisibility(
            visible = drawerOpen,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            // 手勢抽屜維持停用；遮罩只接受點擊來收合。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable { drawerOpen = false },
            )
        }
        AnimatedVisibility(
            visible = drawerOpen,
            modifier = Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it }),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(252.dp),
                shadowElevation = 12.dp,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.storage_spaces),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                    state.volumes.forEach { volume ->
                        val selected = volume.id == activePaneState.volumeId
                        NavigationDrawerItem(
                            label = {
                                Column {
                                    Text(volume.label)
                                    Text(
                                        text = stringResource(
                                            if (volume.writable) R.string.storage_read_write else R.string.storage_read_only,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            selected = selected,
                            onClick = {
                                if (volume.readable) {
                                    controller.switchVolume(volume)
                                    drawerOpen = false
                                }
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(storageIcon(volume.kind)),
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    if (state.volumes.isEmpty()) {
                        Text(
                            text = stringResource(R.string.storage_unavailable),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    state.operation?.let { operation ->
        BlockingFileOperationDialog(operation = operation)
    }

    if (transferRunning) {
        BlockingTransferDialog(
            direction = transferDirection,
            progress = transferProgress,
        )
    }

    contextRequest?.let { request ->
        val pane = if (request.pane == PaneId.LEFT) state.left else state.right
        val targets = if (request.entry.path in pane.selectedPaths && pane.selectedPaths.isNotEmpty()) {
            pane.selectedPaths.toList()
        } else {
            listOf(request.entry.path)
        }
        ContextActionMenu(
            request = request,
            targets = targets,
            sameDirectory = controller.sameDirectory(),
            operationRunning = state.operationRunning,
            onDismiss = { contextRequest = null },
            onCopy = {
                pendingTransfer = PendingTransfer(
                    pane = request.pane,
                    paths = targets,
                    move = false,
                    destination = if (request.pane == PaneId.LEFT) state.right.currentPath else state.left.currentPath,
                )
                contextRequest = null
            },
            onMove = {
                pendingTransfer = PendingTransfer(
                    pane = request.pane,
                    paths = targets,
                    move = true,
                    destination = if (request.pane == PaneId.LEFT) state.right.currentPath else state.left.currentPath,
                )
                contextRequest = null
            },
            onDownload = {
                contextRequest = null
                pendingDownloadPaths = targets
                downloadDirectoryLauncher.launch(null)
            },
            onDelete = {
                pendingDelete = PendingDelete(request.pane, targets, request.entry.name)
                contextRequest = null
            },
            onRename = {
                renameTarget = request.pane to request.entry
                contextRequest = null
            },
            onCompress = {
                // 壓縮檔固定建立在來源ITEM所在欄位的目前目錄，不使用另一欄作為目的地。
                val destinationDirectory = if (request.pane == PaneId.LEFT) {
                    state.left.currentPath
                } else {
                    state.right.currentPath
                }
                val defaultName = if (targets.size == 1) {
                    request.entry.name.trimEnd('/') + ".zip"
                } else {
                    context.getString(R.string.archive_default_name)
                }
                pendingCompression = PendingCompression(
                    pane = request.pane,
                    paths = targets,
                    destinationDirectory = destinationDirectory,
                    defaultFileName = defaultName,
                )
                contextRequest = null
            },
            onProperties = {
                propertiesTarget = request.entry
                contextRequest = null
            },
        )
    }

    pendingTransfer?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingTransfer = null },
            title = {
                Text(stringResource(if (pending.move) R.string.move_confirm_title else R.string.copy_confirm_title))
            },
            text = {
                Text(
                    stringResource(
                        if (pending.move) R.string.move_confirm_message else R.string.copy_confirm_message,
                        pending.paths.size,
                        pending.destination,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pending.move) {
                            controller.moveToOtherPane(pending.pane, pending.paths)
                        } else {
                            controller.copyToOtherPane(pending.pane, pending.paths)
                        }
                        pendingTransfer = null
                    },
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTransfer = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = {
                Text(
                    if (pending.paths.size == 1) {
                        stringResource(R.string.delete_single_message, pending.displayName)
                    } else {
                        stringResource(R.string.delete_multiple_message, pending.paths.size)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.delete(pending.pane, pending.paths)
                        pendingDelete = null
                    },
                ) { Text(stringResource(R.string.delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    pendingCompression?.let { pending ->
        CompressionDialog(
            initialFileName = pending.defaultFileName,
            onDismiss = { pendingCompression = null },
            onConfirm = { fileName, storeOnly ->
                val normalizedName = fileName.trim().let { name ->
                    if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
                }
                val destination = PathUtils.child(pending.destinationDirectory, normalizedName)
                controller.compress(
                    sourcePane = pending.pane,
                    sourcePaths = pending.paths,
                    destination = destination,
                    storeOnly = storeOnly,
                )
                pendingCompression = null
            },
        )
    }

    renameTarget?.let { (pane, entry) ->
        RenameDialog(
            entry = entry,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                controller.rename(pane, entry.path, name)
                renameTarget = null
            },
        )
    }

    propertiesTarget?.let { entry ->
        PropertiesDialog(
            entry = entry,
            permissions = propertiesPermissions,
            onCopy = copyPropertyValue,
            onEditPermissions = { permissions ->
                permissionsEditorTarget = entry to permissions
                propertiesTarget = null
            },
            onDismiss = {
                propertiesTarget = null
                propertiesPermissions = null
            },
        )
    }

    permissionsEditorTarget?.let { (entry, permissions) ->
        PermissionsDialog(
            entry = entry,
            initial = permissions,
            onDismiss = { permissionsEditorTarget = null },
            onConfirm = { updatedMode, applyToFiles, applyToDirectories ->
                permissionsEditorTarget = null
                scope.launch {
                    try {
                        client.setPermissions(
                            path = entry.path,
                            mode = updatedMode,
                            applyToFiles = applyToFiles,
                            applyToDirectories = applyToDirectories,
                        )
                        controller.refresh(PaneId.LEFT)
                        controller.refresh(PaneId.RIGHT)
                        snackbar.showSnackbar(context.getString(R.string.permissions_updated))
                    } catch (error: FileAccessException) {
                        val detail = error.detail?.takeIf { it.isNotBlank() }
                        snackbar.showSnackbar(
                            if (detail != null) {
                                context.getString(R.string.permissions_update_failed_detail, detail)
                            } else {
                                context.getString(R.string.permissions_update_failed)
                            },
                        )
                    } catch (_: Throwable) {
                        snackbar.showSnackbar(context.getString(R.string.permissions_update_failed))
                    }
                }
            },
        )
    }

    operationError?.let { error ->
        AlertDialog(
            onDismissRequest = { operationError = null },
            title = { Text(stringResource(R.string.error_information_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(errorMessageRes(error.first)))
                    error.second?.takeIf { it.isNotBlank() }?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { operationError = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    if (sessionDisconnected) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.remote_session_disconnected_title)) },
            text = { Text(stringResource(R.string.remote_session_disconnected_message)) },
            confirmButton = {
                TextButton(onClick = onExit) {
                    Text(stringResource(R.string.return_to_home))
                }
            },
        )
    }

    pathEditPane?.let { paneId ->
        val pane = if (paneId == PaneId.LEFT) state.left else state.right
        PathDialog(
            initialPath = pane.currentPath,
            onDismiss = { pathEditPane = null },
            onConfirm = { path ->
                controller.navigateToPath(paneId, path)
                pathEditPane = null
            },
        )
    }

    if (showSearchDialog) {
        SearchDialog(
            initialQuery = activePaneState.query,
            onDismiss = { showSearchDialog = false },
            onConfirm = {
                controller.setQuery(state.activePane, it)
                showSearchDialog = false
            },
        )
    }

    if (showFilterDialog) {
        FilterDialog(
            current = activePaneState.filter,
            onDismiss = { showFilterDialog = false },
            onSelect = {
                controller.setFilter(state.activePane, it)
                showFilterDialog = false
            },
        )
    }

    if (showSortDialog) {
        SortDialog(
            field = activePaneState.sortField,
            direction = activePaneState.sortDirection,
            onDismiss = { showSortDialog = false },
            onApply = { field, direction ->
                controller.setSort(state.activePane, field, direction)
                showSortDialog = false
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemotePane(
    modifier: Modifier,
    pane: PaneUiState,
    volume: StorageVolumeInfo?,
    active: Boolean,
    operationRunning: Boolean,
    thumbnailsEnabled: Boolean,
    thumbnailCache: ThumbnailMemoryCache,
    onActivate: () -> Unit,
    onRefresh: () -> Unit,
    onNavigateUp: () -> Unit,
    onOpenDirectory: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    onHorizontalSelect: (FileEntry, List<FileEntry>) -> Unit,
    onContext: (FileEntry, Offset) -> Unit,
) {
    val entries = pane.visibleEntries()
    val listState = rememberLazyListState()
    val activeBorder = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = modifier
            .border(1.dp, activeBorder)
            .pointerInput(onActivate) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onActivate()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        PullToRefreshFileList(
            listState = listState,
            refreshing = pane.loading,
            onRefresh = {
                onActivate()
                onRefresh()
            },
        ) { listModifier ->
            Box(modifier = listModifier) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "parent-directory") {
                        ParentDirectoryRow(
                            enabled = !PathUtils.samePath(pane.currentPath, pane.volumeRoot),
                            onClick = {
                                onActivate()
                                onNavigateUp()
                            },
                        )
                        Divider()
                    }
                    itemsIndexed(
                        items = entries,
                        key = { _, item -> item.path },
                    ) { _, entry ->
                        FileRow(
                            entry = entry,
                            selected = entry.path in pane.selectedPaths,
                            selectionMode = pane.selectedPaths.isNotEmpty(),
                            operationRunning = operationRunning,
                            thumbnailsEnabled = thumbnailsEnabled,
                            thumbnailCache = thumbnailCache,
                            onClick = {
                                onActivate()
                                if (pane.selectedPaths.isNotEmpty()) {
                                    onToggleSelection(entry)
                                } else if (entry.isDirectory) {
                                    onOpenDirectory(entry)
                                }
                            },
                            onHorizontalSelect = {
                                onActivate()
                                onHorizontalSelect(entry, entries)
                            },
                            onLongClick = { anchor ->
                                onActivate()
                                onContext(entry, anchor)
                            },
                        )
                        Divider()
                    }
                }

                if (entries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(
                                when {
                                    pane.loading -> R.string.loading
                                    pane.accessError != null -> R.string.directory_access_failed
                                    else -> R.string.empty_directory
                                },
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ParentDirectoryRow(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_file_folder),
            contentDescription = stringResource(R.string.parent_directory),
            tint = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(34.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.parent_directory_item),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun paneSummary(pane: PaneUiState, volume: StorageVolumeInfo?): String {
    return if (volume != null && volume.totalBytes > 0L) {
        val used = (volume.totalBytes - volume.availableBytes).coerceAtLeast(0L)
        stringResource(
            R.string.pane_directory_summary_with_storage,
            pane.entries.count { it.isDirectory },
            pane.entries.count { !it.isDirectory },
            pane.selectedPaths.size,
            DisplayFormatters.fileSize(LocalContext.current, used),
            DisplayFormatters.fileSize(LocalContext.current, volume.totalBytes),
        )
    } else {
        stringResource(
            R.string.pane_directory_summary,
            pane.entries.count { it.isDirectory },
            pane.entries.count { !it.isDirectory },
            pane.selectedPaths.size,
        )
    }
}

@Composable
private fun PullToRefreshFileList(
    listState: LazyListState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 96.dp.toPx() }
    val maxPullPx = thresholdPx * 1.45f
    var pullDistancePx by remember { mutableFloatStateOf(0f) }
    var gestureStartedAtTop by remember { mutableStateOf(false) }

    val connection = remember(listState, refreshing, onRefresh, thresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && pullDistancePx > 0f) {
                    val consumed = available.y.coerceAtLeast(-pullDistancePx)
                    pullDistancePx = (pullDistancePx + consumed).coerceAtLeast(0f)
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (!refreshing && gestureStartedAtTop && atTop && available.y > 0f) {
                    val consumedY = available.y
                    pullDistancePx = (pullDistancePx + consumedY * 0.45f).coerceAtMost(maxPullPx)
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(listState) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    gestureStartedAtTop = listState.firstVisibleItemIndex == 0 &&
                        listState.firstVisibleItemScrollOffset == 0
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                    } while (event.changes.any { it.pressed })
                    val shouldRefresh = gestureStartedAtTop && pullDistancePx >= thresholdPx
                    pullDistancePx = 0f
                    gestureStartedAtTop = false
                    if (shouldRefresh && !refreshing) onRefresh()
                }
            }
            .nestedScroll(connection),
    ) {
        content(Modifier.fillMaxSize())

        if (pullDistancePx > 0f || refreshing) {
            val progress = if (refreshing) 1f else (pullDistancePx / thresholdPx).coerceIn(0f, 1f)
            val restingOffsetPx = with(density) { 10.dp.toPx() }
            val indicatorOffsetPx = if (refreshing && pullDistancePx <= 0f) {
                restingOffsetPx
            } else {
                (pullDistancePx * 0.48f - with(density) { 22.dp.toPx() })
                    .coerceAtLeast(-with(density) { 18.dp.toPx() })
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, indicatorOffsetPx.roundToInt()) }
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.inverseSurface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.pull_to_refresh),
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = if (refreshing) 180f else progress * 180f },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    operationRunning: Boolean,
    thumbnailsEnabled: Boolean,
    thumbnailCache: ThumbnailMemoryCache,
    onClick: () -> Unit,
    onHorizontalSelect: () -> Unit,
    onLongClick: (Offset) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var rowPosition by remember(entry.path) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val selectionThresholdPx = with(density) { 36.dp.toPx() }
    val maxOffsetPx = with(density) { 64.dp.toPx() }
    val swipeOffset = remember(entry.path) { Animatable(0f) }
    val selectedBackground = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val rowBackground = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(selectedBackground),
    ) {
        if (abs(swipeOffset.value) > 1f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp),
                contentAlignment = if (swipeOffset.value > 0f) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.horizontal_select),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .background(rowBackground)
                .onGloballyPositioned { coordinates ->
                    rowPosition = coordinates.positionInWindow()
                }
                .pointerInput(entry.path, operationRunning) {
                    if (operationRunning) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var total = Offset.Zero
                        var horizontalDrag = false
                        var cancelledByVerticalScroll = false
                        val touchSlop = viewConfiguration.touchSlop

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (horizontalDrag && abs(total.x) >= selectionThresholdPx) {
                                    onHorizontalSelect()
                                }
                                scope.launch { swipeOffset.animateTo(0f) }
                                break
                            }

                            val delta = change.positionChange()
                            total += delta
                            if (!horizontalDrag && !cancelledByVerticalScroll) {
                                if (abs(total.x) >= touchSlop && abs(total.x) > abs(total.y) * 1.2f) {
                                    horizontalDrag = true
                                } else if (abs(total.y) >= touchSlop && abs(total.y) > abs(total.x)) {
                                    cancelledByVerticalScroll = true
                                }
                            }

                            if (horizontalDrag) {
                                scope.launch { swipeOffset.snapTo(total.x.coerceIn(-maxOffsetPx, maxOffsetPx)) }
                                change.consume()
                            } else if (cancelledByVerticalScroll) {
                                if (swipeOffset.value != 0f) {
                                    scope.launch { swipeOffset.snapTo(0f) }
                                }
                                break
                            }
                        }
                    }
                }
                .combinedClickable(
                    enabled = !operationRunning,
                    onClick = onClick,
                    onLongClick = { onLongClick(rowPosition) },
                )
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileVisual(
                entry = entry,
                thumbnailsEnabled = thumbnailsEnabled,
                thumbnailCache = thumbnailCache,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (entry.isDirectory) FontWeight.Medium else FontWeight.Normal,
                )
                if (!entry.isDirectory) {
                    Text(
                        text = DisplayFormatters.fileSize(LocalContext.current, entry.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (selectionMode) {
                    Text(
                        text = stringResource(R.string.type_folder),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileVisual(
    entry: FileEntry,
    thumbnailsEnabled: Boolean,
    thumbnailCache: ThumbnailMemoryCache,
) {
    val shouldLoadThumbnail = thumbnailsEnabled && MimeClassifier.supportsThumbnail(entry.name, entry.category)
    var imageBytes by remember(entry.path, entry.modifiedAt, thumbnailsEnabled) { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(entry.path, entry.modifiedAt, shouldLoadThumbnail) {
        imageBytes = if (shouldLoadThumbnail) thumbnailCache.get(entry, 192) else null
    }

    val bitmap = remember(imageBytes) {
        imageBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(fileContentDescription(entry), entry.name),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(42.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)),
        )
    } else {
        Icon(
            painter = painterResource(fileIcon(entry.category)),
            contentDescription = stringResource(fileIconDescription(entry.category)),
            tint = Color.Unspecified,
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun ContextActionMenu(
    request: ContextRequest,
    targets: List<String>,
    sameDirectory: Boolean,
    operationRunning: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onCompress: () -> Unit,
    onProperties: () -> Unit,
) {
    val density = LocalDensity.current
    val offset = with(density) {
        DpOffset(request.anchor.x.toDp(), request.anchor.y.toDp())
    }
    Box {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            offset = offset,
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (request.pane == PaneId.LEFT) R.string.context_copy_to_right
                            else R.string.context_copy_to_left,
                        ),
                    )
                },
                enabled = !operationRunning,
                onClick = onCopy,
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (request.pane == PaneId.LEFT) R.string.context_move_to_right
                            else R.string.context_move_to_left,
                        ),
                    )
                },
                enabled = !sameDirectory && !operationRunning,
                onClick = onMove,
            )
            Divider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.context_download_to_controller)) },
                enabled = !operationRunning,
                onClick = onDownload,
            )
            Divider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.context_delete)) },
                enabled = !operationRunning,
                onClick = onDelete,
            )
            if (targets.size == 1) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.context_rename)) },
                    enabled = !operationRunning,
                    onClick = onRename,
                )
            }
            Divider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.context_compress)) },
                enabled = !operationRunning,
                onClick = onCompress,
            )
            if (targets.size == 1) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.context_properties)) },
                    onClick = onProperties,
                )
            }
        }
    }
}

@Composable
private fun BlockingFileOperationDialog(operation: BrowserOperationUiState) {
    val title = when (operation.type) {
        BrowserOperationType.COPY -> R.string.operation_copying
        BrowserOperationType.MOVE -> R.string.operation_moving
        BrowserOperationType.DELETE -> R.string.operation_deleting
        BrowserOperationType.RENAME -> R.string.operation_renaming
        BrowserOperationType.COMPRESS -> R.string.operation_compressing
    }
    val completed = operation.completedItems.coerceAtLeast(0)
    val total = operation.totalItems.coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = { /* 長時間檔案操作不可由外部點擊或返回鍵關閉。 */ },
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                operation.currentName?.takeIf { it.isNotBlank() }?.let { name ->
                    Text(
                        text = stringResource(R.string.operation_current_name, name),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (total > 0) {
                    val shownItem = if (completed >= total) total else (completed + 1).coerceAtMost(total)
                    Text(stringResource(R.string.operation_item_progress, shownItem, total))
                }
                if (operation.type == BrowserOperationType.COPY || operation.type == BrowserOperationType.MOVE) {
                    // 單一大型檔案／目錄在完成前沒有可靠的byte進度；使用動態不確定進度避免看起來卡死。
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = stringResource(R.string.operation_please_wait),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun BlockingTransferDialog(
    direction: TransferDirection?,
    progress: TransferProgress?,
) {
    val context = LocalContext.current
    val title = if (direction == TransferDirection.UPLOAD) {
        R.string.transfer_uploading
    } else {
        R.string.transfer_downloading
    }

    AlertDialog(
        onDismissRequest = { /* 傳輸進行中不可誤關閉。 */ },
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                progress?.label?.takeIf { it.isNotBlank() }?.let { label ->
                    Text(
                        text = stringResource(R.string.operation_current_name, label),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val current = progress?.current ?: 0L
                val total = progress?.total ?: 0L
                if (total > 0L) {
                    Text(
                        stringResource(
                            R.string.transfer_byte_progress,
                            DisplayFormatters.fileSize(context, current),
                            DisplayFormatters.fileSize(context, total),
                        ),
                    )
                    LinearProgressIndicator(
                        progress = { (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = stringResource(R.string.operation_please_wait),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun CompressionDialog(
    initialFileName: String,
    onDismiss: () -> Unit,
    onConfirm: (fileName: String, storeOnly: Boolean) -> Unit,
) {
    var fileName by remember(initialFileName) { mutableStateOf(initialFileName) }
    var storeOnly by remember(initialFileName) { mutableStateOf(false) }
    val validName = fileName.trim().isNotBlank() &&
        !fileName.contains('/') &&
        !fileName.contains('\\')

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compress_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(stringResource(R.string.archive_file_name)) },
                    singleLine = true,
                )
                Text(
                    text = stringResource(R.string.archive_compression_level),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { storeOnly = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = storeOnly, onClick = { storeOnly = true })
                    Text(stringResource(R.string.archive_mode_store_only))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { storeOnly = false },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = !storeOnly, onClick = { storeOnly = false })
                    Text(stringResource(R.string.archive_mode_normal))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = validName,
                onClick = { onConfirm(fileName.trim(), storeOnly) },
            ) {
                Text(stringResource(R.string.compress_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun RenameDialog(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(entry.path) { mutableStateOf(entry.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.rename_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun PropertiesDialog(
    entry: FileEntry,
    permissions: FilePermissions?,
    onCopy: (String, String) -> Unit,
    onEditPermissions: (FilePermissions) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val typeLabel = stringResource(fileTypeName(entry.category))
    val sizeLabel = if (entry.isDirectory) {
        stringResource(R.string.not_available)
    } else {
        DisplayFormatters.fileSize(context, entry.size)
    }
    val modifiedLabel = DisplayFormatters.dateTime(context, entry.modifiedAt)
        .ifBlank { stringResource(R.string.unknown_value) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.properties_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PropertyRow(
                    label = stringResource(R.string.property_name),
                    value = entry.name,
                    onClick = { onCopy(context.getString(R.string.property_name), entry.name) },
                )
                PropertyRow(
                    label = stringResource(R.string.property_path),
                    value = entry.path,
                    onClick = { onCopy(context.getString(R.string.property_path), entry.path) },
                )
                PropertyRow(
                    label = stringResource(R.string.property_type),
                    value = typeLabel,
                    onClick = { onCopy(context.getString(R.string.property_type), typeLabel) },
                )
                PropertyRow(
                    label = stringResource(R.string.property_size),
                    value = sizeLabel,
                    onClick = { onCopy(context.getString(R.string.property_size), sizeLabel) },
                )
                PropertyRow(
                    label = stringResource(R.string.property_modified),
                    value = modifiedLabel,
                    onClick = { onCopy(context.getString(R.string.property_modified), modifiedLabel) },
                )
                permissions?.let { value ->
                    PropertyRow(
                        label = stringResource(R.string.property_permissions),
                        value = stringResource(
                            R.string.permissions_summary,
                            value.symbolic(),
                            value.octal(),
                        ),
                        onClick = { onEditPermissions(value) },
                        showCopyIcon = false,
                    )
                    value.uid?.let { uid ->
                        val uidText = uid.toString()
                        PropertyRow(
                            label = stringResource(R.string.property_uid),
                            value = uidText,
                            onClick = { onCopy(context.getString(R.string.property_uid), uidText) },
                        )
                    }
                    value.gid?.let { gid ->
                        val gidText = gid.toString()
                        PropertyRow(
                            label = stringResource(R.string.property_gid),
                            value = gidText,
                            onClick = { onCopy(context.getString(R.string.property_gid), gidText) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun PropertyRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    showCopyIcon: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        if (onClick != null && showCopyIcon) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.copy_property_value),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PermissionsDialog(
    entry: FileEntry,
    initial: FilePermissions,
    onDismiss: () -> Unit,
    onConfirm: (mode: Int, applyToFiles: Boolean, applyToDirectories: Boolean) -> Unit,
) {
    var current by remember(entry.path, initial.mode) { mutableStateOf(initial) }
    var applyToFiles by remember(entry.path) { mutableStateOf(false) }
    var applyToDirectories by remember(entry.path) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permissions_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    PermissionColumnHeader(R.string.permission_read)
                    PermissionColumnHeader(R.string.permission_write)
                    PermissionColumnHeader(R.string.permission_execute)
                }
                PermissionModeRow(
                    label = R.string.permission_owner,
                    permissions = current,
                    masks = intArrayOf(
                        FilePermissions.OWNER_READ,
                        FilePermissions.OWNER_WRITE,
                        FilePermissions.OWNER_EXECUTE,
                    ),
                    onChanged = { current = it },
                )
                PermissionModeRow(
                    label = R.string.permission_group,
                    permissions = current,
                    masks = intArrayOf(
                        FilePermissions.GROUP_READ,
                        FilePermissions.GROUP_WRITE,
                        FilePermissions.GROUP_EXECUTE,
                    ),
                    onChanged = { current = it },
                )
                PermissionModeRow(
                    label = R.string.permission_others,
                    permissions = current,
                    masks = intArrayOf(
                        FilePermissions.OTHER_READ,
                        FilePermissions.OTHER_WRITE,
                        FilePermissions.OTHER_EXECUTE,
                    ),
                    onChanged = { current = it },
                )

                Text(
                    text = stringResource(
                        R.string.permissions_summary,
                        current.symbolic(),
                        current.octal(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )

                if (entry.isDirectory) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { applyToFiles = !applyToFiles },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = applyToFiles,
                            onCheckedChange = { applyToFiles = it },
                        )
                        Text(stringResource(R.string.permissions_apply_child_files))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { applyToDirectories = !applyToDirectories },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = applyToDirectories,
                            onCheckedChange = { applyToDirectories = it },
                        )
                        Text(stringResource(R.string.permissions_apply_child_directories))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(current.normalizedMode, applyToFiles, applyToDirectories)
                },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun PermissionColumnHeader(@StringRes label: Int) {
    Text(
        text = stringResource(label),
        modifier = Modifier.width(48.dp),
        style = MaterialTheme.typography.labelSmall,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun PermissionModeRow(
    @StringRes label: Int,
    permissions: FilePermissions,
    masks: IntArray,
    onChanged: (FilePermissions) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(label),
            modifier = Modifier.weight(1f),
        )
        masks.forEach { mask ->
            Box(
                modifier = Modifier.width(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Checkbox(
                    checked = permissions.has(mask),
                    onCheckedChange = { checked ->
                        onChanged(permissions.with(mask, checked))
                    },
                )
            }
        }
    }
}

@Composable
private fun PathDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var path by remember(initialPath) { mutableStateOf(initialPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.path_dialog_title)) },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text(stringResource(R.string.path_dialog_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(path) }, enabled = path.isNotBlank()) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SearchDialog(
    initialQuery: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_current_directory)) },
        text = {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(query) }) { Text(stringResource(R.string.search)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onConfirm("") }) { Text(stringResource(R.string.clear_search)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun FilterDialog(
    current: FileFilter,
    onDismiss: () -> Unit,
    onSelect: (FileFilter) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filter)) },
        text = {
            Column {
                FilterOption(FileFilter.ALL, current, R.string.filter_all, onSelect)
                FilterOption(FileFilter.FOLDERS, current, R.string.filter_folders, onSelect)
                FilterOption(FileFilter.FILES, current, R.string.filter_files, onSelect)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun FilterOption(
    value: FileFilter,
    current: FileFilter,
    @StringRes label: Int,
    onSelect: (FileFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = current == value, onClick = { onSelect(value) })
        Text(stringResource(label))
    }
}

@Composable
private fun SortDialog(
    field: SortField,
    direction: SortDirection,
    onDismiss: () -> Unit,
    onApply: (SortField, SortDirection) -> Unit,
) {
    var selectedField by remember(field) { mutableStateOf(field) }
    var selectedDirection by remember(direction) { mutableStateOf(direction) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sort)) },
        text = {
            Column {
                SortFieldOption(SortField.NAME, selectedField, R.string.sort_name) { selectedField = it }
                SortFieldOption(SortField.MODIFIED, selectedField, R.string.sort_modified) { selectedField = it }
                SortFieldOption(SortField.SIZE, selectedField, R.string.sort_size) { selectedField = it }
                SortFieldOption(SortField.TYPE, selectedField, R.string.sort_type) { selectedField = it }
                Divider(modifier = Modifier.padding(vertical = 6.dp))
                DirectionOption(SortDirection.ASCENDING, selectedDirection, R.string.sort_ascending) { selectedDirection = it }
                DirectionOption(SortDirection.DESCENDING, selectedDirection, R.string.sort_descending) { selectedDirection = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(selectedField, selectedDirection) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SortFieldOption(
    value: SortField,
    current: SortField,
    @StringRes label: Int,
    onSelect: (SortField) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == current, onClick = { onSelect(value) })
        Text(stringResource(label))
    }
}

@Composable
private fun DirectionOption(
    value: SortDirection,
    current: SortDirection,
    @StringRes label: Int,
    onSelect: (SortDirection) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == current, onClick = { onSelect(value) })
        Text(stringResource(label))
    }
}


private suspend fun uploadUris(
    context: android.content.Context,
    client: RemoteFileClient,
    uris: List<Uri>,
    destinationDirectory: String,
    onProgress: (String, Long, Long) -> Unit,
) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    uris.forEach { uri ->
        val meta = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                    name to size
                }
            }
        val name = meta?.first?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: throw FileNotFoundException()
        val size = (meta?.second ?: -1L).takeIf { it >= 0L }
            ?: resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it >= 0L }
            ?: throw IllegalStateException("Unknown source size")
        val destination = PathUtils.child(destinationDirectory, name)
        resolver.openInputStream(uri)?.use { input ->
            client.uploadFile(input, size, destination) { current, total -> onProgress(name, current, total) }
        } ?: throw FileNotFoundException()
    }
}

private suspend fun downloadPathsToTree(
    context: android.content.Context,
    client: RemoteFileClient,
    paths: List<String>,
    treeUri: Uri,
    onProgress: (String, Long, Long) -> Unit,
) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
    paths.forEach { path ->
        val entry = client.stat(path)
        downloadEntryToDocument(context, client, entry, root, onProgress)
    }
}

private suspend fun downloadEntryToDocument(
    context: android.content.Context,
    client: RemoteFileClient,
    entry: FileEntry,
    parentDocument: Uri,
    onProgress: (String, Long, Long) -> Unit,
) {
    val resolver = context.contentResolver
    if (entry.isDirectory) {
        val folder = DocumentsContract.createDocument(
            resolver,
            parentDocument,
            DocumentsContract.Document.MIME_TYPE_DIR,
            entry.name,
        ) ?: throw IllegalStateException("Unable to create destination folder")
        client.listDirectory(entry.path).forEach { child ->
            downloadEntryToDocument(context, client, child, folder, onProgress)
        }
    } else {
        val document = DocumentsContract.createDocument(
            resolver,
            parentDocument,
            entry.mimeType ?: "application/octet-stream",
            entry.name,
        ) ?: throw IllegalStateException("Unable to create destination file")
        resolver.openOutputStream(document, "w")?.use { output ->
            client.downloadFile(entry.path, output) { current, total -> onProgress(entry.name, current, total) }
        } ?: throw FileNotFoundException()
    }
}

private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"

@DrawableRes
private fun fileIcon(category: FileCategory): Int = when (category) {
    FileCategory.FOLDER -> R.drawable.ic_file_folder
    FileCategory.IMAGE -> R.drawable.ic_file_image
    FileCategory.VIDEO -> R.drawable.ic_file_video
    FileCategory.AUDIO -> R.drawable.ic_file_audio
    FileCategory.TEXT -> R.drawable.ic_file_text
    FileCategory.DOCUMENT -> R.drawable.ic_file_document
    FileCategory.ARCHIVE -> R.drawable.ic_file_archive
    FileCategory.APK -> R.drawable.ic_file_apk
    FileCategory.GENERIC -> R.drawable.ic_file_generic
}

@StringRes
private fun fileIconDescription(category: FileCategory): Int = when (category) {
    FileCategory.FOLDER -> R.string.file_icon_folder
    FileCategory.IMAGE -> R.string.file_icon_image
    FileCategory.VIDEO -> R.string.file_icon_video
    FileCategory.AUDIO -> R.string.file_icon_audio
    FileCategory.TEXT -> R.string.file_icon_text
    FileCategory.DOCUMENT -> R.string.file_icon_document
    FileCategory.ARCHIVE -> R.string.file_icon_archive
    FileCategory.APK -> R.string.file_icon_apk
    FileCategory.GENERIC -> R.string.file_icon_generic
}

@StringRes
private fun fileContentDescription(entry: FileEntry): Int =
    if (entry.isDirectory) R.string.folder_item_content_description else R.string.file_item_content_description

@StringRes
private fun fileTypeName(category: FileCategory): Int = when (category) {
    FileCategory.FOLDER -> R.string.type_folder
    FileCategory.IMAGE -> R.string.type_image
    FileCategory.VIDEO -> R.string.type_video
    FileCategory.AUDIO -> R.string.type_audio
    FileCategory.TEXT -> R.string.type_text
    FileCategory.DOCUMENT -> R.string.type_document
    FileCategory.ARCHIVE -> R.string.type_archive
    FileCategory.APK -> R.string.type_apk
    FileCategory.GENERIC -> R.string.type_generic
}

@StringRes
private fun errorMessageRes(error: FileError): Int = when (error) {
    FileError.PERMISSION_DENIED -> R.string.operation_failed_permission
    FileError.NOT_FOUND -> R.string.operation_failed_not_found
    FileError.CONFLICT -> R.string.operation_failed_conflict
    FileError.READ_ONLY -> R.string.operation_failed_read_only
    FileError.NO_SPACE -> R.string.operation_failed_no_space
    FileError.INVALID_PATH -> R.string.operation_failed_invalid_path
    FileError.UNKNOWN -> R.string.operation_failed_unknown
}

@DrawableRes
private fun storageIcon(kind: StorageKind): Int = when (kind) {
    StorageKind.INTERNAL -> R.drawable.ic_file_folder
    StorageKind.SD_CARD -> R.drawable.ic_file_archive
    StorageKind.USB -> R.drawable.ic_file_generic
    StorageKind.REMOVABLE -> R.drawable.ic_file_generic
    StorageKind.UNKNOWN -> R.drawable.ic_file_folder
}

