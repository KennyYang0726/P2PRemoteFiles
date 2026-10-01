package com.p2premotefiles.app.data.filesystem

import android.system.ErrnoException
import android.system.OsConstants
import com.p2premotefiles.app.domain.model.FileAccessException
import com.p2premotefiles.app.domain.model.FileError
import java.io.FileNotFoundException
import java.io.IOException

object FileErrorMapper {
    fun fromThrowable(throwable: Throwable): FileAccessException {
        val errno = generateSequence(throwable as Throwable?) { it.cause }
            .filterIsInstance<ErrnoException>()
            .firstOrNull()
            ?.errno

        val error = when {
            throwable is SecurityException -> FileError.PERMISSION_DENIED
            errno == OsConstants.EACCES || errno == OsConstants.EPERM -> FileError.PERMISSION_DENIED
            errno == OsConstants.ENOENT -> FileError.NOT_FOUND
            errno == OsConstants.EROFS -> FileError.READ_ONLY
            errno == OsConstants.ENOSPC -> FileError.NO_SPACE
            throwable is FileNotFoundException && throwable.message.orEmpty().contains("Permission denied", ignoreCase = true) -> FileError.PERMISSION_DENIED
            throwable is FileNotFoundException -> FileError.NOT_FOUND
            throwable is IOException && throwable.message.orEmpty().contains("Permission denied", ignoreCase = true) -> FileError.PERMISSION_DENIED
            else -> FileError.UNKNOWN
        }
        val detail = generateSequence(throwable as Throwable?) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?.take(600)
        return FileAccessException(error, throwable, detail)
    }
}
