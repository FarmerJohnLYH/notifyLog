package com.notifylog.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.notifylog.app.R
import com.notifylog.app.data.Settings
import com.notifylog.app.databinding.ActivityKeepaliveBinding
import com.notifylog.app.databinding.ItemKeepaliveStepBinding
import com.notifylog.app.keepalive.KeepAliveManager
import com.notifylog.app.keepalive.VendorGuide
import com.notifylog.app.service.ServiceState
import com.notifylog.app.util.padSystemBars

/**
 * Walk-through of everything the system needs the user to switch on by hand.
 *
 * Only a couple of these can be verified programmatically (notification access,
 * battery-optimisation exemption); the rest — auto-start, the power-keeper
 * policy, locking the task card — have no readable state, so the screen shows
 * them as steps to visit rather than pretending to know their status.
 */
class KeepAliveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeepaliveBinding

    /** A step whose completion we can't read back reports null. */
    private data class Step(
        val titleRes: Int,
        val descRes: Int,
        val isDone: (() -> Boolean)?,
        val onGo: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeepaliveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padSystemBars()

        Settings.ensure(this)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.intro.setText(
            if (KeepAliveManager.isXiaomi) R.string.keepalive_intro_xiaomi
            else R.string.keepalive_intro
        )
    }

    override fun onResume() {
        super.onResume()
        // Rebuild on every resume: the user has just come back from a system
        // settings screen and their answer may have changed.
        renderSteps()
    }

    private fun renderSteps() {
        // Keep the intro (index 0), drop previously rendered steps.
        if (binding.steps.childCount > 1) {
            binding.steps.removeViews(1, binding.steps.childCount - 1)
        }
        val inflater = LayoutInflater.from(this)
        buildSteps().forEach { step -> addStepView(inflater, step) }
    }

    private fun buildSteps(): List<Step> {
        val label = getString(R.string.app_name)
        val steps = mutableListOf(
            Step(
                R.string.keepalive_step_access,
                R.string.keepalive_step_access_desc,
                isDone = { ServiceState.isNotificationAccessGranted(this) },
                onGo = {
                    open(KeepAliveManager.tryStart(this, KeepAliveManager.notificationAccessIntent()))
                }
            )
        )

        // The vendor screens below only exist on HyperOS / MIUI.
        if (KeepAliveManager.isXiaomi) {
            steps += Step(
                R.string.keepalive_step_autostart,
                R.string.keepalive_step_autostart_desc,
                isDone = null,   // no readable state
                onGo = { open(VendorGuide.openAutoStart(this, label)) }
            )
            steps += Step(
                R.string.keepalive_step_battery_policy,
                R.string.keepalive_step_battery_policy_desc,
                isDone = null,
                onGo = { open(VendorGuide.openBatterySaverPolicy(this, label)) }
            )
        }

        steps += Step(
            R.string.keepalive_step_battery_opt,
            R.string.keepalive_step_battery_opt_desc,
            isDone = { KeepAliveManager.isIgnoringBatteryOptimizations(this) },
            onGo = { open(VendorGuide.openBatteryOptimization(this)) }
        )
        steps += Step(
            R.string.keepalive_step_lock,
            R.string.keepalive_step_lock_desc,
            isDone = null,
            onGo = { open(KeepAliveManager.tryStart(this, KeepAliveManager.appDetailsIntent(this))) }
        )
        steps += Step(
            R.string.keepalive_step_foreground,
            R.string.keepalive_step_foreground_desc,
            isDone = { Settings.keepAliveEnabled(this) },
            onGo = { startActivity(android.content.Intent(this, SettingsActivity::class.java)) }
        )
        return steps
    }

    private fun addStepView(inflater: LayoutInflater, step: Step) {
        val b = ItemKeepaliveStepBinding.inflate(inflater, binding.steps, false)
        b.title.setText(step.titleRes)
        b.desc.setText(step.descRes)

        val done = step.isDone?.invoke()
        when (done) {
            true -> {
                b.status.setText(R.string.keepalive_done)
                b.action.visibility = View.GONE
            }

            false -> {
                b.status.text = ""
                b.action.visibility = View.VISIBLE
            }

            null -> {
                b.status.setText(R.string.keepalive_unknown)
                b.action.visibility = View.VISIBLE
            }
        }

        b.action.setOnClickListener { step.onGo() }
        binding.steps.addView(b.root)
    }

    private fun open(succeeded: Boolean) {
        if (!succeeded) {
            Toast.makeText(this, R.string.keepalive_open_failed, Toast.LENGTH_LONG).show()
        }
    }
}
