package com.example.oreshik

import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** Renders the bounded file patch inside the chat's end drawer. */
class FileDiffAdapter : RecyclerView.Adapter<FileDiffAdapter.DiffLineHolder>() {

    private val rows = mutableListOf<ChatDiffLine>()

    fun submitList(newRows: List<ChatDiffLine>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiffLineHolder {
        val density = parent.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val text = TextView(parent.context).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        return DiffLineHolder(text)
    }

    override fun onBindViewHolder(holder: DiffLineHolder, position: Int) {
        val row = rows[position]
        val context = holder.itemView.context
        val textView = holder.text
        val primary = ContextCompat.getColor(context, R.color.text_primary)
        val muted = ContextCompat.getColor(context, R.color.text_secondary)
        val added = ContextCompat.getColor(context, R.color.status_online)
        val removed = ContextCompat.getColor(context, R.color.chip_error_fg)
        when (row.kind) {
            "hunk" -> {
                textView.text = row.text
                textView.setTextColor(muted)
                textView.setBackgroundColor(ContextCompat.getColor(context, R.color.bg_card))
                textView.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            "added" -> {
                textView.text = String.format("%4s %4d + %s", "", row.newLine ?: 0, row.text)
                textView.setTextColor(added)
                textView.setBackgroundColor(ColorUtils.setAlphaComponent(added, 22))
                textView.typeface = Typeface.MONOSPACE
            }
            "removed" -> {
                textView.text = String.format("%4d %4s − %s", row.oldLine ?: 0, "", row.text)
                textView.setTextColor(removed)
                textView.setBackgroundColor(ColorUtils.setAlphaComponent(removed, 20))
                textView.typeface = Typeface.MONOSPACE
            }
            else -> {
                textView.text = String.format("%4d %4d   %s", row.oldLine ?: 0, row.newLine ?: 0, row.text)
                textView.setTextColor(primary)
                textView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                textView.typeface = Typeface.MONOSPACE
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    class DiffLineHolder(val text: TextView) : RecyclerView.ViewHolder(text)
}
