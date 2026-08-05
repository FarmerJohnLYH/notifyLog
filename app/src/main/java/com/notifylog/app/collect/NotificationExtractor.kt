package com.notifylog.app.collect

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.os.BundleCompat
import com.notifylog.app.data.NotificationEntity

/**
 * Pulls the text "preview" fields out of a notification. Kept intentionally
 * light: no bitmaps, no PackageManager calls beyond a cached label lookup.
 */
object NotificationExtractor {

    // Field separator for the dedup hash: an unlikely-in-content control char.
    // Written as an escape rather than a literal byte so the source stays
    // plain text (a raw 0x01 makes tools classify the file as binary).
    private const val SEP = "\u0001"

    private val labelCache = HashMap<String, String>()

    fun extract(context: Context, sbn: StatusBarNotification): NotificationEntity {
        val n = sbn.notification
        val extras = n.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        // Chat apps (MessagingStyle) and multi-line summaries (InboxStyle) put
        // the actual content in these arrays and leave EXTRA_TEXT holding only
        // something like "3 new messages", so fold them into the big-text slot.
        val styleLines = messagingLines(extras) ?: inboxLines(extras)

        val effectiveBig = when {
            styleLines != null -> styleLines
            !bigText.isNullOrEmpty() && bigText != text -> bigText
            else -> null
        }

        return NotificationEntity(
            postTime = sbn.postTime,
            packageName = sbn.packageName,
            appLabel = appLabel(context, sbn.packageName),
            title = title,
            text = text,
            bigText = effectiveBig,
            subText = subText,
            category = n.category,
            channelId = n.channelId,
            sbnKey = sbn.key,
            dedupHash = dedupHash(sbn.key, title, text, effectiveBig)
        )
    }

    /**
     * MessagingStyle: `EXTRA_MESSAGES` is an array of Bundles carrying the
     * sender and the message body. Rendered as `sender: body` per line.
     */
    private fun messagingLines(extras: Bundle): String? {
        val raw = BundleCompat.getParcelableArray(
            extras, Notification.EXTRA_MESSAGES, Bundle::class.java
        ) ?: return null
        // The compat helper type-checks elements at runtime but still hands back
        // a Parcelable array, so each entry needs narrowing.
        val lines = raw.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val body = b.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val sender = b.getCharSequence("sender")?.toString()?.takeIf { it.isNotBlank() }
            if (sender != null) "$sender: $body" else body
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** InboxStyle: `EXTRA_TEXT_LINES` holds one CharSequence per row. */
    private fun inboxLines(extras: Bundle): String? {
        val raw = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) ?: return null
        val lines = raw.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    @Synchronized
    private fun appLabel(context: Context, pkg: String): String {
        labelCache[pkg]?.let { return it }
        val pm = context.packageManager
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
        labelCache[pkg] = label
        return label
    }

    /** Stable, allocation-light content hash (no random / no time input). */
    fun dedupHash(key: String?, title: String?, text: String?, bigText: String?): Long {
        val s = (key ?: "") + SEP + (title ?: "") + SEP + (text ?: "") + SEP + (bigText ?: "")
        var h = 1125899906842597L
        for (c in s) h = 31 * h + c.code
        return h
    }
}
