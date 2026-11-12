package com.notifylog.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.notifylog.app.service.ServiceState
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notifylog.app.R
import com.notifylog.app.collect.NotificationFilter
import com.notifylog.app.data.ListenerEventDao
import com.notifylog.app.data.NotifyDatabase
import com.notifylog.app.data.Settings
import com.notifylog.app.databinding.ActivitySettingsBinding
import com.notifylog.app.keepalive.KeepAliveManager
import com.notifylog.app.util.AppIcons
import com.notifylog.app.util.Formats
import com.notifylog.app.util.padSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled settings screen.
 *
 * Deliberately not `androidx.preference`: that library would add a few hundred
 * kilobytes for a dozen rows, which is a bad trade in an app whose whole point
 * is staying small.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val db by lazy { NotifyDatabase.get(this) }

    /**
     * The keep-alive service posts a (silent) notification, so on API 33+ it
     * needs POST_NOTIFICATIONS. Asked for only when the user turns it on.
     */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableKeepAlive()
        } else {
            binding.switchKeepAlive.isChecked = false
            toast(getString(R.string.keepalive_needs_post_permission))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padSystemBars()

        Settings.ensure(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        bindCaptureSection()
        bindKeepAliveSection()
        bindStorageSection()
        bindStatusSection()
        binding.rowPermission.setOnClickListener {
            KeepAliveManager.tryStart(this, KeepAliveManager.notificationAccessIntent())
        }
        fun expand(row: View, panel: View, key: String) {
            panel.visibility = if (savedInstanceState?.getBoolean(key) == true) View.VISIBLE else View.GONE
            row.setOnClickListener {
                panel.isVisible = !panel.isVisible
                row.isSelected = panel.isVisible
            }
        }
        expand(binding.rowKeepAliveOptions, binding.keepAlivePanel, "keepAliveExpanded")
        expand(binding.rowStorage, binding.storagePanel, "storageExpanded")
        expand(binding.rowMore, binding.morePanel, "moreExpanded")
        binding.rowAppearance.setOnClickListener { showAppearancePicker() }
        binding.rowExport.setOnClickListener {
            startActivity(Intent(this, LogListActivity::class.java)
                .putExtra(LogListActivity.EXTRA_EXPORT, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDynamicSummaries()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("keepAliveExpanded", binding.keepAlivePanel.isVisible)
        outState.putBoolean("storageExpanded", binding.storagePanel.isVisible)
        outState.putBoolean("moreExpanded", binding.morePanel.isVisible)
    }

    private fun showAppearancePicker() {
        val modes = intArrayOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
        val labels = arrayOf(getString(R.string.appearance_system), getString(R.string.appearance_light),
            getString(R.string.appearance_dark))
        MaterialAlertDialogBuilder(this).setTitle(R.string.appearance_title)
            .setSingleChoiceItems(labels, modes.indexOf(Settings.appearance(this))) { dialog, which ->
                Settings.setAppearance(this, modes[which])
                dialog.dismiss()
                AppCompatDelegate.setDefaultNightMode(modes[which])
                refreshDynamicSummaries()
            }.show()
    }

    // ---- capture ----

    private fun bindCaptureSection() {
        binding.switchLogOngoing.isChecked = Settings.logOngoing
        binding.rowLogOngoing.setOnClickListener {
            val next = !binding.switchLogOngoing.isChecked
            binding.switchLogOngoing.isChecked = next
            Settings.setLogOngoing(this, next)
        }

        binding.rowExcluded.setOnClickListener { showExcludedDialog() }
    }

    private fun showExcludedDialog() {
        val excluded = Settings.excludedPackages.sorted()
        if (excluded.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_excluded_title)
                .setMessage(R.string.dialog_excluded_empty)
                .setPositiveButton(R.string.dialog_confirm, null)
                .show()
            return
        }

        lifecycleScope.launch {
            // Resolve labels off the main thread; PackageManager does I/O.
            val labels = withContext(Dispatchers.IO) {
                val pm = packageManager
                excluded.map { pkg ->
                    runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                }
            }
            val checked = BooleanArray(excluded.size) { true }
            // No setMessage alongside setMultiChoiceItems: AlertDialog shows
            // the message INSTEAD of the list (same trap as the export dialog).
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.dialog_excluded_remove)
                .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                    val kept = excluded.filterIndexed { i, _ -> checked[i] }.toSet()
                    Settings.setExcludedPackages(this@SettingsActivity, kept)
                    refreshDynamicSummaries()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    // ---- keep alive ----

    private fun bindKeepAliveSection() {
        binding.switchKeepAlive.isChecked = Settings.keepAliveEnabled(this)
        binding.rowKeepAlive.setOnClickListener {
            if (binding.switchKeepAlive.isChecked) {
                binding.switchKeepAlive.isChecked = false
                Settings.setKeepAlive(this, false)
                KeepAliveManager.stopKeepAlive(this)
            } else {
                binding.switchKeepAlive.isChecked = true
                requestKeepAlive()
            }
        }

        binding.switchHeartbeat.isChecked = Settings.heartbeatEnabled(this)
        binding.rowHeartbeat.setOnClickListener {
            val next = !binding.switchHeartbeat.isChecked
            binding.switchHeartbeat.isChecked = next
            Settings.setHeartbeat(this, next)
            KeepAliveManager.applyHeartbeatSetting(this)
        }

        binding.rowKeepAliveGuide.setOnClickListener {
            startActivity(Intent(this, KeepAliveActivity::class.java))
        }
    }

    private fun requestKeepAlive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        enableKeepAlive()
    }

    private fun enableKeepAlive() {
        Settings.setKeepAlive(this, true)
        binding.switchKeepAlive.isChecked = true
        KeepAliveManager.startKeepAlive(this)
    }

    // ---- storage ----

    private fun bindStorageSection() {
        binding.switchAutoClean.isChecked = Settings.autoCleanEnabled(this)
        binding.rowAutoClean.setOnClickListener {
            val next = !binding.switchAutoClean.isChecked
            binding.switchAutoClean.isChecked = next
            Settings.setAutoClean(this, next)
            refreshDynamicSummaries()
        }

        binding.rowRetention.setOnClickListener {
            pickNumber(
                title = R.string.dialog_retention_title,
                options = RETENTION_OPTIONS,
                current = Settings.retentionDays(this),
                render = { getString(R.string.settings_retention_value, it) }
            ) {
                Settings.setRetentionDays(this, it)
                refreshDynamicSummaries()
            }
        }

        binding.rowMaxRows.setOnClickListener {
            pickNumber(
                title = R.string.dialog_max_rows_title,
                options = MAX_ROW_OPTIONS,
                current = Settings.maxRows(this),
                render = { getString(R.string.settings_max_rows_value, it) }
            ) {
                Settings.setMaxRows(this, it)
                refreshDynamicSummaries()
            }
        }

        binding.rowCleanNow.setOnClickListener { cleanNow() }
        binding.rowClearAll.setOnClickListener { confirmClearAll() }
    }

    private fun pickNumber(
        title: Int,
        options: IntArray,
        current: Int,
        render: (Int) -> String,
        onPick: (Int) -> Unit
    ) {
        val labels = options.map { render(it) }.toTypedArray<CharSequence>()
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, options.indexOf(current)) { dialog, which ->
                onPick(options[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun cleanNow() {
        lifecycleScope.launch {
            val removed = withContext(Dispatchers.IO) {
                val dao = db.notifyDao()
                var n = 0
                runCatching {
                    val days = Settings.retentionDays(this@SettingsActivity)
                    if (days > 0) {
                        n += dao.deleteOlderThan(
                            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
                        )
                    }
                    val maxRows = Settings.maxRows(this@SettingsActivity)
                    if (maxRows > 0) n += dao.trimToNewest(maxRows)
                }
                n
            }
            toast(getString(R.string.dialog_cleaned, removed))
            refreshDynamicSummaries()
        }
    }

    private fun confirmClearAll() {
        lifecycleScope.launch {
            val total = withContext(Dispatchers.IO) {
                runCatching { db.notifyDao().count() }.getOrDefault(0)
            }
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.dialog_clear_all_title)
                .setMessage(getString(R.string.dialog_clear_all_message, total))
                .setPositiveButton(R.string.dialog_confirm) { _, _ -> clearAll() }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun clearAll() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    db.notifyDao().clearAll()
                    db.listenerEventDao().clearAll()
                    // Reclaim the pages rather than leaving a large sparse file.
                    db.openHelper.writableDatabase.execSQL("VACUUM")
                }
            }
            AppIcons.clear()
            // The dedup LRU still remembers recent content hashes; without this
            // a notification identical to a pre-wipe one would be silently
            // skipped right after "clear all".
            NotificationFilter.resetDedupCache()
            toast(getString(R.string.dialog_cleared))
            refreshDynamicSummaries()
        }
    }

    // ---- status ----

    private fun bindStatusSection() {
        binding.rowGaps.setOnClickListener {
            startActivity(Intent(this, GapsActivity::class.java))
        }
    }

    // ---- summaries ----

    private fun refreshDynamicSummaries() {
        binding.excludedSummary.text = getString(R.string.count_value, Settings.excludedPackages.size)
        binding.permissionSummary.setText(if (ServiceState.isNotificationAccessGranted(this))
            R.string.permission_on else R.string.permission_off)
        binding.appearanceSummary.setText(when (Settings.appearance(this)) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.string.appearance_light
            AppCompatDelegate.MODE_NIGHT_YES -> R.string.appearance_dark
            else -> R.string.appearance_system
        })
        binding.retentionSummary.text =
            getString(R.string.settings_retention_value, Settings.retentionDays(this))
        binding.maxRowsSummary.text =
            getString(R.string.settings_max_rows_value, Settings.maxRows(this))

        val autoCleanOn = Settings.autoCleanEnabled(this)
        binding.rowRetention.isEnabled = autoCleanOn
        binding.rowMaxRows.isEnabled = autoCleanOn
        binding.rowRetention.alpha = if (autoCleanOn) 1f else DISABLED_ALPHA
        binding.rowMaxRows.alpha = if (autoCleanOn) 1f else DISABLED_ALPHA

        val dropped = com.notifylog.app.data.LogWriter.droppedCount
        binding.droppedNote.visibility = if (dropped > 0) View.VISIBLE else View.GONE
        if (dropped > 0) {
            binding.droppedNote.text = getString(R.string.settings_dropped, dropped)
        }

        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                val dao = db.notifyDao()
                val count = runCatching { dao.count() }.getOrDefault(0)
                val oldest = runCatching { dao.oldestPostTime() }.getOrNull()
                val size = NotifyDatabase.sizeOnDiskBytes(this@SettingsActivity)
                val gaps = runCatching {
                    ListenerEventDao.toGaps(db.listenerEventDao().recent(GAP_EVENT_LIMIT))
                }.getOrDefault(emptyList())
                Triple(count, oldest, size) to gaps.size
            }
            val (counts, gapCount) = stats
            val (count, oldest, size) = counts

            binding.usageSummary.text = Formats.size(size)
            binding.usageDetail.text = if (count == 0) {
                getString(R.string.settings_usage_empty)
            } else {
                val line = getString(R.string.settings_usage_value, Formats.size(size), count)
                if (oldest != null && oldest > 0) {
                    line + "\n" + getString(R.string.settings_usage_since, Formats.dateTime(oldest))
                } else {
                    line
                }
            }

            binding.gapsSummary.text = if (gapCount == 0) {
                getString(R.string.settings_gaps_none)
            } else {
                getString(R.string.settings_gaps_desc, gapCount)
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private val RETENTION_OPTIONS = intArrayOf(7, 30, 90, 180, 365, 730)
        private val MAX_ROW_OPTIONS = intArrayOf(20_000, 50_000, 100_000, 200_000, 500_000)
        private const val GAP_EVENT_LIMIT = 200
        private const val DISABLED_ALPHA = 0.45f
    }
}
