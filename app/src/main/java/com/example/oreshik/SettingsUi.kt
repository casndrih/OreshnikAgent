package com.example.oreshik

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageButton
import com.google.android.material.materialswitch.MaterialSwitch

/** Small helpers for building the card/row settings UI programmatically. */
object SettingsUi {

    /** Inflates a view_settings_section card into [parent] and returns its rowsContainer. */
    fun addSection(
        inflater: LayoutInflater,
        parent: LinearLayout,
        title: String,
        subtitle: String? = null,
    ): LinearLayout {
        val section = inflater.inflate(R.layout.view_settings_section, parent, false)
        section.findViewById<TextView>(R.id.sectionTitle).text = title
        val subtitleView = section.findViewById<TextView>(R.id.sectionSubtitle)
        if (subtitle.isNullOrBlank()) {
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.text = subtitle
        }
        parent.addView(section)
        return section.findViewById(R.id.rowsContainer)
    }

    /** Inflates a view_settings_row into [rowsContainer]. Returns the row for further setup. */
    fun addRow(
        inflater: LayoutInflater,
        rowsContainer: LinearLayout,
        title: String,
        subtitle: String? = null,
        iconRes: Int? = null,
    ): View {
        val row = inflater.inflate(R.layout.view_settings_row, rowsContainer, false)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        val subtitleView = row.findViewById<TextView>(R.id.rowSubtitle)
        if (subtitle.isNullOrBlank()) subtitleView.visibility = View.GONE
        else subtitleView.text = subtitle
        val icon = row.findViewById<ImageView>(R.id.rowIcon)
        if (iconRes != null) icon.setImageResource(iconRes) else icon.visibility = View.GONE
        rowsContainer.addView(row)
        return row
    }

    fun switchOf(row: View): MaterialSwitch =
        row.findViewById<MaterialSwitch>(R.id.rowSwitch).also { it.visibility = View.VISIBLE }

    fun actionOf(row: View): ImageButton = row.findViewById<ImageButton>(R.id.rowAction).also { it.visibility = View.VISIBLE }

    fun valueOf(row: View): TextView =
        row.findViewById<TextView>(R.id.rowValue).also { it.visibility = View.VISIBLE }
}
