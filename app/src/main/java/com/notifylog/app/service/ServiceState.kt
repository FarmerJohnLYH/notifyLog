package com.notifylog.app.service

import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import android.provider.Settings as AndroidSettings
import com.notifylog.app.data.ListenerEventEntity
import com.notifylog.app.data.NotifyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tracks whether the listener is currently bound, and records every transition
 * so the UI can show *when* capture was down rather than silently missing rows.
 *
 * The live flag is in-memory on purpose. A persisted "connected" boolean cannot
 * be trusted: when the ROM kills the process, `onListenerDisconnected` is never
 * delivered, so the stored value stays `true` forever — and the UI would then
 * report "recording" while nothing is being captured, and never ask for a
 * rebind. Since this is a single-process app, an in-memory flag *is* the truth:
 * a fresh process starts disconnected until the system binds the listener.
 *
 * The stored copy is kept only to detect that exact case on the next start
 * ([reconcileAfterProcessStart]) and to timestamp the outage.
 */
object ServiceState {

    private const val PREF = "notify_state"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_LAST_CONNECTED = "last_connected"
    private const val KEY_LAST_DISCONNECTED = "last_disconnected"

    /** Last moment we know capture was alive; used to date an unclean outage. */
    private const val KEY_LAST_ALIVE = "last_alive"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** In-process truth: set only by the listener's own callbacks. */
    @Volatile
    private var live = false

    @Volatile
    private var reconciled = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun markConnected(context: Context, now: Long) {
        reconcileAfterProcessStart(context)
        val changed = !live
        live = true
        prefs(context).edit {
            putBoolean(KEY_CONNECTED, true)
            putLong(KEY_LAST_CONNECTED, now)
            putLong(KEY_LAST_ALIVE, now)
        }
        if (changed) recordEvent(context, now, connected = true)
    }

    fun markDisconnected(context: Context, now: Long) {
        val changed = live
        live = false
        prefs(context).edit {
            putBoolean(KEY_CONNECTED, false)
            putLong(KEY_LAST_DISCONNECTED, now)
        }
        if (changed) recordEvent(context, now, connected = false)
    }

    /** Don't write the liveness mark more than once a minute. */
    private const val TOUCH_INTERVAL_MS = 60_000L

    @Volatile
    private var lastTouch = 0L

    /**
     * Refreshes the "capture was alive at" mark, so an outage caused by a kill
     * is dated to when capture actually stopped rather than to whenever the user
     * happened to reopen the app.
     *
     * Called from the capture callback — including for notifications that get
     * filtered out — because a quiet hour is not an outage, and dating the gap
     * from the last *stored* row would invent one.
     */
    fun touch(context: Context, now: Long) {
        if (!live) return
        if (now - lastTouch < TOUCH_INTERVAL_MS) return
        lastTouch = now
        prefs(context).edit { putLong(KEY_LAST_ALIVE, now) }
    }

    /**
     * Detects "the previous process died while connected" and books the outage.
     *
     * Runs once per process, from whichever entry point comes first. Without it
     * the gap list would silently swallow every ROM kill, which is the one thing
     * the gap list exists to show.
     */
    @Synchronized
    fun reconcileAfterProcessStart(context: Context) {
        if (reconciled) return
        reconciled = true
        val p = prefs(context)
        if (!p.getBoolean(KEY_CONNECTED, false)) return
        // Stored says connected but this process has never been bound, so the
        // last process was killed. Date the outage at its last sign of life.
        val at = p.getLong(KEY_LAST_ALIVE, 0L)
            .takeIf { it > 0 } ?: p.getLong(KEY_LAST_CONNECTED, 0L)
        p.edit {
            putBoolean(KEY_CONNECTED, false)
            putLong(KEY_LAST_DISCONNECTED, at)
        }
        if (at > 0) recordEvent(context, at, connected = false)
    }

    private fun recordEvent(context: Context, time: Long, connected: Boolean) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                NotifyDatabase.get(app).listenerEventDao()
                    .insert(ListenerEventEntity(time = time, connected = connected))
            }
        }
    }

    /**
     * Whether capture is running *right now*, in this process. Callers use it to
     * decide whether to warn the user and request a rebind.
     */
    fun isConnected(context: Context): Boolean {
        reconcileAfterProcessStart(context)
        return live
    }

    /** When capture was last known to be running; 0 if it never has been. */
    fun lastAliveAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_ALIVE, 0L)

    fun lastConnected(context: Context): Long =
        prefs(context).getLong(KEY_LAST_CONNECTED, 0L)

    fun lastDisconnected(context: Context): Long =
        prefs(context).getLong(KEY_LAST_DISCONNECTED, 0L)

    /**
     * Whether the user has granted notification access, read straight from
     * Settings.Secure. Authoritative and cheap; unlike [isConnected] it says
     * nothing about whether the system has actually bound us yet.
     */
    fun isNotificationAccessGranted(context: Context): Boolean {
        val flat = AndroidSettings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.split(':').any { entry ->
            ComponentName.unflattenFromString(entry)?.packageName == context.packageName
        }
    }
}
