package com.notifylog.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A connect/disconnect transition of the notification listener.
 *
 * Pairing a `connected = false` row with the next `connected = true` row gives
 * the window during which notifications were missed — that gap is what the UI
 * surfaces, so a silent kill by the ROM is visible instead of looking like
 * "nothing happened".
 */
@Entity(tableName = "listener_event", indices = [Index("time")])
data class ListenerEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val connected: Boolean
)

/** A resolved outage. [end] is null while the listener is still disconnected. */
data class Gap(val start: Long, val end: Long?) {
    val durationMs: Long? get() = end?.let { it - start }
}
