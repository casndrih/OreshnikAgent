package com.example.oreshik

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.oreshik.logging.AppLogger
import com.google.android.material.appbar.MaterialToolbar

/**
 * Shown for drawer sections that aren't part of Standalone AgentAgent's REST
 * surface (app theming, biometrics, local caches, etc.) — honest about
 * not being wired up yet, rather than silently landing on an unrelated
 * screen.
 */
class PlaceholderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "placeholder_title"
    }

    private val tag = "Placeholder"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list_settings)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Settings"
        AppLogger.i(tag, "Placeholder screen shown for '$title'")
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            this.title = title
            setNavigationOnClickListener { finish() }
        }
        findViewById<ProgressBar>(R.id.progressBar).visibility = View.GONE
        findViewById<TextView>(R.id.tvEmptyState).apply {
            text = getString(R.string.placeholder_not_backed_yet, title)
            visibility = View.VISIBLE
        }
    }
}
