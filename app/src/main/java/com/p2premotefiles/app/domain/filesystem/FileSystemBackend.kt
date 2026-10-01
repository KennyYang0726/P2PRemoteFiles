package com.p2premotefiles.app.domain.filesystem

import android.os.ParcelFileDescriptor
import com.p2premotefiles.app.domain.model.FileEntry
import com.p2premotefiles.app.domain.model.FilePermissions

interface FileSystemBackend {
    suspend fun listDirectory(path: String): List<FileEntry>
    suspend fun stat(path: String): FileEntry
    suspend fun createDirectory(path: String)
    suspend fun copy(source: String, destination: String)
    suspend fun move(source: String, destination: String)
    suspend fun rename(source: String, destination: String)
    suspend fun delete(path: String)
    suspend fun openRead(path: String): ParcelFileDescriptor
    suspend fun openWrite(path: String, append: Boolean = false): ParcelFileDescriptor
    suspend fun permissions(path: String): FilePermissions?
    suspend fun setPermissions(
        path: String,
        mode: Int,
        applyToFiles: Boolean = false,
        applyToDirectories: Boolean = false,
    )
}
