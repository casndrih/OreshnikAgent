package com.example.oreshik.agent.events

import com.example.oreshik.ChatFileChange
import android.content.Intent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event bus for communication between AgentExecutionService and UI components.
 * Uses SharedFlow to safely handle events without crashing when no collectors are present.
 */
object AgentEventBus {
    // Using extraBufferCapacity to handle bursts of events when UI is not collecting
    // replay = 0 means we don't replay old events to new collectors (each session is independent)
    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    fun send(event: AgentEvent) {
        _events.tryEmit(event)
    }
}

/**
 * Represents an event from the agent execution service.
 * Every event carries [sessionId] so concurrent sessions never cross-talk:
 * UI collectors filter on it, and cancelled/superseded sessions are dropped.
 */
sealed interface AgentEvent {
    val sessionId: String

    data class Thinking(override val sessionId: String, val text: String) : AgentEvent
    data class ToolStarted(override val sessionId: String, val name: String, val description: String) : AgentEvent
    data class ToolCompleted(
        override val sessionId: String,
        val name: String,
        val description: String,
        val status: String,
        val metadataJson: String?,
    ) : AgentEvent
    data class FileChanges(override val sessionId: String, val files: List<ChatFileChange>) : AgentEvent
    data class Text(override val sessionId: String, val text: String, val model: String?) : AgentEvent
    /** Live in-app browser state, broadcast after every web.browser action so the
     *  chat can show/scroll to the live browser bubble and update its URL bar in real time. */
    data class BrowserLive(
        override val sessionId: String,
        val url: String,
        val title: String,
        val progress: Int,
    ) : AgentEvent
    data class Completed(override val sessionId: String) : AgentEvent
    data class Cancelled(override val sessionId: String) : AgentEvent
    data class Error(override val sessionId: String, val message: String) : AgentEvent
}

/**
 * Extension to convert AgentExecutionService event data to AgentEvent.
 * Currently unused by the in-process event flow but kept as a public helper
 * for future cross-process event decoding.
 */
@Suppress("unused")
fun Intent.toAgentEvent(sessionId: String): AgentEvent? {
    val type = getStringExtra("event_type") ?: return null
    val text = getStringExtra("event_text") ?: ""
    val name = getStringExtra("event_name")
    val status = getStringExtra("tool_status")
    val metadataJson = getStringExtra("metadata")
    val model = getStringExtra("model")

    return when (type) {
        "thinking" -> AgentEvent.Thinking(sessionId, text.ifBlank { "Thinking…" })
        "tool_started" -> AgentEvent.ToolStarted(sessionId, name.orEmpty(), text.ifBlank { "Running…" })
        "tool_completed" -> AgentEvent.ToolCompleted(
            sessionId,
            name.orEmpty(),
            text,
            status ?: "done",
            metadataJson
        )
        "file_changes" -> {
            val listType = object : com.google.gson.reflect.TypeToken<List<ChatFileChange>>() {}.type
            val files = runCatching {
                com.google.gson.Gson().fromJson<List<ChatFileChange>>(metadataJson.orEmpty(), listType).orEmpty()
            }.getOrDefault(emptyList())
            AgentEvent.FileChanges(sessionId, files)
        }
        "text" -> AgentEvent.Text(sessionId, text, model)
        "completed" -> AgentEvent.Completed(sessionId)
        "cancelled" -> AgentEvent.Cancelled(sessionId)
        "error" -> AgentEvent.Error(sessionId, text.ifBlank { "Unknown error" })
        else -> null
    }
}
