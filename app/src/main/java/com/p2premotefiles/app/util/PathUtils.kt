package com.p2premotefiles.app.util

object PathUtils {
    fun normalize(path: String): String {
        if (path.isBlank()) return "/"
        val absolute = path.startsWith('/')
        val segments = ArrayDeque<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(segment)
            }
        }
        val joined = segments.joinToString("/")
        return when {
            absolute && joined.isEmpty() -> "/"
            absolute -> "/$joined"
            joined.isEmpty() -> "."
            else -> joined
        }
    }

    fun parent(path: String): String? {
        val normalized = normalize(path)
        if (normalized == "/") return null
        val index = normalized.lastIndexOf('/')
        return when {
            index <= 0 -> if (normalized.startsWith('/')) "/" else null
            else -> normalized.substring(0, index)
        }
    }

    fun child(parent: String, name: String): String {
        val base = normalize(parent)
        return if (base == "/") "/$name" else "$base/$name"
    }

    fun samePath(first: String, second: String): Boolean = normalize(first) == normalize(second)
}
