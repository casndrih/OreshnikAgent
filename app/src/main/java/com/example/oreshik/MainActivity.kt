package com.example.oreshik

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.app.PictureInPictureParams
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.MimeTypeMap
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.Rational
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.core.AgentTaskManager
import com.example.oreshik.agent.core.ToolContext
import com.example.oreshik.agent.events.AgentEvent
import com.example.oreshik.agent.events.AgentEventBus
import com.example.oreshik.agent.runtime.RuntimeManager
import com.example.oreshik.agent.security.PermissionManager
import com.example.oreshik.agent.tools.GgufQuantizeTool
import com.example.oreshik.data.AgentDatabase
import com.example.oreshik.data.SessionEntity
import com.example.oreshik.data.SessionListItem
import com.example.oreshik.logging.AppLogger
import com.example.oreshik.ai.DeviceModelAdvisor
import com.example.oreshik.ai.OfflineModelRuntime
import com.example.oreshik.ai.OfflineModelStore
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.navigation.NavigationView
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.oreshik.data.SettingEntity
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : AppCompatActivity(), ModelSelectionListener {
    private val ggufModelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importOfflineModel)
    }
    private val greetings = listOf("What are we building?", "What should we fix?", "Ready to code.", "Describe the task.")
    private val chatAdapter = ChatAdapter()
    private val history = mutableListOf<JsonObject>()
    private val pendingChatAttachments = mutableListOf<ChatAttachment>()
    private var hasSentFirstMessage = false
    private lateinit var emptyState: View
    private lateinit var messagesRecycler: RecyclerView
    private lateinit var tvGreeting: TextView
    private lateinit var topBar: View
    private lateinit var selectionBar: View
    private lateinit var etMessage: EditText
    private lateinit var composerAttachmentChips: ChipGroup
    private lateinit var btnSend: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var btnGhost: ImageButton
    lateinit var btnModelSelector: TextView
    private lateinit var btnMic: ImageButton
    private lateinit var navView: NavigationView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var fileDiffDrawer: View
    private lateinit var pipChatOverlay: View
    private lateinit var pipChatTitle: TextView
    private lateinit var pipChatStatus: TextView
    private lateinit var pipMessagesRecycler: RecyclerView
    private val modelRepository = NvidiaModelRepository()
    private var cachedModels: List<Model> = emptyList()
    private var cachedModelProviderId: String? = null
    private var requestedModelProviderId: String? = null
    private var refreshOfflineModelsUi: (() -> Unit)? = null
    private val gson = Gson()
    private lateinit var sessionAdapter: SessionAdapter
    private val sessionRefreshGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private var currentSessionId: String = UUID.randomUUID().toString()
    private var currentSessionCreatedAt: Long = System.currentTimeMillis()
    /** Last updatedAt we have seen/persisted for the current session (0 = unknown). */
    private var currentSessionLastUpdatedAt: Long = 0L
    private var currentSessionTitle: String = "New chat"
    /**
     * True while the agent is actively processing for the CURRENT session
     * (thinking, calling tools or streaming the reply). Single source of
     * truth for the composer's send-button icon.
     */
    @Volatile private var agentRunning = false
    /** True while a send is waiting on the session freshness check / service handoff. */
    private var dispatchPending = false
    private var accumulatedResponse = ""
    
    // Voice recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    /** True while a recognition session is alive (until onResults/onError destroy it). */
    private var recognitionInFlight = false
    /** One-shot auto-retry flag for transient server errors (no endless retry loop). */
    private var retriedAfterError = false
    private var pendingVoiceText: String? = null
    @Suppress("PrivatePropertyName")
    private val VOICE_PERMISSION_REQUEST = 1001
    
    // Text-to-speech
    private var textToSpeech: android.speech.tts.TextToSpeech? = null
    private var isSpeaking = false
    @Volatile private var activityDestroyed = false
    private var ttsReady = false
    private var pendingSpeechText: String? = null
    private val ttsLock = Any()

    companion object {
        /**
         * Rotation-survival cap for the JSON snapshots written into
         * onSaveInstanceState. The per-Binder transaction limit is ~1MB
         * (async) / 200KB (sync), and that buffer is shared with every other
         * Binder call in flight — staying well under it avoids the
         * TransactionTooLargeException crash on Activity.stop().
         *
         * Units matter: Bundle strings are parceled as UTF-16, so 1 JSON
         * char costs ~2 bytes in the transaction. A measured crash parcel
         * was 564,072 bytes for ~282K chars — confirming the 2:1 ratio.
         * 150K chars therefore keeps the parcel at ~300KB, which is the
         * safe middle ground.
         *
         * IMPORTANT: this is a SHARED budget across the whole snapshot
         * payload (messages + history together), not a per-string cap —
         * onSaveInstanceState bundles everything into one transaction, so
         * two ~300K-char strings would still be ~1.2MB of parcel. Longer
         * conversations always have a DB row to reload, so the snapshot is
         * simply skipped for them.
         */
        private const val MAX_STATE_JSON_CHARS = 150_000

        /**
         * Above this size (chars; ~2 bytes each once parceled), the history +
         * messages payload must NOT travel through the ACTION_RUN intent:
         * system_server re-parcels the whole intent when delivering the service
         * start, and a TransactionTooLargeException THERE leaves the
         * startForegroundService() obligation pending — the process is then
         * killed 30s later with ForegroundServiceDidNotStartInTimeException.
         * Oversized payloads use the DB row when history persistence is
         * available, or an app-cache payload file otherwise.
         */
        private const val MAX_INTENT_PAYLOAD_CHARS = 80_000
        private const val STATE_MESSAGES_JSON = "MESSAGES_JSON"
        private const val STATE_HISTORY_JSON = "HISTORY_JSON"
        private const val STATE_AGENT_RUNNING = "AGENT_RUNNING"
        private const val STATE_COMPOSER_TEXT = "COMPOSER_TEXT"
        private const val STATE_PENDING_ATTACHMENTS_JSON = "PENDING_ATTACHMENTS_JSON"
        private const val MAX_ATTACHMENT_COPY_BYTES = 50L * 1024L * 1024L
    }

    /** True when the chat was restored from saved instance state (rotation / theme change). */
    private var restoredFromState = false
    private var suppressPipOnNextLeave = false
    private var deviceAccessAuditBusy = false
    private var deviceAccessAuditDoneForResume = false
    private var deviceAccessAuditWaitingForConfig = false
    private var deviceAccessAwaitingSystemUi = false
    private var deviceAccessAwaitingRuntimePermissions = false
    private var deviceAccessRootRequestInFlight = false
    private var deviceAccessRootAvailable = false
    private val deviceAccessPromptedThisForeground = mutableSetOf<String>()

    /** True once this Activity instance registered the agent-event collector (see [startAgentEventCollector]). */
    private var agentEventsRegistered = false
    private val permissionApprovalDialogs = mutableMapOf<String, android.app.AlertDialog>()

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Thinking -> chatAdapter.showProcessing(event.text)
            is AgentEvent.ToolStarted -> {
                chatAdapter.addToolActivity(
                    event.name.ifBlank { event.description.ifBlank { "tool" } },
                    event.description
                )
            }
            is AgentEvent.ToolCompleted -> {
                chatAdapter.completeToolActivity(
                    event.name.ifBlank { event.description.ifBlank { "tool" } },
                    event.status,
                    event.description
                )
                chatAdapter.showProcessing("Thinking…")
                // LIVE browser: instead of static screenshot bubbles, append (once
                // per run) a chat message embedding the live browser bubble. The
                // user then watches the agent browse in real time; later actions
                // just update that same bubble via LiveBrowser state listeners.
                if (event.name.replace('_', '.') == "web.browser") {
                    if (event.metadataJson != null) {
                        try {
                            val metadata = com.google.gson.JsonParser.parseString(event.metadataJson).asJsonObject
                            // Optional HTML card (kept from the old behavior)
                            val html = metadata["html"]?.asString
                            if (!html.isNullOrBlank()) {
                                val titleRegex = java.util.regex.Pattern.compile("<title[^>]*>(.*?)</title>", java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.DOTALL)
                                val pageTitle = titleRegex.matcher(html).let { matcher -> if (matcher.find()) matcher.group(1)?.trim() else null }
                                val urlFromMeta = metadata["url"]?.asString ?: ""
                                if (urlFromMeta.isNotBlank()) {
                                    val webPreview = MediaPreview.WebView(
                                        url = urlFromMeta,
                                        title = pageTitle ?: "Web Page",
                                        content = html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(200),
                                    )
                                    chatAdapter.addMessage(
                                        ChatMessage(
                                            role = "assistant",
                                            content = "",
                                            kind = "text",
                                            linkPreviews = listOf(webPreview),
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {
                            // Ignore preview parsing errors
                        }
                    }
                    showLiveBrowserBubble()
                }
            }
            is AgentEvent.FileChanges -> chatAdapter.addFileChanges(event.files)
            is AgentEvent.BrowserLive -> {
                // Mid-run live updates between tool completions: keep the live
                // browser bubble visible (and newest) while the agent navigates.
                if (event.sessionId == currentSessionId) showLiveBrowserBubble()
            }
            is AgentEvent.Text -> {
                chatAdapter.hideProcessing()
                val value = event.text.trim()
                if (value.isNotBlank()) {
                    val last = chatAdapter.messages.lastOrNull()
                    val appendToExisting = last != null && last.role == "assistant" && last.kind == "text"
                    val duplicateOfLast = appendToExisting && last.content.endsWith(value)
                    when {
                        duplicateOfLast -> {
                            // Defense in depth against the repeated-response bug: the
                            // full final answer is emitted exactly once per run now, and
                            // the event collector is registered once per Activity (see
                            // [startAgentEventCollector]). If an identical full text ever
                            // arrives again, drop it instead of concatenating a copy.
                            AppLogger.w(
                                "Chat",
                                "dropped duplicate final text (${value.length} chars) for session=$currentSessionId"
                            )
                        }
                        appendToExisting -> {
                            chatAdapter.messages[chatAdapter.messages.lastIndex] = last.copy(content = last.content + value)
                            chatAdapter.notifyItemChanged(chatAdapter.messages.lastIndex)
                            accumulatedResponse += value
                        }
                        else -> {
                            chatAdapter.addMessage(ChatMessage(role = "assistant", content = value, model = event.model))
                            accumulatedResponse += value
                        }
                    }
                    // Live "the model is responding" cue: the run is actively
                    // streaming text for this session, so keep the button in the
                    // interrupt state (it flips back on Completed/Error/Stop).
                    if (!agentRunning) {
                        agentRunning = true
                        refreshSendButton()
                    }
                }
            }
            is AgentEvent.Completed -> {
                // The service persists the terminal snapshot before publishing
                // Completed. Refresh even when this run belongs to a different
                // chat so the drawer's ordering and timestamps stay current.
                lifecycleScope.launch { refreshSessions() }
                if (event.sessionId == currentSessionId) {
                    // The model is done responding: the button leaves the
                    // interrupt (stop) state and returns to send/idle.
                    agentRunning = false
                    refreshSendButton()
                    chatAdapter.hideProcessing()
                    // Speak the accumulated response if TTS is enabled
                    if (accumulatedResponse.isNotBlank()) {
                        speakText(accumulatedResponse)
                        accumulatedResponse = ""
                    }
                    // Reload the persisted session so history (and the final reply)
                    // is adopted into memory — this is what keeps the next request
                    // from being dispatched with an empty context (historySize=0).
                    // The service now marks the task terminal BEFORE emitting this
                    // event, so isActive() is false here; the retry covers legacy
                    // orderings and event races.
                    suspend fun reloadSessionWithRetry() {
                        var attempts = 0
                        while (attempts < 3) {
                            attempts++
                            val row = withContext(Dispatchers.IO) {
                                runCatching { AgentDatabase.get(this@MainActivity).sessions().get(event.sessionId) }.getOrNull()
                            }
                            if (row != null) {
                                withContext(Dispatchers.Main) {
                                    if (currentSessionId == row.id) loadSession(row)
                                }
                                if (currentSessionId != event.sessionId || history.isNotEmpty()) return
                            }
                            delay(250.milliseconds)
                        }
                    }
                    lifecycleScope.launch { reloadSessionWithRetry() }
                }
            }
            is AgentEvent.Cancelled -> {
                if (event.sessionId == currentSessionId) {
                    agentRunning = false
                    accumulatedResponse = ""
                    refreshSendButton()
                    chatAdapter.hideProcessing("cancelled")
                    chatAdapter.addMessage(ChatMessage(role = "assistant", content = "Task cancelled.", model = AgentConfig.model))
                }
            }
            is AgentEvent.Error -> {
                agentRunning = false
                accumulatedResponse = ""
                refreshSendButton()
                chatAdapter.hideProcessing("error")
                AppLogger.e("Chat", "agent error in session=$currentSessionId (model=${AgentConfig.model}, provider=${AgentConfig.getRaw("active_provider_id").ifBlank { "nvidia" }}): ${event.message.take(300)}")
                if (event.message.isNotBlank()) chatAdapter.addMessage(ChatMessage(role = "assistant", content = "Error: ${event.message}", model = AgentConfig.model))
            }
        }
        scrollChatToLatest()
        refreshPipPreview()
        if (ChatNotifications.isAppBackgrounded(this) && hasSentFirstMessage) {
            ChatNotifications.postCurrentChat(this, currentSessionId, currentSessionTitle, currentChatPreview())
        }
    }

    /**
     * Ensures exactly ONE live-browser bubble exists in the conversation and
     * that it is the newest visible item: reuses the existing bubble when the
     * current run already created one, otherwise appends a new message whose
     * [MediaPreview.InAppBrowser] preview hosts the shared live WebView.
     */
    private fun showLiveBrowserBubble() {
        val last = chatAdapter.messages.lastOrNull()
        if (last?.kind == "live_browser") {
            // Already the newest item — nothing to add (its LiveBrowserOverlayView
            // follows the page state on its own via LiveBrowser listeners).
        } else {
            chatAdapter.addMessage(
                ChatMessage(
                    role = "assistant",
                    content = "",
                    kind = "live_browser",
                    linkPreviews = listOf(MediaPreview.InAppBrowser(url = ""))
                )
            )
        }
        scrollChatToLatest()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentConfig.init(this)
        deleteStaleAgentPayloadFiles()
        setContentView(R.layout.activity_main)
        if (savedInstanceState != null) {
            savedInstanceState.getString("SESSION_ID")?.let { currentSessionId = it }
            currentSessionCreatedAt = savedInstanceState.getLong("SESSION_CREATED_AT", currentSessionCreatedAt)
            currentSessionLastUpdatedAt = savedInstanceState.getLong("SESSION_LAST_UPDATED", currentSessionLastUpdatedAt)
            savedInstanceState.getString("SESSION_TITLE")?.let { currentSessionTitle = it }
            hasSentFirstMessage = savedInstanceState.getBoolean("HAS_SENT_FIRST_MSG", hasSentFirstMessage)
            savedInstanceState.getString(STATE_PENDING_ATTACHMENTS_JSON)?.let { json ->
                runCatching {
                    val type = object : TypeToken<List<ChatAttachment>>() {}.type
                    pendingChatAttachments.addAll(gson.fromJson<List<ChatAttachment>>(json, type).orEmpty())
                }
            }
        }
        intent.getStringExtra(AgentExecutionService.EXTRA_SESSION_ID)?.let { currentSessionId = it }
        ChatNotifications.setCurrentChat(this, currentSessionId, currentSessionTitle)
        initTextToSpeech()
        bindViews(); setupTopBar(); setupComposer(); setupRecycler(); setupFileDiffDrawer(); setupNavDrawer()
        startAgentEventCollector()
        // Rotation survival: repaint the conversation that was on screen before
        // the activity was recreated. Doing this right after the views are
        // wired up means the async DB load below sees a non-empty adapter and
        // leaves a healthy restored chat alone.
        restoredFromState = savedInstanceState != null && restoreOreshikState(savedInstanceState)
        renderComposerAttachmentChips()
        if (isInPictureInPictureMode) applyPipUi(true)
        if (!restoredFromState) tvGreeting.text = greetings.random()
        refreshPipPreview()
        updatePictureInPictureParams()
        updateModelButton()
        lifecycleScope.launch {
            AgentConfig.awaitReady()
            updateModelButton()
            syncWakeWordService()
            // MIUI bypass (METHOD 4): start this only after persisted settings
            // have been loaded, so a saved disabled state is respected.
            if (AgentConfig.accessibilityEnabled) {
                com.example.oreshik.agent.accessibility.AccessibilityKeepAliveService.start(this@MainActivity)
            }
            refreshModels(false)
        }
        lifecycleScope.launch {
            val latest = if (restoredFromState) {
                // Restored chat: only adopt a DB row NEWER than the restored
                // view — i.e. a reply/error that finished while the activity
                // was being recreated. Never fall back to "most recent
                // session"; that would hijack the restored conversation.
                withContext(Dispatchers.IO) {
                    runCatching { AgentDatabase.get(this@MainActivity).sessions().get(currentSessionId) }.getOrNull()
                }?.takeIf { it.updatedAt > maxOf(currentSessionLastUpdatedAt, currentSessionCreatedAt) }
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { AgentDatabase.get(this@MainActivity).sessions().get(currentSessionId) ?: AgentDatabase.get(this@MainActivity).sessions().recent(1).firstOrNull() }.getOrNull()
                }
            }
            if (latest != null && (chatAdapter.messages.isEmpty() || restoredFromState)) loadSession(latest)
            // Interrupted-run recovery: if the previous process was killed
            // mid-run (MIUI force-stop when backgrounded, low-memory kill,
            // crash), the pending marker survives — re-dispatch the prompt so
            // the user comes back to a working run instead of a dead chat.
            // Runs AFTER the session load completes to avoid the load/resume
            // race on the adapter contents.
            runCatching {
                val pendingPrompts = withContext(Dispatchers.IO) {
                    val database = AgentDatabase.get(this@MainActivity)
                    val sdb = database.settings()
                    val bySession = LinkedHashMap<String, String>()
                    sdb.all().forEach { row ->
                        if (row.key.startsWith(AgentExecutionService.SETTING_PENDING_PREFIX)) {
                            val sid = row.key.removePrefix(AgentExecutionService.SETTING_PENDING_PREFIX)
                            if (sid.isNotBlank() && row.value.isNotBlank()) bySession[sid] = row.value
                        }
                    }
                    // Migrate the single-slot marker written by earlier versions.
                    val oldSid = sdb.get(AgentExecutionService.SETTING_PENDING_SESSION)?.value
                    val oldPrompt = sdb.get(AgentExecutionService.SETTING_PENDING_TEXT)?.value
                    if (!oldSid.isNullOrBlank() && !oldPrompt.isNullOrBlank()) {
                        if (!bySession.containsKey(oldSid)) {
                            bySession[oldSid] = oldPrompt
                            sdb.put(SettingEntity(AgentExecutionService.SETTING_PENDING_PREFIX + oldSid, oldPrompt))
                        }
                        sdb.remove(AgentExecutionService.SETTING_PENDING_SESSION)
                        sdb.remove(AgentExecutionService.SETTING_PENDING_TEXT)
                    }
                    bySession.entries.mapNotNull { (sid, prompt) ->
                        database.sessions().get(sid)?.let { it to prompt }
                            ?: run {
                                sdb.remove(AgentExecutionService.SETTING_PENDING_PREFIX + sid)
                                null
                            }
                    }.sortedByDescending { (session, _) -> session.updatedAt }
                }
                // Keep the most recently active interrupted chat visible while
                // resuming every pending run independently in the service.
                pendingPrompts.firstOrNull()?.first?.let { session ->
                    if (session.id != currentSessionId) loadSession(session)
                }
                pendingPrompts.forEach { (session, prompt) ->
                    // The decision is based on the persisted row, never on a
                    // possibly stale Activity snapshot.
                    resumeInterruptedRun(session, prompt)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("SESSION_ID", currentSessionId)
        outState.putLong("SESSION_CREATED_AT", currentSessionCreatedAt)
        outState.putLong("SESSION_LAST_UPDATED", currentSessionLastUpdatedAt)
        outState.putString("SESSION_TITLE", currentSessionTitle)
        outState.putBoolean("HAS_SENT_FIRST_MSG", hasSentFirstMessage)
        // Rotation survival: snapshot the conversation content itself. Without
        // this, recreation landed on the layout defaults (greeting visible,
        // message list hidden) and the user saw what looked like a brand-new
        // session. Media previews are stripped — they can embed huge base64
        // payloads and are not worth the Binder risk (a live run keeps
        // streaming regardless; the service is activity-independent).
        runCatching {
            val messagesJson = gson.toJson(
                chatAdapter.messages.filter { it.kind == "text" || it.kind == "work" }.map { it.copy(linkPreviews = emptyList()) }
            )
            val historyJson = gson.toJson(history.toList())
            // Oversized snapshots would blow the Binder transaction limit
            // (the whole outState bundle rides in ONE transaction on stop);
            // skip them — such long conversations always have a DB row to
            // reload instead.
            //
            // Budget is SHARED between the two snapshots (they are written
            // into the same Bundle/transaction). Messages win: they restore
            // the visible chat, while history alone is useless without them.
            // If even messages exceed the cap, drop everything and let the
            // DB fallback reload the session on recreation.
            val messagesFits = messagesJson.length <= MAX_STATE_JSON_CHARS
            if (messagesFits && messagesJson.length + historyJson.length <= MAX_STATE_JSON_CHARS) {
                outState.putString(STATE_MESSAGES_JSON, messagesJson)
                outState.putString(STATE_HISTORY_JSON, historyJson)
            } else if (messagesFits) {
                outState.putString(STATE_MESSAGES_JSON, messagesJson)
                AppLogger.i("Chat", "state: history snapshot (${historyJson.length} chars) dropped to stay under the $MAX_STATE_JSON_CHARS-char bundle budget")
            } else {
                AppLogger.i("Chat", "state: snapshot (${messagesJson.length}+${historyJson.length} chars) exceeds the $MAX_STATE_JSON_CHARS-char bundle budget; skipping — session will be reloaded from the DB")
            }
            outState.putBoolean(STATE_AGENT_RUNNING, agentRunning)
            outState.putString(STATE_COMPOSER_TEXT, etMessage.text?.toString().orEmpty())
            outState.putString(STATE_PENDING_ATTACHMENTS_JSON, gson.toJson(pendingChatAttachments))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sessionId = intent.getStringExtra(AgentExecutionService.EXTRA_SESSION_ID) ?: return
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) {
                runCatching { AgentDatabase.get(this@MainActivity).sessions().get(sessionId) }.getOrNull()
            }
            if (session != null) loadSession(session)
        }
    }

    override fun onResume() {
        super.onResume()
        suppressPipOnNextLeave = false
        if (!isInPictureInPictureMode) ChatNotifications.setAppBackgrounded(this, false)
        updatePictureInPictureParams()
        AppLogger.i("DeviceAccessAudit", "onResume: root gate=${AgentConfig.allowRoot}, ready=${AgentConfig.isReady}, piP=$isInPictureInPictureMode")
        if (deviceAccessAwaitingSystemUi) {
            deviceAccessAwaitingSystemUi = false
            deviceAccessAuditDoneForResume = false
        }
        if (!deviceAccessAwaitingRuntimePermissions) scheduleDeviceAccessAudit()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (suppressPipOnNextLeave) {
            suppressPipOnNextLeave = false
            return
        }
        if (!deviceAccessAwaitingSystemUi && !deviceAccessAwaitingRuntimePermissions && !deviceAccessRootRequestInFlight) {
            deviceAccessPromptedThisForeground.clear()
        }
        ChatNotifications.setAppBackgrounded(this, true)
        if (!hasSentFirstMessage || isFinishing) return
        refreshPipPreview()
        ChatNotifications.postCurrentChat(this, currentSessionId, currentSessionTitle, currentChatPreview())
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S && !drawerLayout.isDrawerOpen(navView)) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        applyPipUi(isInPictureInPictureMode)
        ChatNotifications.setAppBackgrounded(this, isInPictureInPictureMode)
        if (isInPictureInPictureMode) refreshPipPreview()
        updatePictureInPictureParams()
    }

    private fun updatePictureInPictureParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !::drawerLayout.isInitialized) return
        val canEnter = hasSentFirstMessage && !isFinishing && !suppressPipOnNextLeave && !drawerLayout.isDrawerOpen(navView)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(canEnter)
        runCatching { setPictureInPictureParams(builder.build()) }
    }

    private fun applyPipUi(inPip: Boolean) {
        if (!::pipChatOverlay.isInitialized) return
        if (inPip && drawerLayout.isDrawerOpen(navView)) drawerLayout.closeDrawers()
        findViewById<View>(R.id.topBarsContainer).isVisible = !inPip
        findViewById<View>(R.id.composerCard).isVisible = !inPip
        navView.isVisible = !inPip
        emptyState.isVisible = !inPip && !hasSentFirstMessage
        messagesRecycler.isVisible = !inPip && hasSentFirstMessage
        pipMessagesRecycler.isVisible = inPip && hasSentFirstMessage
        pipChatOverlay.isVisible = inPip
        if (inPip && hasSentFirstMessage) scrollChatToLatest(smooth = false)
        if (!inPip && hasSentFirstMessage) messagesRecycler.scrollToPosition((chatAdapter.itemCount - 1).coerceAtLeast(0))
        if (!inPip) updatePictureInPictureParams()
    }

    private fun refreshPipPreview() {
        if (!::pipChatOverlay.isInitialized) return
        pipChatTitle.text = currentSessionTitle.ifBlank { "Current chat" }
        pipChatStatus.text = if (agentRunning) "Working · Thinking…" else "Tap to return to chat"
        updatePictureInPictureParams()
    }

    /** Keep whichever conversation surface is visible pinned to the newest live message. */
    private fun scrollChatToLatest(smooth: Boolean = true) {
        val position = (chatAdapter.itemCount - 1).coerceAtLeast(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode &&
            ::pipMessagesRecycler.isInitialized && pipMessagesRecycler.isVisible
        ) {
            if (smooth) pipMessagesRecycler.smoothScrollToPosition(position)
            else pipMessagesRecycler.scrollToPosition(position)
        } else if (smooth) {
            messagesRecycler.smoothScrollToPosition(position)
        } else {
            messagesRecycler.scrollToPosition(position)
        }
    }

    private fun currentChatPreview(): String = chatAdapter.messages
        .filter { it.kind == "text" && it.content.isNotBlank() }
        .takeLast(4)
        .joinToString("\n\n") { message ->
            val speaker = if (message.role == "user") "You" else "Assistant"
            val body = message.content.replace(Regex("\\s+"), " ").trim().take(420)
            "$speaker: $body"
        }
        .takeLast(1500)

    private fun launchActivityOutsideChat(intent: Intent) {
        suppressPipOnNextLeave = true
        updatePictureInPictureParams()
        startActivity(intent)
    }

    /**
     * Rebuilds the on-screen conversation from the snapshot written by
     * [onSaveInstanceState]. Returns true when the chat content was restored;
     * false means "nothing usable" and lets the normal cold-start DB load
     * path (get current session, else most recent) take over.
     */
    private fun restoreOreshikState(state: Bundle): Boolean {
        val messagesJson: String = state.getString(STATE_MESSAGES_JSON) ?: return false
        // A blank snapshot carries no restore value; run the DB fallback below.
        if (messagesJson.isEmpty()) {
            return false
        }
        return runCatching {
            val messageType = object : TypeToken<List<ChatMessage>>() {}.type
            val historyType = object : TypeToken<List<JsonObject>>() {}.type
            val restoredMessages: List<ChatMessage> = gson.fromJson(messagesJson, messageType) ?: emptyList()
            val restoredHistory: List<JsonObject> = state.getString(STATE_HISTORY_JSON)?.let {
                gson.fromJson(it, historyType)
            } ?: emptyList()
            history.clear()
            history.addAll(restoredHistory.map { it.deepCopy() })
            chatAdapter.replaceAll(restoredMessages)
            hasSentFirstMessage = chatAdapter.messages.any { it.role == "user" }
            if (hasSentFirstMessage) {
                emptyState.isVisible = false
                messagesRecycler.isVisible = true
                tvGreeting.text = currentSessionTitle
                scrollChatToLatest(smooth = false)
            } else {
                emptyState.isVisible = true
                messagesRecycler.isVisible = false
                tvGreeting.text = greetings.random()
            }
            // A run may have been streaming when the device rotated; the
            // service survives, so re-attach the live "Thinking…" bubble.
            // The STARTED event collector re-attaches in onStart.
            agentRunning = state.getBoolean(STATE_AGENT_RUNNING, false)
            if (agentRunning) {
                chatAdapter.showProcessing("Thinking…")
                scrollChatToLatest()
            }
            // Composer text: make the restore explicit (the framework's own
            // view-state restore lands later, in onRestoreInstanceState) and
            // position the caret at the end.
            val composer = state.getString(STATE_COMPOSER_TEXT).orEmpty()
            if (composer.isNotEmpty() && etMessage.text?.toString() != composer) {
                etMessage.setText(composer)
                etMessage.setSelection(composer.length)
            }
            // Repaint the composer-driven icon (restored text may flip it to
            // the send icon; a restored running state keeps the stop icon).
            refreshSendButton()
            true
        }.onFailure {
            AppLogger.e("Chat", "restore: failed to rebuild chat state after recreation: ${it.message}")
            chatAdapter.messages.clear()
            history.clear()
        }.getOrDefault(false)
    }

    /**
     * Marks a prompt as dispatched-but-maybe-unfinished before the run starts.
     * If the process is killed mid-run (e.g. MIUI force-stops the app when it
     * is backgrounded), the marker survives in the settings DB and the next
     * app open auto-resumes the interrupted run instead of silently dropping
     * it. The service retires the marker at the matching run's terminal state.
     */
    private suspend fun markPendingPrompt(sessionId: String, prompt: String) {
        // Recovery is only meaningful when the session row itself is persisted;
        // without it there is nothing to verify the run against on reopen and
        // every open would look like an interrupted run.
        if (!AgentConfig.saveHistory) return
        withContext(Dispatchers.IO) {
            runCatching {
                val db = AgentDatabase.get(this@MainActivity).settings()
                db.put(SettingEntity(AgentExecutionService.SETTING_PENDING_PREFIX + sessionId, prompt))
            }
        }
    }

    private suspend fun clearPendingPrompt(sessionId: String, prompt: String? = null) {
        withContext(Dispatchers.IO) {
            runCatching {
                val db = AgentDatabase.get(this@MainActivity).settings()
                val scopedKey = AgentExecutionService.SETTING_PENDING_PREFIX + sessionId
                if (db.get(scopedKey)?.value?.let { prompt == null || it == prompt } == true) {
                    db.remove(scopedKey)
                }
                // Also clear a matching marker from a pre-migration version.
                if (db.get("pending_prompt_session")?.value == sessionId &&
                    (prompt == null || db.get("pending_prompt_text")?.value == prompt)
                ) {
                    db.remove("pending_prompt_session")
                    db.remove("pending_prompt_text")
                }
            }
        }
    }

    /**
     * Re-dispatches a prompt whose run was killed mid-flight (process
     * force-stop while the app was backgrounded). The decision is made from
     * the PERSISTED session row, not the on-screen adapter: at this point the
     * adapter may still be empty (cold start — loadSession applies the DB row
     * asynchronously) or hold the pre-reply snapshot (restored instance
     * state). Deciding "was it answered?" from the adapter raced that load and
     * re-sent prompts the model had already answered whenever the Completed
     * event was dropped while the app was backgrounded.
     */
    private suspend fun resumeInterruptedRun(session: SessionEntity, prompt: String) {
        AgentConfig.awaitReady()
        if (!AgentConfig.saveHistory) return
        val sessionId = session.id
        // The Activity flag describes only the visible chat. Other sessions
        // may be running concurrently and must not block recovery here.
        if (AgentTaskManager.isActive(sessionId)) return
        val messageType = object : TypeToken<List<ChatMessage>>() {}.type
        val dbMessages: List<ChatMessage> = runCatching {
            gson.fromJson<List<ChatMessage>>(session.messagesJson, messageType).orEmpty()
        }.getOrDefault(emptyList())
        val promptIndex = dbMessages.indexOfLast { it.role == "user" && it.content == prompt }
        if (promptIndex < 0) {
            // The prompt is not part of this session row — stale marker;
            // retire it instead of injecting a message into the wrong chat.
            clearPendingPrompt(sessionId, prompt)
            return
        }
        val answeredInDb = dbMessages.drop(promptIndex + 1)
            .any { it.role == "assistant" && it.kind == "text" && it.content.isNotBlank() }
        // The run may also have finished after the row was written but before
        // the process died — trust the live adapter view as a secondary signal.
        val answeredInMemory = (sessionId == currentSessionId) &&
            (chatAdapter.messages.lastOrNull()
                ?.let { it.role == "assistant" && it.kind == "text" && it.content.isNotBlank() } == true)
        if (answeredInDb || answeredInMemory) {
            // The run actually finished (a reply or an error is already
            // persisted) — never re-send an already-answered prompt; just
            // retire the marker.
            clearPendingPrompt(sessionId, prompt)
            return
        }
        AppLogger.i("Chat", "resume: re-dispatching interrupted run for session=$sessionId prompt=${prompt.take(80)}")
        val viewingSession = sessionId == currentSessionId
        if (viewingSession) {
            chatAdapter.showProcessing("Thinking…")
            scrollChatToLatest()
            agentRunning = true
            refreshSendButton()
        }
        // The DB row was persisted moments ago and holds the full payload —
        // hand the run over through the DB (viaDb). The service reloads
        // history + messages from the row and drops the row's trailing copy
        // of the prompt itself; only the small routing fields ride in the
        // intent.
        markPendingPrompt(sessionId, prompt)
        startAgentRun(
            buildRunIntent(
                sessionId = sessionId,
                title = session.title,
                createdAt = session.createdAt,
                userText = prompt,
                historyJson = "",
                messagesJson = "",
                model = session.model.removePrefix("offline:").ifBlank { AgentConfig.activeModelName },
                viaDb = true,
                modelMode = if (session.model.startsWith("offline:")) "offline" else "online",
                offlineModelPath = if (session.model.startsWith("offline:")) {
                    OfflineModelStore.models(this).firstOrNull { it.name.equals(session.model.removePrefix("offline:"), ignoreCase = true) }?.absolutePath.orEmpty()
                } else "",
            ),
            sessionId,
        )
    }

    /**
     * Builds the ACTION_RUN intent for [AgentExecutionService]. Payloads
     * larger than [MAX_INTENT_PAYLOAD_CHARS] travel through the session DB
     * row or an app-cache file instead of intent extras (see the constant's
     * KDoc for why).
     */
    private fun buildRunIntent(
        sessionId: String,
        title: String,
        createdAt: Long,
        userText: String,
        historyJson: String,
        messagesJson: String,
        model: String,
        viaDb: Boolean,
        payloadFileName: String? = null,
        modelMode: String = AgentConfig.modelMode,
        offlineModelPath: String = AgentConfig.offlineModelPath,
    ): Intent = Intent(this, AgentExecutionService::class.java).apply {
        action = AgentExecutionService.ACTION_RUN
        putExtra(AgentExecutionService.EXTRA_SESSION_ID, sessionId)
        putExtra(AgentExecutionService.EXTRA_SESSION_TITLE, title)
        putExtra(AgentExecutionService.EXTRA_SESSION_CREATED, createdAt)
        putExtra(AgentExecutionService.EXTRA_MODEL, model)
        putExtra(AgentExecutionService.EXTRA_MODEL_MODE, modelMode)
        putExtra(AgentExecutionService.EXTRA_OFFLINE_MODEL_PATH, offlineModelPath)
        putExtra(AgentExecutionService.EXTRA_REASONING, AgentConfig.reasoningEffort)
        if (viaDb) {
            putExtra(AgentExecutionService.EXTRA_FROM_DB, true)
        } else if (payloadFileName != null) {
            putExtra(AgentExecutionService.EXTRA_PAYLOAD_FILE, payloadFileName)
            putExtra(AgentExecutionService.EXTRA_BASE_URL, AgentConfig.baseUrl)
            putExtra(AgentExecutionService.EXTRA_API_KEY, AgentConfig.apiKey)
        } else {
            putExtra(AgentExecutionService.EXTRA_USER_TEXT, userText)
            putExtra(AgentExecutionService.EXTRA_HISTORY_JSON, historyJson)
            putExtra(AgentExecutionService.EXTRA_MESSAGES_JSON, messagesJson)
            putExtra(AgentExecutionService.EXTRA_BASE_URL, AgentConfig.baseUrl)
            putExtra(AgentExecutionService.EXTRA_API_KEY, AgentConfig.apiKey)
        }
    }

    private suspend fun writeAgentPayloadFile(userText: String, historyJson: String, messagesJson: String): String? =
        withContext(Dispatchers.IO) {
            val file = File(cacheDir, "agent_payload_${UUID.randomUUID()}.json")
            runCatching {
                file.writeText(gson.toJson(AgentRunPayload(userText, historyJson, messagesJson)))
                file.name
            }.onFailure { file.delete() }.getOrNull()
        }

    private fun deleteStaleAgentPayloadFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            cacheDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("agent_payload_") && it.name.endsWith(".json") && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    /** Starts the agent run; shared failure handling for every dispatch path. */
    private fun startAgentRun(intent: Intent, sessionId: String) {
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (t: Throwable) {
            intent.getStringExtra(AgentExecutionService.EXTRA_PAYLOAD_FILE)?.let { fileName ->
                if (File(cacheDir, fileName).name == fileName && fileName.startsWith("agent_payload_")) {
                    File(cacheDir, fileName).delete()
                }
            }
            agentRunning = false
            refreshSendButton()
            chatAdapter.hideProcessing()
            AppLogger.e("Chat", "failed to start AgentExecutionService for session=$sessionId (model=${AgentConfig.model}): ${t.javaClass.simpleName}: ${t.message}", t)
            Toast.makeText(this, "Unable to start background agent: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun bindViews() {
        emptyState = findViewById(R.id.emptyState); messagesRecycler = findViewById(R.id.messagesRecycler); tvGreeting = findViewById(R.id.tvGreeting)
        etMessage = findViewById(R.id.etMessage); composerAttachmentChips = findViewById(R.id.composerAttachmentChips); btnSend = findViewById(R.id.btnSend); btnMenu = findViewById(R.id.btnMenu); btnGhost = findViewById(R.id.btnGhost)
        btnModelSelector = findViewById(R.id.btnModelSelector); btnMic = findViewById(R.id.btnMic); navView = findViewById(R.id.navView); drawerLayout = findViewById(R.id.drawerLayout)
        fileDiffDrawer = findViewById(R.id.fileDiffDrawer)
        pipChatOverlay = findViewById(R.id.pipChatOverlay); pipChatTitle = findViewById(R.id.pipChatTitle)
        pipChatStatus = findViewById(R.id.pipChatStatus); pipMessagesRecycler = findViewById(R.id.pipMessagesRecycler)
        topBar = findViewById(R.id.topBar)
        selectionBar = findViewById(R.id.selectionBar)
    }

    /** Starts or stops the always-on wake-word assistant according to the setting. */
    private fun syncWakeWordService() {
        if (AgentConfig.wakeWordEnabled) {
            val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                runCatching { ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)) }
            }
        } else {
            runCatching { startService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP)) }
        }
    }

    private fun setupTopBar() {
        val arrow = androidx.appcompat.graphics.drawable.DrawerArrowDrawable(this)
        arrow.color = resources.getColor(R.color.text_primary, theme); btnMenu.setImageDrawable(arrow)
        btnMenu.setOnClickListener { if (drawerLayout.isDrawerOpen(navView)) drawerLayout.closeDrawer(navView) else drawerLayout.openDrawer(navView) }
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener { override fun onDrawerSlide(v: View, o: Float) { arrow.progress = o }; override fun onDrawerOpened(v: View) {}; override fun onDrawerClosed(v: View) {}; override fun onDrawerStateChanged(s: Int) {} })
        btnGhost.setOnClickListener { startNewChat() }

        // Handle system window insets (status bar, navigation bar) - applies to both top bars
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(
                view.paddingStart,
                statusBarInsets.top,
                view.paddingEnd,
                view.paddingBottom
            )
            return@setOnApplyWindowInsetsListener insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(selectionBar) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(
                view.paddingStart,
                statusBarInsets.top,
                view.paddingEnd,
                view.paddingBottom
            )
            return@setOnApplyWindowInsetsListener insets
        }
    }

    /**
     * Switches to a fresh conversation. [followUp=false] skips persisting the
     * outgoing session — used when deleting the active chat, otherwise the
     * just-deleted conversation would be re-saved (persist-first) with a new id.
     */
    private fun startNewChat(followUp: Boolean = true) {
        if (dispatchPending) {
            Toast.makeText(this, "Wait for the current message to be sent.", Toast.LENGTH_SHORT).show()
            return
        }
        if (followUp) persistCurrentSession(preserveConcurrentUpdate = true)
        currentSessionId = UUID.randomUUID().toString()
        currentSessionCreatedAt = System.currentTimeMillis()
        currentSessionLastUpdatedAt = 0L
        currentSessionTitle = "New chat"
        ChatNotifications.setCurrentChat(this, currentSessionId, currentSessionTitle)
        hasSentFirstMessage = false
        agentRunning = false
        accumulatedResponse = ""
        history.clear()
        chatAdapter.replaceAll(emptyList())
        chatAdapter.hideProcessing()
        emptyState.isVisible = true
        messagesRecycler.isVisible = false
        tvGreeting.text = greetings.random()
        etMessage.text?.clear()
        pendingChatAttachments.clear()
        renderComposerAttachmentChips()
        // Fresh session: nothing is running/speaking → the button falls back
        // to the idle waveform / send icon.
        updateSendButtonIcon(etMessage.text)
        refreshPipPreview()
    }

    private fun setupRecycler() {
        messagesRecycler.adapter = chatAdapter
        messagesRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        // PiP uses the same adapter as the full chat. Every text chunk, thinking
        // update, and tool activity notification therefore repaints both surfaces
        // through the normal RecyclerView adapter updates.
        pipMessagesRecycler.adapter = chatAdapter
        pipMessagesRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }

        val topBar = findViewById<LinearLayout>(R.id.topBar)
        val selectionBar = findViewById<LinearLayout>(R.id.selectionBar)
        val topBarsContainer = findViewById<View>(R.id.topBarsContainer)
        val tvSelectionTitle = findViewById<TextView>(R.id.tvSelectionTitle)
        val btnCloseSelection = findViewById<ImageButton>(R.id.btnCloseSelection)
        val btnSelectAll = findViewById<ImageButton>(R.id.btnSelectAll)
        val btnOpenSelection = findViewById<ImageButton>(R.id.btnOpenSelection)
        val btnCopySelection = findViewById<ImageButton>(R.id.btnCopySelection)
        val btnEditSelection = findViewById<ImageButton>(R.id.btnEditSelection)
        val btnDeleteSelection = findViewById<ImageButton>(R.id.btnDeleteSelection)

        chatAdapter.onSelectionModeChanged = { active ->
            topBar.isVisible = !active
            selectionBar.isVisible = active
            // Lift the active toolbar (with its background) above the messages
            // list so it casts a soft shadow over the scrolling content.
            topBarsContainer.translationZ = if (active) 12f else 0f
        }

        chatAdapter.onSelectionCountChanged = { count ->
            tvSelectionTitle.text = resources.getQuantityString(R.plurals.selected_count, count, count)
            val singleUserMessage = selectedUserMessage()
            btnOpenSelection.isVisible = singleUserMessage != null
            btnCopySelection.isVisible = singleUserMessage != null
            btnEditSelection.isVisible = singleUserMessage != null
            btnSelectAll.isVisible = singleUserMessage == null
        }

        chatAdapter.onEditUserMessage = { content ->
            etMessage.setText(content)
            etMessage.setSelection(content.length)
            etMessage.requestFocus()
        }

        btnCloseSelection.setOnClickListener {
            chatAdapter.exitSelectionMode()
        }

        btnSelectAll.setOnClickListener {
            chatAdapter.selectAll()
        }

        btnOpenSelection.setOnClickListener {
            val message = selectedUserMessage() ?: return@setOnClickListener
            chatAdapter.exitSelectionMode()
            showSelectedUserMessage(message.content)
        }

        btnCopySelection.setOnClickListener {
            val message = selectedUserMessage() ?: return@setOnClickListener
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Chat message", message.content))
            chatAdapter.exitSelectionMode()
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        btnEditSelection.setOnClickListener {
            val message = selectedUserMessage() ?: return@setOnClickListener
            chatAdapter.exitSelectionMode()
            chatAdapter.onEditUserMessage?.invoke(message.content)
        }

        btnDeleteSelection.setOnClickListener {
            val removed = chatAdapter.deleteSelected()
            if (removed.isNotEmpty()) {
                persistCurrentSession()
                Toast.makeText(this, "Deleted ${removed.size} message(s)", Toast.LENGTH_SHORT).show()
                if (chatAdapter.messages.none { it.role == "user" }) {
                    hasSentFirstMessage = false
                    emptyState.isVisible = true
                    messagesRecycler.isVisible = false
                    ChatNotifications.cancelSessionNotifications(this, currentSessionId)
                }
                refreshPipPreview()
            }
        }
    }

    private fun setupFileDiffDrawer() {
        val density = resources.displayMetrics.density
        val desiredWidth = minOf((resources.displayMetrics.widthPixels * 0.94f).toInt(), (520 * density).toInt())
        fileDiffDrawer.layoutParams = fileDiffDrawer.layoutParams.apply { width = desiredWidth }

        val diffAdapter = FileDiffAdapter()
        findViewById<RecyclerView>(R.id.rvFileDiff).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = diffAdapter
        }
        val pathView = findViewById<TextView>(R.id.tvDiffFilePath)
        val fileCountsView = findViewById<TextView>(R.id.tvDiffFileCounts)
        val turnAdditionsView = findViewById<TextView>(R.id.tvDiffTurnAdditions)
        val turnDeletionsView = findViewById<TextView>(R.id.tvDiffTurnDeletions)
        val emptyView = findViewById<TextView>(R.id.tvDiffEmpty)

        findViewById<ImageButton>(R.id.btnCloseFileDiff).setOnClickListener {
            drawerLayout.closeDrawer(fileDiffDrawer)
        }
        chatAdapter.onFileChangeClick = { file, turnFiles ->
            if (!isInPictureInPictureMode) {
                pathView.text = file.path
                val typeLabel = when (file.changeType) {
                    "added" -> "Added"
                    "deleted" -> "Deleted"
                    else -> "Modified"
                }
                val fileLineCounts = file.lineCounts()
                fileCountsView.text = fileLineCounts?.let { (additions, deletions) ->
                    "+$additions   -$deletions   ·   $typeLabel"
                } ?: "$typeLabel · line counts unavailable"
                val turnLineCounts = turnFiles.map { it.lineCounts() }
                val turnHasCounts = turnLineCounts.any { it != null }
                val turnAdditions = turnLineCounts.sumOf { it?.first?.toLong() ?: 0L }
                val turnDeletions = turnLineCounts.sumOf { it?.second?.toLong() ?: 0L }
                turnAdditionsView.text = if (turnHasCounts) "+$turnAdditions" else ""
                turnDeletionsView.text = if (turnHasCounts) "-$turnDeletions" else ""
                diffAdapter.submitList(file.diff.orEmpty())
                emptyView.text = file.diffUnavailableReason ?: "No text diff is available for this file."
                emptyView.isVisible = file.diff.isNullOrEmpty()
                if (!drawerLayout.isDrawerOpen(fileDiffDrawer)) drawerLayout.openDrawer(fileDiffDrawer)
            }
        }
    }

    private fun selectedUserMessage(): ChatMessage? {
        if (chatAdapter.selectedIndices.size != 1) return null
        val index = chatAdapter.selectedIndices.first()
        return chatAdapter.messages.getOrNull(index)?.takeIf { it.role == "user" && it.kind == "text" }
    }

    private fun showSelectedUserMessage(content: String) {
        val density = resources.displayMetrics.density
        val messageView = TextView(this).apply {
            text = content
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setTextIsSelectable(true)
            setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), (16 * density).toInt())
        }
        val scrollView = android.widget.ScrollView(this).apply {
            addView(messageView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * density).toInt())
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.open_message)
            .setView(scrollView)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun setupComposer() {
        supportFragmentManager.setFragmentResultListener(AttachmentBottomSheet.RESULT_KEY, this) { _, result ->
            val rawUris = result.getStringArrayList(AttachmentBottomSheet.RESULT_URIS)
                ?: listOfNotNull(result.getString(AttachmentBottomSheet.RESULT_URI))
            val uris = rawUris.mapNotNull { runCatching { it.toUri() }.getOrNull() }.distinctBy(Uri::toString)
            if (uris.isNotEmpty()) importChatAttachments(uris)
        }
        findViewById<ImageButton>(R.id.btnPlus).setOnClickListener { AttachmentBottomSheet().show(supportFragmentManager, AttachmentBottomSheet.TAG) }
        btnModelSelector.setOnClickListener { showModelPicker() }
        btnMic.setOnClickListener { startVoiceListening() }
        etMessage.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun afterTextChanged(s: Editable?) { refreshSendButton() } })
        btnSend.setOnClickListener {
            val text = etMessage.text?.toString()?.trim().orEmpty()
            when {
                // Typed text always wins: this is a send (or a steering redirect
                // while the model is responding). The composer is cleared by the
                // send path itself, never here.
                text.isNotBlank() || pendingChatAttachments.isNotEmpty() -> sendToAgent(text.ifBlank { attachmentOnlyPrompt() })
                // Empty composer while the model is working / speaking: the button
                // is the visible Interrupt control for the current session.
                agentRunning || isSpeaking -> stopCurrentTask()
            }
        }
    }

    private fun importChatAttachments(uris: List<Uri>) {
        lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val name = attachmentDisplayName(uri)
                    val mime = contentResolver.getType(uri).orEmpty().ifBlank {
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                            ?: "application/octet-stream"
                    }
                    val size = runCatching {
                        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
                        }
                    }.getOrNull() ?: 0L
                    val path = copyAttachmentToWorkspace(uri, name)
                    path.fold(
                        onSuccess = { relativePath -> ChatAttachment(relativePath, name, mime, size) },
                        onFailure = { failure ->
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Could not attach $name: ${failure.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                            }
                            null
                        },
                    )
                }
            }
            if (copied.isNotEmpty()) {
                pendingChatAttachments += copied
                renderComposerAttachmentChips()
                val current = etMessage.text?.toString().orEmpty()
                if (current.isBlank()) etMessage.requestFocus()
                refreshSendButton()
                val count = copied.size
                Toast.makeText(this@MainActivity, "$count attachment${if (count == 1) "" else "s"} ready to send", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderComposerAttachmentChips() {
        if (!::composerAttachmentChips.isInitialized) return
        composerAttachmentChips.removeAllViews()
        composerAttachmentChips.isVisible = pendingChatAttachments.isNotEmpty()
        pendingChatAttachments.forEach { attachment ->
            val chip = Chip(this).apply {
                text = attachment.displayName
                isCloseIconVisible = true
                closeIconContentDescription = "Remove ${attachment.displayName}"
                setOnCloseIconClickListener {
                    pendingChatAttachments.remove(attachment)
                    val workspace = AgentConfig.workspaceRoot(this@MainActivity)
                    AgentConfig.chatAttachmentFile(this@MainActivity, attachment.relativePath)?.delete()
                    renderComposerAttachmentChips()
                    refreshSendButton()
                }
            }
            composerAttachmentChips.addView(chip)
        }
    }

    private fun attachmentOnlyPrompt(): String = when {
        pendingChatAttachments.any { it.mimeType.startsWith("image/", true) } -> "Please inspect the attached image."
        pendingChatAttachments.any { it.mimeType.startsWith("audio/", true) } -> "Please listen to and analyze the attached audio."
        else -> "Please analyze the attached file."
    }

    private fun attachmentDisplayName(uri: Uri): String {
        val fromProvider = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "attachment"
    }

    private fun copyAttachmentToWorkspace(uri: Uri, displayName: String): Result<String> = runCatching {
        val attachmentDirectory = AgentConfig.chatAttachmentsRoot(applicationContext).apply {
            check(isDirectory || mkdirs()) { "Unable to create the private chat attachments folder" }
        }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).trim('_').ifBlank { "attachment" }
        val target = File(attachmentDirectory, "${UUID.randomUUID()}_$safeName")
        try {
            val input = contentResolver.openInputStream(uri) ?: error("The selected item is no longer available")
            input.use { source ->
                target.outputStream().use { destination ->
                    val buffer = ByteArray(8192)
                    var totalBytes = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        require(totalBytes <= MAX_ATTACHMENT_COPY_BYTES) { "Attachments must be 50 MB or smaller" }
                        destination.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        target.name
    }

    /**
     * Derives the send button's icon + action from ONE place so the visual and
     * the behavior can never disagree:
     *
     *  - text typed in the composer            → SEND icon; tap sends/steers
     *  - agent running (thinking/streaming)    → STOP icon; tap interrupts
     *  - TTS speaking the reply                → STOP icon; tap silences
     *  - otherwise (new chat / session stopped) → waveform "voice" idle icon
     *
     * The icon is deliberately independent of the running state while the
     * composer holds text: steering a running chat (typing a new instruction)
     * must still show a send affordance, because tapping WILL send. Whenever
     * the run ends (Completed/Error/Stop) or a new chat starts, the state
     * flags reset and this falls back to the send / idle icons.
     */
    private fun refreshSendButton() {
        val hasText = !etMessage.text.isNullOrBlank() || pendingChatAttachments.isNotEmpty()
        btnSend.setImageResource(
            when {
                hasText -> R.drawable.ic_send
                agentRunning || isSpeaking -> R.drawable.ic_stop
                else -> R.drawable.ic_waveform
            }
        )
        btnSend.contentDescription = getString(
            when {
                hasText -> R.string.send
                agentRunning -> R.string.stop_response
                isSpeaking -> R.string.stop_speaking
                else -> R.string.voice_input
            }
        )
    }

    /** Legacy entry point kept for state-only callers (snapshot restore etc.). */
    private fun updateSendButtonIcon(@Suppress("UNUSED_PARAMETER") s: Editable?) = refreshSendButton()

    /**
     * Visible interrupt control: cancels the active operation for the current
     * session without touching unrelated sessions. TTS speech is stopped too.
     */
    private fun stopCurrentTask(showToast: Boolean = true) {
        val nativeInferenceFinishing = OfflineModelRuntime.isInferenceActive()
        stopSpeaking()
        runCatching {
            startService(Intent(this, AgentExecutionService::class.java).apply {
                action = AgentExecutionService.ACTION_STOP
                putExtra(AgentExecutionService.EXTRA_SESSION_ID, currentSessionId)
            })
        }
        agentRunning = false
        accumulatedResponse = ""
        refreshSendButton()
        chatAdapter.hideProcessing()
        if (showToast) {
            val message = if (nativeInferenceFinishing) {
                "Stop requested. The local engine will finish its current native generation, then stop."
            } else {
                "Stopped."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendToAgent(userText: String, attachments: List<ChatAttachment> = pendingChatAttachments.toList()) {
        val attachmentsToSend = attachments.toList()
        if (dispatchPending) {
            Toast.makeText(this, "A message is already being sent.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!AgentConfig.isReady) {
            dispatchPending = true
            lifecycleScope.launch {
                try {
                    AgentConfig.awaitReady()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Toast.makeText(this@MainActivity, "Unable to load saved settings: ${failure.message}", Toast.LENGTH_LONG).show()
                    return@launch
                } finally {
                    dispatchPending = false
                }
                if (!isFinishing && !isDestroyed) sendToAgent(userText, attachmentsToSend)
            }
            return
        }
        val selectedOfflineFile = if (AgentConfig.isOfflineMode) OfflineModelStore.selectedFile(this) else null
        if ((AgentConfig.isOfflineMode && (selectedOfflineFile == null || !DeviceModelAdvisor.detect(this).supportsRuntime)) ||
            (!AgentConfig.isOfflineMode && AgentConfig.model.isBlank())
        ) {
            showModelPicker()
            Toast.makeText(
                this,
                if (AgentConfig.isOfflineMode) "Choose and load a GGUF model first." else "Select an online provider model first.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val dispatchSessionId = currentSessionId
        dispatchPending = true
        // Last line of defense against the "chat forgets the previous
        // conversation" bug: if the service persisted a fresher row for this
        // session than what this Activity holds in memory (the terminal event
        // was missed while the app was backgrounded), adopt the DB content
        // BEFORE appending the new user message. Otherwise persistCurrentSession()
        // below would overwrite the fresher row with stale history and the
        // previous exchange would vanish from the model's context.
        lifecycleScope.launch {
            try {
                val runModel = resolveAttachmentModel(attachmentsToSend)
                if (runModel == null) {
                    val needsVision = attachmentsToSend.any(com.example.oreshik.ai.AttachmentRequestBuilder::needsVision)
                    val needsAudio = attachmentsToSend.any(com.example.oreshik.ai.AttachmentRequestBuilder::needsAudio)
                    val capability = when {
                        needsVision && needsAudio -> "vision and audio"
                        needsVision -> "vision"
                        else -> "audio"
                    }
                    Toast.makeText(this@MainActivity, "Choose a $capability-capable model to send these attachments.", Toast.LENGTH_LONG).show()
                    if (!AgentConfig.isOfflineMode) showModelPicker()
                    return@launch
                }
                if (!AgentConfig.isOfflineMode && runModel != AgentConfig.model) {
                    Toast.makeText(this@MainActivity, "Using ${runModel.substringAfterLast('/')} for this attachment.", Toast.LENGTH_LONG).show()
                }
                // Clear the composer only after the attachment has a compatible model route.
                if (etMessage.text?.toString()?.trim() == userText || userText == attachmentOnlyPrompt()) etMessage.text?.clear()
                pendingChatAttachments.removeAll(attachmentsToSend.toSet())
                renderComposerAttachmentChips()
                refreshSendButton()
                if (isSpeaking) stopSpeaking()
                // A new request supersedes the active run only after its attachments are ready.
                if (agentRunning) {
                    agentRunning = false
                    accumulatedResponse = ""
                    refreshSendButton()
                    Toast.makeText(this@MainActivity, "Switching to your new request…", Toast.LENGTH_SHORT).show()
                }
                if (AgentConfig.saveHistory) {
                    runCatching {
                        val row = withContext(Dispatchers.IO) {
                            AgentDatabase.get(this@MainActivity).sessions().get(dispatchSessionId)
                        }
                        if (row != null && row.id == dispatchSessionId && currentSessionId == dispatchSessionId &&
                            row.updatedAt > currentSessionLastUpdatedAt &&
                            row.messageCount >= chatAdapter.messages.count { it.kind == "text" }
                        ) {
                            AppLogger.i(
                                "Chat",
                                "send: adopting fresher DB row for session=$dispatchSessionId (db=${row.messageCount} msgs, memory=${chatAdapter.messages.count { it.kind == "text" }})"
                            )
                            val historyType = object : TypeToken<List<JsonObject>>() {}.type
                            val messageType = object : TypeToken<List<ChatMessage>>() {}.type
                            val parsed = withContext(Dispatchers.IO) {
                                val loadedHistory: List<JsonObject> = gson.fromJson(row.historyJson, historyType) ?: emptyList()
                                val loadedMessages: List<ChatMessage> = gson.fromJson(row.messagesJson, messageType) ?: emptyList()
                                loadedHistory to loadedMessages
                            }
                            history.clear(); history.addAll(parsed.first.map { it.deepCopy() })
                            chatAdapter.replaceAll(parsed.second)
                            hasSentFirstMessage = chatAdapter.messages.any { it.role == "user" }
                            currentSessionCreatedAt = row.createdAt
                            currentSessionTitle = row.title
                            currentSessionLastUpdatedAt = row.updatedAt
                            if (hasSentFirstMessage) {
                                emptyState.isVisible = false
                                messagesRecycler.isVisible = true
                            }
                        }
                    }
                }
                if (currentSessionId == dispatchSessionId) dispatchToAgent(userText, attachmentsToSend, runModel)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Toast.makeText(this@MainActivity, "Unable to prepare attachments: ${failure.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            } finally {
                dispatchPending = false
            }
        }
    }

    private suspend fun resolveAttachmentModel(attachments: List<ChatAttachment>): String? {
        if (attachments.isEmpty()) return AgentConfig.activeModelName
        val needsVision = attachments.any(com.example.oreshik.ai.AttachmentRequestBuilder::needsVision)
        val needsAudio = attachments.any(com.example.oreshik.ai.AttachmentRequestBuilder::needsAudio)
        if (!needsVision && !needsAudio) return AgentConfig.activeModelName
        if (AgentConfig.isOfflineMode) return null

        val provider = runCatching { com.example.oreshik.ai.ProviderManager.getActiveProvider() }.getOrNull() ?: return null
        if (needsAudio && provider.id == com.example.oreshik.ai.ChatGptPlanProvider.ID) return null
        var models = cachedModels.takeIf { cachedModelProviderId == provider.id }.orEmpty()
        if (models.isEmpty() || models.none { it.id == AgentConfig.model }) {
            val fetched = provider.fetchModels().getOrNull()
            if (fetched != null) {
                models = com.example.oreshik.ai.ModelTester.overlaySavedResults(provider.id, fetched)
                cachedModels = models
                cachedModelProviderId = provider.id
            }
        }
        fun supports(model: Model): Boolean = (!needsVision || model.vision || inferredVisionModel(model.id)) &&
            (!needsAudio || model.audio || com.example.oreshik.ModelCapabilities.supportsChatAudio(model.id, model.description))

        val selected = models.firstOrNull { it.id == AgentConfig.model }
        if (selected != null && selected.tested != false && supports(selected)) return selected.id
        if (selected == null && ((!needsVision || inferredVisionModel(AgentConfig.model)) &&
                (!needsAudio || com.example.oreshik.ModelCapabilities.supportsChatAudio(AgentConfig.model)))) {
            return AgentConfig.model
        }
        return models.firstOrNull { it.tested != false && supports(it) }?.id
    }

    private fun inferredVisionModel(modelId: String): Boolean =
        listOf("vision", "-vl", "_vl", "llava", "pixtral", "gpt-4o", "gpt-4.1").any { modelId.contains(it, true) }

    /**
     * Appends the user message and starts the background agent run. Called on
     * Main from a coroutine — commits the initial session snapshot before the
     * service starts, and also uses that row for oversized payload hand-off.
     */
    private suspend fun dispatchToAgent(userText: String, attachments: List<ChatAttachment>, runModel: String) {
        if (!hasSentFirstMessage) {
            hasSentFirstMessage = true
            emptyState.isVisible = false
            messagesRecycler.isVisible = true
            currentSessionTitle = makeSessionTitle(userText)
        }
        ChatNotifications.setCurrentChat(this, currentSessionId, currentSessionTitle)
        chatAdapter.addMessage(ChatMessage(role = "user", content = userText, attachments = attachments))
        chatAdapter.showProcessing("Thinking…")
        refreshPipPreview()
        scrollChatToLatest()
        // The service only updates existing rows so a deleted chat cannot be
        // resurrected. Await the first user-turn write before starting it: the
        // old fire-and-forget save raced the service's final save and could
        // leave a fast or closed chat missing from history.
        if (AgentConfig.saveHistory) {
            if (persistCurrentSessionAwaiting()) {
                refreshSessions()
            } else {
                AppLogger.w("Chat", "send: could not persist the initial session row before dispatch; this chat may not appear in history")
            }
        }
        agentRunning = true
        refreshSendButton()
        markPendingPrompt(currentSessionId, userText)
        val activeProviderId = if (AgentConfig.isOfflineMode) "offline_gguf" else AgentConfig.getRaw("active_provider_id").ifBlank { "nvidia" }
        AppLogger.i("Chat", "send: session=$currentSessionId provider=$activeProviderId model=$runModel reasoning=${AgentConfig.reasoningEffort} chars=${userText.length} attachments=${attachments.size}")
        // The intent parcel must stay small: system_server re-parcels the whole
        // intent when delivering the service start, and an oversized one
        // throws TransactionTooLargeException inside the SYSTEM process —
        // leaving the startForegroundService() obligation pending until the
        // app is killed 30s later (ForegroundServiceDidNotStartInTimeException).
        // Oversized payloads use the fresh DB row when possible and an app-cache
        // payload file otherwise, keeping the system-service intent small.
        val historyJson = gson.toJson(history)
        val messagesJson = gson.toJson(chatAdapter.messages)
        var viaDb = historyJson.length + messagesJson.length + userText.length > MAX_INTENT_PAYLOAD_CHARS
        var payloadFileName: String? = null
        if (viaDb) {
            if (!persistCurrentSessionAwaiting()) {
                // History saving may be disabled or the DB may be unavailable.
                // Keep large conversation data out of the Binder transaction
                // by passing a short cache filename to the service instead.
                payloadFileName = writeAgentPayloadFile(userText, historyJson, messagesJson)
                if (payloadFileName == null) {
                    agentRunning = false
                    refreshSendButton()
                    chatAdapter.hideProcessing()
                    clearPendingPrompt(currentSessionId, userText)
                    chatAdapter.addMessage(ChatMessage(role = "assistant", content = "Unable to prepare this large request. Please try again.", model = AgentConfig.model))
                    Toast.makeText(this, "Unable to prepare the request.", Toast.LENGTH_LONG).show()
                    return
                }
                viaDb = false
                AppLogger.w("Chat", "send: oversized payload stored temporarily for service handoff; history persistence is unavailable for session=$currentSessionId")
            } else {
                AppLogger.i("Chat", "send: oversized payload (${historyJson.length}+${messagesJson.length} chars) handed to the service via the session DB row")
            }
        }
        startAgentRun(
            buildRunIntent(
                sessionId = currentSessionId,
                title = currentSessionTitle,
                createdAt = currentSessionCreatedAt,
                userText = userText,
                historyJson = historyJson,
                messagesJson = messagesJson,
                model = runModel,
                viaDb = viaDb,
                payloadFileName = payloadFileName,
            ),
            currentSessionId,
        )
    }

    private fun setupNavDrawer() {
        val header = navView.getHeaderView(0)
        val sessionsRecycler = header.findViewById<RecyclerView>(R.id.sessionsRecycler)
        sessionAdapter = SessionAdapter(
            onClick = { session -> openSession(session) },
            onLongClick = { anchor, session -> showSessionMenu(anchor, session); true }
        )
        sessionsRecycler.layoutManager = LinearLayoutManager(this)
        sessionsRecycler.adapter = sessionAdapter
        lifecycleScope.launch { refreshSessions() }

        navView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(navView)
            when (item.itemId) {
                R.id.nav_new_chat -> startNewChat()
                R.id.nav_models -> showModelPicker()
                R.id.nav_providers -> launchActivityOutsideChat(Intent(this, ProvidersActivity::class.java))
                R.id.nav_tools -> launchActivityOutsideChat(Intent(this, ToolsActivity::class.java))
                R.id.nav_mcp -> launchActivityOutsideChat(Intent(this, McpServersActivity::class.java))
                R.id.nav_skills -> launchActivityOutsideChat(Intent(this, SkillsActivity::class.java))
                R.id.nav_memory -> launchActivityOutsideChat(Intent(this, MemoryActivity::class.java))
                R.id.nav_wake_diagnostics -> launchActivityOutsideChat(Intent(this, WakeWordDiagnosticsActivity::class.java))
                // Backup & Restore: jumps straight to the Settings screen section
                // that exports/imports the full app config (keys, model, sessions…).
                R.id.nav_settings -> launchActivityOutsideChat(Intent(this, SettingsActivity::class.java))
                R.id.nav_about -> showAboutDialog()
            }; true
        }
    }

    private fun openSession(item: SessionListItem) {
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) {
                runCatching { AgentDatabase.get(this@MainActivity).sessions().get(item.id) }
                    .onFailure { AppLogger.e("Chat", "session load failed for id=${item.id}", it) }
                    .getOrNull()
            }
            if (session != null) loadSession(session)
            else Toast.makeText(this@MainActivity, "Could not load this saved chat.", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSession(session: SessionEntity) {
        if (dispatchPending) {
            Toast.makeText(this, "Wait for the current message to be sent.", Toast.LENGTH_SHORT).show()
            return
        }
        // A run is still streaming for this session in this process — do not
        // reload stale DB content over it (and never persist the stale view
        // back, which would erase the freshly persisted reply).
        val taskActive = AgentTaskManager.isActive(session.id)
        if (taskActive && session.id == currentSessionId) return
        // A run completed for this session while no event collector was active
        // (app backgrounded): the DB row is NEWER than the stale in-memory
        // view. Reload it, but never persist the stale view back over it —
        // that would erase the assistant reply from the DB.
        val dbNewer = session.id == currentSessionId && session.updatedAt > maxOf(currentSessionLastUpdatedAt, currentSessionCreatedAt)
        val memoryCount = chatAdapter.messages.count { it.kind == "text" }
        if (dbNewer && session.messageCount >= memoryCount) {
            // The dropped terminal event left a stale agentRunning flag; the
            // task manager says nothing is active anymore — correct it.
            agentRunning = false
            refreshSendButton()
            chatAdapter.hideProcessing()
        } else if (session.id != currentSessionId) {
            // Switching to a different session: flush the CURRENT conversation
            // before adopting another one.
            persistCurrentSession(preserveConcurrentUpdate = true)
        }
        // Same-session reload with a non-newer row: never persist the in-memory
        // view back. The row on disk already reflects the service's latest
        // write; re-persisting a possibly stale/partial snapshot here stamped a
        // fresh updatedAt over it and was the mechanism that ERASED conversation
        // history between turns (the next send then adopted the emptied row).
        currentSessionLastUpdatedAt = session.updatedAt
        lifecycleScope.launch {
            // Parse on IO, mutate UI/state on Main. loadSession is invoked
            // from the agent Completed-event handler too — running the state
            // mutations on Main is what keeps chatAdapter.messages and
            // history single-thread-confined (they are serialized by
            // persistCurrentSession on the main thread).
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val historyType = object : TypeToken<List<JsonObject>>() {}.type
                    val messageType = object : TypeToken<List<ChatMessage>>() {}.type
                    val loadedHistory: List<JsonObject> = gson.fromJson(session.historyJson, historyType) ?: emptyList()
                    val loadedMessages: List<ChatMessage> = gson.fromJson(session.messagesJson, messageType) ?: emptyList()
                    loadedHistory to loadedMessages
                }.getOrNull()
            } ?: return@launch
            currentSessionId = session.id
            currentSessionCreatedAt = session.createdAt
            currentSessionLastUpdatedAt = session.updatedAt
            currentSessionTitle = session.title
            ChatNotifications.setCurrentChat(this@MainActivity, currentSessionId, currentSessionTitle)
            if (session.model.startsWith("offline:")) {
                val localName = session.model.removePrefix("offline:")
                val localFile = OfflineModelStore.models(this@MainActivity)
                    .firstOrNull { it.name.equals(localName, ignoreCase = true) }
                if (localFile != null) {
                    AgentConfig.offlineModelPath = localFile.absolutePath
                    AgentConfig.offlineModelName = localFile.name
                    AgentConfig.modelMode = "offline"
                } else {
                    AgentConfig.modelMode = "online"
                    Toast.makeText(this@MainActivity, "This chat's GGUF file is missing. Switched to online mode.", Toast.LENGTH_LONG).show()
                }
            } else if (session.model.isNotBlank()) {
                AgentConfig.model = session.model
                AgentConfig.modelMode = "online"
            }
            updateModelButton()
            // Repair legacy corrupted rows: with the duplicate-collector bug the
            // same reply was concatenated ×N inside one assistant bubble (and
            // persisted that way). Collapse exact repeated copies on load; the
            // next persist writes the healed bubble back to the DB.
            val healed = loaded.second.map { msg ->
                if (msg.role == "assistant" && msg.kind == "text")
                    msg.copy(content = dedupeRepeatedAssistantContent(msg.content))
                else msg
            }
            history.clear(); history.addAll(loaded.first.map { it.deepCopy() })
            chatAdapter.replaceAll(healed)
            // (No full-refresh notification needed: replaceAll already notified.)
            hasSentFirstMessage = chatAdapter.messages.any { it.role == "user" }
            emptyState.isVisible = !hasSentFirstMessage
            messagesRecycler.isVisible = hasSentFirstMessage
            tvGreeting.text = if (hasSentFirstMessage) session.title else greetings.random()
            etMessage.text?.clear()
            drawerLayout.closeDrawer(navView)
            // The button state must follow the TARGET session: a run may still
            // stream for the newly loaded session (or the previous session's
            // run state must not leak into this one).
            agentRunning = runCatching { AgentTaskManager.isActive(session.id) }.getOrDefault(false)
            chatAdapter.hideProcessing()
            if (agentRunning) chatAdapter.showProcessing("Thinking…")
            refreshSendButton()
            if (hasSentFirstMessage) scrollChatToLatest(smooth = false)
            refreshPipPreview()
        }
    }

    /**
     * Collapses exact repeated copies of one reply inside a single assistant
     * bubble. The duplicate-collector bug (fixed in [startAgentEventCollector])
     * concatenated the same reply ×N onto itself before persisting; loading
     * such a row verbatim kept showing the tripled greeting. If the bubble is
     * exactly the same text repeated 2+ times, keep one copy. Deliberately
     * conservative: only exact full-content repetition is collapsed — distinct
     * replies that happen to share sentences are never touched.
     *
     * Uses the KMP failure function to find the smallest period in O(n), which
     * handles every multiplier (×2, ×3, ×5, …) and stays fast on long replies.
     */
    private fun dedupeRepeatedAssistantContent(content: String): String {
        val trimmed = content.trim()
        val n = trimmed.length
        if (n < 2) return content
        val fail = IntArray(n)
        var k = 0
        for (i in 1 until n) {
            while (k > 0 && trimmed[i] != trimmed[k]) k = fail[k - 1]
            if (trimmed[i] == trimmed[k]) k++
            fail[i] = k
        }
        val period = n - fail[n - 1]
        return if (period < n && n % period == 0) trimmed.substring(0, period) else content
    }

    private fun persistCurrentSession() {
        persistCurrentSession(preserveConcurrentUpdate = false)
    }

    private fun persistCurrentSession(preserveConcurrentUpdate: Boolean) {
        val snapshot = buildSessionSnapshot() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val written = writeSessionEntity(snapshot, preserveConcurrentUpdate)
            if (written) {
                withContext(Dispatchers.Main) {
                    if (currentSessionId == snapshot.entity.id) {
                        currentSessionLastUpdatedAt = maxOf(currentSessionLastUpdatedAt, snapshot.entity.updatedAt)
                    }
                }
            }
            refreshSessions()
        }
    }

    /**
     * Persist variant for the oversized-dispatch hand-off: the ACTION_RUN
     * intent carries no payload (EXTRA_FROM_DB) and the service loads the
     * session row on start — the write MUST have landed before the service
     * reads it, so this suspends until the DB write completes. Returns false
     * when nothing was persisted (the caller must not use the viaDb path).
     */
    private suspend fun persistCurrentSessionAwaiting(): Boolean {
        val snapshot = buildSessionSnapshot() ?: return false
        val written = withContext(Dispatchers.IO) {
            runCatching { writeSessionEntity(snapshot, preserveConcurrentUpdate = false) }.getOrDefault(false)
        }
        if (written && currentSessionId == snapshot.entity.id) {
            currentSessionLastUpdatedAt = maxOf(currentSessionLastUpdatedAt, snapshot.entity.updatedAt)
        }
        return written
    }

    private data class SessionSnapshot(
        val entity: SessionEntity,
        val expectedUpdatedAt: Long,
    )

    /** Snapshot + serialize on the caller (main) thread; null when not persistable. */
    private fun buildSessionSnapshot(): SessionSnapshot? {
        // Serializing chatAdapter.messages/history on a background thread
        // raced with main-thread mutations (streamed text, hideProcessing)
        // and threw ConcurrentModificationException, silently dropping the
        // session save and making the chat appear cancelled after the run
        // finished — hence the snapshot is taken HERE.
        val messagesSnapshot = chatAdapter.messages.filter { it.kind == "text" || it.kind == "work" }
        if (!AgentConfig.saveHistory || messagesSnapshot.none { it.role == "user" }) return null
        val now = System.currentTimeMillis()
        val sessionId = currentSessionId
        val entity = SessionEntity(
            id = sessionId,
            title = currentSessionTitle.ifBlank { "New chat" },
            model = AgentConfig.sessionModelName,
            createdAt = currentSessionCreatedAt,
            updatedAt = maxOf(now, currentSessionLastUpdatedAt + 1),
            messagesJson = gson.toJson(messagesSnapshot),
            historyJson = gson.toJson(history.toList()),
            summary = history.firstOrNull { it.get("role")?.asString == "system" && it.get("content")?.asString?.startsWith("Conversation context summary") == true }?.get("content")?.asString,
            compressedAt = if (history.any { it.get("role")?.asString == "system" && it.get("content")?.asString?.startsWith("Conversation context summary") == true }) now else null,
            messageCount = messagesSnapshot.size
        )
        return SessionSnapshot(entity, currentSessionLastUpdatedAt)
    }

    private suspend fun writeSessionEntity(
        snapshot: SessionSnapshot,
        preserveConcurrentUpdate: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val entity = snapshot.entity
        runCatching {
            val db = AgentDatabase.get(this@MainActivity).sessions()
            if (preserveConcurrentUpdate) {
                val wrote = db.putIfUnchanged(entity, snapshot.expectedUpdatedAt)
                if (!wrote) {
                    AppLogger.i("Chat", "persist: skipped stale Activity snapshot for session=${entity.id}; the DB row changed after it was loaded")
                }
                wrote
            } else {
                db.put(entity)
                true
            }
        }.onFailure { AppLogger.e("Chat", "session write failed for id=${entity.id}", it) }
            .getOrDefault(false)
    }

    private suspend fun refreshSessions() {
        if (!::sessionAdapter.isInitialized) return
        val generation = sessionRefreshGeneration.incrementAndGet()
        val sessions = withContext(Dispatchers.IO) {
            runCatching { AgentDatabase.get(this@MainActivity).sessions().recentListItems(50) }
                .onFailure { AppLogger.e("Chat", "session list refresh failed", it) }
                .getOrNull()
        } ?: return
        withContext(Dispatchers.Main) {
            if (generation != sessionRefreshGeneration.get() || !::sessionAdapter.isInitialized) return@withContext
            sessionAdapter.submitList(sessions)
            navView.getHeaderView(0)?.findViewById<TextView>(R.id.tvSessionCount)?.text = if (sessions.isEmpty()) "" else sessions.size.toString()
            navView.getHeaderView(0)?.findViewById<View>(R.id.tvSessionsEmpty)?.isVisible = sessions.isEmpty()
        }
    }

    private fun showSessionMenu(anchor: View, session: SessionListItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Memory privacy state lives in the settings table so SessionLearner (which reads it
            // on Dispatchers.IO) and this menu stay in sync.
            val noLearn = runCatching {
                AgentDatabase.get(this@MainActivity).settings().get("no_learn_${session.id}")?.value?.toBoolean() ?: false
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                PopupMenu(this@MainActivity, anchor).apply {
                    menu.add("Rename")
                    menu.add("Delete")
                    menu.add(if (noLearn) getString(R.string.session_menu_resume_remember) else getString(R.string.session_menu_dont_remember))
                    setOnMenuItemClickListener { item ->
                        when (item.title.toString()) {
                            "Rename" -> renameSession(session)
                            "Delete" -> deleteSession(session)
                            getString(R.string.session_menu_dont_remember) -> setSessionNoLearn(session, true)
                            getString(R.string.session_menu_resume_remember) -> setSessionNoLearn(session, false)
                        }
                        true
                    }
                    show()
                }
            }
        }
    }

    /** Marks a session as exempt from autonomous learning (facts are never stored from it). */
    private fun setSessionNoLearn(session: SessionListItem, noLearn: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val db = AgentDatabase.get(this@MainActivity).settings()
                if (noLearn) db.put(SettingEntity("no_learn_${session.id}", "true"))
                else db.remove("no_learn_${session.id}")
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    if (noLearn) "This chat won't be remembered." else "This chat will be remembered again.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun renameSession(session: SessionListItem) {
        val input = EditText(this).apply { setText(session.title); selectAll(); setSingleLine(true) }
        android.app.AlertDialog.Builder(this).setTitle("Rename chat").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
            val title = input.text.toString().trim().ifBlank { session.title }
            lifecycleScope.launch(Dispatchers.IO) { AgentDatabase.get(this@MainActivity).sessions().rename(session.id, title, System.currentTimeMillis()); refreshSessions() }
            if (session.id == currentSessionId) {
                currentSessionTitle = title
                ChatNotifications.setCurrentChat(this, currentSessionId, currentSessionTitle)
                refreshPipPreview()
            }
        }.show()
    }

    private fun deleteSession(session: SessionListItem) {
        if (dispatchPending) {
            Toast.makeText(this, "Wait for the current message to be sent.", Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(this).setTitle("Delete chat?").setMessage(session.title).setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
            ChatNotifications.cancelDeletedSession(this, session.id)
            // Stop any in-flight agent run for this session first: its final
            // persist() would otherwise re-insert the row we are about to
            // delete (SessionDao.put is a REPLACE upsert), resurrecting the
            // chat in the history list.
            runCatching {
                startService(Intent(this, AgentExecutionService::class.java).apply {
                    action = AgentExecutionService.ACTION_STOP
                    putExtra(AgentExecutionService.EXTRA_SESSION_ID, session.id)
                })
            }
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { AgentDatabase.get(this@MainActivity).sessions().delete(session.id) }
                // The session is gone — retire any pending-run marker for it
                // so the next app open doesn't try to resume a deleted chat.
                runCatching {
                    val sdb = AgentDatabase.get(this@MainActivity).settings()
                    sdb.remove(AgentExecutionService.SETTING_PENDING_PREFIX + session.id)
                    if (sdb.get("pending_prompt_session")?.value == session.id) {
                        sdb.remove("pending_prompt_session"); sdb.remove("pending_prompt_text")
                    }
                }
                // Re-check under the coroutine: the user may have switched or
                // started a new chat while the delete was in flight.
                if (session.id == currentSessionId) {
                    withContext(Dispatchers.Main) {
                        if (session.id == currentSessionId) startNewChat(followUp = false)
                    }
                }
                refreshSessions()
            }
        }.show()
    }

    private fun makeSessionTitle(text: String): String = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > 58) it.take(55) + "…" else it }.ifBlank { "New chat" }

    override fun onStart() {
        super.onStart()
        if (::sessionAdapter.isInitialized) lifecycleScope.launch { refreshSessions() }
        updateModelButton()
        val activeProviderId = runCatching { com.example.oreshik.ai.ProviderManager.getActiveProvider().id }.getOrNull()
        if (activeProviderId != null && activeProviderId != cachedModelProviderId && activeProviderId != requestedModelProviderId) {
            cachedModels = emptyList()
            lifecycleScope.launch { refreshModels(false) }
        }
        // Recover from runs that finished while this Activity was below
        // STARTED (screen off / task switched away): the Completed event was
        // dropped by the paused collector and agentRunning stayed true, which
        // blocked the DB reload below AND made the next send() persist a
        // stale history over the fresher row (the "chat forgets the previous
        // conversation" bug). Reconcile with the real task state first.
        val runStillActive = runCatching { AgentTaskManager.isActive(currentSessionId) }.getOrDefault(false)
        if (!runStillActive && agentRunning) {
            agentRunning = false
            refreshSendButton()
            chatAdapter.hideProcessing()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            AgentDatabase.get(this@MainActivity).sessions().get(currentSessionId)?.let { session ->
                withContext(Dispatchers.Main) {
                    // Load whenever the DB row has content beyond what this
                    // process last saw (first launch uses createdAt as the
                    // floor; after a rotation the restored updatedAt is the
                    // floor — a NEWER row means a reply/error finished while
                    // the activity was being recreated and must be adopted;
                    // anything else leaves the restored chat untouched).
                    if (session.updatedAt > maxOf(currentSessionCreatedAt, currentSessionLastUpdatedAt)) loadSession(session)
                }
            }
        }
    }

    /**
     * Registers the agent-event collector exactly once per Activity instance.
     *
     * BUG FIXED ("the response repeats itself"): the collector block used to
     * live directly inside onStart(), wrapped in repeatOnLifecycle(STARTED).
     * onStart() runs on EVERY foreground return, and repeatOnLifecycle() never
     * re-enters an already-running block — so each background/foreground cycle
     * leaked one MORE permanent collector coroutine. With N leaked collectors
     * every AgentEvent.Text was handled N times: the first collector created
     * the reply bubble and the remaining N-1 appended the same text onto it,
     * storing the reply duplicated ×2/×3/… in the UI and the session DB (the
     * duplication grew the more the app had been backgrounded). Registering
     * from a once-guarded method keeps the collector instance-scoped while
     * repeatOnLifecycle still suspends it below STARTED and cancels it when
     * this Activity is destroyed.
     */
    private fun startAgentEventCollector() {
        if (agentEventsRegistered) return
        agentEventsRegistered = true
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    AgentEventBus.events.collect { event ->
                        // Always check against the CURRENT session ID, not a captured value.
                        // This ensures events from stale sessions are ignored immediately.
                        if (event.sessionId != currentSessionId) {
                            // A different session is active; ignore events from old sessions.
                            return@collect
                        }
                        handleAgentEvent(event)
                    }
                }
                launch {
                    com.example.oreshik.agent.security.PermissionApprovalCoordinator.requests.collect { requests ->
                        val activeIds = requests.mapTo(mutableSetOf()) { it.id }
                        permissionApprovalDialogs.keys.toList()
                            .filterNot(activeIds::contains)
                            .forEach { id ->
                                permissionApprovalDialogs.remove(id)?.let { dialog ->
                                    dialog.setOnCancelListener(null)
                                    dialog.dismiss()
                                }
                            }
                        requests.forEach(::showPermissionApproval)
                    }
                }
            }
        }
    }

    private fun showPermissionApproval(
        request: com.example.oreshik.agent.security.PermissionApprovalRequest,
    ) {
        if (isFinishing || isDestroyed || permissionApprovalDialogs.containsKey(request.id)) return
        val permissionNames = request.permissions
            .joinToString("\n") { it.name.replace('_', ' ') }
        val message = buildString {
            append("The agent wants to run ")
            append(request.tool)
            append(".\n\nPermissions:\n")
            append(permissionNames)
            if (request.sessionId.isNotBlank()) {
                append("\n\nSession: ")
                append(request.sessionId.take(48))
            }
        }
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("Agent permission required")
            .setMessage(message)
            .setNegativeButton("Deny") { _, _ ->
                com.example.oreshik.agent.security.PermissionApprovalCoordinator.resolve(
                    request.id,
                    com.example.oreshik.agent.security.PermissionBroker.Decision.DENY,
                )
            }
        if (request.alwaysAllowAvailable) {
            builder
                .setNeutralButton("Allow once") { _, _ ->
                    com.example.oreshik.agent.security.PermissionApprovalCoordinator.resolve(
                        request.id,
                        com.example.oreshik.agent.security.PermissionBroker.Decision.ALLOW_ONCE,
                    )
                }
                .setPositiveButton("Always allow") { _, _ ->
                    com.example.oreshik.agent.security.PermissionApprovalCoordinator.resolve(
                        request.id,
                        com.example.oreshik.agent.security.PermissionBroker.Decision.ALWAYS_ALLOW,
                    )
                }
        } else {
            builder.setPositiveButton("Allow this change") { _, _ ->
                com.example.oreshik.agent.security.PermissionApprovalCoordinator.resolve(
                    request.id,
                    com.example.oreshik.agent.security.PermissionBroker.Decision.ALLOW_ONCE,
                )
            }
        }
        val dialog = builder.create()
        dialog.setOnCancelListener {
            com.example.oreshik.agent.security.PermissionApprovalCoordinator.resolve(
                request.id,
                com.example.oreshik.agent.security.PermissionBroker.Decision.DENY,
            )
        }
        dialog.setOnDismissListener { permissionApprovalDialogs.remove(request.id) }
        permissionApprovalDialogs[request.id] = dialog
        dialog.show()
    }

    override fun onStop() {
        permissionApprovalDialogs.values.toList().forEach { dialog ->
            dialog.setOnCancelListener(null)
            dialog.dismiss()
        }
        permissionApprovalDialogs.clear()
        super.onStop()
    }

    /** Re-check device access each time the chat screen is brought to the foreground. */
    private fun scheduleDeviceAccessAudit() {
        if (deviceAccessAuditBusy || deviceAccessAuditDoneForResume || deviceAccessAuditWaitingForConfig ||
            isFinishing || isDestroyed || isInPictureInPictureMode
        ) {
            AppLogger.i("DeviceAccessAudit", "schedule skipped: busy=$deviceAccessAuditBusy done=$deviceAccessAuditDoneForResume configWait=$deviceAccessAuditWaitingForConfig finishing=$isFinishing destroyed=$isDestroyed piP=$isInPictureInPictureMode")
            return
        }
        deviceAccessAuditDoneForResume = true
        deviceAccessAuditWaitingForConfig = true
        lifecycleScope.launch {
            AgentConfig.awaitReady()
            window.decorView.post {
                deviceAccessAuditWaitingForConfig = false
                deviceAccessAuditDoneForResume = false
                AppLogger.i("DeviceAccessAudit", "settings ready; lifecycle=${lifecycle.currentState}, root=${AgentConfig.allowRoot}")
                maybeStartDeviceAccessAudit()
            }
        }
    }

    private fun maybeStartDeviceAccessAudit() {
        if (deviceAccessAuditBusy || deviceAccessAuditDoneForResume || !AgentConfig.isReady ||
            isFinishing || isDestroyed || isInPictureInPictureMode ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        ) {
            AppLogger.i("DeviceAccessAudit", "audit start skipped: busy=$deviceAccessAuditBusy done=$deviceAccessAuditDoneForResume ready=${AgentConfig.isReady} finishing=$isFinishing destroyed=$isDestroyed piP=$isInPictureInPictureMode lifecycle=${lifecycle.currentState}")
            return
        }
        deviceAccessAuditDoneForResume = true
        deviceAccessAuditBusy = true
        lifecycleScope.launch {
            AgentConfig.awaitReady()
            if (!AgentConfig.allowRoot) {
                deviceAccessRootAvailable = false
                showRootAccessPrompt("Root access is currently disabled in Oreshik. It is used for root-only tools, reliable screen capture on some Xiaomi devices, and the optional background-restriction fix.")
                return@launch
            }
            deviceAccessRootRequestInFlight = true
            val root = withContext(Dispatchers.IO) {
                runCatching {
                    com.example.oreshik.agent.security.RootManager(applicationContext)
                        .status(force = true, timeoutSeconds = 30)
                }.getOrDefault(com.example.oreshik.agent.security.RootManager.RootStatus(false))
            }
            deviceAccessRootRequestInFlight = false
            deviceAccessRootAvailable = root.available
            if (root.available) continueDeviceAccessAudit()
            else showRootAccessPrompt("Oreshik cannot currently get su access. Approve the request in your root manager to restore root-only tools and screen capture.")
        }
    }

    private fun showRootAccessPrompt(message: String, promptKey: String = "root") {
        if (!deviceAccessPromptedThisForeground.add(promptKey)) {
            continueDeviceAccessAudit()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Allow root access")
            .setMessage(message + " Only approve root access on a device you control.")
            .setPositiveButton("Request root") { _, _ -> requestRootAccess() }
            .setNegativeButton("Not now") { _, _ ->
                AgentConfig.allowRoot = false
                deviceAccessRootAvailable = false
                continueDeviceAccessAudit()
            }
            .setOnCancelListener {
                AgentConfig.allowRoot = false
                deviceAccessRootAvailable = false
                continueDeviceAccessAudit()
            }
            .show()
    }

    private fun requestRootAccess() {
        AgentConfig.allowRoot = true
        deviceAccessRootRequestInFlight = true
        val progress = android.app.AlertDialog.Builder(this)
            .setTitle("Requesting root")
            .setMessage("Approve Oreshik in your root manager if it asks.")
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) {
                runCatching {
                    com.example.oreshik.agent.security.RootManager(applicationContext)
                        .status(force = true, timeoutSeconds = 30)
                }.getOrDefault(com.example.oreshik.agent.security.RootManager.RootStatus(false))
            }
            deviceAccessRootRequestInFlight = false
            if (!isFinishing && !isDestroyed) progress.dismiss()
            deviceAccessRootAvailable = root.available
            if (root.available) {
                AgentConfig.allowRoot = true
            } else {
                AgentConfig.allowRoot = false
                Toast.makeText(this@MainActivity, "Root access was not granted; continuing with available Android permissions.", Toast.LENGTH_LONG).show()
            }
            continueDeviceAccessAudit()
        }
    }

    private fun continueDeviceAccessAudit() {
        if (!deviceAccessAuditBusy || isFinishing || isDestroyed) return
        requestMissingRuntimePermissions()
    }

    private fun requestMissingRuntimePermissions() {
        val permissionManager = com.example.oreshik.agent.security.AndroidPermissionManager
        val missing = permissionManager.missingRuntimePermissions(this)
        if (missing.isEmpty()) {
            checkNotificationAccess()
            return
        }

        val prefs = getSharedPreferences("device_access_audit", MODE_PRIVATE)
        val permanentlyDenied = missing.any { permission ->
            prefs.getBoolean("requested:$permission", false) &&
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
        }
        if (permanentlyDenied) {
            showDeviceAccessPrompt(
                key = "runtime_settings",
                title = "Grant app permissions",
                message = "One or more app permissions were denied before. Android now requires you to grant them in Oreshik’s system app settings.",
                positive = "Open app settings",
                onPositive = { openDeviceAccessSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = "package:$packageName".toUri() }) },
                onNotNow = { checkNotificationAccess() },
                onAlreadyPrompted = { checkNotificationAccess() },
            )
            return
        }
        if (!deviceAccessPromptedThisForeground.add("runtime")) {
            checkNotificationAccess()
            return
        }
        prefs.edit {
            missing.forEach { permission -> putBoolean("requested:$permission", true) }
        }
        deviceAccessAwaitingRuntimePermissions = true
        deviceAccessAuditBusy = false
        permissionManager.requestRuntime(this)
    }

    private fun checkNotificationAccess() {
        if (com.example.oreshik.notification.NotificationMonitorService.isEnabled(this)) {
            lifecycleScope.launch(Dispatchers.IO) {
                com.example.oreshik.notification.NotificationMonitorService.requestRebindIfNeeded(applicationContext)
            }
            checkAccessibilityAccess()
            return
        }
        showDeviceAccessPrompt(
            key = "notification_access",
            title = "Allow notification access",
            message = "This lets Oreshik read notifications from other apps and keep its active-chat notification in sync. Android will show the system notification-access screen.",
            positive = "Open settings",
            onPositive = { openDeviceAccessSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            onNotNow = { checkAccessibilityAccess() },
            onAlreadyPrompted = { checkAccessibilityAccess() },
        )
    }

    private fun checkAccessibilityAccess() {
        val serviceEnabled = com.example.oreshik.agent.security.AccessibilityRootFixer.isServiceRegistered(this)
        if (AgentConfig.accessibilityEnabled && serviceEnabled) {
            checkScreenCaptureAccess()
            return
        }
        val message = if (!serviceEnabled) {
            "Enable Oreshik Agent Accessibility in Android Settings so the agent can inspect and control visible UI. Android requires you to turn this service on yourself."
        } else {
            "The Android accessibility service is on, but accessibility tools are disabled in Oreshik. Enable them to continue."
        }
        showDeviceAccessPrompt(
            key = "accessibility",
            title = "Allow accessibility access",
            message = message,
            positive = if (serviceEnabled) "Enable tools" else "Open settings",
            onPositive = {
                AgentConfig.accessibilityEnabled = true
                if (serviceEnabled) {
                    checkScreenCaptureAccess()
                } else {
                    openDeviceAccessSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            },
            onNotNow = { checkScreenCaptureAccess() },
            onAlreadyPrompted = { checkScreenCaptureAccess() },
        )
    }

    private fun checkScreenCaptureAccess() {
        val accessibilityReady = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            com.example.oreshik.agent.security.AccessibilityRootFixer.isServiceRegistered(this) &&
            com.example.oreshik.agent.accessibility.AgentAccessibilityService.isBound()
        val needsRootFallback = com.example.oreshik.agent.accessibility.AccessibilityWatchdog.isXiaomiDevice()
        val available = deviceAccessRootAvailable || (accessibilityReady && !needsRootFallback)
        if (available) {
            checkBatteryAndBackgroundAccess()
            return
        }
        if (!deviceAccessPromptedThisForeground.add("screen_capture")) {
            checkBatteryAndBackgroundAccess()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Allow screen capture")
            .setMessage("The agent needs a working screenshot path. Enable its Android accessibility service on Android 11 or later, or approve root access. Xiaomi/MIUI devices may require root because their screenshot service can block accessibility capture. Android does not keep a separate permanent screen-capture grant for this app.")
            .setPositiveButton("Open accessibility settings") { _, _ ->
                AgentConfig.accessibilityEnabled = true
                openDeviceAccessSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("Request root") { _, _ -> requestRootAccess() }
            .setNegativeButton("Not now") { _, _ -> checkBatteryAndBackgroundAccess() }
            .setOnCancelListener { checkBatteryAndBackgroundAccess() }
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun checkBatteryAndBackgroundAccess() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(packageName)) {
            checkMiuiBackgroundRestrictions()
            return
        }
        showDeviceAccessPrompt(
            key = "battery_optimization",
            title = "Allow background activity",
            message = "Unrestricted battery use helps keep the active chat and accessibility agent running when Oreshik is in the background.",
            positive = "Open settings",
            onPositive = {
                openDeviceAccessSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                })
            },
            onNotNow = { checkMiuiBackgroundRestrictions() },
            onAlreadyPrompted = { checkMiuiBackgroundRestrictions() },
        )
    }

    private fun checkMiuiBackgroundRestrictions() {
        if (!com.example.oreshik.agent.accessibility.AccessibilityWatchdog.isXiaomiDevice()) {
            finishDeviceAccessAudit()
            return
        }
        val serviceEnabled = com.example.oreshik.agent.security.AccessibilityRootFixer.isServiceRegistered(this)
        if (!AgentConfig.accessibilityEnabled || !serviceEnabled) {
            finishDeviceAccessAudit()
            return
        }
        val diagnosis = com.example.oreshik.agent.accessibility.AccessibilityWatchdog.diagnose(this)
        val needsRecovery = !com.example.oreshik.agent.security.AccessibilityRootFixer.hasSuccessfulBackgroundFix(this) ||
            !com.example.oreshik.agent.accessibility.AgentAccessibilityService.isBound()
        if (!needsRecovery) {
            finishDeviceAccessAudit()
            return
        }
        val detail = diagnosis.details
        showDeviceAccessPrompt(
            key = "miui_background",
            title = "Fix Xiaomi background restrictions",
            message = "$detail\n\nOreshik can re-apply its app-specific background and autostart fixes with approved root access. Without root, open app settings and set battery use to No restrictions and enable Autostart in the Security app.",
            positive = if (deviceAccessRootAvailable) "Apply fix" else "Open app settings",
            onPositive = {
                if (deviceAccessRootAvailable) runMiuiBackgroundFix()
                else openDeviceAccessSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = "package:$packageName".toUri() })
            },
            onAlreadyPrompted = { finishDeviceAccessAudit() },
        )
    }

    private fun runMiuiBackgroundFix() {
        val progress = android.app.AlertDialog.Builder(this)
            .setTitle("Applying background fix")
            .setMessage("Checking app-specific MIUI/HyperOS restrictions…")
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    val fixReport = com.example.oreshik.agent.security.AccessibilityRootFixer.applyAll(applicationContext)
                    if (com.example.oreshik.notification.NotificationMonitorService.isEnabled(applicationContext)) {
                        com.example.oreshik.notification.NotificationMonitorService.requestRebindIfNeeded(
                            applicationContext,
                            force = true,
                        )
                    }
                    fixReport
                }.getOrElse { "Background fix stopped: ${it.message ?: it.javaClass.simpleName}" }
            }
            if (!isFinishing && !isDestroyed) {
                progress.dismiss()
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Background fix result")
                    .setMessage(report)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finishDeviceAccessAudit() }
                    .setOnCancelListener { finishDeviceAccessAudit() }
                    .show()
            } else {
                deviceAccessAuditBusy = false
            }
        }
    }

    private fun showDeviceAccessPrompt(
        key: String,
        title: String,
        message: String,
        positive: String,
        onPositive: () -> Unit,
        onNotNow: () -> Unit = { finishDeviceAccessAudit() },
        onAlreadyPrompted: () -> Unit,
    ) {
        if (!deviceAccessPromptedThisForeground.add(key)) {
            onAlreadyPrompted()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onPositive() }
            .setNegativeButton("Not now") { _, _ -> onNotNow() }
            .setOnCancelListener { onNotNow() }
            .show()
    }

    private fun openDeviceAccessSettings(intent: Intent) {
        deviceAccessAuditBusy = false
        deviceAccessAwaitingSystemUi = true
        runCatching { startActivity(intent) }.onFailure {
            deviceAccessAwaitingSystemUi = false
            Toast.makeText(this, "Could not open the required Android settings page.", Toast.LENGTH_LONG).show()
            finishDeviceAccessAudit()
        }
    }

    private fun finishDeviceAccessAudit() {
        deviceAccessAuditBusy = false
    }

    override fun onPause() {
        deviceAccessAuditDoneForResume = false
        persistCurrentSession(preserveConcurrentUpdate = true)
        super.onPause()
    }

    private fun showAboutDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        val tvVersion = view.findViewById<TextView>(R.id.tvAboutVersion)
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        tvVersion.text = getString(R.string.about_version_line, versionName)

        // Populate feature chips
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupAbout)
        val chipLabels = listOf(
            R.string.about_chip_privacy,
            R.string.about_chip_runtime,
            R.string.about_chip_tools,
            R.string.about_chip_sessions,
            R.string.about_chip_offline,
            R.string.about_chip_opensource
        )
        for (labelRes in chipLabels) {
            val chip = Chip(this).apply {
                text = getString(labelRes)
                isClickable = false
                isCheckable = false
                setChipBackgroundColorResource(R.color.chip_neutral)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setPaddingRelative(
                    resources.getDimensionPixelSize(R.dimen.chip_padding_horizontal),
                    resources.getDimensionPixelSize(R.dimen.chip_padding_vertical),
                    resources.getDimensionPixelSize(R.dimen.chip_padding_horizontal),
                    resources.getDimensionPixelSize(R.dimen.chip_padding_vertical)
                )
            }
            chipGroup.addView(chip)
        }

        // Populate "What's inside" feature list
        val featureList = view.findViewById<LinearLayout>(R.id.aboutFeatureList)
        val features = listOf(
            Pair(R.drawable.ic_root, R.string.about_hi_privacy),
            Pair(R.drawable.ic_runtime, R.string.about_hi_runtime),
            Pair(R.drawable.ic_tools, R.string.about_hi_tools),
            Pair(R.drawable.ic_sessions, R.string.about_hi_sessions)
        )
        for ((iconRes, textRes) in features) {
            val row = LayoutInflater.from(this).inflate(R.layout.about_feature_row, featureList, false)
            row.findViewById<ImageView>(R.id.ivFeatureIcon).setImageResource(iconRes)
            row.findViewById<TextView>(R.id.tvFeatureText).text = getString(textRes)
            featureList.addView(row)
        }

        val dialog = android.app.AlertDialog.Builder(this).setView(view).create()
        view.findViewById<ImageButton>(R.id.btnCloseAbout).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun updateModelButton() {
        val model = AgentConfig.activeModelName
        btnModelSelector.text = when {
            AgentConfig.isOfflineMode && model.isNotBlank() -> "$model  ·  Offline"
            AgentConfig.isOfflineMode -> "Choose offline GGUF"
            model.isBlank() -> "Choose model"
            else -> model.substringAfterLast('/') + "  ·  ${AgentConfig.reasoningEffort.replaceFirstChar { it.uppercase() }}"
        }
    }

    /** @return true when a fresh catalog was fetched from the provider. */
    private suspend fun refreshModels(showErrors: Boolean): Boolean {
        // Use the ACTIVE provider (may be OpenRouter / BlueSmind / NaraRouter),
        // falling back to the legacy NVIDIA repository only when the registry is empty.
        val activeProvider = runCatching { com.example.oreshik.ai.ProviderManager.getActiveProvider() }.getOrNull()
        val providerName = activeProvider?.displayName ?: "NVIDIA"
        val providerId = activeProvider?.id ?: "nvidia"
        requestedModelProviderId = providerId
        val result: Result<List<Model>> = if (activeProvider != null) {
            activeProvider.fetchModels()
        } else {
            AppLogger.w("Chat", "provider registry empty — falling back to legacy NVIDIA catalog fetch")
            modelRepository.fetch()
        }
        result.onSuccess { models ->
            AppLogger.i("Chat", "models loaded: provider=${activeProvider?.id ?: "nvidia"} count=${models.size} selected=${AgentConfig.model.ifBlank { "(none)" }}")
            cachedModels = models
            cachedModelProviderId = providerId
            if (requestedModelProviderId == providerId) requestedModelProviderId = null
            if (AgentConfig.model.isBlank() && models.isNotEmpty()) {
                val preferred = models.firstOrNull { it.reasoning && ("coder" in it.id.lowercase() || "deepseek" in it.id.lowercase() || "qwen" in it.id.lowercase()) } ?: models.first()
                AgentConfig.model = preferred.id
            }
            withContext(Dispatchers.Main) { updateModelButton() }
            return true
        }.onFailure {
            if (requestedModelProviderId == providerId) requestedModelProviderId = null
            if (cachedModelProviderId != providerId) cachedModels = emptyList()
            AppLogger.e("Chat", "failed to load models for ${activeProvider?.id ?: "nvidia"} ($providerName): ${it.javaClass.simpleName}: ${it.message?.take(250)}", it)
            if (showErrors) withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "$providerName models: ${it.message}", Toast.LENGTH_LONG).show() }
        }
        return false
    }

    private fun showModelPicker() {
        val activeProvider = runCatching { com.example.oreshik.ai.ProviderManager.getActiveProvider() }.getOrNull()
        val providerName = activeProvider?.displayName ?: "NVIDIA"
        val providerId = activeProvider?.id ?: "nvidia"
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_models, (window.decorView as? ViewGroup), false)
        dialog.setContentView(view)
        var job: kotlinx.coroutines.Job? = null
        val adapter = ModelAdapter(cachedModels.takeIf { cachedModelProviderId == providerId } ?: emptyList()) { model ->
            AgentConfig.model = model.id
            AgentConfig.modelMode = "online"
            if (AgentConfig.reasoningEffort !in model.thinkingLevels) AgentConfig.reasoningEffort = model.thinkingLevels.lastOrNull() ?: "off"
            AppLogger.i("Chat", "model selected: provider=$providerId model=${model.id} reasoning=${AgentConfig.reasoningEffort}")
            updateModelButton(); job?.cancel(); dialog.dismiss()
        }
        val list = view.findViewById<RecyclerView>(R.id.rvModels)
        list.layoutManager = LinearLayoutManager(this); list.adapter = adapter
        val search = view.findViewById<EditText>(R.id.etModelSearch)
        search.hint = "Search $providerName models…"
        val selected = view.findViewById<TextView>(R.id.tvSelectedModel)
        val status = view.findViewById<TextView>(R.id.tvModelsStatus)
        val empty = view.findViewById<TextView>(R.id.tvModelsEmpty)
        val thinking = view.findViewById<LinearLayout>(R.id.thinkingOptions)
        val onlinePanel = view.findViewById<LinearLayout>(R.id.onlineModelPanel)
        val offlinePanel = view.findViewById<LinearLayout>(R.id.offlineModelPanel)
        val onlineTab = view.findViewById<TextView>(R.id.btnOnlineMode)
        val offlineTab = view.findViewById<TextView>(R.id.btnOfflineMode)
        val offlineDevice = view.findViewById<TextView>(R.id.tvOfflineDeviceProfile)
        val offlineSelected = view.findViewById<TextView>(R.id.tvOfflineSelectedModel)
        val offlineDetails = view.findViewById<TextView>(R.id.tvOfflineModelDetails)
        val offlineStatus = view.findViewById<TextView>(R.id.tvOfflineModelStatus)
        val routeStatus = view.findViewById<TextView>(R.id.tvModelModeStatus)
        val useOfflineButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnUseOfflineModel)
        val quantizeButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQuantizeGguf)
        val unloadButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnUnloadGguf)
        val deleteModelButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteGguf)
        fun renderThinking(model: Model?) {
            thinking.removeAllViews()
            val levels = model?.thinkingLevels ?: listOf("off", "minimal", "low", "medium", "high", "max")
            levels.forEach { level ->
                val chip = TextView(this).apply {
                    text = level.replaceFirstChar { it.uppercase() }
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    background = androidx.core.content.res.ResourcesCompat.getDrawable(resources, R.drawable.bg_pill_ripple, theme)
                    setPadding(22, 12, 22, 12)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 8, 0) }
                    isAllCaps = false
                }
                chip.alpha = if (level == AgentConfig.reasoningEffort) 1f else .65f
                chip.setOnClickListener { AgentConfig.reasoningEffort = level; renderThinking(model); updateModelButton(); selected.text = model?.id?.let { "Selected: $it" } ?: "Thinking: $level" }
                thinking.addView(chip)
            }
        }
        fun filter(q: String) {
            val filtered = cachedModels.filter { (it.id + " " + it.description).contains(q, true) }
            adapter.updateData(filtered)
            empty.isVisible = filtered.isEmpty()
            val modelCount = resources.getQuantityString(R.plurals.models_count_fmt, cachedModels.size, cachedModels.size)
            val shownCount = resources.getQuantityString(
                R.plurals.models_shown_count_fmt,
                filtered.size,
                filtered.size,
                cachedModels.size,
            )
            status.text = getString(R.string.models_status_line, providerName, modelCount, shownCount)
            val active = cachedModels.firstOrNull { it.id == AgentConfig.model }; selected.text = active?.let { "Selected: ${it.id}" } ?: "No model selected"; renderThinking(active)
        }
        fun updateModeTabs(showOffline: Boolean) {
            onlinePanel.isVisible = !showOffline
            offlinePanel.isVisible = showOffline
            fun styleTab(tab: TextView, selected: Boolean) {
                val density = resources.displayMetrics.density
                tab.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 18f * density
                    setColor(ContextCompat.getColor(this@MainActivity, if (selected) R.color.brand_600 else R.color.bg_pill))
                }
                tab.setTextColor(ContextCompat.getColor(this@MainActivity, if (selected) R.color.white else R.color.text_primary))
                tab.alpha = if (selected) 1f else .8f
            }
            styleTab(onlineTab, !showOffline)
            styleTab(offlineTab, showOffline)
        }
        fun refreshOfflinePanel() {
            val profile = DeviceModelAdvisor.detect(this@MainActivity)
            val selectedFile = OfflineModelStore.selectedFile(this@MainActivity)
            val activeRoute = if (AgentConfig.isOfflineMode) {
                "Current mode: Offline GGUF"
            } else {
                "Current mode: Online • $providerName"
            }
            routeStatus.text = activeRoute
            offlineDevice.text = buildString {
                append(profile.deviceLabel)
                append("\nRAM: ").append(OfflineModelStore.formatBytes(profile.totalRamBytes))
                append(" total • ").append(OfflineModelStore.formatBytes(profile.availableRamBytes)).append(" available")
                append("\nCPU: ").append(profile.cpuThreads).append(" inference threads • ").append(profile.contextSize).append(" token context")
                append("\nRecommended: ").append(profile.recommendation)
                append("\nFree model storage: ").append(OfflineModelStore.formatBytes(profile.availableStorageBytes))
            }
            offlineSelected.text = selectedFile?.name ?: "No GGUF model selected"
            offlineDetails.text = selectedFile?.let {
                "${OfflineModelStore.formatBytes(it.length())} • stored in this app's private model folder"
            } ?: "Import a compatible .gguf file from your device. Model files are not included in the app download."
            useOfflineButton.isEnabled = selectedFile != null && profile.supportsRuntime
            quantizeButton.isEnabled = selectedFile != null && profile.supportsRuntime
            deleteModelButton.isEnabled = selectedFile != null
            unloadButton.isVisible = true
            unloadButton.isEnabled = OfflineModelRuntime.loadedModelPath() != null
            when {
                !profile.supportsRuntime -> offlineStatus.text = "This inference package currently supports arm64-v8a phones."
                OfflineModelRuntime.isInferenceActive() -> offlineStatus.text = "A local response is being generated on the CPU. Stop in chat to retire the run; llama.cpp must finish its current native call before memory can be released."
                AgentConfig.isOfflineMode && selectedFile != null && OfflineModelRuntime.isLoaded(selectedFile.absolutePath) -> offlineStatus.text = "Offline mode is active. Model is loaded and ready."
                selectedFile != null && selectedFile.length() > profile.recommendedModelBytes -> offlineStatus.text = "This model is larger than the recommended size for this phone. Loading it may cause Android to stop the app."
                selectedFile != null -> offlineStatus.text = "Ready to load. Model is processed locally using the CPU; no GGUF model is bundled in the APK."
                else -> offlineStatus.text = "Offline model tools, web search and screenshot analysis are unavailable while using local text inference."
            }
        }

        fun startQuantization(source: File, quantizationType: String) {
            val progressText = TextView(this).apply {
                text = "Preparing ${source.name} for $quantizationType quantization…"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 14f
                val padding = (20 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            }
            val progressDialog = android.app.AlertDialog.Builder(this)
                .setTitle("Quantizing GGUF to $quantizationType")
                .setView(progressText)
                .setCancelable(false)
                .create()
            progressDialog.show()
            quantizeButton.isEnabled = false
            useOfflineButton.isEnabled = false
            deleteModelButton.isEnabled = false
            unloadButton.isEnabled = false
            var completionMessage: String? = null
            lifecycleScope.launch {
                try {
                    val app = applicationContext
                    val toolContext = ToolContext(
                        workspace = AgentConfig.workspaceRoot(app),
                        app = app,
                        permissionManager = PermissionManager(app),
                        runtime = RuntimeManager(app),
                        onProgress = { message ->
                            withContext(Dispatchers.Main.immediate) { progressText.text = message }
                        },
                    )
                    val args = JsonObject().apply {
                        addProperty("source_path", source.absolutePath)
                        addProperty("quantization_type", quantizationType)
                        // This explicit, user-confirmed UI action supports Q8 -> Q4 conversion.
                        addProperty("allow_requantize", true)
                    }
                    val result = GgufQuantizeTool().execute(toolContext, args)
                    check(result.ok) { result.error ?: "GGUF quantization failed." }
                    val outputPath = result.metadata["output_path"] as? String
                        ?: error("Quantization completed without returning its output path.")
                    val outputFile = File(outputPath)
                    AgentConfig.offlineModelPath = outputFile.absolutePath
                    AgentConfig.offlineModelName = outputFile.name
                    AgentConfig.modelMode = "offline"
                    updateModelButton()
                    completionMessage = "Created ${outputFile.name}. It is selected and unloaded; tap Load and switch to offline to use it."
                    Toast.makeText(this@MainActivity, "$quantizationType model ready", Toast.LENGTH_LONG).show()
                } catch (failure: Exception) {
                    AppLogger.e("OfflineModel", "GGUF quantization failed: ${failure.message}", failure)
                    completionMessage = "Quantization failed: ${failure.message ?: failure.javaClass.simpleName}"
                    Toast.makeText(this@MainActivity, "GGUF quantization failed", Toast.LENGTH_LONG).show()
                } finally {
                    progressDialog.dismiss()
                    refreshOfflinePanel()
                    completionMessage?.let { offlineStatus.text = it }
                }
            }
        }

        // Probes untested models of the ACTIVE provider while the sheet is open and
        // flips each row's status dot in real time as results stream in.
        fun startProbing() {
            job?.cancel()
            val provider = activeProvider ?: return
            job = lifecycleScope.launch {
                val baseUrl = provider.getEffectiveBaseUrl()
                val keys = provider.getApiKeys().filter { it.isNotBlank() }
                val pending = cachedModels.filter { it.tested == null }
                if (pending.isEmpty()) return@launch
                status.text = resources.getQuantityString(R.plurals.models_probing_fmt, pending.size, pending.size, providerName)
                val remaining = java.util.concurrent.atomic.AtomicInteger(pending.size)
                val probed = com.example.oreshik.ai.ModelTester.probeAll(
                    providerId = providerId,
                    models = pending,
                    baseUrl = baseUrl,
                    keys = keys,
                    useCache = true,
                    onProgress = { _, _, latest ->
                        val left = remaining.decrementAndGet()
                        runOnUiThread {
                            adapter.updateStatus(latest.id, com.example.oreshik.ai.ModelTester.statusFor(latest))
                            status.text = resources.getQuantityString(R.plurals.models_probing_fmt, left, left, providerName)
                        }
                    },
                )
                // Merge probe results back into the cached catalog + persist for next time.
                val byId = probed.associateBy { it.id }
                cachedModels = cachedModels.map { byId[it.id] ?: it }
                com.example.oreshik.ai.ModelTester.persistResults(providerId, cachedModels)
                filter(search.text.toString())
            }
        }
        search.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int,b: Int,c: Int){}; override fun onTextChanged(s: CharSequence?, a: Int,b: Int,c: Int){ filter(s?.toString().orEmpty()) }; override fun afterTextChanged(s: Editable?){} })
        view.findViewById<ImageButton>(R.id.btnRefreshModels).setOnClickListener {
            job?.cancel()
            lifecycleScope.launch {
                status.text = getString(R.string.models_refreshing, providerName)
                com.example.oreshik.ai.ModelTester.clearCache(providerId)
                refreshModels(true)
                // Force a full live re-check: forget old outcomes so every dot re-probes.
                cachedModels = cachedModels.map { it.copy(tested = null, testNote = "") }
                adapter.resetStatuses()
                filter(search.text.toString())
                startProbing()
            }
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnImportGguf)
            .setOnClickListener { ggufModelPicker.launch(arrayOf("*/*")) }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnChooseGguf)
            .setOnClickListener {
                val files = OfflineModelStore.models(this)
                if (files.isEmpty()) {
                    Toast.makeText(this, "Import a GGUF model first.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val selectedPath = OfflineModelStore.selectedFile(this)?.absolutePath
                val labels = files.map { "${it.name}\n${OfflineModelStore.formatBytes(it.length())}" }.toTypedArray()
                var choice = files.indexOfFirst { it.absolutePath == selectedPath }.coerceAtLeast(0)
                android.app.AlertDialog.Builder(this)
                    .setTitle("Choose a local GGUF")
                    .setSingleChoiceItems(labels, choice) { _, which -> choice = which }
                    .setPositiveButton("Select") { _, _ ->
                        files.getOrNull(choice)?.let { file ->
                            AgentConfig.offlineModelPath = file.absolutePath
                            AgentConfig.offlineModelName = file.name
                            refreshOfflinePanel()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

        quantizeButton.setOnClickListener {
            val file = OfflineModelStore.selectedFile(this)
            if (file == null) {
                Toast.makeText(this, "Choose a saved GGUF model first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val profile = DeviceModelAdvisor.detect(this)
            if (!profile.supportsRuntime) {
                offlineStatus.text = "GGUF quantization requires an arm64-v8a device."
                return@setOnClickListener
            }
            val sizeWarning = if (file.length() > profile.recommendedModelBytes) {
                "\n\nThis model exceeds this phone's recommended size and quantization may be slow or run out of memory."
            } else ""
            val targets = arrayOf("Q4_K_M — quality and size balance", "Q3_K_M — smaller file, lower quality")
            val suggested = if (file.length() > profile.recommendedModelBytes) 1 else 0
            android.app.AlertDialog.Builder(this)
                .setTitle("Optimize GGUF for this phone")
                .setMessage("Suggested: ${if (suggested == 0) "Q4_K_M" else "Q3_K_M"}. This phone has ${OfflineModelStore.formatBytes(profile.availableRamBytes)} RAM available and recommends a model up to ${OfflineModelStore.formatBytes(profile.recommendedModelBytes)}.$sizeWarning\n\nThe original is preserved. Choose a smaller Q3_K_M output when the Q4_K_M result would still exceed the phone's memory budget.")
                .setSingleChoiceItems(targets, suggested) { chooser, which ->
                    chooser.dismiss()
                    val type = if (which == 1) "Q3_K_M" else "Q4_K_M"
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Quantize to $type?")
                        .setMessage("Create a new $type model beside ${file.name}. The original will be kept. Quantization uses the phone's CPU and may take several minutes. If the source is already quantized, lowering its precision can reduce model quality.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Quantize") { _, _ -> startQuantization(file, type) }
                        .show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        unloadButton.setOnClickListener {
            val loadedPath = OfflineModelRuntime.loadedModelPath()
            if (loadedPath == null) {
                refreshOfflinePanel()
                return@setOnClickListener
            }
            if (OfflineModelRuntime.isInferenceActive()) {
                stopCurrentTask(showToast = false)
                offlineStatus.text = "Stopping the active local response. llama.cpp finishes its current native step before the model can be released."
            }
            unloadButton.isEnabled = false
            if (!OfflineModelRuntime.isInferenceActive()) offlineStatus.text = "Unloading ${File(loadedPath).name} from memory…"
            lifecycleScope.launch {
                val released = withContext(Dispatchers.IO) { OfflineModelRuntime.unload(loadedPath) }
                refreshOfflinePanel()
                offlineStatus.text = if (released) {
                    "Model unloaded from memory. Its GGUF file remains in app storage and will load again when used."
                } else {
                    "The loaded model could not be released. Its GGUF file is unchanged."
                }
            }
        }

        deleteModelButton.setOnClickListener {
            val file = OfflineModelStore.selectedFile(this)
            if (file == null) {
                refreshOfflinePanel()
                return@setOnClickListener
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("Remove GGUF model?")
                .setMessage("Permanently remove ${file.name} (${OfflineModelStore.formatBytes(file.length())}) from this app's model folder? The original file cannot be restored from the app.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove model") { _, _ ->
                    if (OfflineModelRuntime.isInferenceActive()) {
                        stopCurrentTask(showToast = false)
                        offlineStatus.text = "Stopping the active local response. The GGUF can be removed after llama.cpp finishes its current native step."
                    }
                    deleteModelButton.isEnabled = false
                    if (!OfflineModelRuntime.isInferenceActive()) offlineStatus.text = "Removing ${file.name}…"
                    lifecycleScope.launch {
                        try {
                            val removed = withContext(Dispatchers.IO) {
                                OfflineModelRuntime.unload(file.absolutePath)
                                !file.exists() || file.delete()
                            }
                            check(removed) { "The model file could not be removed." }
                            if (File(AgentConfig.offlineModelPath).absolutePath == file.absolutePath) {
                                AgentConfig.offlineModelPath = ""
                                AgentConfig.offlineModelName = ""
                                if (AgentConfig.isOfflineMode) AgentConfig.modelMode = "online"
                                updateModelButton()
                            }
                            refreshOfflinePanel()
                            offlineStatus.text = "Removed ${file.name} from this app's model folder."
                            Toast.makeText(this@MainActivity, "GGUF model removed", Toast.LENGTH_SHORT).show()
                        } catch (failure: Exception) {
                            refreshOfflinePanel()
                            offlineStatus.text = "Could not remove model: ${failure.message ?: failure.javaClass.simpleName}"
                        }
                    }
                }
                .show()
        }

        useOfflineButton.setOnClickListener {
            val file = OfflineModelStore.selectedFile(this)
            if (file == null) {
                ggufModelPicker.launch(arrayOf("*/*"))
                return@setOnClickListener
            }
            val profile = DeviceModelAdvisor.detect(this)
            if (!profile.supportsRuntime) {
                offlineStatus.text = "Offline GGUF inference is not available for this CPU architecture."
                return@setOnClickListener
            }
            val load = {
                useOfflineButton.isEnabled = false
                offlineStatus.text = "Loading ${file.name}… Keep the app open; first load can take a little while."
                val loadingContent = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val padding = (20 * resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding)
                    addView(android.widget.ProgressBar(this@MainActivity), LinearLayout.LayoutParams(
                        (32 * resources.displayMetrics.density).toInt(),
                        (32 * resources.displayMetrics.density).toInt(),
                    ))
                    addView(TextView(this@MainActivity).apply {
                        text = "Loading ${file.name}\nThe first load may take a few minutes."
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                        textSize = 14f
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (16 * resources.displayMetrics.density).toInt()
                    })
                }
                val progress = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Loading offline model")
                    .setView(loadingContent)
                    .setCancelable(false)
                    .create()
                    .also { it.show() }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { OfflineModelRuntime.load(file.absolutePath, profile) }
                        AgentConfig.offlineModelPath = file.absolutePath
                        AgentConfig.offlineModelName = file.name
                        AgentConfig.modelMode = "offline"
                        updateModelButton()
                        refreshOfflinePanel()
                        offlineStatus.text = "Offline mode is active. This model runs on-device."
                        Toast.makeText(this@MainActivity, "Switched to offline GGUF", Toast.LENGTH_SHORT).show()
                    } catch (failure: Throwable) {
                        AppLogger.e("OfflineModel", "could not activate ${file.name}: ${failure.message}", failure)
                        offlineStatus.text = "Could not load model: ${failure.message ?: failure.javaClass.simpleName}"
                        Toast.makeText(this@MainActivity, "Could not load GGUF model: ${failure.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        progress.dismiss()
                        useOfflineButton.isEnabled = true
                    }
                }
            }
            if (file.length() > profile.recommendedModelBytes) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Large model for this phone")
                    .setMessage("${file.name} is ${OfflineModelStore.formatBytes(file.length())}. The device profile recommends files up to ${OfflineModelStore.formatBytes(profile.recommendedModelBytes)}. Continuing may make Android stop the app when memory runs low.")
                    .setNegativeButton("Choose another", null)
                    .setPositiveButton("Load anyway") { _, _ -> load() }
                    .show()
            } else {
                load()
            }
        }
        onlineTab.setOnClickListener {
            val wasOffline = AgentConfig.isOfflineMode
            AgentConfig.modelMode = "online"
            updateModelButton()
            updateModeTabs(showOffline = false)
            filter(search.text.toString())
            if (wasOffline || cachedModelProviderId != providerId) {
                job?.cancel()
                job = lifecycleScope.launch {
                    status.text = getString(R.string.models_loading, providerName)
                    val fetched = refreshModels(false)
                    if (fetched) cachedModels = com.example.oreshik.ai.ModelTester.overlaySavedResults(providerId, cachedModels)
                    else status.text = getString(R.string.load_models_failed_fmt, "$providerName catalog")
                    withContext(Dispatchers.Main) { filter(search.text.toString()) }
                    if (fetched) startProbing()
                }
            }
        }
        offlineTab.setOnClickListener {
            updateModeTabs(showOffline = true)
            refreshOfflinePanel()
        }
        // The moment the sheet opens: paint the last-known list + persisted dots
        // INSTANTLY, then auto-refresh the catalog from the ACTIVE provider in the
        // background (no refresh-button press needed) and probe every model whose
        // outcome is unknown — dots flip green/red/orange in real time.
        refreshOfflineModelsUi = { refreshOfflinePanel() }
        filter("")
        updateModeTabs(showOffline = AgentConfig.isOfflineMode)
        if (AgentConfig.isOfflineMode) {
            refreshOfflinePanel()
        } else {
            lifecycleScope.launch {
                status.text = getString(R.string.models_loading, providerName)
                val fetched = refreshModels(false)
                if (fetched) {
                    cachedModels = com.example.oreshik.ai.ModelTester.overlaySavedResults(providerId, cachedModels)
                } else {
                    // Provider unreachable: show why. Keep the cached list visible so the
                    // user still gets last-known dots, but never leave a stuck "Loading…".
                    status.text = getString(R.string.load_models_failed_fmt, "$providerName catalog")
                    if (cachedModels.isEmpty()) empty.isVisible = true
                }
                withContext(Dispatchers.Main) { filter(search.text.toString()) }
                startProbing()
            }
        }
        dialog.setOnShowListener {
            @Suppress("DEPRECATION")
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            BottomSheetSurface.clearOuterSurfaces(dialog, view)
            (dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet) as? View)?.let { sheet ->
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.setOnDismissListener { job?.cancel(); refreshOfflineModelsUi = null }
        dialog.show()
    }

    /** Copies a selected GGUF into app-specific storage so the native runtime can read it safely. */
    private fun importOfflineModel(uri: Uri) {
        val displayName = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                    name to size
                }
            }
        }.getOrNull() ?: ("local-model.gguf" to -1L)
        val sourceName = displayName.first.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        if (!sourceName.endsWith(".gguf", ignoreCase = true)) {
            Toast.makeText(this, "Choose a .gguf model file.", Toast.LENGTH_LONG).show()
            return
        }
        val safeBaseName = sourceName
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .take(150)
            .ifBlank { "local-model.gguf" }
        val directory = OfflineModelStore.directory(this)
        val available = runCatching { android.os.StatFs(directory.absolutePath).availableBytes }.getOrDefault(0L)
        if ((displayName.second > 0L) && (displayName.second + 32L * 1024L * 1024L > available)) {
            Toast.makeText(this, "Not enough free space to copy ${OfflineModelStore.formatBytes(displayName.second)} GGUF model.", Toast.LENGTH_LONG).show()
            return
        }
        var target = File(directory, safeBaseName)
        if (target.exists()) {
            target = File(directory, "${safeBaseName.removeSuffix(".gguf")}-${System.currentTimeMillis()}.gguf")
        }
        val temporary = File(directory, ".${target.name}.partial")
        val copyProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = displayName.second <= 0L
        }
        val copyProgressText = TextView(this).apply {
            text = "Copying GGUF model into app storage…"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 14f
        }
        val copyContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(copyProgressText)
            addView(copyProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * resources.displayMetrics.density).toInt()
            })
        }
        val progress = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Importing ${safeBaseName}")
            .setView(copyContent)
            .setCancelable(false)
            .create()
            .also { it.show() }
        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val expectedBytes = displayName.second
                    contentResolver.openInputStream(uri)?.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var copied = 0L
                            var nextUpdate = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                copied += count
                                if ((expectedBytes > 0L) && (copied >= nextUpdate)) {
                                    nextUpdate = copied + 8L * 1024L * 1024L
                                    val percent = ((copied * 100L) / expectedBytes).coerceIn(0, 100).toInt()
                                    runOnUiThread {
                                        if (progress.isShowing) {
                                            copyProgress.progress = percent
                                            copyProgressText.text = "Copying GGUF model… $percent%"
                                        }
                                    }
                                }
                            }
                            output.flush()
                        }
                    } ?: error("Could not open the selected model file")
                    if (temporary.length() < 16L * 1024L * 1024L) error("This file is too small to be a usable GGUF model")
                    if (!temporary.renameTo(target)) error("Could not finish saving the model")
                    target
                }.onFailure { temporary.delete() }
            }
            progress.dismiss()
            imported.onSuccess { file ->
                AgentConfig.offlineModelPath = file.absolutePath
                AgentConfig.offlineModelName = file.name
                refreshOfflineModelsUi?.invoke()
                AppLogger.i("OfflineModel", "imported ${file.name} (${OfflineModelStore.formatBytes(file.length())})")
                Toast.makeText(this@MainActivity, "Imported ${file.name}", Toast.LENGTH_SHORT).show()
            }.onFailure { failure ->
                AppLogger.e("OfflineModel", "GGUF import failed: ${failure.message}", failure)
                Toast.makeText(this@MainActivity, "Could not import GGUF: ${failure.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Creates a fresh SpeechRecognizer session per mic tap. A recognizer is single-use:
     * after onResults/onError the instance must be destroyed and re-created. Reusing the
     * dead instance is why the mic seemed to ignore taps after a command or an error.
     */
    private fun startVoiceRecognitionSession() {
        // Tear down any previous (dead or busy) session first.
        destroyVoiceRecognition()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                updateMicButtonState()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(dB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
                updateMicButtonState()
            }
            override fun onError(error: Int) {
                isListening = false
                recognitionInFlight = false
                updateMicButtonState()
                val msg = SpeechErrors.message(error)
                Toast.makeText(this@MainActivity, "Speech error $error: $msg", Toast.LENGTH_SHORT).show()
                // The session is dead - destroy so the next tap creates a fresh one.
                destroyVoiceRecognition()
                // One silent auto-retry for transient server/busy errors; never an endless loop.
                if (!retriedAfterError && (error == SpeechRecognizer.ERROR_SERVER ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) {
                    retriedAfterError = true
                    etMessage.postDelayed({ if (!isListening && !agentRunning) startVoiceRecognitionSession() }, 1200)
                } else {
                    retriedAfterError = false
                }
            }
            override fun onPartialResults(partial: Bundle?) {
                val list = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                list?.firstOrNull()?.let { text ->
                    pendingVoiceText = text
                    etMessage.text.clear()
                    etMessage.append(text)
                }
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = list?.firstOrNull() ?: pendingVoiceText ?: ""
                pendingVoiceText = null
                isListening = false
                recognitionInFlight = false
                retriedAfterError = false
                updateMicButtonState()
                // Session complete - destroy it so the next tap starts fresh.
                destroyVoiceRecognition()
                if (transcript.isNotBlank()) {
                    etMessage.text.clear()
                    etMessage.append(transcript)
                    // Auto-send after a short delay for natural conversation flow
                    etMessage.postDelayed({ sendToAgent(transcript) }, 500)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        isListening = false
        recognitionInFlight = true
        updateMicButtonState()
        speechRecognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
        )
    }

    /** Tears down the current recognition session (if any). */
    private fun destroyVoiceRecognition() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        recognitionInFlight = false
    }

    private fun initTextToSpeech() {
        // TextToSpeech's constructor performs synchronous binder calls to the
        // TTS engine. On this device logcat recorded a 5.9s ITextToSpeechService
        // transaction, which froze the first frame when run on the main thread.
        // Bind on a worker thread and retain any response until initialization
        // completes. The application context and destruction check prevent a
        // late bind from retaining a destroyed Activity.
        Thread {
            val engine = runCatching {
                android.speech.tts.TextToSpeech(applicationContext) { status ->
                    runOnUiThread { onTextToSpeechInitialized(status) }
                }
            }.getOrElse {
                AppLogger.w("MainActivity", "TTS init failed: ${it.message}")
                runOnUiThread { onTextToSpeechUnavailable() }
                return@Thread
            }
            val shutDownImmediately = synchronized(ttsLock) {
                if (activityDestroyed) true else {
                    textToSpeech = engine
                    false
                }
            }
            if (shutDownImmediately) shutdownTextToSpeech(engine)
        }.start()
    }

    private fun onTextToSpeechInitialized(status: Int) {
        if (activityDestroyed) return
        val engine = synchronized(ttsLock) { textToSpeech } ?: return
        if (status != android.speech.tts.TextToSpeech.SUCCESS) {
            onTextToSpeechUnavailable()
            return
        }
        Thread {
            val languageResult = runCatching { engine.setLanguage(java.util.Locale.getDefault()) }
                .getOrDefault(android.speech.tts.TextToSpeech.ERROR)
            runOnUiThread {
                if (activityDestroyed) return@runOnUiThread
                if (languageResult == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED ||
                    languageResult == android.speech.tts.TextToSpeech.ERROR
                ) {
                    Toast.makeText(this@MainActivity, "TTS language not supported", Toast.LENGTH_SHORT).show()
                    onTextToSpeechUnavailable()
                    return@runOnUiThread
                }
                ttsReady = true
                pendingSpeechText?.let { pending ->
                    pendingSpeechText = null
                    speakTextNow(pending, engine)
                }
            }
        }.start()
    }

    private fun onTextToSpeechUnavailable() {
        if (activityDestroyed) return
        ttsReady = false
        pendingSpeechText = null
        isSpeaking = false
        updateMicButtonState()
        refreshSendButton()
    }

    private fun shutdownTextToSpeech(engine: android.speech.tts.TextToSpeech?) {
        Thread {
            runCatching {
                engine?.stop()
                engine?.shutdown()
            }
        }.start()
    }

    private fun updateMicButtonState() {
        runOnUiThread {
            btnMic.setImageResource(
                if (isListening) R.drawable.ic_mic_active else if (isSpeaking) R.drawable.ic_volume_up else R.drawable.ic_mic
            )
            btnMic.setColorFilter(
                if (isListening) ContextCompat.getColor(this, R.color.brand_500)
                else if (isSpeaking) ContextCompat.getColor(this, R.color.brand_500)
                else ContextCompat.getColor(this, R.color.text_secondary)
            )
        }
    }

    private fun startVoiceListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), VOICE_PERMISSION_REQUEST)
            return
        }
        if (isListening && recognitionInFlight) {
            // Tap again while listening stops the current session.
            speechRecognizer?.stopListening()
            return
        }
        // Fresh session per tap: a SpeechRecognizer is single-use, so the previous
        // instance is always destroyed and a new one created (fixes the ignored
        // taps after a command or an error ended the previous session).
        startVoiceRecognitionSession()
    }

    private fun speakText(text: String) {
        if (activityDestroyed) return
        val engine = synchronized(ttsLock) { textToSpeech }
        if (!ttsReady || engine == null) {
            pendingSpeechText = text
            isSpeaking = true
            updateMicButtonState()
            refreshSendButton()
            return
        }
        speakTextNow(text, engine)
    }

    private fun speakTextNow(text: String, engine: android.speech.tts.TextToSpeech) {
        if (activityDestroyed) return
        if (isSpeaking) engine.stop()
        isSpeaking = true
        updateMicButtonState()
        // The reply is being read aloud: the composer button turns into the
        // "silence" control until the utterance finishes.
        refreshSendButton()
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            @Deprecated("Deprecated in Java")
            override fun onDone(utteranceId: String?) {
                // TTS callbacks may arrive on a binder thread — hop to Main
                // before touching views.
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    isSpeaking = false
                    updateMicButtonState()
                    refreshSendButton()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    isSpeaking = false
                    updateMicButtonState()
                    refreshSendButton()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStart(utteranceId: String?) {}
        })
        val result = runCatching {
            engine.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
        }.getOrDefault(android.speech.tts.TextToSpeech.ERROR)
        if (result == android.speech.tts.TextToSpeech.ERROR) onTextToSpeechUnavailable()
    }

    private fun stopSpeaking() {
        pendingSpeechText = null
        val engine = synchronized(ttsLock) { textToSpeech }
        if (ttsReady) runCatching { engine?.stop() }
        isSpeaking = false
        updateMicButtonState()
        refreshSendButton()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == com.example.oreshik.agent.security.AndroidPermissionManager.REQUEST_RUNTIME) {
            deviceAccessAwaitingRuntimePermissions = false
            deviceAccessAuditBusy = false
            deviceAccessAuditDoneForResume = false
            maybeStartDeviceAccessAudit()
        }
        if (requestCode == VOICE_PERMISSION_REQUEST && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceListening()
        }
    }

    override fun onDestroy() {
        activityDestroyed = true
        destroyVoiceRecognition()
        ttsReady = false
        pendingSpeechText = null
        val tts = synchronized(ttsLock) { textToSpeech.also { textToSpeech = null } }
        // stop()/shutdown() may block on the TTS Binder service.
        shutdownTextToSpeech(tts)
        super.onDestroy()
    }

    override fun onModelSelected(model: Model) { AgentConfig.model = model.id; updateModelButton() }
}


