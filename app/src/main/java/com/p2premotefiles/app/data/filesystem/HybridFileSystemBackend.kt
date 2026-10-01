package com.p2premotefiles.app.data.filesystem

import android.os.ParcelFileDescriptor
import com.p2premotefiles.app.domain.filesystem.FileSystemBackend
import com.p2premotefiles.app.domain.model.FileAccessException
import com.p2premotefiles.app.domain.model.FileEntry
import com.p2premotefiles.app.domain.model.FileError
import com.p2premotefiles.app.domain.model.FilePermissions

/**
 * 檔案操作權限優先序：
 * 1. Root Shizuku / Sui（UID 0）
 * 2. libsu RootService（su只負責啟動root process，後續走Binder）
 * 3. Direct File / NIO
 * 4. Shell Shizuku（通常UID 2000）
 */
class HybridFileSystemBackend(
    private val direct: DirectFileBackend,
    private val shizuku: ShizukuFileBackend,
    private val root: RootFileBackend,
) : FileSystemBackend {
    override suspend fun listDirectory(path: String): List<FileEntry> = prioritized(
        fallbackOnNotFound = isKnownRestrictedPath(path),
        rootShizukuCall = { shizuku.listDirectory(path) },
        rootServiceCall = { root.listDirectory(path) },
        directCall = { direct.listDirectory(path) },
        shellShizukuCall = { shizuku.listDirectory(path) },
    )

    override suspend fun stat(path: String): FileEntry = prioritized(
        fallbackOnNotFound = isKnownRestrictedPath(path),
        rootShizukuCall = { shizuku.stat(path) },
        rootServiceCall = { root.stat(path) },
        directCall = { direct.stat(path) },
        shellShizukuCall = { shizuku.stat(path) },
    )

    override suspend fun createDirectory(path: String) = prioritized(
        fallbackOnNotFound = isKnownRestrictedPath(path),
        rootShizukuCall = { shizuku.createDirectory(path) },
        rootServiceCall = { root.createDirectory(path) },
        directCall = { direct.createDirectory(path) },
        shellShizukuCall = { shizuku.createDirectory(path) },
    )

    override suspend fun copy(source: String, destination: String) = prioritized(
        fallbackOnNotFound = isKnownRestrictedPath(source) || isKnownRestrictedPath(destination),
        rootShizukuCall = { shizuku.copy(source, destination) },
        rootServiceCall = { root.copy(source, destination) },
        directCall = { direct.copy(source, destination) },
        shellShizukuCall = { shizuku.copy(source, destination) },
    )

    override suspend fun move(source: String, destination: String) = prioritized(
        fallbackOnNotFound = isKnownRestrictedPath(source) || isKnownRestrictedPath(destination),
        rootShizukuCall = { shizuku.move(source, destination) },
        rootServiceCall = { root.move(source, destination) },
        directCall = { direct.move(source, destination) },
        shellShizukuCall = { shizuku.move(source, destination) },
    )

    override suspend fun rename(source: String, destination: String) = prioritized(
        fallbackOnNotFound = isKnownRestrictedPath(source) || isKnownRestrictedPath(destination),
        rootShizukuCall = { shizuku.rename(source, destination) },
        rootServiceCall = { root.rename(source, destination) },
        directCall = { direct.rename(source, destination) },
        shellShizukuCall = { shizuku.rename(source, destination) },
    )

    override suspend fun delete(path: String) = prioritized(
        fallbackOnNotFound = isKnownRestrictedPath(path),
        rootShizukuCall = { shizuku.delete(path) },
        rootServiceCall = { root.delete(path) },
        directCall = { direct.delete(path) },
        shellShizukuCall = { shizuku.delete(path) },
    )

    override suspend fun openRead(path: String): ParcelFileDescriptor = prioritized(
        fallbackOnNotFound = isKnownRestrictedPath(path),
        rootShizukuCall = { shizuku.openRead(path) },
        rootServiceCall = { root.openRead(path) },
        directCall = { direct.openRead(path) },
        shellShizukuCall = { shizuku.openRead(path) },
    )

    override suspend fun openWrite(path: String, append: Boolean): ParcelFileDescriptor = prioritized(
        fallbackOnNotFound = isKnownRestrictedPath(path),
        rootShizukuCall = { shizuku.openWrite(path, append) },
        rootServiceCall = { root.openWrite(path, append) },
        directCall = { direct.openWrite(path, append) },
        shellShizukuCall = { shizuku.openWrite(path, append) },
    )

    /**
     * 權限是可選metadata，因此不應因單一backend無法stat就整體放棄。
     * 依同一優先級逐層嘗試，取得第一份可靠的mode/uid/gid就回傳。
     */
    override suspend fun permissions(path: String): FilePermissions? {
        if (shizuku.isRootMode()) {
            runCatching { shizuku.permissions(path) }.getOrNull()?.let { return it }
        }
        runCatching { root.permissions(path) }.getOrNull()?.let { return it }
        runCatching { direct.permissions(path) }.getOrNull()?.let { return it }
        if (shizuku.isShellMode()) {
            runCatching { shizuku.permissions(path) }.getOrNull()?.let { return it }
        }
        return null
    }

    override suspend fun setPermissions(
        path: String,
        mode: Int,
        applyToFiles: Boolean,
        applyToDirectories: Boolean,
    ) {
        val expectedMode = mode and 0x1FF
        prioritized(
            fallbackOnNotFound = isKnownRestrictedPath(path),
            rootShizukuCall = {
                shizuku.setPermissions(path, expectedMode, applyToFiles, applyToDirectories)
                verifyPermissions(expectedMode) { shizuku.permissions(path) }
            },
            rootServiceCall = {
                root.setPermissions(path, expectedMode, applyToFiles, applyToDirectories)
                verifyPermissions(expectedMode) { root.permissions(path) }
            },
            directCall = {
                direct.setPermissions(path, expectedMode, applyToFiles, applyToDirectories)
                verifyPermissions(expectedMode) { direct.permissions(path) }
            },
            shellShizukuCall = {
                shizuku.setPermissions(path, expectedMode, applyToFiles, applyToDirectories)
                verifyPermissions(expectedMode) { shizuku.permissions(path) }
            },
        )
    }

    private suspend fun verifyPermissions(
        expectedMode: Int,
        readBack: suspend () -> FilePermissions?,
    ) {
        val actual = readBack()?.normalizedMode
        if (actual == null || actual != expectedMode) {
            throw FileAccessException(FileError.PERMISSION_DENIED)
        }
    }

    private suspend fun <T> prioritized(
        fallbackOnNotFound: Boolean,
        rootShizukuCall: suspend () -> T,
        rootServiceCall: suspend () -> T,
        directCall: suspend () -> T,
        shellShizukuCall: suspend () -> T,
    ): T {
        var preferredError: FileAccessException? = null

        if (shizuku.isRootMode()) {
            try {
                return rootShizukuCall()
            } catch (error: FileAccessException) {
                if (!canFallback(error, fallbackOnNotFound)) throw error
                preferredError = error
            } catch (_: SecurityException) {
                preferredError = FileAccessException(FileError.PERMISSION_DENIED)
            }
        }

        try {
            return rootServiceCall()
        } catch (error: FileAccessException) {
            if (!canFallback(error, fallbackOnNotFound)) throw error
            if (preferredError == null) preferredError = error
        } catch (_: SecurityException) {
            if (preferredError == null) preferredError = FileAccessException(FileError.PERMISSION_DENIED)
        }

        try {
            return directCall()
        } catch (error: FileAccessException) {
            if (!canFallback(error, fallbackOnNotFound)) throw error
            preferredError = error
        } catch (error: SecurityException) {
            preferredError = FileAccessException(FileError.PERMISSION_DENIED, error)
        }

        if (shizuku.isShellMode()) {
            try {
                return shellShizukuCall()
            } catch (error: FileAccessException) {
                if (!canFallback(error, fallbackOnNotFound)) throw error
                preferredError = error
            } catch (_: SecurityException) {
                // 最後一層，往下回傳前面最有意義的錯誤。
            }
        }

        throw preferredError ?: FileAccessException(FileError.PERMISSION_DENIED)
    }

    private fun canFallback(error: FileAccessException, fallbackOnNotFound: Boolean): Boolean =
        error.error == FileError.PERMISSION_DENIED ||
            (fallbackOnNotFound && error.error == FileError.NOT_FOUND)

    private fun isKnownRestrictedPath(path: String): Boolean {
        val normalized = path.replace('\\', '/').trimEnd('/')
        return normalized.contains("/Android/data/") ||
            normalized.endsWith("/Android/data") ||
            normalized.contains("/Android/obb/") ||
            normalized.endsWith("/Android/obb")
    }
}
