package com.notifylog.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notifylog.app.R
import com.notifylog.app.data.Gap
import com.notifylog.app.data.ListenerEventDao
import com.notifylog.app.data.NotifyDatabase
import com.notifylog.app.databinding.ActivityGapsBinding
import com.notifylog.app.databinding.ItemGapBinding
import com.notifylog.app.util.Formats
import com.notifylog.app.util.padSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows when capture was *not* running.
 *
 * Without this a ROM kill is invisible — the log simply has fewer rows and the
 * user has no way to tell "nothing arrived" from "we weren't listening".
 */
class GapsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGapsBinding
    private val adapter = GapAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGapsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padSystemBars()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val gaps = withContext(Dispatchers.IO) {
                runCatching {
                    ListenerEventDao.toGaps(
                        NotifyDatabase.get(this@GapsActivity)
                            .listenerEventDao()
                            .recent(EVENT_LIMIT)
                    )
                }.getOrDefault(emptyList())
            }
            adapter.submit(gaps)
            binding.empty.visibility = if (gaps.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private inner class GapAdapter : RecyclerView.Adapter<GapAdapter.VH>() {

        private val items = ArrayList<Gap>()

        // The whole list is replaced in one shot from a single load, and gaps
        // have no identity to diff on, so a blanket invalidate is the honest
        // call here rather than a fake fine-grained notification.
        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Gap>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemGapBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        override fun getItemCount() = items.size

        inner class VH(private val b: ItemGapBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(gap: Gap) {
                val end = gap.end?.let { Formats.dateTime(it) }
                    ?: getString(R.string.gaps_ongoing)
                b.range.text = getString(R.string.gaps_item, Formats.dateTime(gap.start), end)

                val duration = gap.durationMs
                b.duration.visibility = if (duration == null) View.GONE else View.VISIBLE
                if (duration != null) {
                    b.duration.text = getString(R.string.gaps_duration, Formats.duration(duration))
                }
            }
        }
    }

    companion object {
        private const val EVENT_LIMIT = 200
    }
}
