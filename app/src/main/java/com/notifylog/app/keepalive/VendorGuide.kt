package com.notifylog.app.keepalive

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Deep links into HyperOS / MIUI's background-management screens.
 *
 * Component names here are not public API and have moved between HyperOS
 * releases, so every entry point walks a chain of candidates and finally falls
 * back to the standard app-details page. A dead deep link must degrade to "the
 * user lands somewhere useful", never to a crash.
 */
object VendorGuide {

    /**
     * Auto-start permission — the single most important switch on HyperOS.
     * Without it the system will not restart the process after a kill, no
     * matter that the listener is a system-bound service.
     */
    fun openAutoStart(context: Context, appLabel: String): Boolean {
        val candidates = listOf(
            // HyperOS / recent MIUI
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            // Older MIUI builds
            Intent("miui.intent.action.OP_AUTO_START")
                .addCategory(Intent.CATEGORY_DEFAULT),
            // Security centre home, as a coarser landing spot
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.securitycenter.Main"
                )
            )
        )
        return startFirstAvailable(context, candidates, appLabel)
    }

    /**
     * Battery saver policy for this app ("no restrictions" is what we want the
     * user to pick). Lives in the power-keeper app, not in system settings.
     */
    fun openBatterySaverPolicy(context: Context, appLabel: String): Boolean {
        val candidates = listOf(
            Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
                .setClassName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                ),
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity"
                )
            )
        )
        return startFirstAvailable(context, candidates, appLabel)
    }

    /** System-level battery optimisation exemption (AOSP, present on HyperOS too). */
    fun openBatteryOptimization(context: Context): Boolean =
        KeepAliveManager.requestIgnoreBatteryOptimizations(context)

    private fun startFirstAvailable(
        context: Context,
        candidates: List<Intent>,
        appLabel: String
    ): Boolean {
        for (intent in candidates) {
            val prepared = intent
                .putExtra("package_name", context.packageName)
                .putExtra("package_label", appLabel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (KeepAliveManager.tryStart(context, prepared)) return true
        }
        // Last resort: the app's own details page always exists.
        return KeepAliveManager.tryStart(context, KeepAliveManager.appDetailsIntent(context))
    }
}
