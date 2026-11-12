package com.notifylog.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import android.widget.TextView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import java.time.LocalDate
import java.time.ZoneId
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isEmpty
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notifylog.app.R
import com.notifylog.app.data.Exporter
import com.notifylog.app.data.NotificationEntity
import com.notifylog.app.data.NotifyDao
import com.notifylog.app.data.NotifyDatabase
import com.notifylog.app.data.Settings
import com.notifylog.app.databinding.ActivityLogListBinding
import com.notifylog.app.keepalive.KeepAliveManager
import com.notifylog.app.service.ServiceState
import com.notifylog.app.util.Formats
import com.notifylog.app.util.padSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.notifylog.app.util.ExportWindow
import java.time.Instant
import java.time.ZoneOffset

/**
 * The log timeline: newest first, with keyword search and a per-app filter.
 *
 * Paging is manual `LIMIT/OFFSET` (no Paging library, by design). Live updates
 * arrive as an "the table changed" signal and only refresh the list when the
 * user is parked at the top — someone scrolled into last week's notifications
 * should not have the list yanked out from under them.
 */
class LogListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogListBinding
    private lateinit var adapter: LogAdapter

    private val dao by lazy { NotifyDatabase.get(this).notifyDao() }

    private val items = ArrayList<NotificationEntity>()
    private var packageFilter: String? = null
    private var packageFilterLabel: String? = null
    private var query: String? = null
    private var endReached = false
    private var loading = false
    private var reloadPending = false
    private var bannerRechecks = 0
    private var searchJob: Job? = null
    private var appTabJob: Job? = null

    private var pendingExportFormat: Exporter.Format = Exporter.Format.JSON
    private var pendingExportFrom: Long? = null
    private var pendingExportTo: Long? = null
    private lateinit var exportLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padSystemBars()

        Settings.ensure(this)

        adapter = LogAdapter(lifecycleScope, ::showItemMenu, ::showDetail)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.recycler.addOnScrollListener(pagingScrollListener)
        (binding.recycler.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false

        binding.statusBanner.setOnClickListener {
            KeepAliveManager.tryStart(this, KeepAliveManager.notificationAccessIntent())
        }

        binding.toolbar.setOnMenuItemClickListener(::onMenuItem)
        binding.captureStatus.setOnClickListener {
            if (!ServiceState.isNotificationAccessGranted(this)) {
                KeepAliveManager.tryStart(this, KeepAliveManager.notificationAccessIntent())
            } else startActivity(Intent(this, GapsActivity::class.java))
        }
        binding.exportButton.setOnClickListener { showExportDialog() }

        exportLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            result.data?.data?.let { runExport(it, pendingExportFormat) }
        }

        savedInstanceState?.let { restore(it) }
        setupSearchView()
        binding.searchInput.setQuery(query.orEmpty(), false)
        binding.searchInput.clearFocus()
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_EXPORT, false)) {
            intent.removeExtra(EXTRA_EXPORT)
            binding.root.post { showExportDialog() }
        }
        // A range picker that survived recreation still holds its own state but
        // lost our callback; re-attach it or confirming the range does nothing.
        (supportFragmentManager.findFragmentByTag(RANGE_PICKER_TAG)
            as? MaterialDatePicker<androidx.core.util.Pair<Long, Long>>)
            ?.let { attachRangeListener(it, pendingExportFormat) }

        renderFilterChips()
        observeChanges()
        reload()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_EXPORT, false)) showExportDialog()
    }

    override fun onResume() {
        super.onResume()
        bannerRechecks = 0
        updateStatusBanner()
        // The change-flow only runs while STARTED, so anything captured while
        // the app was in the background would otherwise stay invisible until
        // the next notification arrived.
        if (isScrolledToTop()) reload()
        // A force-stop cancels the sticky service; re-apply what the user asked
        // for, from the foreground where starting an FGS is allowed.
        if (Settings.keepAliveEnabled(this)) KeepAliveManager.startKeepAlive(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The SAF picker and the date picker can outlive this activity; without
        // these the export would silently run with the wrong format or scope.
        outState.putString(STATE_PKG, packageFilter)
        outState.putString(STATE_PKG_LABEL, packageFilterLabel)
        outState.putString(STATE_QUERY, query)
        outState.putString(STATE_FORMAT, pendingExportFormat.name)
        pendingExportFrom?.let { outState.putLong(STATE_FROM, it) }
        pendingExportTo?.let { outState.putLong(STATE_TO, it) }
    }

    private fun restore(state: Bundle) {
        packageFilter = state.getString(STATE_PKG)
        packageFilterLabel = state.getString(STATE_PKG_LABEL)
        query = state.getString(STATE_QUERY)
        pendingExportFormat = state.getString(STATE_FORMAT)
            ?.let { runCatching { Exporter.Format.valueOf(it) }.getOrNull() }
            ?: Exporter.Format.JSON
        pendingExportFrom = state.takeIf { it.containsKey(STATE_FROM) }?.getLong(STATE_FROM)
        pendingExportTo = state.takeIf { it.containsKey(STATE_TO) }?.getLong(STATE_TO)
    }

    // ---- data ----

    /** Emits whenever the table changes; used only as a refresh trigger. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeChanges() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.changeFlow()
                    .drop(1)              // the initial value is already on screen
                    .debounce(400)        // coalesce a burst of captures
                    .collect {
                        // New rows mean the listener is alive — the banner may
                        // still show the state from before it connected.
                        updateStatusBanner()
                        if (isScrolledToTop()) reload()
                    }
            }
        }
    }

    private fun reload() {
        endReached = false
        loadPage(reset = true)
    }

    private fun loadPage(reset: Boolean) {
        if (loading) {
            // A filter change that arrives mid-load must not be dropped, or the
            // chips end up describing a list they no longer match.
            if (reset) reloadPending = true
            return
        }
        if (endReached && !reset) return
        loading = true
        // Page below the oldest row already shown rather than by OFFSET: rows
        // arriving at the top while the user scrolls would otherwise shift the
        // window and re-deliver rows that are already on screen.
        val before = if (reset) null else items.lastOrNull()
        val escaped = query?.takeIf { it.isNotBlank() }?.let { NotifyDao.escapeLike(it) }

        lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                runCatching {
                    dao.page(
                        pkg = packageFilter, q = escaped,
                        afterTime = before?.postTime, afterId = before?.id,
                        limit = PAGE_SIZE
                    )
                }.getOrDefault(emptyList())
            }
            if (reset) items.clear()
            items.addAll(page)
            if (page.size < PAGE_SIZE) endReached = true

            adapter.submitEntries(ArrayList(items)) {
                // Rows prepended at 0 land above the viewport anchor and stay
                // invisible; a reset load should always end up showing the top.
                if (reset) binding.recycler.scrollToPosition(0)
            }
            updateEmptyView()
            if (reset) renderAppTabs()
            loading = false
            if (reloadPending) {
                reloadPending = false
                reload()
            }
        }
    }

    private val pagingScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0 || loading || endReached) return
            val lm = rv.layoutManager as? LinearLayoutManager ?: return
            if (lm.findLastVisibleItemPosition() >= items.size - PREFETCH_DISTANCE) {
                loadPage(reset = false)
            }
        }
    }

    private fun isScrolledToTop(): Boolean {
        val lm = binding.recycler.layoutManager as? LinearLayoutManager ?: return true
        return lm.findFirstVisibleItemPosition() <= 0
    }

    // ---- filters ----

    private fun setupSearchView() {
        val search = binding.searchInput
        search.queryHint = getString(R.string.search_hint)
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                searchJob?.cancel()
                applyQuery(q)
                search.clearFocus()
                return true
            }

            override fun onQueryTextChange(q: String?): Boolean {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(220)
                    applyQuery(q)
                }
                return true
            }
        })
    }

    private fun applyQuery(q: String?) {
        val next = q?.trim()?.takeIf { it.isNotEmpty() }
        if (next == query) return
        query = next
        renderFilterChips()
        reload()
    }

    private fun applyPackageFilter(pkg: String?, label: String? = null) {
        if (pkg == packageFilter) return
        packageFilter = pkg
        packageFilterLabel = label
        renderFilterChips()
        reload()
    }

    private fun renderFilterChips() {
        val group = binding.filterChips
        group.removeAllViews()

        query?.let { q ->
            group.addView(filterChip(getString(R.string.chip_query, q)) {
                binding.searchInput.setQuery("", false)
                applyQuery(null)
            })
        }
        binding.filterBar.visibility = if (group.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderAppTabs() {
        appTabJob?.cancel()
        appTabJob = lifecycleScope.launch {
            val counts = withContext(Dispatchers.IO) { dao.appCounts() }
            binding.appTabs.removeAllViews()
            fun add(label: String, selected: Boolean, click: () -> Unit) {
                val pill = TextView(this@LogListActivity, null, 0, R.style.Pill).apply {
                    text = label
                    isSelected = selected
                    contentDescription = label
                    setOnClickListener { click() }
                }
                binding.appTabs.addView(pill, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
            }
            add(getString(R.string.filter_all), packageFilter == null) { applyPackageFilter(null) }
            val displayed = counts.take(4).toMutableList()
            counts.find { it.packageName == packageFilter }?.let { selected ->
                if (displayed.none { it.packageName == selected.packageName }) displayed.add(selected)
            }
            displayed.forEach { app ->
                add(app.appLabel ?: app.packageName, app.packageName == packageFilter) {
                    applyPackageFilter(app.packageName, app.appLabel ?: app.packageName)
                }
            }
            if (counts.size > 4) add(getString(R.string.filter_more), false) { showAppPicker() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun filterChip(text: String, onClose: () -> Unit): Chip =
        Chip(this).apply {
            this.text = text
            isCloseIconVisible = true
            setOnCloseIconClickListener { onClose() }
            setOnClickListener { onClose() }
        }

    // ---- menu ----

    private fun onMenuItem(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }
        else -> false
    }

    private fun showAppPicker() {
        lifecycleScope.launch {
            val counts = withContext(Dispatchers.IO) {
                runCatching { dao.appCounts() }.getOrDefault(emptyList())
            }
            if (counts.isEmpty()) return@launch

            val labels = arrayOf<CharSequence>(getString(R.string.picker_all)) + counts.map {
                "${it.appLabel ?: it.packageName}  ${getString(R.string.picker_count, it.logCount)}"
            }
            MaterialAlertDialogBuilder(this@LogListActivity)
                .setTitle(R.string.picker_title)
                .setItems(labels) { _, which ->
                    if (which == 0) {
                        applyPackageFilter(null)
                    } else {
                        val app = counts[which - 1]
                        applyPackageFilter(app.packageName, app.appLabel ?: app.packageName)
                    }
                }
                .show()
        }
    }

    private fun showDetail(entity: NotificationEntity) {
        val detail = listOfNotNull(
            entity.title, entity.bigText ?: entity.text, entity.subText
        ).filter { it.isNotBlank() }.distinct().joinToString("\n\n")
        val message = TextView(this).apply {
            text = getString(R.string.detail_message, Formats.dateTime(entity.postTime), detail)
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            setTextIsSelectable(true)
            setPadding(dp(24), dp(12), dp(24), dp(20))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(message) }
        MaterialAlertDialogBuilder(this)
            .setTitle(entity.appLabel ?: entity.packageName)
            .setView(scroll)
            .setPositiveButton(R.string.item_copy) { _, _ -> copy(entity) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showItemMenu(entity: NotificationEntity) {
        val actions = listOf<Pair<String, () -> Unit>>(
            getString(R.string.item_copy) to { copy(entity) },
            getString(R.string.item_filter_this_app) to {
                applyPackageFilter(entity.packageName, entity.appLabel ?: entity.packageName)
            },
            getString(R.string.item_delete) to { delete(entity) },
            getString(R.string.item_exclude_app) to { excludeApp(entity) }
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(entity.appLabel ?: entity.packageName)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .show()
    }

    private fun copy(entity: NotificationEntity) {
        val text = listOfNotNull(entity.title, entity.bigText ?: entity.text, entity.subText)
            .joinToString("\n")
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("NotifyLog", text))
        toast(getString(R.string.copied))
    }

    private fun delete(entity: NotificationEntity) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { dao.deleteById(entity.id) } }
            items.removeAll { it.id == entity.id }
            adapter.submitEntries(ArrayList(items))
            updateEmptyView()
            toast(getString(R.string.deleted))
        }
    }

    private fun excludeApp(entity: NotificationEntity) {
        val excluded = Settings.excludedPackages.toMutableSet()
        excluded.add(entity.packageName)
        Settings.setExcludedPackages(this, excluded)
        toast(getString(R.string.excluded_app, entity.appLabel ?: entity.packageName))
    }

    // ---- export ----

    private fun showExportDialog() {
        if (supportFragmentManager.findFragmentByTag(EXPORT_SHEET_TAG) != null) return
        ExportSheet.newInstance(packageFilterLabel ?: getString(R.string.picker_all), query)
            .show(supportFragmentManager, EXPORT_SHEET_TAG)
    }

    fun confirmExport(format: Exporter.Format, range: String) {
        pendingExportFormat = format
        when (range) {
            "custom" -> showExportRangePicker(format)
            "today", "week" -> {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val window = if (range == "week") ExportWindow.recentWeek(today, zone)
                    else ExportWindow.days(today, today, zone)
                launchCreateDocument(format, window.from, window.until)
            }
            else -> launchCreateDocument(format, null, null)
        }
    }

    private fun showExportRangePicker(format: Exporter.Format) {
        // Remembered so a recreation while the picker is up can re-attach the
        // listener with the format the user actually chose.
        pendingExportFormat = format
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.export_time_title)
            .build()
        attachRangeListener(picker, format)
        picker.show(supportFragmentManager, RANGE_PICKER_TAG)
    }

    private fun attachRangeListener(
        picker: MaterialDatePicker<androidx.core.util.Pair<Long, Long>>,
        format: Exporter.Format
    ) {
        picker.addOnPositiveButtonClickListener { range ->
            // The picker hands back UTC midnights; shift to local midnights so
            // "8月5日" means the user's own day, then make the end exclusive.
            val first = Instant.ofEpochMilli(range.first).atZone(ZoneOffset.UTC).toLocalDate()
            val last = Instant.ofEpochMilli(range.second).atZone(ZoneOffset.UTC).toLocalDate()
            val window = ExportWindow.days(first, last, ZoneId.systemDefault())
            launchCreateDocument(format, from = window.from, to = window.until)
        }
    }

    private fun launchCreateDocument(format: Exporter.Format, from: Long?, to: Long?) {
        pendingExportFormat = format
        pendingExportFrom = from
        pendingExportTo = to
        val stamp = Formats.dateTime(System.currentTimeMillis()).filter { it.isDigit() }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(with(Exporter) { format.mimeType })
            .putExtra(
                Intent.EXTRA_TITLE,
                "notifylog-$stamp.${with(Exporter) { format.extension }}"
            )
        runCatching { exportLauncher.launch(intent) }
            .onFailure { toast(getString(R.string.export_failed, it.message ?: "SAF unavailable")) }
    }

    private fun runExport(uri: Uri, format: Exporter.Format) {
        toast(getString(R.string.export_running))
        lifecycleScope.launch {
            runCatching {
                Exporter.export(
                    this@LogListActivity, uri, format,
                    packageFilter, query, pendingExportFrom, pendingExportTo
                )
            }
                .onSuccess { toast(getString(R.string.export_done, it)) }
                .onFailure { toast(getString(R.string.export_failed, it.message ?: "unknown")) }
        }
    }

    // ---- chrome ----

    private fun updateStatusBanner() {
        val granted = ServiceState.isNotificationAccessGranted(this)
        val connected = ServiceState.isConnected(this)
        binding.captureStatus.setText(when {
            !granted -> R.string.capture_permission
            connected -> R.string.recording
            else -> R.string.capture_paused
        })
        binding.captureStatus.setTextColor(ContextCompat.getColor(this,
            if (granted && connected) R.color.success else R.color.secondary))
        when {
            !granted -> {
                binding.statusBanner.setText(R.string.status_not_granted)
                binding.statusBanner.visibility = View.VISIBLE
            }
            // Granted but unbound means the process was killed and never
            // rebound (or the system has not bound us yet after a cold start).
            // Ask for a rebind and re-check shortly: a successful bind usually
            // lands within a second or two and should clear the banner without
            // the user having to do anything.
            !connected -> {
                binding.statusBanner.setText(R.string.status_granted_not_connected)
                binding.statusBanner.visibility = View.VISIBLE
                KeepAliveManager.requestRebind(this)
                scheduleBannerRecheck()
            }

            else -> binding.statusBanner.visibility = View.GONE
        }
    }

    /** Polls the bind state a few times after a rebind request, then gives up. */
    private fun scheduleBannerRecheck() {
        if (bannerRechecks >= MAX_BANNER_RECHECKS) return
        bannerRechecks++
        lifecycleScope.launch {
            delay(BANNER_RECHECK_MS)
            if (ServiceState.isConnected(this@LogListActivity)) {
                bannerRechecks = 0
                updateStatusBanner()
            } else {
                scheduleBannerRecheck()
            }
        }
    }

    private fun updateEmptyView() {
        val filtered = packageFilter != null || query != null
        binding.emptyState.setText(if (filtered) R.string.empty_filtered else R.string.empty_hint)
        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val PAGE_SIZE = 100
        private const val PREFETCH_DISTANCE = 20
        const val EXTRA_EXPORT = "openExport"
        private const val EXPORT_SHEET_TAG = "exportSheet"
        private const val BANNER_RECHECK_MS = 1_500L
        private const val MAX_BANNER_RECHECKS = 8
        private const val RANGE_PICKER_TAG = "export_range"
        private const val STATE_PKG = "pkg"
        private const val STATE_PKG_LABEL = "pkgLabel"
        private const val STATE_QUERY = "query"
        private const val STATE_FORMAT = "exportFormat"
        private const val STATE_FROM = "exportFrom"
        private const val STATE_TO = "exportTo"
    }
}
