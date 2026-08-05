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
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}
