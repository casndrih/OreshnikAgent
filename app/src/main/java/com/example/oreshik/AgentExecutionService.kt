package com.example.oreshik

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.core.AgentEngine
import com.example.oreshik.agent.core.AgentTaskManager
import com.example.oreshik.agent.events.AgentEvent
import com.example.oreshik.agent.events.AgentEventBus
import com.example.oreshik.agent.security.PermissionBroker
import com.example.oreshik.agent.security.PermissionManager
import com.example.oreshik.data.AgentDatabase
import com.example.oreshik.data.SessionEntity
import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class AgentRunPayload(
    val userText: String,
    val historyJson: String,
    val messagesJson: String,
)

class AgentExecutionService : Service() {
    companion object {
        const val ACTION_RUN = "com.example.oreshik.action.RUN_AGENT"
        const val ACTION_STOP = "com.example.oreshik.action.STOP_AGENT"
        const val ACTION_EVENT = "com.example.oreshik.action.AGENT_EVENT"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SESSION_TITLE = "session_title"
        const val EXTRA_SESSION_CREATED = "session_created"
        const val EXTRA_USER_TEXT = "user_text"
        const val EXTRA_HISTORY_JSON = "history_json"
        const val EXTRA_MESSAGES_JSON = "messages_json"
        /**
         * When true, the big payload (history + messages + prompt) is NOT in
         * the intent: the caller persisted it into the session DB row and the
         * service reloads it from there. This keeps the intent parcel small —
         * an oversized intent throws TransactionTooLargeException inside
         * system_server while the start is delivered, which silently drops
         * the startForegroundService() obligation and gets the process
         * killed 30s later (ForegroundServiceDidNotStartInTimeException).
         */
        const val EXTRA_FROM_DB = "via_db"
        const val EXTRA_PAYLOAD_FILE = "payload_file"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_MODEL = "model"
        const val EXTRA_MODEL_MODE = "model_mode"
        const val EXTRA_OFFLINE_MODEL_PATH = "offline_model_path"
        const val EXTRA_REASONING = "reasoning"
        const val SETTING_PENDING_SESSION = "pending_prompt_session"
        const val SETTING_PENDING_TEXT = "pending_prompt_text"
        const val SETTING_PENDING_PREFIX = "pending_prompt_for_"
        const val EXTRA_TYPE = "event_type"
        const val EXTRA_TEXT = "event_text"
        const val EXTRA_NAME = "event_name"
        const val EXTRA_TOOL_STATUS = "tool_status"
        private const val CHANNEL = "agent_responses"
        private const val FOREGROUND_ID = 7101
        private const val RESPONSE_ID = 7102
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var latestStartId = 0
    @Volatile private var foregroundStarted = false

    /** Current task for each session, used to route per-session stop requests. */
    private val runningTasks = java.util.concurrent.ConcurrentHashMap<String, AgentTaskManager.Task>()
    /** All tasks owned by this service, including superseded tasks still unwinding. */
    private val activeTasks = java.util.concurrent.ConcurrentHashMap<String, AgentTaskManager.Task>()
    private val sessionTitles = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var foregroundSessionId: String? = null
    @Volatile private var foregroundSessionTitle: String = "Oreshik Agent"
    @Volatile private var foregroundModel: String = ""
    @Volatile private var foregroundStatus: String = "Working…"

    override fun onCreate() {
        super.onCreate()
        AgentConfig.init(this)
        // The service runs in the same process but must register the provider
        // registry itself; without this the provider lookup returns null and the
        // engine silently falls back to the legacy single-provider path.
        runCatching { com.example.oreshik.ai.ProviderManager.initialize(this) }
        createChannel()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:agent")?.apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_STOP -> {
                // Cancel one session (EXTRA_SESSION_ID) or everything when absent.
                val sid = intent.getStringExtra(EXTRA_SESSION_ID)
                val targets = if (sid != null) listOfNotNull(runningTasks[sid]) else activeTasks.values.toList()
                if (sid != null && sid == foregroundSessionId) {
                    val replacement = activeTasks.values.firstOrNull { it.sessionId != sid }
                    if (replacement == null) {
                        val status = if (com.example.oreshik.ai.OfflineModelRuntime.isInferenceActive()) {
                            "Stopping local generation…"
                        } else {
                            "Stopping chat…"
                        }
                        setForegroundSession(sid, foregroundSessionTitle, foregroundModel, status)
                    } else {
                        setForegroundSession(
                            replacement.sessionId,
                            ChatNotifications.currentTitle(
                                this,
                                replacement.sessionId,
                                sessionTitles[replacement.sessionId].orEmpty().ifBlank { "New chat" },
                            ),
                            replacement.model,
                            "Working…",
                        )
                    }
                }
                scope.launch {
                    targets.forEach { AgentTaskManager.cancel(it.id) }
                    stopServiceIfIdle()
                }
                return START_NOT_STICKY
            }
            ACTION_RUN -> { /* fall through to execution */ }
            else -> {
                scope.launch { stopServiceIfIdle() }
                return START_NOT_STICKY
            }
        }
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: UUID.randomUUID().toString()
        val sessionTitle = ChatNotifications.currentTitle(
            this,
            sessionId,
            intent.getStringExtra(EXTRA_SESSION_TITLE).orEmpty().ifBlank { "New chat" },
        )
        val model = intent.getStringExtra(EXTRA_MODEL).orEmpty()
        sessionTitles[sessionId] = sessionTitle
        foregroundSessionId = sessionId
        foregroundSessionTitle = sessionTitle
        foregroundModel = model
        foregroundStatus = "Thinking…"
        startForeground(FOREGROUND_ID, buildWorkingNotification(sessionId, sessionTitle, model, foregroundStatus))
        foregroundStarted = true
        // Wake lock for the maximum practical agent window (30 min); the service
        // keeps the CPU running whether the app is foreground or background.
        runCatching { if (wakeLock?.isHeld != true) wakeLock?.acquire(30 * 60 * 1000L) }
        val task = AgentTaskManager.create(sessionId, intent.getStringExtra(EXTRA_MODEL).orEmpty())
        activeTasks[task.id] = task
        runningTasks.put(sessionId, task)?.let { previous ->
            scope.launch { AgentTaskManager.cancel(previous.id) }
        }
        // Install the task in the session map before yielding to a coroutine so
        // an immediately following ACTION_STOP can find and cancel it.
        scope.launch { execute(intent, sessionId, task) }
        // START_NOT_STICKY: don't restart with the last intent after process death.
        // If the session was deleted, we don't want to resurrect its work.
        return START_NOT_STICKY
    }

    private suspend fun execute(intent: Intent, sessionId: String, task: AgentTaskManager.Task) {
        val job = currentCoroutineContext()[Job]
        try {
            // Mark under NonCancellable so a stop received before this coroutine
            // starts cannot prevent the queued task from recording its job/state.
            withContext(NonCancellable) {
                if (job != null) AgentTaskManager.markRunning(task, job)
            }
            if (task.state == AgentTaskManager.State.CANCELLING) {
                throw kotlinx.coroutines.CancellationException("Agent run cancelled before setup")
            }
            executeRun(intent, sessionId, task)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                if (task.state != AgentTaskManager.State.CANCELLED && task.state != AgentTaskManager.State.COMPLETED && task.state != AgentTaskManager.State.FAILED) {
                    AgentTaskManager.markTerminal(task, AgentTaskManager.State.CANCELLED)
                }
                if (runningTasks[sessionId] === task) {
                    clearPendingPromptMarker(sessionId, intent.getStringExtra(EXTRA_USER_TEXT))
                    sendEvent(sessionId, "cancelled", null, null, "done")
                }
            }
        } catch (failure: Exception) {
            AppLogger.e("AgentService", "run aborted: session=$sessionId: ${failure.javaClass.simpleName}: ${failure.message?.take(300)}", failure)
            withContext(NonCancellable) {
                if (task.state == AgentTaskManager.State.CANCELLING) {
                    AgentTaskManager.markTerminal(task, AgentTaskManager.State.CANCELLED)
                    if (runningTasks[sessionId] === task) {
                        clearPendingPromptMarker(sessionId, intent.getStringExtra(EXTRA_USER_TEXT))
                        sendEvent(sessionId, "cancelled", null, null, "done")
                    }
                } else if (task.state != AgentTaskManager.State.CANCELLED && task.state != AgentTaskManager.State.COMPLETED && task.state != AgentTaskManager.State.FAILED) {
                    AgentTaskManager.markTerminal(task, AgentTaskManager.State.FAILED)
                    if (runningTasks[sessionId] === task) {
                        clearPendingPromptMarker(sessionId, intent.getStringExtra(EXTRA_USER_TEXT))
                        sendEvent(sessionId, "error", failure.message ?: "Agent failed", null, "error")
                        notifyResponse(sessionId, intent.getStringExtra(EXTRA_SESSION_TITLE).orEmpty().ifBlank { "New chat" }, "Agent failed: ${failure.message ?: "Unknown error"}")
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (task.state == AgentTaskManager.State.QUEUED || task.state == AgentTaskManager.State.RUNNING || task.state == AgentTaskManager.State.CANCELLING || task.state == AgentTaskManager.State.WAITING_FOR_USER_INPUT || task.state == AgentTaskManager.State.PAUSED) {
                    val terminal = if (task.state == AgentTaskManager.State.CANCELLING) AgentTaskManager.State.CANCELLED else AgentTaskManager.State.FAILED
                    AgentTaskManager.markTerminal(task, terminal)
                }
                runningTasks.remove(sessionId, task)
                activeTasks.remove(task.id, task)
                if (runningTasks[sessionId] == null) sessionTitles.remove(sessionId)
                stopServiceIfIdle()
            }
        }
    }

    private suspend fun stopServiceIfIdle() = withContext(Dispatchers.Main.immediate) {
        if (activeTasks.isNotEmpty()) {
            val activeSessionId = activeTasks.values.firstOrNull()?.sessionId
            if (activeSessionId != null && activeSessionId != foregroundSessionId) {
                val activeTask = activeTasks.values.firstOrNull { it.sessionId == activeSessionId }
                setForegroundSession(
                    activeSessionId,
                    ChatNotifications.currentTitle(
                        this@AgentExecutionService,
                        activeSessionId,
                        sessionTitles[activeSessionId].orEmpty().ifBlank { "New chat" },
                    ),
                    activeTask?.model.orEmpty(),
                    "Working…",
                )
            }
            return@withContext
        }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        foregroundSessionId = null
        stopSelfResult(latestStartId)
    }

    private suspend fun executeRun(intent: Intent, sessionId: String, task: AgentTaskManager.Task) {
        AgentConfig.awaitReady()
        var title = intent.getStringExtra(EXTRA_SESSION_TITLE).orEmpty().ifBlank { "New chat" }
        var createdAt = intent.getLongExtra(EXTRA_SESSION_CREATED, System.currentTimeMillis())
        var userText = intent.getStringExtra(EXTRA_USER_TEXT).orEmpty()
        val runModel = intent.getStringExtra(EXTRA_MODEL).orEmpty().ifBlank { task.model }
        val runMode = intent.getStringExtra(EXTRA_MODEL_MODE).orEmpty().ifBlank { AgentConfig.modelMode }
        val offlineModelPath = intent.getStringExtra(EXTRA_OFFLINE_MODEL_PATH).orEmpty().ifBlank { AgentConfig.offlineModelPath }
        val sessionModel = if (runMode.equals("offline", ignoreCase = true)) {
            "offline:${File(offlineModelPath).name.ifBlank { AgentConfig.offlineModelName }}"
        } else runModel
        val historyType = object : TypeToken<List<JsonObject>>() {}.type
        val messageType = object : TypeToken<List<ChatMessage>>() {}.type
        // DB pass-through handoff (EXTRA_FROM_DB): the payload exceeded the
        // intent parcel budget, so the caller persisted it into the session
        // row — reload history + messages (+ prompt, if needed) from the row
        // instead of the oversized extras.
        var history: MutableList<JsonObject>
        var messages: MutableList<ChatMessage>
        val payloadFileName = intent.getStringExtra(EXTRA_PAYLOAD_FILE)
        if (!payloadFileName.isNullOrBlank()) {
            val payloadFile = File(cacheDir, payloadFileName)
            val payload = runCatching {
                require(payloadFile.name == payloadFileName && payloadFileName.startsWith("agent_payload_") && payloadFileName.endsWith(".json"))
                gson.fromJson(payloadFile.readText(), AgentRunPayload::class.java)
            }.getOrNull()
            payloadFile.delete()
            if (payload == null) {
                AppLogger.e("AgentService", "run aborted: payload file was missing or invalid for session=$sessionId")
                clearPendingPromptMarker(sessionId)
                sendEvent(sessionId, "error", "Run payload unavailable.", null, "error")
                return
            }
            userText = payload.userText
            history = runCatching { gson.fromJson<List<JsonObject>>(payload.historyJson, historyType)?.toMutableList() ?: mutableListOf() }.getOrElse { mutableListOf() }
            messages = runCatching { gson.fromJson<List<ChatMessage>>(payload.messagesJson, messageType)?.toMutableList() ?: mutableListOf() }.getOrElse { mutableListOf() }
        } else {
            history = runCatching { gson.fromJson<List<JsonObject>>(intent.getStringExtra(EXTRA_HISTORY_JSON).orEmpty(), historyType)?.toMutableList() ?: mutableListOf() }.getOrElse { mutableListOf() }
            messages = runCatching { gson.fromJson<List<ChatMessage>>(intent.getStringExtra(EXTRA_MESSAGES_JSON).orEmpty(), messageType)?.toMutableList() ?: mutableListOf() }.getOrElse { mutableListOf() }
        }
        if (intent.getBooleanExtra(EXTRA_FROM_DB, false)) {
            val row = runCatching { AgentDatabase.get(this).sessions().get(sessionId) }.getOrNull()
            if (row == null) {
                AppLogger.e("AgentService", "run aborted: viaDb session=$sessionId has no DB row to reload the payload from")
                clearPendingPromptMarker(sessionId)
                sendEvent(sessionId, "error", "Run aborted: session data unavailable.", null, "error")
                return
            }
            title = row.title.ifBlank { title }
            createdAt = row.createdAt
            history = runCatching { gson.fromJson<List<JsonObject>>(row.historyJson, historyType)?.toMutableList() ?: mutableListOf() }.getOrElse { mutableListOf() }
            messages = runCatching { gson.fromJson<List<ChatMessage>>(row.messagesJson, messageType)?.toMutableList() ?: mutableListOf() }.getOrElse { mutableListOf() }
            if (userText.isBlank()) {
                // The prompt travels in the intent normally; only recover it
                // from the row when it was omitted (resume hand-off path).
                userText = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
            }
            // Safety (resume path): the row may carry a trailing copy of the
            // prompt inside its history — drop it so the re-dispatch does not
            // ask the model the same question twice inside its own context.
            // Trailing-only: an exact-content match EARLIER in the history is
            // a legitimate prior message and must be kept.
            if (history.lastOrNull()?.let {
                    it.get("role")?.takeUnless { element -> element.isJsonNull }?.asString == "user" &&
                        it.get("content")?.takeUnless { element -> element.isJsonNull }?.asString == userText
                } == true) {
                history.removeAt(history.lastIndex)
            }
        }
        var assistantText = ""
        val workStartedAt = messages.lastOrNull { it.kind == "work" && it.workFinishedAt == null }
            ?.workStartedAt ?: System.currentTimeMillis()
        val workspaceRoot = AgentConfig.workspaceRoot(this)
        val workspaceBefore = withContext(Dispatchers.IO) { WorkspaceChangeTracker.capture(workspaceRoot) }
        val activityItems = mutableListOf<ChatActivityItem>()
        fun recordProgress(text: String?) {
            val note = text.orEmpty().trim().removePrefix("[model]").trim().take(360)
            if (note.isBlank() || note.matches(Regex("""Agent step \d+"""))) return
            if (activityItems.lastOrNull()?.let { it.type == "progress" && it.title == note } != true) {
                activityItems += ChatActivityItem(type = "progress", title = note)
                while (activityItems.size > 48) activityItems.removeAt(0)
            }
        }
        fun recordToolStart(name: String?, description: String?) {
            val tool = name.orEmpty().substringAfterLast('/').substringAfterLast('.')
            if (tool.isBlank()) return
            activityItems += ChatActivityItem(
                type = "tool",
                title = tool,
                detail = description?.trim()?.take(360)?.takeIf { it.isNotBlank() },
                status = "running"
            )
            while (activityItems.size > 48) activityItems.removeAt(0)
        }
        fun recordToolCompletion(name: String?, ok: Boolean, detail: String?) {
            val tool = name.orEmpty().substringAfterLast('/').substringAfterLast('.')
            if (tool.isBlank()) return
            val index = activityItems.indexOfLast { it.type == "tool" && it.title == tool && it.status == "running" }
            val state = if (ok) "done" else "error"
            val safeDetail = detail?.trim()?.take(360)?.takeIf { it.isNotBlank() }
            if (index >= 0) {
                activityItems[index] = activityItems[index].copy(
                    status = state,
                    detail = safeDetail ?: activityItems[index].detail
                )
            } else {
                activityItems += ChatActivityItem(type = "tool", title = tool, status = state, detail = safeDetail)
                while (activityItems.size > 48) activityItems.removeAt(0)
            }
        }
        val permissions = PermissionBroker(PermissionManager(this))
        permissions.onApprovalRequired = { tool, required ->
            com.example.oreshik.agent.security.PermissionApprovalCoordinator.request(
                this@AgentExecutionService,
                sessionId,
                tool,
                required,
            )
        }
        val engine = AgentEngine(this, permissions = permissions)
        val pendingAssistantTextEvents = mutableListOf<String>()

        // Synchronous payload/database setup may not observe coroutine
        // cancellation until it returns, so check task identity before calling
        // the provider. A replacement run must not start stale work.
        if (task.state == AgentTaskManager.State.CANCELLING || runningTasks[sessionId] !== task) {
            throw kotlinx.coroutines.CancellationException("Agent run was cancelled or replaced")
        }

        // Use ProviderManager to get the active provider's API client
        val providerApiClient = com.example.oreshik.ai.ProviderManager.createActiveApiClient(runMode, offlineModelPath)
        val activeProvider = com.example.oreshik.ai.ProviderManager.getExecutionProvider(runMode)
        AppLogger.i("AgentService", "run: session=$sessionId provider=${activeProvider.id} model=${intent.getStringExtra(EXTRA_MODEL).orEmpty().ifBlank { "(unset)" }} reasoning=${intent.getStringExtra(EXTRA_REASONING).orEmpty()} clientCreated=${providerApiClient != null} historySize=${history.size}")
        
        val emit: suspend (com.example.oreshik.agent.core.AgentEngine.Event) -> Unit = emit@ { event ->
            // Check cancellation before emitting each event
            if (task.state == AgentTaskManager.State.CANCELLING || runningTasks[sessionId] !== task) return@emit
            // AgentEngine emits ERROR immediately before returning Result.failure.
            // The terminal-result handler below owns the user-visible error event;
            // forwarding this intermediate event too produced duplicate error
            // messages and duplicate MainActivity error logs for a single run.
            if (event.type == com.example.oreshik.agent.core.AgentEngine.Event.Type.ERROR) return@emit
            val type = when (event.type) {
                com.example.oreshik.agent.core.AgentEngine.Event.Type.THINKING -> "thinking"
                com.example.oreshik.agent.core.AgentEngine.Event.Type.TOOL_STARTED -> "tool_started"
                com.example.oreshik.agent.core.AgentEngine.Event.Type.TOOL_COMPLETED -> "tool_completed"
                com.example.oreshik.agent.core.AgentEngine.Event.Type.TEXT -> "text"
                com.example.oreshik.agent.core.AgentEngine.Event.Type.ERROR -> "error"
            }
            when (event.type) {
                com.example.oreshik.agent.core.AgentEngine.Event.Type.THINKING -> recordProgress(event.text)
                com.example.oreshik.agent.core.AgentEngine.Event.Type.TOOL_STARTED -> recordToolStart(event.name, event.text)
                com.example.oreshik.agent.core.AgentEngine.Event.Type.TOOL_COMPLETED -> recordToolCompletion(event.name, event.result?.ok == true, event.result?.error)
                else -> Unit
            }
            if (event.type == com.example.oreshik.agent.core.AgentEngine.Event.Type.TEXT) {
                val chunk = event.text.orEmpty()
                // Guard against stacked duplicates: drop a TEXT chunk identical to what
                // was just accumulated (the engine now only emits the final answer, but
                // this keeps the "repeated response" bug from ever resurfacing via any
                // other path).
                if (chunk.isNotEmpty() && assistantText.endsWith(chunk)) return@emit
                assistantText += chunk
                if (chunk.isNotEmpty()) pendingAssistantTextEvents += chunk
                // Delay the final answer event until the workspace snapshot has
                // produced the file-change card, so that card stays in the same
                // work row immediately before the assistant response.
                return@emit
            }
            val metadataJson = event.result?.metadata?.let { gson.toJson(it) }
            val eventText = event.text ?: event.result?.error
            sendEvent(sessionId, type, eventText, event.name, if (event.result?.ok == true) "done" else "error", metadataJson, runModel)
        }
        
        // activeProvider is non-null by contract (ProviderManager.getActiveProvider());
        // only the client can be missing (no API key configured).
        val result: Result<String> = try {
            val runAttachments = messages.lastOrNull { it.role == "user" }?.attachments.orEmpty()
            if (providerApiClient != null) {
                // Use the new multi-provider system
                engine.runWithProvider(
                    userText,
                    history,
                    activeProvider,
                    providerApiClient,
                    intent.getStringExtra(EXTRA_MODEL).orEmpty(),
                    intent.getStringExtra(EXTRA_REASONING).orEmpty(),
                    attachments = runAttachments,
                    sessionId = sessionId,
                    emit = emit
                )
            } else {
                // Fallback to legacy single-provider mode
                engine.run(
                    userText,
                    history,
                    intent.getStringExtra(EXTRA_BASE_URL).orEmpty(),
                    intent.getStringExtra(EXTRA_API_KEY).orEmpty(),
                    intent.getStringExtra(EXTRA_MODEL).orEmpty(),
                    intent.getStringExtra(EXTRA_REASONING).orEmpty(),
                    attachments = runAttachments,
                    sessionId = sessionId,
                    emit = emit
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            Result.failure(cancelled)
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        val workspaceAfter = withContext(NonCancellable + Dispatchers.IO) {
            WorkspaceChangeTracker.capture(workspaceRoot)
        }
        val fileChanges = WorkspaceChangeTracker.compare(workspaceBefore, workspaceAfter)
        val currentRun = runningTasks[sessionId] === task
        if (fileChanges.isNotEmpty()) {
            activityItems += ChatActivityItem(
                type = "changes",
                title = "${fileChanges.size} file${if (fileChanges.size == 1) "" else "s"} changed",
                status = "done",
                expanded = true,
                files = fileChanges,
            )
            while (activityItems.size > 48) activityItems.removeAt(0)
            if (currentRun) AgentEventBus.send(AgentEvent.FileChanges(sessionId, fileChanges))
        }
        if (currentRun) {
            pendingAssistantTextEvents.forEach { chunk ->
                sendEvent(sessionId, "text", chunk, null, "done", model = runModel)
            }
        }
        val error = result.exceptionOrNull()
        val cancelled = task.state == AgentTaskManager.State.CANCELLING || runningTasks[sessionId] !== task || error is kotlinx.coroutines.CancellationException
        if (error != null && !cancelled) {
            AppLogger.e("AgentService", "run FAILED: session=$sessionId provider=${activeProvider.id} model=${intent.getStringExtra(EXTRA_MODEL).orEmpty()}: ${error.javaClass.simpleName}: ${error.message?.take(300)}", error)
            // Mark terminal BEFORE the UI event: MainActivity's Error/Completed handlers
            // reload the session from the DB and skip while a task still looks active.
            AgentTaskManager.markTerminal(task, AgentTaskManager.State.FAILED)
            clearPendingPromptMarker(sessionId, userText)
            persist(sessionId, title, createdAt, sessionModel, history, messages, assistantText + "\n\nError: " + (error.message ?: "Agent failed"), activityItems, workStartedAt, "error")
            sendEvent(sessionId, "error", error.message ?: "Agent failed", null, "error")
            notifyResponse(sessionId, title, "Agent failed: ${error.message ?: "Unknown error"}")
        } else {
            if (cancelled) {
                AppLogger.i("AgentService", "run CANCELLED: session=$sessionId provider=${activeProvider.id} model=${intent.getStringExtra(EXTRA_MODEL).orEmpty()}")
                withContext(NonCancellable) {
                    AgentTaskManager.markTerminal(task, AgentTaskManager.State.CANCELLED)
                    // A replaced run must not clear the new run's recovery marker
                    // or reset its UI. Only the still-current run represents an
                    // explicit stop/cancellation for this session.
                    if (runningTasks[sessionId] === task) {
                        clearPendingPromptMarker(sessionId, userText)
                        persist(sessionId, title, createdAt, sessionModel, history, messages, assistantText, activityItems, workStartedAt, "cancelled")
                        sendEvent(sessionId, "cancelled", null, null, "done")
                    }
                }
            } else {
                AppLogger.i("AgentService", "run COMPLETED: session=$sessionId provider=${activeProvider.id} model=${intent.getStringExtra(EXTRA_MODEL).orEmpty()} responseChars=${assistantText.length}")
                // Mark terminal BEFORE the completed event: the UI's Completed handler
                // reloads the session from the DB but loadSession() bails out while
                // AgentTaskManager.isActive() is still true — marking first is what
                // makes the post-run reload (and history adoption for the next turn)
                // actually happen instead of silently keeping historySize=0.
                AgentTaskManager.markTerminal(task, AgentTaskManager.State.COMPLETED)
                persist(sessionId, title, createdAt, sessionModel, history, messages, assistantText, activityItems, workStartedAt, "done")
                clearPendingPromptMarker(sessionId, userText)
                sendEvent(sessionId, "completed", null, null, "done")
                notifyResponse(sessionId, title, if (assistantText.isBlank()) "The model finished processing." else assistantText.take(180))
            }
        }
    }

    /**
     * Retires the pending-prompt marker from the settings DB. Called by the
     * SERVICE at every terminal state (completed / failed / cancelled /
     * pre-start cancellation) — the service is process-wide and cannot miss
     * the event, unlike the activity's lifecycle-gated event collector whose
     * Completed event is silently dropped while the app is backgrounded.
     * Without this, a stale marker made every subsequent app open re-send the
     * last prompt even though the model had already replied.
     */
    private fun clearPendingPromptMarker(sessionId: String, prompt: String? = null) {
        runCatching {
            val db = AgentDatabase.get(this).settings()
            val scopedKey = SETTING_PENDING_PREFIX + sessionId
            val scopedPrompt = db.get(scopedKey)?.value
            if (scopedPrompt != null && (prompt == null || scopedPrompt == prompt)) {
                db.remove(scopedKey)
            }
            // Retire markers written by older app versions during migration.
            if (db.get(SETTING_PENDING_SESSION)?.value == sessionId &&
                (prompt == null || db.get(SETTING_PENDING_TEXT)?.value == prompt)
            ) {
                db.remove(SETTING_PENDING_SESSION)
                db.remove(SETTING_PENDING_TEXT)
            }
        }
    }

    private suspend fun persist(
        sessionId: String,
        title: String,
        createdAt: Long,
        model: String,
        history: MutableList<JsonObject>,
        messages: MutableList<ChatMessage>,
        assistantText: String,
        activityItems: List<ChatActivityItem>,
        workStartedAt: Long,
        terminalStatus: String
    ) = withContext(Dispatchers.IO) {
        if (!AgentConfig.saveHistory) return@withContext
        // Skip persisting for sessions that were deleted while this run was
        // in flight — SessionDao.put is an upsert (REPLACE), so writing here
        // would resurrect the just-deleted row ("delete didn't work" bug).
        if (AgentDatabase.get(this@AgentExecutionService).sessions().get(sessionId) == null) {
            AppLogger.i("AgentService", "persist skipped: session=$sessionId was deleted mid-run")
            return@withContext
        }
        val activityExpanded = messages.lastOrNull {
            it.kind == "work" && it.workStartedAt == workStartedAt
        }?.activityExpanded ?: true
        messages.removeAll {
            (it.kind != "text" && it.kind != "work") ||
                (it.kind == "work" && it.workFinishedAt == null && it.workStartedAt == workStartedAt)
        }
        val terminalAt = System.currentTimeMillis()
        val finishedActivities = activityItems.map { item ->
            when {
                item.type == "tool" && item.status == "running" -> item.copy(
                    status = if (terminalStatus == "done") "done" else "error",
                    detail = item.detail ?: terminalStatus.replaceFirstChar { it.uppercase() },
                )
                item.type == "progress" && item.status == "running" -> item.copy(
                    status = if (terminalStatus == "done") "done" else "error",
                )
                else -> item
            }
        }
        messages.add(
            ChatMessage(
                role = "assistant",
                content = finishedActivities.lastOrNull { it.type == "progress" }?.title ?: "Provider activity",
                timestamp = workStartedAt,
                kind = "work",
                toolStatus = terminalStatus,
                workStartedAt = workStartedAt,
                workFinishedAt = terminalAt,
                activityItems = finishedActivities,
                activityExpanded = activityExpanded
            )
        )
        if (assistantText.isNotBlank()) messages.add(ChatMessage(role = "assistant", content = assistantText.trim(), model = model))
        val now = System.currentTimeMillis()
        val summary = history.firstOrNull { it.get("role")?.asString == "system" && it.get("content")?.asString?.startsWith("Conversation context summary") == true }?.get("content")?.asString
        AgentDatabase.get(this@AgentExecutionService).sessions().put(SessionEntity(sessionId, title, model, createdAt, now, gson.toJson(messages), gson.toJson(history), summary, if (summary != null) now else null, messages.size))
    }

    private fun sendEvent(sessionId: String, type: String, text: String?, name: String?, status: String?, metadataJson: String? = null, model: String? = null) {
        // Limit metadata size to prevent TransactionTooLargeException (Binder limit ~1MB)
        // Use 500KB as safe threshold for metadata
        val MAX_METADATA_SIZE = 500 * 1024
        val filteredMetadataJson = if (metadataJson != null && metadataJson.length > MAX_METADATA_SIZE) {
            // Truncate large metadata - keep only essential fields
            try {
                val json = com.google.gson.JsonParser.parseString(metadataJson).asJsonObject
                val filtered = com.google.gson.JsonObject()
                // Keep only small fields, exclude large ones like 'result', 'html', 'preview_base64', etc.
                json.entrySet().forEach { entry ->
                    val value = entry.value
                    val valueStr = value.toString()
                    if (valueStr.length < 10000) { // Keep fields under 10KB
                        filtered.add(entry.key, value)
                    } else {
                        filtered.addProperty(entry.key, "[TRUNCATED: ${valueStr.length} chars]")
                    }
                }
                gson.toJson(filtered)
            } catch (e: Exception) {
                "[METADATA_TOO_LARGE: ${metadataJson.length} chars]"
            }
        } else {
            metadataJson
        }
        
        AgentEventBus.send(
            when (type) {
                "thinking" -> AgentEvent.Thinking(sessionId, text.orEmpty().ifBlank { "Thinking…" })
                "tool_started" -> AgentEvent.ToolStarted(sessionId, name.orEmpty(), text.orEmpty().ifBlank { "Running…" })
                "tool_completed" -> AgentEvent.ToolCompleted(
                    sessionId,
                    name.orEmpty(),
                    text.orEmpty(),
                    status ?: "done",
                    filteredMetadataJson ?: ""
                )
                "text" -> AgentEvent.Text(sessionId, text.orEmpty(), model?.takeIf { it.isNotBlank() })
                "completed" -> AgentEvent.Completed(sessionId)
                "cancelled" -> AgentEvent.Cancelled(sessionId)
                "error" -> AgentEvent.Error(sessionId, text.orEmpty().ifBlank { "Unknown error" })
                else -> return
            }
        )
        if (sessionId == foregroundSessionId) {
            val nextStatus = when (type) {
                "thinking" -> text.orEmpty().ifBlank { "Thinking…" }
                "tool_started" -> text.orEmpty().ifBlank { "Running ${name.orEmpty().ifBlank { "tool" }}…" }
                "tool_completed" -> "Thinking…"
                "text" -> "Writing the response…"
                "completed" -> "Response ready"
                "cancelled" -> "Chat run stopped"
                "error" -> "Agent run failed"
                else -> foregroundStatus
            }
            if (nextStatus != foregroundStatus) {
                foregroundStatus = nextStatus
                foregroundSessionTitle = ChatNotifications.currentTitle(this, sessionId, sessionTitles[sessionId].orEmpty())
                runCatching {
                    getSystemService(NotificationManager::class.java)?.notify(
                        FOREGROUND_ID,
                        buildWorkingNotification(sessionId, foregroundSessionTitle, foregroundModel, nextStatus),
                    )
                }
            }
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "Agent responses", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Notifications when the coding agent finishes a model response." })
    }

    private fun setForegroundSession(sessionId: String?, title: String, model: String, status: String) {
        foregroundSessionId = sessionId
        foregroundSessionTitle = title
        foregroundModel = model
        foregroundStatus = status
        if (foregroundStarted) {
            runCatching {
                getSystemService(NotificationManager::class.java)?.notify(
                    FOREGROUND_ID,
                    buildWorkingNotification(sessionId, title, model, status),
                )
            }
        }
    }

    private fun buildWorkingNotification(sessionId: String?, title: String, model: String, status: String): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(status)
            .setSubText("Oreshik Agent • ${model.substringAfterLast('/').ifBlank { "selected model" }}")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (!sessionId.isNullOrBlank()) {
            val notificationIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                data = "oreshik://chat/$sessionId".toUri()
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            val pi = PendingIntent.getActivity(
                this,
                sessionId.hashCode(),
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(pi)
        }
        return builder.build()
    }

    private fun notifyResponse(sessionId: String, title: String, body: String) {
        ChatNotifications.postResponse(
            this,
            sessionId,
            ChatNotifications.currentTitle(this, sessionId, title),
            body,
        )
    }

    override fun onDestroy() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
