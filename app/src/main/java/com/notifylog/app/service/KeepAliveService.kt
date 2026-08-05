package com.notifylog.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.notifylog.app.R
import com.notifylog.app.data.NotifyDatabase
import com.notifylog.app.data.Settings
import com.notifylog.app.keepalive.KeepAliveManager
import com.notifylog.app.ui.LogListActivity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Optional hardening: an empty foreground service whose only job is to raise the
 * process's importance so aggressive ROMs are less eager to kill it.
 *
 * It does no work — the capture path is entirely inside
 * [NotifyListenerService]. It exists purely because on HyperOS a process with a
 * foreground service survives cleanups that a plain bound service does not.
 * Off by default; the user turns it on in settings.
 *
 * The resident notification doubles as a live counter ("n captured in the last
 * hour"), refreshed by [LogWriter][com.notifylog.app.data.LogWriter] after each
 * write batch — event-driven, so a quiet device costs nothing. Between arrivals
 * the number can go stale; the next capture corrects it.
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.keepAliveEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        // A rebind request costs nothing here and covers the case where the
        // listener was unbound while this service was being (re)started.
        KeepAliveManager.requestRebind(this)
        // Show a real count right away instead of placeholder text.
        scope.launch { refreshStats(applicationContext) }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = build(this, getString(R.string.keepalive_notification_text))
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // targetSdk 34+ requires a declared type; none of the system
                // categories describe "keep a notification listener resident",
                // so specialUse (documented in the manifest) is the honest one.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                running = true
            } else {
                startForeground(NOTIFICATION_ID, notification)
                running = true
            }
        }.onFailure { stopSelf() }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keepalive_channel_name),
            // MIN keeps it silent and collapsed at the bottom of the shade.
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.keepalive_channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "keepalive"
        private const val NOTIFICATION_ID = 1

        /** True while the foreground notification is showing. */
        @Volatile
        var running = false
            private set

        /**
         * Re-queries the last-hour count and repaints the resident notification.
         * No-op unless the service is actually in the foreground.
         */
        suspend fun refreshStats(context: Context) {
            if (!running) return
            val app = context.applicationContext
            runCatching {
                val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
                val n = NotifyDatabase.get(app).notifyDao().countSince(cutoff)
                val nm = app.getSystemService(NotificationManager::class.java) ?: return
                nm.notify(NOTIFICATION_ID, build(app, app.getString(R.string.keepalive_stats, n)))
            }
        }

        private fun build(context: Context, text: String): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, LogListActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.keepalive_notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentIntent(open)
                .setOngoing(true)
                // Hide from the shade as far as the platform allows: this is
                // plumbing, not something the user needs to read.
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setShowWhen(false)
                .build()
        }
    }
}
