package com.example.oreshik

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.example.oreshik.logging.AppLogger
import com.google.android.material.appbar.MaterialToolbar

class ConfigActivity : AppCompatActivity() {
    private val key by lazy { intent.getStringExtra(EXTRA_KEY) ?: "unknown" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)
        AppLogger.i(TAG, "Config editor opened for key='$key'")
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = intent.getStringExtra(EXTRA_TITLE) ?: key
            setNavigationOnClickListener { finish() }
        }
        findViewById<TextView>(R.id.tvConfigKey).text = key
        val input = findViewById<EditText>(R.id.etConfigValue)
        input.setText(read(this, key).orEmpty())
        findViewById<View>(R.id.progressBar).visibility = View.GONE
        findViewById<TextView>(R.id.btnSaveConfig).setOnClickListener {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                putString(PREFIX + key, input.text.toString())
            }
            AppLogger.i(TAG, "Config value saved for key='$key' (${input.text.length} chars)")
            findViewById<TextView>(R.id.tvConfigStatus).text = getString(R.string.config_saved_locally)
        }
    }

    companion object {
        const val EXTRA_KEY = "config_key"
        const val EXTRA_TITLE = "config_title"
        private const val PREFS = "agent_preferences"
        private const val PREFIX = "config_"
        private const val TAG = "Config"

        fun read(context: Context, key: String) =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREFIX + key, null)
    }
}
