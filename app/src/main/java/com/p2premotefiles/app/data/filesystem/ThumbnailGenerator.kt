package com.p2premotefiles.app.data.filesystem

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.p2premotefiles.app.domain.filesystem.FileSystemBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class ThumbnailGenerator(private val backend: FileSystemBackend) {
    suspend fun generate(path: String, maxSizePx: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            backend.openRead(path).use { descriptor ->
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxSizePx)
            }
            val bitmap = backend.openRead(path).use { descriptor ->
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, options)
            } ?: return@runCatching null

            bitmap.useBitmap { source ->
                val scaled = scaleDown(source, maxSizePx)
                ByteArrayOutputStream().use { output ->
                    @Suppress("DEPRECATION")
                    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        Bitmap.CompressFormat.WEBP
                    }
                    if (!scaled.compress(format, 82, output)) return@use null
                    output.toByteArray()
                }.also {
                    if (scaled !== source) scaled.recycle()
                }
            }
        }.getOrNull()
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxSize || height / (sample * 2) >= maxSize) {
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, maxSize: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxSize) return bitmap
        val ratio = maxSize.toFloat() / largest.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T {
        try {
            return block(this)
        } finally {
            recycle()
        }
    }
}
