package com.notifylog.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Every user-tunable knob, backed by SharedPreferences.
 *
 * The capture filter reads these on the binder callback for *every* notification,
 * so the hot values are mirrored into volatile fields and refreshed by a change
 * listener instead of hitting SharedPreferences each time.
 */
object Settings {

    private const val PREF = "notify_settings"

    const val KEY_LOG_ONGOING = "log_ongoing"
    const val KEY_ONGOING_ALLOW = "ongoing_allow"
    const val KEY_EXCLUDED = "excluded_packages"
    const val KEY_KEEP_ALIVE = "keep_alive"
    const val KEY_HEARTBEAT = "heartbeat"
    const val KEY_AUTO_CLEAN = "auto_clean"
    const val KEY_RETENTION_DAYS = "retention_days"
    const val KEY_MAX_ROWS = "max_rows"

    /** Default retention when auto-clean is switched on (it is off by default). */
    const val DEFAULT_RETENTION_DAYS = 180
    const val DEFAULT_MAX_ROWS = 200_000

    @Volatile
    private var prefs: SharedPreferences? = null

    // ---- hot-path mirrors (read per notification) ----

    /** Record ongoing/foreground-service notifications (music, downloads). Noisy: off. */
    @Volatile
    var logOngoing: Boolean = false
        private set

    /** Packages allowed through even when [logOngoing] is off. */
    @Volatile
    var ongoingAllowList: Set<String> = emptySet()
        private set

    /** Packages never recorded at all. */
    @Volatile
    var excludedPackages: Set<String> = emptySet()
        private set

    // Held as a field so the (weakly referenced) registration stays alive.
    private val changeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { p, _ -> mirror(p) }

    /** Idempotent; safe to call from any entry point (service, receiver, activity). */
    @Synchronized
    fun ensure(context: Context): SharedPreferences {
        prefs?.let { return it }
        val p = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        mirror(p)
        p.registerOnSharedPreferenceChangeListener(changeListener)
        prefs = p
        return p
    }

    private fun mirror(p: SharedPreferences) {
        logOngoing = p.getBoolean(KEY_LOG_ONGOING, false)
        ongoingAllowList = p.getStringSet(KEY_ONGOING_ALLOW, emptySet()) ?: emptySet()
        excludedPackages = p.getStringSet(KEY_EXCLUDED, emptySet()) ?: emptySet()
    }

    // ---- cold-path accessors (settings screen, service start-up) ----

    fun keepAliveEnabled(context: Context): Boolean =
        ensure(context).getBoolean(KEY_KEEP_ALIVE, false)

    fun setKeepAlive(context: Context, enabled: Boolean) =
        ensure(context).edit { putBoolean(KEY_KEEP_ALIVE, enabled) }

    fun heartbeatEnabled(context: Context): Boolean =
        ensure(context).getBoolean(KEY_HEARTBEAT, false)

    fun setHeartbeat(context: Context, enabled: Boolean) =
        ensure(context).edit { putBoolean(KEY_HEARTBEAT, enabled) }

    fun autoCleanEnabled(context: Context): Boolean =
        ensure(context).getBoolean(KEY_AUTO_CLEAN, false)

    fun setAutoClean(context: Context, enabled: Boolean) =
        ensure(context).edit { putBoolean(KEY_AUTO_CLEAN, enabled) }

    fun retentionDays(context: Context): Int =
        ensure(context).getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)

    fun setRetentionDays(context: Context, days: Int) =
        ensure(context).edit { putInt(KEY_RETENTION_DAYS, days) }

    fun maxRows(context: Context): Int =
        ensure(context).getInt(KEY_MAX_ROWS, DEFAULT_MAX_ROWS)

    fun setMaxRows(context: Context, rows: Int) =
        ensure(context).edit { putInt(KEY_MAX_ROWS, rows) }

    fun setLogOngoing(context: Context, enabled: Boolean) =
        ensure(context).edit { putBoolean(KEY_LOG_ONGOING, enabled) }

    fun setExcludedPackages(context: Context, packages: Set<String>) =
        ensure(context).edit { putStringSet(KEY_EXCLUDED, packages) }

    fun setOngoingAllowList(context: Context, packages: Set<String>) =
        ensure(context).edit { putStringSet(KEY_ONGOING_ALLOW, packages) }
}
