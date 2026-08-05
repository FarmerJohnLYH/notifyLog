package com.notifylog.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folding connect/disconnect events into outage windows. The input arrives
 * newest-first (that's how the DAO returns it), which is easy to get backwards.
 */
class GapFoldingTest {

    private fun event(time: Long, connected: Boolean) =
        ListenerEventEntity(time = time, connected = connected)

    @Test
    fun `no events means no gaps`() {
        assertTrue(ListenerEventDao.toGaps(emptyList()).isEmpty())
    }

    @Test
    fun `a closed outage pairs the disconnect with the following connect`() {
        // Newest first: connected at 200, disconnected at 100.
        val gaps = ListenerEventDao.toGaps(
            listOf(event(200, true), event(100, false))
        )
        assertEquals(1, gaps.size)
        assertEquals(100L, gaps[0].start)
        assertEquals(200L, gaps[0].end)
        assertEquals(100L, gaps[0].durationMs)
    }

    @Test
    fun `an outage still in progress has no end`() {
        val gaps = ListenerEventDao.toGaps(listOf(event(100, false)))
        assertEquals(1, gaps.size)
        assertEquals(100L, gaps[0].start)
        assertNull(gaps[0].end)
        assertNull(gaps[0].durationMs)
    }

    @Test
    fun `multiple outages are reported newest first`() {
        // timeline: down@100 up@200 down@300 up@400
        val gaps = ListenerEventDao.toGaps(
            listOf(
                event(400, true),
                event(300, false),
                event(200, true),
                event(100, false)
            )
        )
        assertEquals(2, gaps.size)
        assertEquals(300L, gaps[0].start)
        assertEquals(400L, gaps[0].end)
        assertEquals(100L, gaps[1].start)
        assertEquals(200L, gaps[1].end)
    }

    @Test
    fun `a connect with no preceding disconnect produces no gap`() {
        assertTrue(ListenerEventDao.toGaps(listOf(event(100, true))).isEmpty())
    }

    @Test
    fun `a truncated window does not invent an end for the oldest outage`() {
        // The query is limited, so the oldest row can be a disconnect whose
        // matching connect fell outside the window — but we still know it is
        // bounded above by the next connect we *do* have.
        val gaps = ListenerEventDao.toGaps(
            listOf(event(300, true), event(200, false), event(100, false))
        )
        assertEquals(2, gaps.size)
        assertEquals(200L, gaps[0].start)
        assertEquals(300L, gaps[0].end)
        // Two disconnects in a row: the older one has no connect between them.
        assertEquals(100L, gaps[1].start)
        assertNull(gaps[1].end)
    }
}
