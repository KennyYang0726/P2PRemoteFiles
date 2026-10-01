package com.p2premotefiles.app.network.lan

import com.p2premotefiles.app.domain.model.FileCategory
import com.p2premotefiles.app.domain.model.FileEntry
import com.p2premotefiles.app.domain.model.FileError
import com.p2premotefiles.app.domain.model.FilePermissions
import com.p2premotefiles.app.domain.model.StorageKind
import com.p2premotefiles.app.domain.model.StorageVolumeInfo
import org.json.JSONArray
import org.json.JSONObject

internal object LanRpcCodec {
    fun fileEntry(entry: FileEntry): JSONObject = JSONObject()
        .put("name", entry.name)
        .put("path", entry.path)
        .put("directory", entry.isDirectory)
        .put("size", entry.size)
        .put("modifiedAt", entry.modifiedAt)
        .put("mimeType", entry.mimeType ?: JSONObject.NULL)
        .put("category", entry.category.name)

    fun fileEntry(json: JSONObject): FileEntry = FileEntry(
        name = json.getString("name"),
        path = json.getString("path"),
        isDirectory = json.getBoolean("directory"),
        size = json.getLong("size"),
        modifiedAt = json.getLong("modifiedAt"),
        mimeType = json.opt("mimeType")?.takeUnless { it == JSONObject.NULL } as? String,
        category = runCatching { FileCategory.valueOf(json.getString("category")) }
            .getOrDefault(FileCategory.GENERIC),
    )

    fun fileEntries(entries: List<FileEntry>): JSONArray = JSONArray().apply {
        entries.forEach { put(fileEntry(it)) }
    }

    fun fileEntries(array: JSONArray): List<FileEntry> = buildList(array.length()) {
        for (index in 0 until array.length()) add(fileEntry(array.getJSONObject(index)))
    }

    fun volume(volume: StorageVolumeInfo): JSONObject = JSONObject()
        .put("id", volume.id)
        .put("label", volume.label)
        .put("rootPath", volume.rootPath)
        .put("kind", volume.kind.name)
        .put("removable", volume.removable)
        .put("readable", volume.readable)
        .put("writable", volume.writable)
        .put("totalBytes", volume.totalBytes)
        .put("availableBytes", volume.availableBytes)

    fun volume(json: JSONObject): StorageVolumeInfo = StorageVolumeInfo(
        id = json.getString("id"),
        label = json.getString("label"),
        rootPath = json.getString("rootPath"),
        kind = runCatching { StorageKind.valueOf(json.getString("kind")) }
            .getOrDefault(StorageKind.UNKNOWN),
        removable = json.getBoolean("removable"),
        readable = json.getBoolean("readable"),
        writable = json.getBoolean("writable"),
        totalBytes = json.optLong("totalBytes", 0L),
        availableBytes = json.optLong("availableBytes", 0L),
    )

    fun volumes(volumes: List<StorageVolumeInfo>): JSONArray = JSONArray().apply {
        volumes.forEach { put(volume(it)) }
    }

    fun volumes(array: JSONArray): List<StorageVolumeInfo> = buildList(array.length()) {
        for (index in 0 until array.length()) add(volume(array.getJSONObject(index)))
    }

    fun permissions(value: FilePermissions): JSONObject = JSONObject()
        .put("mode", value.normalizedMode)
        .put("uid", value.uid ?: JSONObject.NULL)
        .put("gid", value.gid ?: JSONObject.NULL)

    fun permissions(json: JSONObject): FilePermissions = FilePermissions(
        mode = json.optInt("mode", 0) and 0x1FF,
        uid = if (json.isNull("uid")) null else json.optInt("uid"),
        gid = if (json.isNull("gid")) null else json.optInt("gid"),
    )

    fun errorName(error: FileError): String = error.name

    fun error(value: String): FileError = runCatching { FileError.valueOf(value) }
        .getOrDefault(FileError.UNKNOWN)
}
