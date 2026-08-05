package com.notifylog.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notifylog.app.data.NotificationEntity
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
    private val onLongClick: (NotificationEntity) -> Unit
) : ListAdapter<NotificationEntity, LogAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(e: NotificationEntity) {
            b.app.text = e.appLabel ?: e.packageName
            b.time.text = Formats.time(e.postTime)

            val title = e.title
            b.title.text = title.orEmpty()
            b.title.visibility = if (title.isNullOrBlank()) View.GONE else View.VISIBLE

            val body = e.bigText ?: e.text ?: e.subText ?: ""
            b.body.text = body
            b.body.visibility = if (body.isBlank()) View.GONE else View.VISIBLE

            bindIcon(e.packageName)

            b.root.setOnLongClickListener {
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
            b.icon.setImageDrawable(null)
            val context = b.icon.context.applicationContext
            scope.launch {
                val icon = withContext(Dispatchers.IO) { AppIcons.load(context, pkg) }
                    ?: return@launch
                if (b.icon.tag == pkg) b.icon.setImageDrawable(icon)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NotificationEntity>() {
            override fun areItemsTheSame(a: NotificationEntity, b: NotificationEntity) = a.id == b.id
            override fun areContentsTheSame(a: NotificationEntity, b: NotificationEntity) = a == b
        }
    }
}
