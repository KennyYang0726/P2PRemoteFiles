package com.p2premotefiles.app.util

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import java.util.Date

object DisplayFormatters {
    fun fileSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun dateTime(context: Context, millis: Long): String {
        if (millis <= 0L) return ""
        val date = Date(millis)
        val datePart = DateFormat.getDateFormat(context).format(date)
        val timePart = DateFormat.getTimeFormat(context).format(date)
        return "$datePart $timePart"
    }

    fun deviceId(raw: String): String = raw.chunked(3).joinToString(" ")
}
