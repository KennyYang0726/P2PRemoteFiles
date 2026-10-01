package com.p2premotefiles.app.shizuku

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@Keep
class PrivilegedFileService : IPrivilegedFileService.Stub() {
    override fun listDirectory(path: String): String {
        val directory = File(path)
        if (!directory.exists()) throw IllegalArgumentException("Path does not exist")
        if (!directory.isDirectory) throw IllegalArgumentException("Path is not a directory")
        val children = directory.listFiles() ?: throw SecurityException("Directory cannot be listed")
        val array = JSONArray()
        children.forEach { file -> array.put(toJson(file)) }
        return array.toString()
    }

    override fun stat(path: String): String {
        val file = File(path)
        if (!file.exists()) return JSONObject().put("exists", false).toString()
        return toJson(file).put("exists", true).toString()
    }

    override fun createDirectory(path: String): Boolean {
        val file = File(path)
        return !file.exists() && file.mkdirs()
    }

    override fun copy(source: String, destination: String): Boolean = runCatching {
        val src = File(source)
        val dst = File(destination)
        if (!src.exists()) return false
        copyRecursive(src, dst)
        true
    }.getOrDefault(false)

    override fun move(source: String, destination: String): Boolean = runCatching {
        val src = File(source)
        val dst = File(destination)
        if (!src.exists()) return false
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return true
        copyRecursive(src, dst)
        deleteInternal(src)
    }.getOrDefault(false)

    override fun rename(source: String, destination: String): Boolean {
        val src = File(source)
        val dst = File(destination)
        if (!src.exists() || dst.exists()) return false
        dst.parentFile?.mkdirs()
        return src.renameTo(dst)
    }

    override fun deleteRecursively(path: String): Boolean {
        val file = File(path)
        return file.exists() && deleteInternal(file)
    }

    override fun openRead(path: String): ParcelFileDescriptor? = runCatching {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    }.getOrNull()

    override fun openWrite(path: String, append: Boolean): ParcelFileDescriptor? = runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        val mode = ParcelFileDescriptor.MODE_WRITE_ONLY or
            ParcelFileDescriptor.MODE_CREATE or
            if (append) ParcelFileDescriptor.MODE_APPEND else ParcelFileDescriptor.MODE_TRUNCATE
        ParcelFileDescriptor.open(file, mode)
    }.getOrNull()

    override fun serviceUid(): Int = Process.myUid()

    override fun permissions(path: String): String = runCatching {
        val file = File(path)
        if (!file.exists()) return@runCatching JSONObject().put("available", false).toString()
        val stat = runCatching { Os.lstat(path) }.getOrElse { Os.stat(path) }
        JSONObject()
            .put("available", true)
            .put("mode", stat.st_mode and 0x1FF)
            .put("uid", stat.st_uid)
            .put("gid", stat.st_gid)
            .toString()
    }.getOrElse { JSONObject().put("available", false).toString() }

    override fun setPermissions(
        path: String,
        mode: Int,
        applyToFiles: Boolean,
        applyToDirectories: Boolean,
    ): Boolean = runCatching {
        val target = File(path)
        if (!target.exists()) return false
        val normalizedMode = mode and 0x1FF
        Os.chmod(target.absolutePath, normalizedMode)
        if (target.isDirectory && (applyToFiles || applyToDirectories)) {
            applyPermissionsRecursively(target, normalizedMode, applyToFiles, applyToDirectories)
        }
        true
    }.getOrDefault(false)

    override fun setPermissionsDetailed(
        path: String,
        mode: Int,
        applyToFiles: Boolean,
        applyToDirectories: Boolean,
    ): String = runCatching {
        val target = File(path)
        if (!target.exists()) {
            return@runCatching JSONObject()
                .put("ok", false)
                .put("error", "File does not exist")
                .toString()
        }
        val normalizedMode = mode and 0x1FF
        Os.chmod(target.absolutePath, normalizedMode)
        if (target.isDirectory && (applyToFiles || applyToDirectories)) {
            applyPermissionsRecursively(target, normalizedMode, applyToFiles, applyToDirectories)
        }
        JSONObject().put("ok", true).toString()
    }.getOrElse { error ->
        JSONObject()
            .put("ok", false)
            .put("error", error.message ?: error.javaClass.simpleName)
            .toString()
    }

    override fun destroy() {
        System.exit(0)
    }

    private fun toJson(file: File): JSONObject = JSONObject()
        .put("name", file.name.ifBlank { file.absolutePath })
        .put("path", file.absolutePath)
        .put("directory", file.isDirectory)
        .put("size", if (file.isDirectory) 0L else file.length())
        .put("modified", file.lastModified())

    private fun copyRecursive(source: File, destination: File) {
        if (source.isDirectory) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw IllegalStateException()
            }
            val children = source.listFiles() ?: throw IllegalStateException()
            children.forEach { child -> copyRecursive(child, File(destination, child.name)) }
            destination.setLastModified(source.lastModified())
        } else {
            destination.parentFile?.mkdirs()
            FileInputStream(source).use { input ->
                FileOutputStream(destination, false).use { output ->
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
        val children = directory.listFiles() ?: throw SecurityException("Directory cannot be listed")
        children.forEach { child ->
            val stat = runCatching { Os.lstat(child.absolutePath) }.getOrNull() ?: return@forEach
            if ((stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK) return@forEach
            if (child.isDirectory) {
                if (applyToDirectories) Os.chmod(child.absolutePath, mode)
                applyPermissionsRecursively(child, mode, applyToFiles, applyToDirectories)
            } else if (applyToFiles) {
                Os.chmod(child.absolutePath, mode)
            }
        }
    }

    private fun deleteInternal(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            children.forEach { child -> if (!deleteInternal(child)) return false }
        }
        return file.delete()
    }
}
