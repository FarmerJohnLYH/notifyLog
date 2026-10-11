package com.notifylog.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.notifylog.app.collect.NotificationExtractor
import com.notifylog.app.collect.NotificationFilter
import com.notifylog.app.data.LogWriter
import com.notifylog.app.data.NotificationEntity
import com.notifylog.app.data.Settings
import com.notifylog.app.keepalive.KeepAliveManager

/**
 * The core: the system binds this once the user grants notification access, and
 * rebinds it on boot or after a kill.
 *
 * The binder callback is kept deliberately cheap — filter, extract, enqueue —
 * and never touches disk, because it runs for every notification on the device.
 */
class NotifyListenerService : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        Settings.ensure(applicationContext)
        LogWriter.start(applicationContext)
    }

    override fun onDestroy() {
        // The process may be going away; get the buffered rows onto disk.
        LogWriter.flushBlocking()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Settings.ensure(applicationContext)
        LogWriter.start(applicationContext)
        // Read the liveness mark *before* marking connected: it is the moment
        // capture stopped, which is exactly the cutoff for the catch-up below.
        val outageStart = ServiceState.lastAliveAt(applicationContext)
        ServiceState.markConnected(applicationContext, System.currentTimeMillis())
        catchUpOnActiveNotifications(outageStart)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        ServiceState.markDisconnected(applicationContext, System.currentTimeMillis())
        LogWriter.flushBlocking()
        // Proactively ask the system to rebind (helps on aggressive ROMs).
        KeepAliveManager.requestRebind(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        // Proof of life even for notifications we end up discarding: a quiet or
        // fully-filtered stretch must not later look like an outage.
        ServiceState.touch(applicationContext, System.currentTimeMillis())
        record(n)
    }

    /**
     * Stores everything still showing in the shade at bind time.
     *
     * After an outage — a ROM kill, a reboot, a revoked-and-regranted
     * permission — the notifications posted while we were unbound are simply
     * gone as far as callbacks are concerned, but the ones that are still active
     * can be read back.
     *
     * Only entries posted after [since] are taken. Without that cutoff every
     * rebind would re-store whatever happens to be sitting in the shade, so a
     * long-lived notification would accumulate one copy per reconnect.
     */
    private fun catchUpOnActiveNotifications(since: Long) {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        var recovered = 0
        for (sbn in active) {
            if (sbn.postTime <= since) continue
            if (record(sbn)) recovered++
        }
        if (recovered > 0) Log.i(TAG, "recovered $recovered notifications missed while unbound")
    }

    /** Shared capture path. Returns true when the notification was enqueued. */
    private fun record(sbn: StatusBarNotification): Boolean {
        if (NotificationFilter.shouldSkip(packageName, sbn)) return false

        val entity: NotificationEntity =
            runCatching { NotificationExtractor.extract(applicationContext, sbn) }
                .getOrElse { return false }

        // Drop truly empty notifications (no text preview to log).
        if (entity.title.isNullOrBlank() &&
            entity.text.isNullOrBlank() &&
            entity.bigText.isNullOrBlank() &&
            entity.subText.isNullOrBlank()
        ) return false

        if (NotificationFilter.isDuplicate(entity.dedupHash)) return false

        LogWriter.enqueue(entity)
        return true
    }

    private companion object {
        const val TAG = "NotifyListener"
    }
}
