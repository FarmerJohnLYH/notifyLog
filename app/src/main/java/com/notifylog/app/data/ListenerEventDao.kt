package com.notifylog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ListenerEventDao {

    @Insert
    suspend fun insert(event: ListenerEventEntity)

    /** Newest transitions first, bounded — the UI only shows recent outages. */
    @Query("SELECT * FROM listener_event ORDER BY time DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ListenerEventEntity>

    @Query("SELECT * FROM listener_event ORDER BY time DESC LIMIT 1")
    suspend fun last(): ListenerEventEntity?

    @Query("DELETE FROM listener_event WHERE time < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM listener_event")
    suspend fun clearAll()

    companion object {
        /**
         * Folds a newest-first event list into outage windows.
         *
         * A gap opens at a `connected = false` event and closes at the next
         * `connected = true` one; a still-open gap (the listener is down right
         * now) is returned with a null end.
         */
        fun toGaps(newestFirst: List<ListenerEventEntity>): List<Gap> {
            val gaps = ArrayList<Gap>()
            var pendingEnd: Long? = null
            for (e in newestFirst) {
                if (e.connected) {
                    // Walking backwards: a connect marks the end of the gap below it.
                    pendingEnd = e.time
                } else {
                    gaps.add(Gap(start = e.time, end = pendingEnd))
                    pendingEnd = null
                }
            }
            return gaps
        }
    }
}
