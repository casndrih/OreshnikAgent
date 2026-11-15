package com.example.oreshik.agent.memory

import android.content.Context
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.core.arrayOrNull
import com.example.oreshik.data.AgentDatabase
import com.example.oreshik.data.MemoryEventEntity
import com.example.oreshik.data.MemoryStore
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Autonomous long-term learning engine.
 *
 * Fired after every completed agent run, it permanently stores durable knowledge from the
 * conversation into the vector memory so it is available in that chat by default (and other
 * conversations only when cross-session context is enabled in Settings):
 *
 * 1. **Explicit capture** (cheap, deterministic, no LLM call): user messages that clearly ask the
 *    assistant to remember something ("remember that…", "keep in mind…", "note that…",
 *    "don't forget…", "save this…") are distilled into a permanent fact memory immediately.
 *
 * 2. **Periodic LLM distillation**: every configured number of exchanges it asks the active model
 *    to extract durable facts from recent session context and stores them permanently (kind='fact',
 *    source='auto') so future conversations can use that knowledge.
 *
 * Design constraints:
 * - Runs in its own [SupervisorJob] scope on Dispatchers.IO — never delays or fails the reply.
 * - Single-flight per session (the ConcurrentHashMap guards concurrent invocations).
 * - Honors [AgentConfig.memoryEnabled], [AgentConfig.memoryAutoLearn] and the per-session
 *   "don't remember this chat" flag (`no_learn_<sessionId>` in the settings table).
 * - Every write goes through [MemoryStore.add] so secrets/credentials are rejected by the
 *   same validation the user-facing flows use.
 */
object SessionLearner {
    private const val TAG = "SessionLearner"
    private const val MAX_FACTS_PER_PASS = 5
    private const val MAX_TRANSCRIPT_CHARS = 6000

    /** Captures the remainder of natural phrasing such as "remember that I prefer …". */
    private val explicitRememberRegex = Regex(
        "(?is)^\\s*(?:hey[\\s,]*)?(?:please[\\s,]+)?(?:assistant[\\s,]+)?" +
            "(?:remember(?:\\s+(?:that|this))?|keep\\s+in\\s+mind(?:\\s+that)?|note\\s+that|don'?t\\s+forget(?:\\s+(?:that|this))?|save\\s+(?:this|that))" +
            "(?:\\s*[:,]\\s*|\\s+)(.+)$",
    )

    /** Distillation request persona: strict JSON array output, durable facts only. */
    private const val DISTILL_SYSTEM_PROMPT =
        "You extract durable, reusable facts from recent exchanges in an ongoing conversation with a " +
        "user. Return STRICT JSON only: a JSON array of short third-person fact strings, each a " +
        "standalone declarative sentence, no commentary, no markdown fences. Store only durable " +
        "knowledge: stable user preferences, identity facts, project structure, tooling, where " +
        "credentials live (never the secret itself), ongoing goals, corrections to earlier facts. " +
        "Do NOT store transient chit-chat, time-bound status, anything obvious from the current " +
        "message alone, secrets, API keys, passwords or tokens. Return [] when nothing durable " +
        "was learned."

    /** Single-flight guard: one distillation pass at a time per session. */
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    /** Own scope: learning outlives the request that triggered it, failures never propagate. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Entry point — fire-and-forget. Called by [com.example.oreshik.agent.core.AgentEngine] after a
     * successful run. [sessionId] groups exchanges; 'voice' / 'voice_activity' are special ids
     * used by the spoken interfaces.
     */
    fun onExchangeAsync(
        context: Context,
        sessionId: String,
        userText: String,
        assistantText: String,
        force: Boolean = false,
        modelMode: String? = null,
        offlineModelPath: String? = null,
    ) {
        val ctx = context.applicationContext
        scope.launch {
            runCatching { processExchange(ctx, sessionId, userText, assistantText, force, modelMode, offlineModelPath) }
                .onFailure { AppLogger.w(TAG, "learning pass failed (session=$sessionId): ${it.message}") }
        }
    }

