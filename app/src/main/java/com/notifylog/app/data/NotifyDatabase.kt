package com.notifylog.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

@Database(
    entities = [NotificationEntity::class, ListenerEventEntity::class],
    version = 2,
    exportSchema = true
)
abstract class NotifyDatabase : RoomDatabase() {

    abstract fun notifyDao(): NotifyDao

    abstract fun listenerEventDao(): ListenerEventDao

    companion object {
        private const val DB_NAME = "notify_log.db"

        /** v2 adds the listener connect/disconnect log used for gap visualisation. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `listener_event` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `time` INTEGER NOT NULL,
                        `connected` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_listener_event_time` ON `listener_event` (`time`)"
                )
            }
        }

        @Volatile
        private var INSTANCE: NotifyDatabase? = null

        fun get(context: Context): NotifyDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotifyDatabase::class.java,
                    DB_NAME
                )
                    // WAL: writes don't block reads, fewer fsyncs -> lower battery cost.
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }

        /**
         * On-disk footprint of the log, including the WAL side files — what the
         * settings screen reports, since retention is unlimited by default.
         */
        fun sizeOnDiskBytes(context: Context): Long {
            val dir = context.applicationContext.getDatabasePath(DB_NAME).parentFile ?: return 0L
            return listOf(DB_NAME, "$DB_NAME-wal", "$DB_NAME-shm")
                .map { File(dir, it) }
                .filter { it.exists() }
                .sumOf { it.length() }
        }
    }
}
