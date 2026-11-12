package com.notifylog.app.data

import com.notifylog.app.util.ExportWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportWindowTest {
    @Test fun todayUsesLocalMidnightAndExclusiveEnd() {
        val day = LocalDate.of(2026, 11, 12)
        val range = ExportWindow.days(day, day, ZoneId.of("Asia/Shanghai"))
        assertEquals(Instant.parse("2026-11-11T16:00:00Z").toEpochMilli(), range.from)
        assertEquals(Instant.parse("2026-11-12T16:00:00Z").toEpochMilli(), range.until)
    }

    @Test fun weekIncludesTodayAcrossYearBoundary() {
        val range = ExportWindow.recentWeek(LocalDate.of(2027, 1, 3), ZoneId.of("UTC"))
        assertEquals(Instant.parse("2026-12-28T00:00:00Z").toEpochMilli(), range.from)
        assertEquals(Instant.parse("2027-01-04T00:00:00Z").toEpochMilli(), range.until)
    }

    @Test fun springDayHasTwentyThreeHours() {
        val day = LocalDate.of(2026, 3, 8)
        val range = ExportWindow.days(day, day, ZoneId.of("America/New_York"))
        assertEquals(23L * 3600000, range.until - range.from)
    }

    @Test fun autumnDayHasTwentyFiveHours() {
        val day = LocalDate.of(2026, 11, 1)
        val range = ExportWindow.days(day, day, ZoneId.of("America/New_York"))
        assertEquals(25L * 3600000, range.until - range.from)
    }

    @Test(expected = IllegalArgumentException::class) fun reversedRangeRejected() {
        ExportWindow.days(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 11), ZoneId.of("UTC"))
    }
}
