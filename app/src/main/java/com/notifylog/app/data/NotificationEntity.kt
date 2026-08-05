package com.notifylog.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One captured notification. Text-only by design (no large icons / avatars),
 * so each row stays small even with permanent retention.
 */
@Entity(
    tableName = "notification_log",
    indices = [Index("postTime"), Index("packageName")]
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postTime: Long,
    val packageName: String,
    val appLabel: String?,
    val title: String?,
    val text: String?,
    /** Stored only when different from [text], to avoid duplication. */
    val bigText: String?,
    val subText: String?,
    val category: String?,
    val channelId: String?,
    val sbnKey: String?,
    /** Hash of key + content, used to skip duplicate updates of the same notification. */
    val dedupHash: Long
)
