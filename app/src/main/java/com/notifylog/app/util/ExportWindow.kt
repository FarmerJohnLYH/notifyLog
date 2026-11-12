package com.notifylog.app.util

import java.time.LocalDate
import java.time.ZoneId

/** Half-open local-day boundaries, including 23/25-hour daylight-saving days. */
data class ExportWindow(val from: Long, val until: Long) {
    companion object {
        fun days(first: LocalDate, last: LocalDate, zone: ZoneId): ExportWindow {
            require(!last.isBefore(first))
            return ExportWindow(first.atStartOfDay(zone).toInstant().toEpochMilli(),
                last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        }

        fun recentWeek(today: LocalDate, zone: ZoneId) = days(today.minusDays(6), today, zone)
    }
}
