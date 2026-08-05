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
 * The live flag lives in SharedPreferences (cheap, readable from anywhere); the
 * transition history goes to the database, where it can be paired into gaps.
 */
object ServiceState {

    private const val PREF = "notify_state"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_LAST_CONNECTED = "last_connected"
    private const val KEY_LAST_DISCONNECTED = "last_disconnected"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun markConnected(context: Context, now: Long) {
        val p = prefs(context)
        // Only record a transition when the state actually changed, so a rebind
        // storm doesn't fill the table with no-op events.
        val changed = !p.getBoolean(KEY_CONNECTED, false)
        p.edit {
            putBoolean(KEY_CONNECTED, true)
            putLong(KEY_LAST_CONNECTED, now)
        }
        if (changed) recordEvent(context, now, connected = true)
    }

    fun markDisconnected(context: Context, now: Long) {
        val p = prefs(context)
        val changed = p.getBoolean(KEY_CONNECTED, false)
        p.edit {
            putBoolean(KEY_CONNECTED, false)
            putLong(KEY_LAST_DISCONNECTED, now)
        }
        if (changed) recordEvent(context, now, connected = false)
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

    fun isConnected(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONNECTED, false)

    fun lastConnected(context: Context): Long =
        prefs(context).getLong(KEY_LAST_CONNECTED, 0L)

    fun lastDisconnected(context: Context): Long =
        prefs(context).getLong(KEY_LAST_DISCONNECTED, 0L)

    /**
     * Whether the user has granted notification access, read straight from
     * Settings.Secure. This is the authoritative source: unlike [isConnected] it
     * stays correct even if the process was killed before it could record its
     * own disconnect.
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
