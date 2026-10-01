package com.p2premotefiles.app.data.filesystem

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.p2premotefiles.app.domain.filesystem.FileSystemBackend
import com.p2premotefiles.app.domain.model.FileAccessException
import com.p2premotefiles.app.domain.model.FileEntry
import com.p2premotefiles.app.domain.model.FileError
import com.p2premotefiles.app.domain.model.FilePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class DirectFileBackend : FileSystemBackend {
    override suspend fun listDirectory(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        try {
            val directory = File(path)
            if (!directory.exists()) throw FileAccessException(FileError.NOT_FOUND)
            if (!directory.isDirectory) throw FileAccessException(FileError.INVALID_PATH)
            val children = directory.listFiles()
                ?: throw FileAccessException(FileError.PERMISSION_DENIED)
            children.map(::toEntry)
        } catch (e: FileAccessException) {
            throw e
        } catch (t: Throwable) {
            throw FileErrorMapper.fromThrowable(t)
        }
    }

    override suspend fun stat(path: String): FileEntry = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) throw FileAccessException(FileError.NOT_FOUND)
            toEntry(file)
        } catch (e: FileAccessException) {
            throw e
        } catch (t: Throwable) {
            throw FileErrorMapper.fromThrowable(t)
        }
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (file.exists()) throw FileAccessException(FileError.CONFLICT)
            if (!file.mkdirs()) throw FileAccessException(FileError.PERMISSION_DENIED)
        } catch (e: FileAccessException) {
            throw e
        } catch (t: Throwable) {
            throw FileErrorMapper.fromThrowable(t)
        }
    }

    override suspend fun copy(source: String, destination: String) = withContext(Dispatchers.IO) {
        try {
            val src = File(source)
            val dst = File(destination)
            if (!src.exists()) throw FileAccessException(FileError.NOT_FOUND)
            if (dst.exists()) throw FileAccessException(FileError.CONFLICT)
            copyRecursive(src, dst)
        } catch (e: FileAccessException) {
            throw e
        } catch (t: Throwable) {
            throw FileErrorMapper.fromThrowable(t)
        }
    }

    override suspend fun move(source: String, destination: String) = withContext(Dispatchers.IO) {
        try {
            val src = File(source)
            val dst = File(destination)
            if (!src.exists()) throw FileAccessException(FileError.NOT_FOUND)
            if (dst.exists()) throw FileAccessException(FileError.CONFLICT)
            dst.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) throw FileAccessException(FileError.PERMISSION_DENIED)
            }
            if (!src.renameTo(dst)) {
                copyRecursive(src, dst)
                if (!deleteRecursive(src)) throw FileAccessException(FileError.UNKNOWN)
            }
        } catch (e: FileAccessException) {
            throw e
        } catch (t: Throwable) {
            throw FileErrorMapper.fromThrowable(t)
        }
    }

    override suspend fun rename(source: String, destination: String) = withContext(Dispatchers.IO) {
        try {
            val src = File(source)
            val dst = File(destination)
            if (!src.exists()) throw FileAccessException(FileError.NOT_FOUND)
            if (dst.exists()) throw FileAccessException(FileError.CONFLICT)
            if (!src.renameTo(dst)) throw FileAccessException(FileError.PERMISSION_DENIED)
        } catch (e: FileAccessException) {
            throw e
        } catch (t: Throwable) {
            throw FileErrorMapper.fromThrowable(t)
        }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) throw FileAccessException(FileError.NOT_FOUND)
            if (!deleteRecursive(file)) throw FileAccessException(FileError.PERMISSION_DENIED)
        } catch (e: FileAccessException) {
            throw e
        } catch (t: Throwable) {
            throw FileErrorMapper.fromThrowable(t)
        }
    }

    override suspend fun openRead(path: String): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        try {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            throw FileErrorMapper.fromThrowable(t)
        }
    }

    override suspend fun openWrite(path: String, append: Boolean): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            file.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) throw FileAccessException(FileError.PERMISSION_DENIED)
            }
            val mode = ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or
                if (append) ParcelFileDescriptor.MODE_APPEND else ParcelFileDescriptor.MODE_TRUNCATE
            ParcelFileDescriptor.open(file, mode)
        } catch (e: FileAccessException) {
            throw e
        } catch (t: Throwable) {
            throw FileErrorMapper.fromThrowable(t)
        }
    }

    override suspend fun permissions(path: String): FilePermissions? = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) throw FileAccessException(FileError.NOT_FOUND)
            // lstat可避免符號連結解析造成某些掛載點stat失敗；若ROM不支援再退回stat。
            val stat = runCatching { Os.lstat(path) }.getOrElse { Os.stat(path) }
            FilePermissions(
                mode = stat.st_mode and 0x1FF,
                uid = stat.st_uid,
                gid = stat.st_gid,
            )
        } catch (e: FileAccessException) {
            throw e
        } catch (t: Throwable) {
            throw FileErrorMapper.fromThrowable(t)
        }
    }

    override suspend fun setPermissions(
        path: String,
        mode: Int,
        applyToFiles: Boolean,
        applyToDirectories: Boolean,
    ) = withContext(Dispatchers.IO) {
        try {
            val target = File(path)
            if (!target.exists()) throw FileAccessException(FileError.NOT_FOUND)
            val normalizedMode = mode and 0x1FF
            Os.chmod(target.absolutePath, normalizedMode)
            if (target.isDirectory && (applyToFiles || applyToDirectories)) {
                applyPermissionsRecursively(target, normalizedMode, applyToFiles, applyToDirectories)
            }
        } catch (e: FileAccessException) {
            throw e
        } catch (t: Throwable) {
            throw FileErrorMapper.fromThrowable(t)
        }
    }

    fun canReadPath(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        if (!file.canRead()) return false
        return if (file.isDirectory) file.list() != null else true
    }

    fun canWriteDestination(path: String): Boolean {
        val destination = File(path)
        val parent = destination.parentFile ?: return false
        return parent.exists() && parent.canWrite()
    }

    fun canDeletePath(path: String): Boolean {
        val file = File(path)
        return file.exists() && (file.parentFile?.canWrite() == true)
    }

    private fun toEntry(file: File): FileEntry {
        val isDirectory = file.isDirectory
        return FileEntry(
            name = file.name.ifBlank { file.absolutePath },
            path = file.absolutePath,
            isDirectory = isDirectory,
            size = if (isDirectory) 0L else file.length(),
            modifiedAt = file.lastModified(),
            mimeType = if (isDirectory) null else MimeClassifier.mimeTypeFor(file.name),
            category = MimeClassifier.classify(file.name, isDirectory),
        )
    }

    private fun copyRecursive(source: File, destination: File) {
        if (source.isDirectory) {
            if (!destination.mkdirs() && !destination.isDirectory) {
                throw FileAccessException(FileError.PERMISSION_DENIED)
            }
            val children = source.listFiles() ?: throw FileAccessException(FileError.PERMISSION_DENIED)
            for (child in children) {
                copyRecursive(child, File(destination, child.name))
            }
            destination.setLastModified(source.lastModified())
        } else {
            destination.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) throw FileAccessException(FileError.PERMISSION_DENIED)
            }
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            destination.setLastModified(source.lastModified())
        }
    }

    private fun applyPermissionsRecursively(
        directory: File,
        mode: Int,
        applyToFiles: Boolean,
        applyToDirectories: Boolean,
    ) {
        val children = directory.listFiles() ?: throw FileAccessException(FileError.PERMISSION_DENIED)
        for (child in children) {
            val stat = runCatching { Os.lstat(child.absolutePath) }.getOrNull() ?: continue
            if ((stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK) continue
            if (child.isDirectory) {
                if (applyToDirectories) Os.chmod(child.absolutePath, mode)
                applyPermissionsRecursively(child, mode, applyToFiles, applyToDirectories)
            } else if (applyToFiles) {
                Os.chmod(child.absolutePath, mode)
            }
        }
    }

    private fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            for (child in children) {
                if (!deleteRecursive(child)) return false
            }
        }
        return file.delete()
    }
}
