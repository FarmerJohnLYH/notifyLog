package com.notifylog.app.collect

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.notifylog.app.data.Settings

/**
 * Decides which notifications to keep, and suppresses rapid duplicate updates
 * of the same notification (progress bars, re-posts) via a small in-memory LRU.
 *
 * Runs on the binder callback for every notification on the device, so it does
 * no I/O: the settings it consults are volatile mirrors kept fresh by
 * [Settings], not SharedPreferences reads.
 */
object NotificationFilter {

    private const val DEDUP_LRU_SIZE = 200

    /**
     * How long the same content is treated as a re-post rather than a new
     * notification. Long enough to swallow the per-second churn of progress and
     * media notifications, short enough that a contact sending "ok" twice in an
     * afternoon is recorded twice — the count-only cap this replaces could
     * suppress that for days on a quiet device.
     */
    private const val DEDUP_WINDOW_MS = 60_000L

    // hash -> last time it was seen; capped for memory, aged out by time
    private val recent = object : LinkedHashMap<Long, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>): Boolean =
            size > DEDUP_LRU_SIZE
    }

    fun shouldSkip(ownPackage: String, sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return true
        return shouldSkip(
            ownPackage = ownPackage,
            packageName = sbn.packageName,
            flags = n.flags,
            logOngoing = Settings.logOngoing,
            ongoingAllowList = Settings.ongoingAllowList,
            excludedPackages = Settings.excludedPackages
        )
    }

    /**
     * The decision itself, with the settings passed in rather than read from
     * the singleton — keeps the rule set testable without an Android runtime.
     */
    internal fun shouldSkip(
        ownPackage: String,
        packageName: String,
        flags: Int,
        logOngoing: Boolean,
        ongoingAllowList: Set<String>,
        excludedPackages: Set<String>
    ): Boolean {
        if (packageName == ownPackage) return true
        if (packageName in excludedPackages) return true

        // A group summary carries no content of its own; the children have it.
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return true

        if (isOngoing(flags) && !logOngoing && packageName !in ongoingAllowList) {
            // Music players, downloads and navigation re-post several times a
            // second; recording them is the single biggest source of write churn.
            return true
        }
        return false
    }

    private fun isOngoing(flags: Int): Boolean =
        flags and Notification.FLAG_ONGOING_EVENT != 0 ||
            flags and Notification.FLAG_FOREGROUND_SERVICE != 0

    /** True if this exact content was already seen within [DEDUP_WINDOW_MS]. */
    @Synchronized
    fun isDuplicate(hash: Long, now: Long = System.currentTimeMillis()): Boolean {
        val previous = recent.put(hash, now)
        return previous != null && now - previous < DEDUP_WINDOW_MS
    }

    /** Drops the dedup memory; used by tests and after a "clear all". */
    @Synchronized
    fun resetDedupCache() = recent.clear()
}
