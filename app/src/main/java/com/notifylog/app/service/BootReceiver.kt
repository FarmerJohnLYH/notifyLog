package com.notifylog.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notifylog.app.data.Settings
import com.notifylog.app.keepalive.KeepAliveManager

/**
 * Fallback for ROMs that don't auto-rebind the listener after a reboot or app
 * update: ask the system to rebind, and restore the optional hardening the user
 * had switched on (those don't survive a reboot on their own).
 *
 * Direct-boot aware, so it also runs at LOCKED_BOOT_COMPLETED — before first
 * unlock the app's own storage is not readable yet, so that pass only issues
 * the rebind request and leaves the rest to BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // Credential-protected storage is still locked here; a rebind
                // request needs no storage, anything reading settings does.
                KeepAliveManager.requestRebind(context)
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                KeepAliveManager.requestRebind(context)
                runCatching {
                    Settings.ensure(context)
                    KeepAliveManager.applyKeepAliveSetting(context)
                    KeepAliveManager.applyHeartbeatSetting(context)
                }
            }
        }
    }
}
