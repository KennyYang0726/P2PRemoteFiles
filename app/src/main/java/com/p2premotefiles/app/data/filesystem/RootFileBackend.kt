package com.p2premotefiles.app.data.filesystem

import android.os.ParcelFileDescriptor
import com.p2premotefiles.app.domain.filesystem.FileSystemBackend
import com.p2premotefiles.app.domain.model.FileAccessException
import com.p2premotefiles.app.domain.model.FileEntry
import com.p2premotefiles.app.domain.model.FileError
import com.p2premotefiles.app.domain.model.FilePermissions
import com.p2premotefiles.app.root.RootServiceBridge
import com.p2premotefiles.app.shizuku.IPrivilegedFileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** RootService Binder後端。所有實際I/O都在UID 0的RootService process中執行。 */
class RootFileBackend(private val bridge: RootServiceBridge) : FileSystemBackend {
    override suspend fun listDirectory(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val service = requireService()
        val metadata = statJson(service, path)
        if (!metadata.optBoolean("exists", false)) throw FileAccessException(FileError.NOT_FOUND)
        if (!metadata.optBoolean("directory", false)) throw FileAccessException(FileError.INVALID_PATH)
        runCatching {
            val array = JSONArray(service.listDirectory(path))
            buildList(array.length()) {
                for (index in 0 until array.length()) add(parseEntry(array.getJSONObject(index)))
            }
        }.getOrElse { throw FileAccessException(FileError.PERMISSION_DENIED, it) }
    }

    override suspend fun stat(path: String): FileEntry = withContext(Dispatchers.IO) {
        val service = requireService()
        val json = statJson(service, path)
        if (!json.optBoolean("exists", false)) throw FileAccessException(FileError.NOT_FOUND)
        parseEntry(json)
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        val service = requireService()
        if (statJson(service, path).optBoolean("exists", false)) throw FileAccessException(FileError.CONFLICT)
        if (!service.createDirectory(path)) throw FileAccessException(FileError.PERMISSION_DENIED)
    }

    override suspend fun copy(source: String, destination: String) = withContext(Dispatchers.IO) {
        val service = requireService()
        ensureExists(service, source)
        ensureDestinationMissing(service, destination)
        if (!service.copy(source, destination)) throw FileAccessException(FileError.PERMISSION_DENIED)
    }

    override suspend fun move(source: String, destination: String) = withContext(Dispatchers.IO) {
        val service = requireService()
        ensureExists(service, source)
        ensureDestinationMissing(service, destination)
        if (!service.move(source, destination)) throw FileAccessException(FileError.PERMISSION_DENIED)
    }

    override suspend fun rename(source: String, destination: String) = withContext(Dispatchers.IO) {
        val service = requireService()
        ensureExists(service, source)
        ensureDestinationMissing(service, destination)
        if (!service.rename(source, destination)) throw FileAccessException(FileError.PERMISSION_DENIED)
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val service = requireService()
        ensureExists(service, path)
        if (!service.deleteRecursively(path)) throw FileAccessException(FileError.PERMISSION_DENIED)
    }

    override suspend fun openRead(path: String): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        val service = requireService()
        ensureExists(service, path)
        service.openRead(path) ?: throw FileAccessException(FileError.PERMISSION_DENIED)
    }

    override suspend fun openWrite(path: String, append: Boolean): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        requireService().openWrite(path, append) ?: throw FileAccessException(FileError.PERMISSION_DENIED)
    }

    override suspend fun permissions(path: String): FilePermissions? = withContext(Dispatchers.IO) {
        val raw = runCatching { requireService().permissions(path) }.getOrNull().orEmpty()
        if (raw.isBlank()) return@withContext null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
        if (!json.optBoolean("available", false)) return@withContext null
        FilePermissions(
            mode = json.optInt("mode", 0) and 0x1FF,
            uid = if (json.has("uid")) json.optInt("uid") else null,
            gid = if (json.has("gid")) json.optInt("gid") else null,
        )
    }

    override suspend fun setPermissions(
        path: String,
        mode: Int,
        applyToFiles: Boolean,
        applyToDirectories: Boolean,
    ) = withContext(Dispatchers.IO) {
        val raw = runCatching {
            requireService().setPermissionsDetailed(path, mode and 0x1FF, applyToFiles, applyToDirectories)
        }.getOrElse { error ->
            throw FileAccessException(FileError.PERMISSION_DENIED, error, error.message)
        }
        val result = runCatching { JSONObject(raw) }.getOrNull()
        if (result?.optBoolean("ok", false) != true) {
            throw FileAccessException(
                FileError.PERMISSION_DENIED,
                detail = result?.optString("error")?.takeIf { it.isNotBlank() } ?: "Operation not permitted",
            )
        }
    }

    private suspend fun requireService(): IPrivilegedFileService = bridge.service()
        ?: throw FileAccessException(FileError.PERMISSION_DENIED)

    private fun statJson(service: IPrivilegedFileService, path: String): JSONObject = runCatching {
        JSONObject(service.stat(path))
    }.getOrElse { throw FileAccessException(FileError.PERMISSION_DENIED, it) }

    private fun ensureExists(service: IPrivilegedFileService, path: String) {
        if (!statJson(service, path).optBoolean("exists", false)) throw FileAccessException(FileError.NOT_FOUND)
    }

    private fun ensureDestinationMissing(service: IPrivilegedFileService, path: String) {
        if (statJson(service, path).optBoolean("exists", false)) throw FileAccessException(FileError.CONFLICT)
    }

    private fun parseEntry(json: JSONObject): FileEntry {
        val name = json.getString("name")
        val isDirectory = json.getBoolean("directory")
        return FileEntry(
            name = name,
            path = json.getString("path"),
            isDirectory = isDirectory,
            size = json.optLong("size", 0L),
            modifiedAt = json.optLong("modified", 0L),
            mimeType = if (isDirectory) null else MimeClassifier.mimeTypeFor(name),
            category = MimeClassifier.classify(name, isDirectory),
        )
    }
}
