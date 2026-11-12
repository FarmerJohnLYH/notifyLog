package com.notifylog.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notifylog.app.data.NotificationEntity
import com.notifylog.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.notifylog.app.databinding.ItemLogBinding
import com.notifylog.app.util.AppIcons
import com.notifylog.app.util.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders the log. Rows are diffed rather than reloaded wholesale, so a new
 * notification arriving doesn't blow away scroll position or flash the list.
 */
class LogAdapter(
    private val scope: CoroutineScope,
    private val onLongClick: (NotificationEntity) -> Unit,
    private val onClick: (NotificationEntity) -> Unit
) : ListAdapter<LogAdapter.Row, LogAdapter.VH>(DIFF) {

    data class Row(val entity: NotificationEntity, val date: LocalDate, val showHeader: Boolean)

    fun submitEntries(entries: List<NotificationEntity>, committed: Runnable? = null) {
        val zone = ZoneId.systemDefault()
        var previous: LocalDate? = null
        val rows = entries.map { entity ->
            val date = Instant.ofEpochMilli(entity.postTime).atZone(zone).toLocalDate()
            Row(entity, date, date != previous).also { previous = date }
        }
        submitList(rows, committed)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(row: Row) {
            val e = row.entity
            val today = LocalDate.now()
            b.dayHeader.text = when (row.date) {
                today -> b.root.context.getString(R.string.today)
                today.minusDays(1) -> b.root.context.getString(R.string.yesterday)
                else -> row.date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
            }
            b.dayHeader.visibility = if (row.showHeader) View.VISIBLE else View.GONE
            b.app.text = e.appLabel ?: e.packageName
            b.time.text = Instant.ofEpochMilli(e.postTime).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))

            val title = e.title
            b.title.text = title.orEmpty()
            b.title.visibility = if (title.isNullOrBlank()) View.GONE else View.VISIBLE

            val body = e.bigText ?: e.text ?: e.subText ?: ""
            b.body.text = body
            b.body.visibility = if (body.isBlank()) View.GONE else View.VISIBLE

            bindIcon(e.packageName)

            b.content.setOnClickListener { onClick(e) }
            b.content.setOnLongClickListener {
                onLongClick(e)
                true
            }
        }

        /**
         * Icons come from PackageManager, which does I/O. A cache hit binds
         * synchronously; a miss loads off the main thread and only applies the
         * result if the holder hasn't been recycled onto another package.
         */
        private fun bindIcon(pkg: String) {
            b.icon.tag = pkg
            val cached = AppIcons.cached(pkg)
            if (cached != null) {
                b.icon.setImageDrawable(cached)
                return
            }
            b.icon.setImageResource(R.drawable.ic_box)
            val context = b.icon.context.applicationContext
            scope.launch {
                val icon = withContext(Dispatchers.IO) { AppIcons.load(context, pkg) }
                    ?: return@launch
                if (b.icon.tag == pkg) b.icon.setImageDrawable(icon)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.entity.id == b.entity.id
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }
}
