package com.notifylog.app.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
import com.notifylog.app.data.Settings as AppSettings
import com.notifylog.app.service.HeartbeatReceiver
import com.notifylog.app.service.KeepAliveService
import com.notifylog.app.service.NotifyListenerService
import java.util.concurrent.TimeUnit

/**
 * Everything that fights to keep the listener bound, in one place.
 *
 * The listener is normally kept alive by the system itself; these are the
 * fallbacks for ROMs (HyperOS in particular) that kill the process and then
 * decline to rebind it.
 */
object KeepAliveManager {

    /**
     * Heartbeat period. Deliberately inexact: `setAndAllowWhileIdle` needs no
     * permission, while the exact variant would require SCHEDULE_EXACT_ALARM —
     * a heavier ask than a rebind check that can drift by a few minutes.
     */
    private val HEARTBEAT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(30)

    private const val HEARTBEAT_REQUEST = 1001

    fun listenerComponent(context: Context) =
        ComponentName(context.applicationContext, NotifyListenerService::class.java)

    /** Ask the system to (re)bind the listener. Safe to call from anywhere. */
    fun requestRebind(context: Context) {
        runCatching {
            NotificationListenerService.requestRebind(listenerComponent(context))
        }
    }

    // ---- optional foreground service ----

    fun applyKeepAliveSetting(context: Context) {
        if (AppSettings.keepAliveEnabled(context)) startKeepAlive(context) else stopKeepAlive(context)
    }

    fun startKeepAlive(context: Context) {
        runCatching {
            val intent = Intent(context, KeepAliveService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    fun stopKeepAlive(context: Context) {
        runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
    }

    // ---- optional heartbeat ----

    fun applyHeartbeatSetting(context: Context) {
        if (AppSettings.heartbeatEnabled(context)) scheduleHeartbeat(context)
        else cancelHeartbeat(context)
    }

    fun scheduleHeartbeat(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, heartbeatIntent(context))
        }
    }

    fun cancelHeartbeat(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(heartbeatIntent(context)) }
    }

    private fun heartbeatIntent(context: Context): PendingIntent {
        val intent = Intent(context, HeartbeatReceiver::class.java)
            .setAction(HeartbeatReceiver.ACTION_HEARTBEAT)
        return PendingIntent.getBroadcast(
            context,
            HEARTBEAT_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---- battery optimisation ----

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * Opens the battery-optimisation exemption prompt.
     *
     * Requesting it directly needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which
     * app stores flag; this build is sideloaded for personal use, so it holds
     * the permission and falls back to the settings list if the dialog is
     * unavailable on this ROM.
     */
    @android.annotation.SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(context, direct)) return true

        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(context, list)
    }

    // ---- shared helpers ----

    fun tryStart(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("Redmi", ignoreCase = true) ||
            Build.BRAND.equals("POCO", ignoreCase = true)
}
