package com.notifylog.app.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads a screen's root view by the system-bar (and cutout) insets so toolbars
 * never render under the status bar.
 *
 * Done with an explicit listener rather than `android:fitsSystemWindows`: under
 * the enforced edge-to-edge of targetSdk 35+, some ROMs (HyperOS among them)
 * apply the XML attribute inconsistently, while dispatched insets are reliable.
 */
fun View.padSystemBars() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        ViewCompat.getWindowInsetsController(v)?.let { controller ->
            val light = v.resources.getBoolean(com.notifylog.app.R.bool.light_system_bars)
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
        insets
    }
}