    /** Suspend body — also callable directly from tests. */
    suspend fun processExchange(
        context: Context,
        sessionId: String,
        userText: String,
        assistantText: String,
        force: Boolean = false,
        modelMode: String? = null,
        offlineModelPath: String? = null,
    ) {
        if (sessionId.isBlank()) return
        if (!AgentConfig.memoryEnabled) return
        if (userText.isBlank() && assistantText.isBlank()) return
        val db = AgentDatabase.get(context)

        // Per-session privacy escape hatch ("Don't remember this chat" in the session menu).
        val noLearn = runCatching {
            db.settings().get("no_learn_$sessionId")?.value?.toBoolean() ?: false
        }.getOrDefault(defaultValue = false)
        if (noLearn && !force) {
            AppLogger.d(TAG, "session=$sessionId is marked no-learn — skipping")
            return
        }

        // 1) Explicit "remember that…" capture — cheap, deterministic, no LLM needed.
        explicitRememberRegex.find(userText)?.let { match ->
            val fact = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (fact.isNotBlank()) {
                val result = MemoryStore(context).add(
                    text = fact,
                    kind = "fact",
                    source = "auto",
                    sessionId = sessionId,
                )
                AppLogger.i(
                    TAG,
                    "explicit capture (session=$sessionId): ${if (result.isSuccess) "stored" else "rejected"}",
                )
            }
        }

        // 2) Periodic LLM distillation — use the configured exchange interval, single-flight.
        maybeDistill(context, sessionId, userText, assistantText, force, modelMode, offlineModelPath)
    }

    private suspend fun maybeDistill(
        context: Context,
        sessionId: String,
        userText: String,
        assistantText: String,
        force: Boolean,
        modelMode: String?,
        offlineModelPath: String?,
    ) {
        if (!AgentConfig.memoryAutoLearn && !force) return

        val db = AgentDatabase.get(context)
        val previous = runCatching { db.memories().getEvent(sessionId) }.getOrElse {
            AppLogger.w(TAG, "could not read learning progress (session=$sessionId): ${it.message}")
            return
        }
        val exchangeCount = (previous?.exchangeCount ?: 0) + 1
        val interval = AgentConfig.memoryLearnEvery.coerceIn(1, 10)
        if (!force && exchangeCount % interval != 0) {
            runCatching {
                db.memories().insertEvent(
                    MemoryEventEntity(
                        id = previous?.id ?: 0,
                        sessionId = sessionId,
                        exchangeCount = exchangeCount,
                        factsLearned = previous?.factsLearned ?: 0,
                        createdAt = previous?.createdAt ?: System.currentTimeMillis(),
                    ),
                )
            }.onFailure { AppLogger.w(TAG, "could not save learning progress (session=$sessionId): ${it.message}") }
            return
        }
        // Single-flight per session: putIfAbsent returns null when we won the slot.
        if (inFlight.putIfAbsent(sessionId, true) != null) return

        try {
            val facts = distillFacts(context, sessionId, userText, assistantText, modelMode, offlineModelPath) ?: return
            var learned = 0
            if (facts.isNotEmpty()) {
                val store = MemoryStore(context)
                for (fact in facts.take(MAX_FACTS_PER_PASS)) {
                    store.add(text = fact, kind = "fact", source = "auto", sessionId = sessionId)
                        .onSuccess { learned++ }
                        .onFailure { AppLogger.w(TAG, "fact rejected: ${it.message}") }
                }
            }
            // Preserve progress and cumulative successful fact count for later passes.
            runCatching {
                db.memories().insertEvent(
                    MemoryEventEntity(
                        id = previous?.id ?: 0,
                        sessionId = sessionId,
                        exchangeCount = exchangeCount,
                        factsLearned = (previous?.factsLearned ?: 0) + learned,
                        createdAt = previous?.createdAt ?: System.currentTimeMillis(),
                    ),
                )
            }
            AppLogger.i(TAG, "distill pass: $learned fact(s) stored permanently (session=$sessionId, exchanges=$exchangeCount)")
        } finally {
            inFlight.remove(sessionId)
        }
    }

