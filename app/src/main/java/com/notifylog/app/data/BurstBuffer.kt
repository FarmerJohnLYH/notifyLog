package com.notifylog.app.data

import kotlinx.coroutines.channels.Channel

/** One wake-up per nonempty burst, not per notification arriving during the flush delay. */
internal class BurstBuffer<T>(private val capacity: Int) {
    private val rows = ArrayList<T>()
    val signal = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun offer(row: T): Boolean {
        if (rows.size >= capacity) return false
        val wasEmpty = rows.isEmpty()
        rows.add(row)
        if (wasEmpty) signal.trySend(Unit)
        return true
    }

    @Synchronized
    fun isEmpty() = rows.isEmpty()

    @Synchronized
    fun drain(): List<T> = rows.toList().also { rows.clear() }

    /** A timed-out flush must retain its rows even if fresh arrivals filled the queue. */
    @Synchronized
    fun restore(batch: List<T>) {
        rows.addAll(0, batch)
        if (rows.isNotEmpty()) signal.trySend(Unit)
    }
}
