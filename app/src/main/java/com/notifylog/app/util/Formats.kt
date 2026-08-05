package com.notifylog.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Shared, locale-aware formatting helpers. */
object Formats {

    private val timeFmt = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    }

    private val fullFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    private val isoFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    }

    fun time(millis: Long): String = timeFmt.get()!!.format(Date(millis))

    fun dateTime(millis: Long): String = fullFmt.get()!!.format(Date(millis))

    fun iso(millis: Long): String = isoFmt.get()!!.format(Date(millis))

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 ->
            String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
        else ->
            String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    /** Compact duration for outage windows: "2小时13分" style, largest two units. */
    fun duration(millis: Long): String {
        val days = TimeUnit.MILLISECONDS.toDays(millis)
        val hours = TimeUnit.MILLISECONDS.toHours(millis) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return when {
            days > 0 -> "${days}天${hours}小时"
            hours > 0 -> "${hours}小时${minutes}分"
            minutes > 0 -> "${minutes}分${seconds}秒"
            else -> "${seconds}秒"
        }
    }
}
