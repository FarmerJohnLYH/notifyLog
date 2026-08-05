package com.notifylog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One row of the "filter by app" picker: package, its label, and how many logs it has. */
data class AppCount(
    val packageName: String,
    val appLabel: String?,
    val logCount: Int
)

@Dao
interface NotifyDao {

    @Insert
    suspend fun insertAll(items: List<NotificationEntity>)

    /**
     * One page of the log, newest first, with optional app filter and keyword.
     *
     * Both filters are applied in SQL with null meaning "no filter", which keeps
     * the UI to a single query path. The keyword match is a plain `LIKE` scan by
     * design (no FTS table — it would roughly double on-disk size); callers must
     * pass the query through [escapeLike] so user-typed `%`/`_` stay literal.
     */
    @Query(
        """
        SELECT * FROM notification_log
        WHERE (:pkg IS NULL OR packageName = :pkg)
          AND (:from IS NULL OR postTime >= :from)
          AND (:to IS NULL OR postTime < :to)
          AND (:q IS NULL
               OR title    LIKE '%' || :q || '%' ESCAPE '\'
               OR text     LIKE '%' || :q || '%' ESCAPE '\'
               OR bigText  LIKE '%' || :q || '%' ESCAPE '\'
               OR subText  LIKE '%' || :q || '%' ESCAPE '\'
               OR appLabel LIKE '%' || :q || '%' ESCAPE '\')
        ORDER BY postTime DESC, id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun pageRange(
        pkg: String?,
        q: String?,
        from: Long?,
        to: Long?,
        limit: Int,
        offset: Int
    ): List<NotificationEntity>

    /** The unbounded-time page the UI uses. */
    suspend fun page(pkg: String?, q: String?, limit: Int, offset: Int): List<NotificationEntity> =
        pageRange(pkg, q, from = null, to = null, limit = limit, offset = offset)

    /**
     * Emits on every write to the table. The list screen uses it purely as an
     * "something changed" signal to refresh the first page.
     */
    @Query("SELECT COUNT(*) FROM notification_log")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM notification_log")
    suspend fun count(): Int

    /** Rows captured since [since]; powers the resident notification's counter. */
    @Query("SELECT COUNT(*) FROM notification_log WHERE postTime >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT MIN(postTime) FROM notification_log")
    suspend fun oldestPostTime(): Long?

    /** Apps that have at least one log, most talkative first — powers the filter picker. */
    @Query(
        """
        SELECT packageName, MAX(appLabel) AS appLabel, COUNT(*) AS logCount
        FROM notification_log
        GROUP BY packageName
        ORDER BY logCount DESC
        """
    )
    suspend fun appCounts(): List<AppCount>

    @Query("DELETE FROM notification_log WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM notification_log WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String): Int

    @Query("DELETE FROM notification_log WHERE postTime < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /**
     * Trim the table down to its newest [keep] rows. Used by the optional
     * row-count cap; a no-op when the table is already smaller.
     */
    @Query(
        """
        DELETE FROM notification_log WHERE id NOT IN (
            SELECT id FROM notification_log ORDER BY postTime DESC, id DESC LIMIT :keep
        )
        """
    )
    suspend fun trimToNewest(keep: Int): Int

    @Query("DELETE FROM notification_log")
    suspend fun clearAll()

    companion object {
        /** Makes user input safe for the `LIKE ... ESCAPE '\'` patterns above. */
        fun escapeLike(raw: String): String =
            raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
