package com.example.oreshik

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.core.AgentEngine
import com.example.oreshik.agent.security.PermissionManager
import com.example.oreshik.agent.security.PermissionBroker

class VoiceActivity : AppCompatActivity() {

    companion object {
        const val MODE = "voice_mode"
        const val MODE_CONVERSATION = "conversation"
        const val MODE_SEARCH = "search"
        const val EXTRA_RESULT = "voice_result"
    }

    private var recognizer: SpeechRecognizer? = null
    private var currentMode = MODE_CONVERSATION
    private var isProcessing = false
    private lateinit var statusLabel: TextView
    private lateinit var transcriptionText: TextView
    private lateinit var responseText: TextView
    private lateinit var actionButton: ImageButton
    private lateinit var engine: AgentEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice)
        AgentConfig.init(this)
        val permissions = PermissionBroker(PermissionManager(this))
        permissions.onApprovalRequired = { tool, required ->
            com.example.oreshik.agent.security.requestToolPermission(this@VoiceActivity, tool, required)
        }
        engine = AgentEngine(this, permissions = permissions)

        currentMode = intent.getStringExtra(MODE) ?: MODE_CONVERSATION

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = if (currentMode == MODE_SEARCH) "Voice Search" else "Voice Chat"
            setNavigationOnClickListener { finish() }
        }

        statusLabel = findViewById(R.id.tvStatus)
        transcriptionText = findViewById(R.id.tvTranscript)
        responseText = findViewById(R.id.tvResponse)
        actionButton = findViewById(R.id.btnMicLarge)

        statusLabel.text = getString(R.string.voice_status_idle)

        actionButton.setOnClickListener {
            when {
                isProcessing -> { /* a Standalone Agent request is in flight; ignore taps until it resolves */ }
                recognizer != null -> stopListening()
                else -> startListening()
            }
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42)
            return
        }

        // Starting a fresh turn — clear out whatever the previous turn left behind
        // so old transcript/response text doesn't linger under the new one.
        transcriptionText.text = null
        transcriptionText.visibility = View.GONE
        responseText.text = null
        responseText.visibility = View.GONE

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say something...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            recognizer!!.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        statusLabel.text = getString(R.string.voice_status_listening)
                    }

                    override fun onBeginningOfSpeech() { statusLabel.text = getString(R.string.voice_status_listening) }
                    override fun onRmsChanged(dB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { statusLabel.text = getString(R.string.voice_status_processing) }
                    override fun onError(error: Int) {
                        val msg = SpeechErrors.message(error)
                        statusLabel.text = msg
                        Toast.makeText(this@VoiceActivity, "Speech error $error: $msg", Toast.LENGTH_SHORT).show()
                        stopListening()
                    }

                    override fun onPartialResults(partial: Bundle?) {
                        val list = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        transcriptionText.text = list?.firstOrNull() ?: ""
                        transcriptionText.visibility = if (!transcriptionText.text.isNullOrEmpty()) View.VISIBLE else View.GONE
                    }

                    override fun onResults(results: Bundle?) {
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val transcript = list?.firstOrNull() ?: ""
                        handleTranscript(transcript)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                },
            )

            recognizer!!.startListening(intent)
        } catch (e: Exception) {
            statusLabel.text = getString(R.string.voice_status_unavailable)
            Toast.makeText(this, e.message ?: "Error", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopListening() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun handleTranscript(text: String) {
        transcriptionText.text = text
        transcriptionText.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
        stopListening()

        if (currentMode == MODE_SEARCH) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
            finish()
            return
        }

        // Conversation mode: run through the same native agent engine as the main chat.
        responseText.visibility = View.VISIBLE
        responseText.text = null
        statusLabel.text = getString(R.string.voice_status_thinking)
        isProcessing = true
        lifecycleScope.launch {
            try {
                val history = mutableListOf<com.google.gson.JsonObject>()
                val result = engine.run(
                    text,
                    history,
                    AgentConfig.baseUrl,
                    AgentConfig.apiKey,
                    AgentConfig.model.ifBlank { "default" },
                    sessionId = "voice_activity",
                ) { engineEvent ->
                    handleVoiceEngineEvent(engineEvent)
                }
                result.exceptionOrNull()?.let { withContext(Dispatchers.Main) { responseText.append("\nError: ${it.message}") } }
            } finally { withContext(Dispatchers.Main) { statusLabel.text = getString(R.string.voice_status_idle) }; isProcessing = false }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if ((requestCode == 42) && (grantResults.isNotEmpty()) &&
            (grantResults[0] == PackageManager.PERMISSION_GRANTED)
        ) {
            startListening()
        }
    }

    private suspend fun handleVoiceEngineEvent(engineEvent: AgentEngine.Event) {
        if (engineEvent.type == AgentEngine.Event.Type.TEXT) {
            val chunk = engineEvent.text.orEmpty()
            withContext(Dispatchers.Main) { responseText.append(chunk) }
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }
}
