package com.example.oreshik

import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.oreshik.data.AgentDatabase
import com.example.oreshik.data.MemoryEntity
import com.example.oreshik.data.MemoryStore
import com.example.oreshik.logging.AppLogger
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class MemoryActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)
        AppLogger.i(TAG, "Memory screen opened")
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        list = findViewById(R.id.memoryList)
        findViewById<MaterialButton>(R.id.btnAddMemory).setOnClickListener {
            val input = findViewById<EditText>(R.id.etMemory)
            val text = input.text.toString().trim()
            if (text.isNotBlank()) {
                scope.launch {
                    val result = MemoryStore(this@MemoryActivity).add(text)
                    withContext(Dispatchers.Main) {
                        if (result.isSuccess) {
                            AppLogger.i(TAG, "Memory saved (${text.take(60)}…)")
                            input.text.clear()
                            load()
                        } else {
                            AppLogger.w(TAG, "Memory save failed: ${result.exceptionOrNull()?.message}")
                            input.error = result.exceptionOrNull()?.message
                        }
                    }
                }
            }
        }
        load()
    }

    private fun load() {
        scope.launch(Dispatchers.IO) {
            val memories = AgentDatabase.get(this@MemoryActivity).memories().active()
            AppLogger.d(TAG, "Loaded ${memories.size} active memories")
            withContext(Dispatchers.Main) {
                list.removeAllViews()
                if (memories.isEmpty()) {
                    list.addView(
                        TextView(this@MemoryActivity).apply {
                            text = getString(R.string.memory_empty)
                            setPadding(0, 24, 0, 24)
                        }
                    )
                }
                memories.forEach { addMemory(it) }
            }
        }
    }

    private fun addMemory(m: MemoryEntity) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tv = TextView(this).apply { text = m.text; textSize = 15f }
        // Badge: semantic-embedding state + memory kind + how it was written
        // (manual UI entry, memory.save tool call, or autonomous learning).
        val kindLabel = when (m.kind) {
            "session_digest" -> "digest"
            "note" -> "note"
            else -> "fact"
        }
        val sourceLabel = when (m.source) {
            "auto" -> "auto-learned"
            "tool" -> "agent-saved"
            else -> "manual"
        }
        val badge = TextView(this).apply {
            text = if (m.embedding != null) "⬡ vector · $kindLabel · $sourceLabel" else "⬡ plain · $kindLabel · $sourceLabel"
            textSize = 11f
            setTextColor(getColor(if (m.embedding != null) R.color.brand_600 else R.color.text_tertiary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 0) }
        }
        textCol.addView(tv)
        textCol.addView(badge)
        val del = MaterialButton(this).apply {
            text = getString(R.string.memory_delete)
            setOnClickListener {
                scope.launch(Dispatchers.IO) {
                    AppLogger.i(TAG, "Memory deleted #${m.id}")
                    AgentDatabase.get(this@MemoryActivity).memories().delete(m.id)
                    withContext(Dispatchers.Main) { load() }
                }
            }
        }
        row.addView(textCol)
        row.addView(del)
        list.addView(row)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Memory"
    }
}
