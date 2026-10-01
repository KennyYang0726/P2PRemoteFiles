package com.p2premotefiles.app.domain.model

enum class FileCategory {
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    TEXT,
    DOCUMENT,
    ARCHIVE,
    APK,
    GENERIC,
}

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val mimeType: String?,
    val category: FileCategory,
)

data class FilePermissions(
    val mode: Int,
    val uid: Int? = null,
    val gid: Int? = null,
) {
    val normalizedMode: Int get() = mode and 0x1FF

    fun has(mask: Int): Boolean = normalizedMode and mask != 0

    fun with(mask: Int, enabled: Boolean): FilePermissions {
        val next = if (enabled) normalizedMode or mask else normalizedMode and mask.inv()
        return copy(mode = next)
    }

    fun symbolic(): String = buildString(9) {
        append(if (has(OWNER_READ)) 'r' else '-')
        append(if (has(OWNER_WRITE)) 'w' else '-')
        append(if (has(OWNER_EXECUTE)) 'x' else '-')
        append(if (has(GROUP_READ)) 'r' else '-')
        append(if (has(GROUP_WRITE)) 'w' else '-')
        append(if (has(GROUP_EXECUTE)) 'x' else '-')
        append(if (has(OTHER_READ)) 'r' else '-')
        append(if (has(OTHER_WRITE)) 'w' else '-')
        append(if (has(OTHER_EXECUTE)) 'x' else '-')
    }

    fun octal(): String = Integer.toOctalString(normalizedMode).padStart(3, '0')

    companion object {
        const val OWNER_READ = 0x100
        const val OWNER_WRITE = 0x080
        const val OWNER_EXECUTE = 0x040
        const val GROUP_READ = 0x020
        const val GROUP_WRITE = 0x010
        const val GROUP_EXECUTE = 0x008
        const val OTHER_READ = 0x004
        const val OTHER_WRITE = 0x002
        const val OTHER_EXECUTE = 0x001
    }
}

enum class StorageKind {
    INTERNAL,
    SD_CARD,
    USB,
    REMOVABLE,
    UNKNOWN,
}

data class StorageVolumeInfo(
    val id: String,
    val label: String,
    val rootPath: String,
    val kind: StorageKind,
    val removable: Boolean,
    val readable: Boolean,
    val writable: Boolean,
    val totalBytes: Long = 0L,
    val availableBytes: Long = 0L,
)

enum class FileError {
    PERMISSION_DENIED,
    NOT_FOUND,
    CONFLICT,
    READ_ONLY,
    NO_SPACE,
    INVALID_PATH,
    UNKNOWN,
}

class FileAccessException(
    val error: FileError,
    cause: Throwable? = null,
    val detail: String? = cause?.message,
) : Exception(detail, cause)

enum class FileFilter {
    ALL,
    FOLDERS,
    FILES,
}

enum class SortField {
    NAME,
    MODIFIED,
    SIZE,
    TYPE,
}

enum class SortDirection {
    ASCENDING,
    DESCENDING,
}
