package com.notifylog.app.ui

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.notifylog.app.R
import com.notifylog.app.data.Exporter

/** One compact export surface. Selections survive theme changes and recreation. */
class ExportSheet : BottomSheetDialogFragment() {
    private var format = Exporter.Format.CSV
    private var range = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        format = savedInstanceState?.getString("format")
            ?.let { runCatching { Exporter.Format.valueOf(it) }.getOrNull() } ?: Exporter.Format.CSV
        range = savedInstanceState?.getString("range") ?: "all"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("format", format.name)
        outState.putString("range", range)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext())
        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(28))
            setBackgroundResource(R.drawable.glass_sheet)
        }
        body.addView(View(requireContext()).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.secondary))
        }, LinearLayout.LayoutParams(dp(36), dp(3)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(24)
        })
        body.addView(label(getString(R.string.export_title), 28f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        fun section(title: Int) {
            body.addView(label(getString(title), 13f).apply {
                setTextColor(ContextCompat.getColor(context, R.color.secondary))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(10) })
        }
        section(R.string.export_format_label)
        body.addView(choices(listOf("CSV" to "CSV", "JSON" to "JSON"), format.name) {
            format = Exporter.Format.valueOf(it)
        })
        section(R.string.export_range_label)
        body.addView(choices(listOf("today" to getString(R.string.today),
            "week" to getString(R.string.recent_week), "all" to getString(R.string.filter_all),
            "custom" to getString(R.string.export_time_custom)), range) { range = it })
        section(R.string.export_app_label)
        body.addView(label(requireArguments().getString("app").orEmpty(), 16f).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundResource(R.drawable.glass_capsule)
        })
        requireArguments().getString("query")?.let { query ->
            body.addView(label(getString(R.string.export_filter_note, query), 13f).apply {
                setPadding(0, dp(12), 0, 0)
                setTextColor(ContextCompat.getColor(context, R.color.secondary))
            })
        }
        body.addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = getString(R.string.export_action)
            textSize = 17f
            cornerRadius = dp(28)
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.rgb(8, 122, 255))
            setOnClickListener {
                dismiss()
                (activity as? LogListActivity)?.confirmExport(format, range)
            }
        }, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(28) })
        val scroll = ScrollView(requireContext()).apply { addView(body) }
        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        return dialog
    }

    private fun choices(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundResource(R.drawable.glass_capsule)
        }
        options.forEach { (value, title) ->
            val button = label(title, 14f).apply {
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setTextColor(ContextCompat.getColorStateList(context, R.color.pill_text))
                setBackgroundResource(R.drawable.pill_background)
                isSelected = value == selected
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    for (i in 0 until row.childCount) row.getChildAt(i).isSelected = row.getChildAt(i) === this
                    onPick(value)
                }
            }
            row.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return row
    }

    private fun label(value: String, size: Float) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(ContextCompat.getColor(context, R.color.ink))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance(app: String, query: String?) = ExportSheet().apply {
            arguments = Bundle().apply { putString("app", app); putString("query", query) }
        }
    }
}
