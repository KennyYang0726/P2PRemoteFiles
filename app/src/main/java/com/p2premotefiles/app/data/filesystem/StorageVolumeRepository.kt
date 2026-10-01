package com.p2premotefiles.app.data.filesystem

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.p2premotefiles.app.R
import com.p2premotefiles.app.domain.model.StorageKind
import com.p2premotefiles.app.domain.model.StorageVolumeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class StorageVolumeRepository(private val context: Context) {
    suspend fun listVolumes(): List<StorageVolumeInfo> = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(StorageManager::class.java)
        manager.storageVolumes.mapNotNull { volume ->
            val root = resolveRoot(volume) ?: return@mapNotNull null
            val rootFile = File(root)
            val description = runCatching { volume.getDescription(context) }.getOrNull().orEmpty()
            val kind = detectKind(volume, description)
            val stats = runCatching { StatFs(root) }.getOrNull()
            StorageVolumeInfo(
                id = volume.uuid ?: if (volume.isPrimary) "primary" else root,
                label = description.ifBlank { defaultLabel(kind) },
                rootPath = root,
                kind = kind,
                removable = volume.isRemovable,
                readable = rootFile.exists() && rootFile.canRead(),
                writable = rootFile.exists() && rootFile.canWrite(),
                totalBytes = stats?.totalBytes ?: 0L,
                availableBytes = stats?.availableBytes ?: 0L,
            )
        }.distinctBy { it.rootPath }
    }

    private fun resolveRoot(volume: StorageVolume): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory?.absolutePath?.let { return it }
        }
        if (volume.isPrimary) {
            @Suppress("DEPRECATION")
            return Environment.getExternalStorageDirectory().absolutePath
        }

        val uuid = volume.uuid
        val externalDirs = context.getExternalFilesDirs(null)
        val match = externalDirs.firstOrNull { dir ->
            dir != null && uuid != null && dir.absolutePath.contains("/$uuid/")
        } ?: return null
        val marker = "/Android/data/${context.packageName}/files"
        return match.absolutePath.substringBefore(marker).takeIf { it != match.absolutePath }
    }

    private fun detectKind(volume: StorageVolume, description: String): StorageKind {
        if (volume.isPrimary) return StorageKind.INTERNAL
        val normalized = description.lowercase(Locale.ROOT)
        if (normalized.contains("usb")) return StorageKind.USB
        if (normalized.contains("sd")) return StorageKind.SD_CARD
        return if (volume.isRemovable) StorageKind.REMOVABLE else StorageKind.UNKNOWN
    }

    private fun defaultLabel(kind: StorageKind): String = when (kind) {
        StorageKind.INTERNAL -> context.getString(R.string.storage_internal)
        StorageKind.SD_CARD -> context.getString(R.string.storage_sd_card)
        StorageKind.USB -> context.getString(R.string.storage_usb)
        StorageKind.REMOVABLE -> context.getString(R.string.storage_removable)
        StorageKind.UNKNOWN -> context.getString(R.string.storage_unknown)
    }
}
