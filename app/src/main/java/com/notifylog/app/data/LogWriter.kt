package com.notifylog.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Batched, single-writer path from the listener callback to disk.
 *
 * The binder callback only appends to an in-memory buffer and pokes a conflated
 * signal — it never touches disk. A single background coroutine coalesces each
 * burst into one transaction, which is what keeps a chatty device from turning
 * into a stream of tiny fsyncs.
 */
object LogWriter {

    private const val TAG = "LogWriter"

    /** How long to let a burst accumulate before writing it as one transaction. */
    private const val FLUSH_INTERVAL_MS = 500L

    /**
     * Hard cap on unwritten rows. Only reachable if the DB is wedged; dropping
     * is preferable to growing the buffer until the process is killed.
     */
    private const val MAX_PENDING = 5_000

    private val MAINTENANCE_INTERVAL_MS = TimeUnit.HOURS.toMillis(6)

    /** Floor between resident-notification counter repaints. */
    private const val STATS_INTERVAL_MS = 30_000L

    @Volatile
    private var lastStatsRefresh = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private val buffer = ArrayList<NotificationEntity>(64)

    /** Conflated: many enqueues collapse into a single "there is work" wake-up. */
    private val signal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var dao: NotifyDao? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var started = false

    /** Rows discarded because the buffer was full; surfaced in the settings screen. */
    @Volatile
    var droppedCount: Long = 0L
        private set

    private var lastMaintenance = 0L

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        val d = NotifyDatabase.get(app).notifyDao()
        dao = d

        scope.launch {
            while (isActive) {
                signal.receive()
                delay(FLUSH_INTERVAL_MS)      // let the rest of the burst land
                writeBatch(d, takeAll())
                maybeRefreshStats(app)
                maybeRunMaintenance(app, d)
            }
        }
    }

    /**
     * Repaints the resident notification's last-hour counter, at most once per
     * [STATS_INTERVAL_MS]. Event-driven on purpose: no timer, so the counter can
     * lag on a quiet device, which is a fair trade for zero idle cost.
     */
    private suspend fun maybeRefreshStats(context: Context) {
        if (!com.notifylog.app.service.KeepAliveService.running) return
        val now = System.currentTimeMillis()
        if (now - lastStatsRefresh < STATS_INTERVAL_MS) return
        lastStatsRefresh = now
        com.notifylog.app.service.KeepAliveService.refreshStats(context)
    }

    fun enqueue(entity: NotificationEntity) {
        synchronized(lock) {
            if (buffer.size >= MAX_PENDING) {
                droppedCount++
                return
            }
            buffer.add(entity)
        }
        signal.trySend(Unit)
    }

    /**
     * Writes everything still buffered, on the calling thread.
     *
     * Called from the listener's `onDestroy`, where the process may be about to
     * go away — the bounded wait is a deliberate trade: a short block on the
     * main thread in exchange for not losing the last half-second of captures.
     */
    fun flushBlocking(timeoutMs: Long = 2_000) {
        val d = dao ?: return
        val batch = takeAll()
        if (batch.isEmpty()) return
        runBlocking {
            withTimeoutOrNull(timeoutMs) { writeBatch(d, batch) }
        }
    }

    private fun takeAll(): List<NotificationEntity> = synchronized(lock) {
        if (buffer.isEmpty()) emptyList()
        else ArrayList<NotificationEntity>(buffer).also { buffer.clear() }
    }

    private suspend fun writeBatch(d: NotifyDao, batch: List<NotificationEntity>) {
        if (batch.isEmpty()) return
        runCatching { d.insertAll(batch) }
            .onFailure { Log.w(TAG, "batch insert of ${batch.size} rows failed", it) }
    }

    /**
     * Optional retention trimming. Off by default — the product decision is to
     * keep everything — so this usually costs one cheap timestamp comparison.
     */
    private suspend fun maybeRunMaintenance(context: Context, d: NotifyDao) {
        if (!Settings.autoCleanEnabled(context)) return
        val now = System.currentTimeMillis()
        if (now - lastMaintenance < MAINTENANCE_INTERVAL_MS) return
        lastMaintenance = now

        runCatching {
            val days = Settings.retentionDays(context)
            if (days > 0) {
                d.deleteOlderThan(now - TimeUnit.DAYS.toMillis(days.toLong()))
            }
            val maxRows = Settings.maxRows(context)
            if (maxRows > 0) d.trimToNewest(maxRows)
        }.onFailure { Log.w(TAG, "maintenance failed", it) }
    }
}
