package com.notifylog.app.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The dedup hash is what stops a progress bar re-posting 60 times a minute from
 * becoming 60 rows, so it has to be stable across restarts and sensitive to
 * every field it claims to cover.
 */
class DedupHashTest {

    @Test
    fun `identical input gives identical hash`() {
        val a = NotificationExtractor.dedupHash("key", "title", "text", "big")
        val b = NotificationExtractor.dedupHash("key", "title", "text", "big")
        assertEquals(a, b)
    }

    @Test
    fun `each field changes the hash`() {
        val base = NotificationExtractor.dedupHash("key", "title", "text", "big")
        assertNotEquals(base, NotificationExtractor.dedupHash("key2", "title", "text", "big"))
        assertNotEquals(base, NotificationExtractor.dedupHash("key", "title2", "text", "big"))
        assertNotEquals(base, NotificationExtractor.dedupHash("key", "title", "text2", "big"))
        assertNotEquals(base, NotificationExtractor.dedupHash("key", "title", "text", "big2"))
    }

    @Test
    fun `nulls are distinct from empty strings only where it matters`() {
        // Both collapse to "" by design; what matters is that this is stable
        // and doesn't throw.
        assertEquals(
            NotificationExtractor.dedupHash(null, null, null, null),
            NotificationExtractor.dedupHash("", "", "", "")
        )
    }

    @Test
    fun `field boundaries cannot be forged by shifting content across fields`() {
        // Without a separator, ("ab", "c") and ("a", "bc") would collide.
        val left = NotificationExtractor.dedupHash("k", "ab", "c", null)
        val right = NotificationExtractor.dedupHash("k", "a", "bc", null)
        assertNotEquals(left, right)
    }

    @Test
    fun `hash is independent of when it is computed`() {
        val first = NotificationExtractor.dedupHash("k", "t", "x", null)
        Thread.sleep(5)
        assertEquals(first, NotificationExtractor.dedupHash("k", "t", "x", null))
    }
}
