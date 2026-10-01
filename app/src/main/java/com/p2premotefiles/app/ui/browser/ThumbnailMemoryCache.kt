package com.p2premotefiles.app.ui.browser

import android.util.LruCache
import com.p2premotefiles.app.domain.model.FileEntry
import com.p2premotefiles.app.remote.RemoteFileClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ThumbnailMemoryCache(private val client: RemoteFileClient) {
    private val cache = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val mutex = Mutex()

    suspend fun get(entry: FileEntry, maxSizePx: Int): ByteArray? {
        val key = buildKey(entry, maxSizePx)
        cache.get(key)?.let { return it }
        return mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            client.thumbnail(entry.path, maxSizePx)?.also { cache.put(key, it) }
        }
    }

    private fun buildKey(entry: FileEntry, maxSizePx: Int): String =
        "${entry.path}|${entry.size}|${entry.modifiedAt}|$maxSizePx"
}
