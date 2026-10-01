package com.p2premotefiles.app.remote

import com.p2premotefiles.app.domain.model.FileEntry
import com.p2premotefiles.app.domain.model.FilePermissions
import com.p2premotefiles.app.domain.model.StorageVolumeInfo
import java.io.InputStream
import java.io.OutputStream

interface RemoteFileClient {
    suspend fun listVolumes(): List<StorageVolumeInfo>
    suspend fun listDirectory(path: String): List<FileEntry>
    suspend fun stat(path: String): FileEntry
    suspend fun createDirectory(path: String)
    suspend fun copy(source: String, destination: String)
    suspend fun move(source: String, destination: String)
    suspend fun rename(source: String, destination: String)
    suspend fun delete(path: String)
    suspend fun thumbnail(path: String, maxSizePx: Int): ByteArray?
    suspend fun permissions(path: String): FilePermissions?
    suspend fun setPermissions(
        path: String,
        mode: Int,
        applyToFiles: Boolean = false,
        applyToDirectories: Boolean = false,
    )
    suspend fun downloadFile(path: String, output: OutputStream, onProgress: (Long, Long) -> Unit = { _, _ -> })
    suspend fun uploadFile(input: InputStream, size: Long, destination: String, onProgress: (Long, Long) -> Unit = { _, _ -> })
    suspend fun compress(
        paths: List<String>,
        destination: String,
        storeOnly: Boolean,
    ) {
        throw UnsupportedOperationException("compress_not_supported")
    }
    suspend fun healthCheck() = Unit
    fun close() = Unit
}

class RemoteSessionDisconnectedException(cause: Throwable? = null) : Exception("remote_session_disconnected", cause)
