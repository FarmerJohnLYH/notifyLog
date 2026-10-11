package com.notifylog.app.collect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The dedup filter must swallow the per-second churn of progress/media
 * notifications without ever swallowing a genuinely new message that happens to
 * repeat earlier content ("ok" sent twice in one afternoon).
 */
class DedupWindowTest {

    @Before
    fun setUp() = NotificationFilter.resetDedupCache()

    private val hash = NotificationExtractor.dedupHash("k1", "标题", "正文", null)

    @Test
    fun `first sighting is not a duplicate`() {
        assertFalse(NotificationFilter.isDuplicate(hash, now = 1_000L))
    }

    @Test
    fun `an immediate re-post is a duplicate`() {
        NotificationFilter.isDuplicate(hash, now = 1_000L)
        assertTrue(NotificationFilter.isDuplicate(hash, now = 1_500L))
    }

    @Test
    fun `the same content after the window is recorded again`() {
        NotificationFilter.isDuplicate(hash, now = 1_000L)
        assertFalse(NotificationFilter.isDuplicate(hash, now = 1_000L + 60_001L))
    }

    @Test
    fun `repeated re-posts keep sliding the window`() {
        // A progress bar ticking every 30s must stay suppressed the whole time.
        var t = 0L
        assertFalse(NotificationFilter.isDuplicate(hash, now = t))
        repeat(10) {
            t += 30_000L
            assertTrue(NotificationFilter.isDuplicate(hash, now = t))
        }
    }

    @Test
    fun `different content is never a duplicate`() {
        val other = NotificationExtractor.dedupHash("k1", "标题", "另一条", null)
        assertFalse(NotificationFilter.isDuplicate(hash, now = 1_000L))
        assertFalse(NotificationFilter.isDuplicate(other, now = 1_000L))
    }

    @Test
    fun `clearing the cache lets identical content be recorded again`() {
        NotificationFilter.isDuplicate(hash, now = 1_000L)
        NotificationFilter.resetDedupCache()
        assertFalse(NotificationFilter.isDuplicate(hash, now = 1_100L))
    }
}
