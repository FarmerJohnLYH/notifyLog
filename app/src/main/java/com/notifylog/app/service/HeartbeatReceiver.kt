package com.notifylog.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notifylog.app.data.Settings
import com.notifylog.app.keepalive.KeepAliveManager

/**
 * Low-frequency safety net: wakes up every ~30 minutes, asks the system to
 * rebind the listener if it looks disconnected, and re-arms itself.
 *
 * This is a fallback for ROMs that kill the process and never rebind. It is off
 * by default, and inexact by design — an alarm that can drift a few minutes is
 * enough for a liveness check and avoids needing SCHEDULE_EXACT_ALARM.
 */
class HeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_HEARTBEAT) return
        if (!Settings.heartbeatEnabled(context)) return

        // Only nudge when access is granted; otherwise there is nothing to bind.
        if (ServiceState.isNotificationAccessGranted(context) &&
            !ServiceState.isConnected(context)
        ) {
            KeepAliveManager.requestRebind(context)
        }

        // Inexact alarms are one-shot, so each firing schedules the next one.
        KeepAliveManager.scheduleHeartbeat(context)
    }

    companion object {
        const val ACTION_HEARTBEAT = "com.notifylog.app.action.HEARTBEAT"
    }
}
