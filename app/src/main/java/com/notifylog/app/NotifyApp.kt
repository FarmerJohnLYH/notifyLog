package com.notifylog.app

import android.app.Application
import com.notifylog.app.data.Settings
import com.notifylog.app.keepalive.KeepAliveManager
import com.notifylog.app.service.ServiceState

/**
 * Runs once per process, whatever woke it up — activity, listener bind, alarm.
 *
 * Its whole job is reconciliation. A force-stop cancels the app's alarms and
 * clears the sticky-service record, so the optional hardening the user switched
 * on would otherwise stay dead until the next reboot while the settings screen
 * still showed it as enabled. Re-applying it from here makes the stored setting
 * describe reality again.
 */
class NotifyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Settings.ensure(this)
        // Books the outage if the previous process was killed while connected.
        ServiceState.reconcileAfterProcessStart(this)
        // Cheap and safe when access was never granted; the system ignores it.
        KeepAliveManager.requestRebind(this)
        KeepAliveManager.applyHeartbeatSetting(this)
    }
}
