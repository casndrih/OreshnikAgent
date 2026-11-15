package com.example.oreshik

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.core.NvidiaKeyManager
import com.example.oreshik.logging.AppLogger
import com.google.android.material.appbar.MaterialToolbar

/**
 * Manage one or multiple NVIDIA API keys. Keys are shown masked; add/remove and
 * live validation via a lightweight models-list probe. Rotation behavior is
 * automatic (round-robin + cooldown on 429/401/403/5xx) and needs no controls.
 */
class KeyManagementActivity : AppCompatActivity() {

    private val keyManager = NvidiaKeyManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentConfig.init(this)
        setContentView(R.layout.activity_key_management)
        AppLogger.i(TAG, "Key management opened (${keyManager.loadKeys().size} key(s) on file)")

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val input = findViewById<EditText>(R.id.etNewKey)
        val listContainer = findViewById<LinearLayout>(R.id.keysContainer)
        val empty = findViewById<TextView>(R.id.tvKeysEmpty)
        val status = findViewById<TextView>(R.id.tvKeyStatus)

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddKey).setOnClickListener {
            val key = input.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "Paste an NVIDIA API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            keyManager.addKey(key)
            AppLogger.i(TAG, "Key added (${NvidiaKeyManager.mask(key)})")
            input.text.clear()
            Toast.makeText(this, "Key added", Toast.LENGTH_SHORT).show()
            renderKeys(listContainer, empty, status)
        }

        renderKeys(listContainer, empty, status)
    }

    private fun renderKeys(listContainer: LinearLayout, empty: TextView, status: TextView) {
        listContainer.removeAllViews()
        val keys = keyManager.loadKeys()
        AppLogger.d(TAG, "Rendering ${keys.size} key(s)")
        empty.visibility = if (keys.isEmpty()) View.VISIBLE else View.GONE
        status.text = when {
            keys.isEmpty() -> "No keys configured. The app needs one key for requests."
            keys.size == 1 -> "Single key mode. Add more keys to enable automatic rotation."
            else -> "${keys.size} keys configured — requests rotate automatically across healthy keys (rate-limited keys cool down, then return)."
        }
        val inflater = layoutInflater
        keys.forEach { key ->
            val row = inflater.inflate(R.layout.item_api_key, listContainer, false)
            row.findViewById<TextView>(R.id.tvKeyMasked).text = NvidiaKeyManager.mask(key)
            row.findViewById<View>(R.id.btnRemoveKey).setOnClickListener {
                AppLogger.i(TAG, "Key removed (${NvidiaKeyManager.mask(key)})")
                keyManager.removeKey(key)
                renderKeys(listContainer, empty, status)
                Toast.makeText(this, "Key removed", Toast.LENGTH_SHORT).show()
            }
            listContainer.addView(row)
        }
    }

    private companion object {
        const val TAG = "Keys"
    }
}
