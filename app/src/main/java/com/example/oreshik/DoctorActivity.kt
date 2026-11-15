package com.example.oreshik

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.oreshik.agent.runtime.RuntimeManager
import com.example.oreshik.logging.AppLogger
import com.google.android.material.appbar.MaterialToolbar

class DoctorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list_settings)
        AppLogger.i(TAG, "Doctor diagnostics screen opened")
        findViewById<MaterialToolbar>(R.id.toolbar).apply { title = "Agent Diagnostics"; setNavigationOnClickListener { finish() } }
        findViewById<TextView>(R.id.tvSubtitle).apply { text = getString(R.string.doctor_subtitle); visibility = android.view.View.VISIBLE }
        val container = findViewById<LinearLayout>(R.id.sectionContainer)
        val rows = SettingsUi.addSection(LayoutInflater.from(this), container, "Runtime")
        RuntimeManager(this).capabilityReport().forEach { (name, ok) ->
            val row = SettingsUi.addRow(LayoutInflater.from(this), rows, name, if (ok) "Available" else "Not installed")
            SettingsUi.valueOf(row).text = if (ok) "OK" else "Missing"
            AppLogger.d(TAG, "Capability $name: ${if (ok) "OK" else "MISSING"}")
        }
    }

    private companion object {
        const val TAG = "Doctor"
    }
}
