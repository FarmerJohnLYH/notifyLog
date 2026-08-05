package com.notifylog.app.collect

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The filter runs for every notification on the device, so its rules are the
 * difference between a useful log and a log full of music-player churn.
 */
class NotificationFilterTest {

    private val own = "com.notifylog.app"
    private val other = "com.example.chat"

    private fun skip(
        pkg: String = other,
        flags: Int = 0,
        logOngoing: Boolean = false,
        allow: Set<String> = emptySet(),
        excluded: Set<String> = emptySet()
    ) = NotificationFilter.shouldSkip(own, pkg, flags, logOngoing, allow, excluded)

    @Before
    fun setUp() = NotificationFilter.resetDedupCache()

    @Test
    fun `keeps an ordinary notification`() {
        assertFalse(skip())
    }

    @Test
    fun `skips our own notifications`() {
        assertTrue(skip(pkg = own))
    }

    @Test
    fun `skips excluded packages`() {
        assertTrue(skip(excluded = setOf(other)))
        assertFalse(skip(excluded = setOf("com.other.app")))
    }

    @Test
    fun `skips group summaries because the children carry the content`() {
        assertTrue(skip(flags = Notification.FLAG_GROUP_SUMMARY))
    }

    @Test
    fun `skips ongoing notifications by default`() {
        assertTrue(skip(flags = Notification.FLAG_ONGOING_EVENT))
        assertTrue(skip(flags = Notification.FLAG_FOREGROUND_SERVICE))
    }

    @Test
    fun `records ongoing notifications once the setting is on`() {
        assertFalse(skip(flags = Notification.FLAG_ONGOING_EVENT, logOngoing = true))
    }

    @Test
    fun `allow-list lets a specific ongoing app through`() {
        assertFalse(skip(flags = Notification.FLAG_ONGOING_EVENT, allow = setOf(other)))
        assertTrue(skip(flags = Notification.FLAG_ONGOING_EVENT, allow = setOf("com.someone.else")))
    }

    @Test
    fun `exclusion beats the ongoing allow-list`() {
        assertTrue(
            skip(
                flags = Notification.FLAG_ONGOING_EVENT,
                allow = setOf(other),
                excluded = setOf(other)
            )
        )
    }

    // ---- dedup LRU ----

    @Test
    fun `first sighting of a hash is not a duplicate, the second is`() {
        assertFalse(NotificationFilter.isDuplicate(42L))
        assertTrue(NotificationFilter.isDuplicate(42L))
    }

    @Test
    fun `distinct hashes do not collide`() {
        assertFalse(NotificationFilter.isDuplicate(1L))
        assertFalse(NotificationFilter.isDuplicate(2L))
    }

    @Test
    fun `an old hash falls out of the bounded cache`() {
        NotificationFilter.isDuplicate(0L)
        // Push well past the LRU capacity so entry 0 is evicted.
        for (i in 1L..400L) NotificationFilter.isDuplicate(i)
        assertFalse(NotificationFilter.isDuplicate(0L))
    }
}
