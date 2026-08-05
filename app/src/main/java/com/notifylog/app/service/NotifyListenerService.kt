package com.notifylog.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.notifylog.app.collect.NotificationExtractor
import com.notifylog.app.collect.NotificationFilter
import com.notifylog.app.data.LogWriter
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
        ServiceState.markConnected(applicationContext, System.currentTimeMillis())
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
        if (NotificationFilter.shouldSkip(packageName, n)) return

        val entity = NotificationExtractor.extract(applicationContext, n)

        // Drop truly empty notifications (no text preview to log).
        if (entity.title.isNullOrBlank() &&
            entity.text.isNullOrBlank() &&
            entity.bigText.isNullOrBlank() &&
            entity.subText.isNullOrBlank()
        ) return

        if (NotificationFilter.isDuplicate(entity.dedupHash)) return

        LogWriter.enqueue(entity)
    }
}