    /**
     * One LLM call over recent session context. Returns extracted durable facts, an empty list
     * when the model found nothing durable, or null when the response is unusable (null = "don't
     * advance progress; retry on a later exchange").
     */
    private suspend fun distillFacts(
        context: Context,
        sessionId: String,
        userText: String,
        assistantText: String,
        modelMode: String?,
        offlineModelPath: String?,
    ): List<String>? {
        val client = com.example.oreshik.ai.ProviderManager.createActiveApiClient(modelMode, offlineModelPath) ?: return null
        val model = if (modelMode.equals("offline", ignoreCase = true)) {
            AgentConfig.offlineModelName.removeSuffix(".gguf").ifBlank { "offline-gguf" }
        } else AgentConfig.model.ifBlank { return null }
        val transcript = buildRecentTranscript(context, sessionId, userText, assistantText)
        if (transcript.length < 40) return null

        val messages = JsonObject().apply {
            add(
                "messages",
                JsonArray().apply {
                    add(message("system", DISTILL_SYSTEM_PROMPT))
                    add(message("user", "Recent exchanges from an ongoing conversation:\n$transcript"))
                },
            )
        }

        val raw = runCatching {
            val response = client.completeText(model, messages, "off").getOrThrow()
            response.arrayOrNull("choices")?.firstOrNull()?.asJsonObject
                ?.get("message")?.takeUnless { it.isJsonNull }?.asJsonObject
                ?.get("content")?.takeUnless { it.isJsonNull }?.asString
        }.getOrElse {
            AppLogger.w(TAG, "distill call failed: ${it.message?.take(150)}")
            return null
        } ?: return null

        val facts = parseFacts(raw) ?: return null
        if (facts.isEmpty()) AppLogger.d(TAG, "distill: model found no durable facts")
        return facts
    }

    /** Builds a bounded tail of the session, replacing any saved copy of the current exchange. */
    private fun buildRecentTranscript(context: Context, sessionId: String, userText: String, assistantText: String): String {
        val messages: MutableList<Pair<String, String>> = runCatching {
            val raw = AgentDatabase.get(context).sessions().get(sessionId)?.messagesJson.orEmpty()
            if (raw.isBlank()) return@runCatching emptyList()
            val array = JsonParser.parseString(raw).takeIf { it.isJsonArray }?.asJsonArray ?: return@runCatching emptyList()
            array.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                val role = obj.get("role")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
                val content = obj.get("content")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
                if (role == "user" || role == "assistant") role to content else null
            }
        }.getOrDefault(emptyList<Pair<String, String>>()).toMutableList()

        if (messages.lastOrNull() == ("user" to userText)) messages.removeAt(messages.lastIndex)
        if (assistantText.isNotBlank() && messages.lastOrNull() == ("assistant" to assistantText)) {
            messages.removeAt(messages.lastIndex)
        }
        messages.add("user" to userText)
        if (assistantText.isNotBlank()) messages.add("assistant" to assistantText)

        val maxMessages = (AgentConfig.memoryLearnEvery.coerceIn(1, 10) * 2 + 2).coerceAtMost(22)
        return messages.takeLast(maxMessages)
            .joinToString("\n\n") { (role, content) -> "${role.uppercase()}: $content" }
            .takeLast(MAX_TRANSCRIPT_CHARS)
    }

    /** Parses the model reply into clean fact strings; null means invalid/unusable JSON. */
    private fun parseFacts(raw: String): List<String>? {
        if (raw.isBlank()) return null
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching {
            val parsed = JsonParser.parseString(cleaned)
            if (!parsed.isJsonArray) return@runCatching null
            parsed.asJsonArray
                .mapNotNull { el -> (el as? com.google.gson.JsonPrimitive)?.takeIf { it.isString }?.asString }
                .asSequence()
                .map { fact -> fact.trim() }
                .filter { fact -> (fact.length >= 10) && (fact.length <= 500) }
                .toList()
        }.getOrNull()
    }

    private fun message(role: String, content: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }
}
