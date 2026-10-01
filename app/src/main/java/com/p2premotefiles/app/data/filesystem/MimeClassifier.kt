package com.p2premotefiles.app.data.filesystem

import android.webkit.MimeTypeMap
import com.p2premotefiles.app.domain.model.FileCategory
import java.util.Locale

object MimeClassifier {
    private val textApplicationTypes = setOf(
        "application/json",
        "application/ld+json",
        "application/xml",
        "application/javascript",
        "application/x-javascript",
        "application/x-yaml",
        "application/yaml",
        "application/sql",
    )

    private val documentTypes = setOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/rtf",
        "application/epub+zip",
    )

    private val archiveTypes = setOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/x-7z-compressed",
        "application/x-rar-compressed",
        "application/vnd.rar",
        "application/gzip",
        "application/x-gzip",
        "application/x-tar",
    )

    private val textExtensions = setOf(
        "txt", "md", "markdown", "html", "htm", "css", "js", "mjs", "ts", "tsx", "jsx",
        "json", "xml", "yaml", "yml", "csv", "log", "ini", "conf", "cfg", "properties",
        "kt", "kts", "java", "c", "cpp", "h", "hpp", "py", "rb", "go", "rs", "sh", "sql",
    )

    private val archiveExtensions = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")

    fun mimeTypeFor(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    fun classify(name: String, isDirectory: Boolean): FileCategory {
        if (isDirectory) return FileCategory.FOLDER

        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = mimeTypeFor(name)

        if (extension == "apk" || mime == "application/vnd.android.package-archive") {
            return FileCategory.APK
        }
        if (mime?.startsWith("image/") == true) return FileCategory.IMAGE
        if (mime?.startsWith("video/") == true) return FileCategory.VIDEO
        if (mime?.startsWith("audio/") == true) return FileCategory.AUDIO
        if (mime?.startsWith("text/") == true || mime in textApplicationTypes || extension in textExtensions) {
            return FileCategory.TEXT
        }
        if (mime in documentTypes) return FileCategory.DOCUMENT
        if (mime in archiveTypes || extension in archiveExtensions) return FileCategory.ARCHIVE
        return FileCategory.GENERIC
    }

    fun supportsThumbnail(entryName: String, category: FileCategory): Boolean {
        if (category != FileCategory.IMAGE) return false
        return entryName.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp")
    }
}
