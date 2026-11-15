package com.example.oreshik.data

import android.content.Context
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Persistent, long-term vector memory access. All writes go through this class so the UI, tools
 * and the autonomous learner share the same validation/deduplication rules. Every memory is
 * embedded at write time and recalled via hybrid vector (cosine) + keyword scoring, boosted by an
 * importance weight that is reinforced every time a memory is recalled or re-stated. Memories
 * survive app restarts. Retrieval is session-scoped by default; cross-session recall is optional. */
class MemoryStore(context: Context) {
    private val db = AgentDatabase.get(context)

    suspend fun active(): List<MemoryEntity> = withContext(Dispatchers.IO) {
        if (AgentConfig.memoryEnabled) db.memories().active() else emptyList()
    }

    suspend fun activeForSession(sessionId: String): List<MemoryEntity> = withContext(Dispatchers.IO) {
        if (!AgentConfig.memoryEnabled) return@withContext emptyList()
        val memories = if (sessionId.isBlank()) emptyList() else db.memories().activeForSession(sessionId)
        (memories + db.memories().activeGlobalManual()).distinctBy { it.id }
    }

    /**
     * Hybrid long-term recall: vector cosine similarity ranks by MEANING, keyword score
     * ranks by EXACT terms; the max of the two normalized signals wins, then the memory's
     * importance [MemoryEntity.weight] (reinforced on every recall) tips close races.
     * Memories without an embedding (legacy rows) fall back to keyword ranking only.
     *
     * Returned memories are reinforced (accessCount+1) so frequently useful facts keep
     * floating to the top of future recalls — the store literally learns what matters.
     */
    suspend fun search(
        query: String,
        limit: Int = 12,
        sessionId: String = "",
        includeOtherSessions: Boolean = false,
    ): List<MemoryEntity> = withContext(Dispatchers.IO) {
        if (!AgentConfig.memoryEnabled) return@withContext emptyList()
        val all = if (includeOtherSessions) {
            db.memories().active()
        } else {
            val scoped = if (sessionId.isBlank()) emptyList() else db.memories().activeForSession(sessionId)
            (scoped + db.memories().activeGlobalManual()).distinctBy { it.id }
        }
        if (all.isEmpty()) return@withContext emptyList()

        val queryVec = EmbeddingEngine.embed(query)
        val keywordScores = rankScores(all, query)

        val scoredPairs = all.map { memory ->
            val vectorScore = EmbeddingEngine.decode(memory.embedding)
                ?.let { vec -> EmbeddingEngine.cosineSimilarity(queryVec, vec) }
                ?: 0f
            // vectorScore ∈ [0,1]; keywordScore normalized to [0,1] inside rankScores.
            val combined = maxOf(vectorScore, keywordScores[memory.id] ?: 0f) * memory.weight.coerceIn(1f, 3f)
            memory to combined
        }
        val scored = scoredPairs
            .filter { pair -> pair.second > 0.02f }
            .sortedByDescending { pair -> pair.second }
            .take(limit)
            .map { pair -> pair.first }

        // Reinforcement loop: memories that proved useful (were recalled) become easier to
        // recall next time. Fire-and-forget — never blocks the caller on the UPDATE.
        if (scored.isNotEmpty()) {
            val now = System.currentTimeMillis()
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                runCatching { db.memories().touch(scored.map { it.id }, now) }
            }
        }

        AppLogger.d("MemoryStore", "hybrid recall: ${scored.size} hits for '${query.take(40)}' (of ${all.size} memories)")
        scored
    }

    /**
     * Stores a permanent long-term memory. Accepts the memory kind ('fact' = durable user/project
     * fact, 'session_digest' = summary of a past conversation, 'note' = free-form), the source
     * ('manual' UI, 'tool' memory.save, 'auto' autonomous learner) and the originating session.
     *
     * Exact duplicates update lastAccessed; SEMANTIC near-duplicates (cosine ≥ 0.97) instead
     * REINFORCE the existing memory (weight +0.1 up to 3.0) — re-stating a fact makes it
     * stronger instead of polluting the store with copies.
     */
    suspend fun add(
        text: String,
        kind: String = "fact",
        source: String = "manual",
        sessionId: String = "",
    ): Result<Long> = withContext(Dispatchers.IO) {
        if (!AgentConfig.memoryEnabled) return@withContext Result.failure(IllegalStateException("Memory is disabled in Settings."))
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        validate(cleaned)?.let { return@withContext Result.failure(IllegalArgumentException(it)) }
        val normalized = normalize(cleaned)
        // Deduplicate only within the destination session (plus user-authored
        // global memories). A fact in another chat must not silently become the
        // owner of this session's memory when cross-session recall is disabled.
        val active = if (sessionId.isBlank()) {
            db.memories().activeGlobalManual()
        } else {
            (db.memories().activeForSession(sessionId) + db.memories().activeGlobalManual()).distinctBy { it.id }
        }
        val embedding = EmbeddingEngine.embed(cleaned)
        val now = System.currentTimeMillis()

        // 1) Exact (normalized) duplicate → just refresh access time, keep one row.
        active.firstOrNull { normalize(it.text) == normalized }?.let { exact ->
            runCatching { db.memories().reinforce(exact.id, 0.1f, now) }
            return@withContext Result.success(exact.id)
        }
        // 2) Semantic near-duplicate → reinforce instead of inserting a copy.
        active.firstOrNull { existing ->
            val existingVec = EmbeddingEngine.decode(existing.embedding) ?: return@firstOrNull false
            EmbeddingEngine.cosineSimilarity(embedding, existingVec) >= DUPLICATE_COSINE
        }?.let { near ->
            runCatching { db.memories().reinforce(near.id, 0.1f, now) }
            AppLogger.d(TAG, "dedup: reinforced #${near.id} instead of storing near-duplicate")
            return@withContext Result.success(near.id)
        }

        // 3) Genuinely new fact → insert permanently with its vector embedding.
        val newId = db.memories().insert(
            MemoryEntity(
                text = cleaned,
                embedding = EmbeddingEngine.encode(embedding),
                kind = kind,
                source = source,
                sessionId = sessionId,
                lastAccessedAt = now,
            ),
        )
        Result.success(newId)
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { db.memories().delete(id) }
    suspend fun deleteForSession(id: Long, sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val deletedFromSession = if (sessionId.isBlank()) 0 else db.memories().deleteOwned(id, sessionId)
        (deletedFromSession > 0) || (db.memories().deleteGlobalManual(id) > 0)
    }
    suspend fun clear() = withContext(Dispatchers.IO) { db.memories().clear() }

    companion object {
        private const val TAG = "MemoryStore"

        /** Cosine similarity above which a new text is considered a re-statement of an existing memory. */
        private const val DUPLICATE_COSINE = 0.97f

        private val secretPatterns = listOf(
            Regex("(?i)\\b(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|passwd|secret|private[_ -]?key|client[_ -]?secret|bearer)\\b\\s*(?:[:=]|is)\\s*\\S+"),
            Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{12,}"),
            Regex("(?i)\\b(?:sk|pk)[-_][A-Za-z0-9_-]{16,}\\b"),
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        )

        fun normalize(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")

        fun validate(text: String): String? {
            if (text.isBlank()) return "Memory text is empty"
            if (text.length > 2000) return "Memory is too long (maximum 2000 characters)."
            if (secretPatterns.any { it.containsMatchIn(text) }) return "Refused to store credentials or secrets in persistent memory."
            return null
        }

        /**
         * Keyword scoring (previously `rank`). Returns id → score in [0,1].
         * Word-boundary matches weigh more than substrings; longer tokens count more.
         */
        fun rankScores(memories: List<MemoryEntity>, query: String): Map<Long, Float> {
            if (memories.isEmpty()) return emptyMap()
            val tokens = tokenize(query)
            if (tokens.isEmpty()) {
                val fallback = LinkedHashMap<Long, Float>(memories.size)
                for (i in memories.indices) {
                    fallback[memories[i].id] = 0.05f
                }
                return fallback // recency fallback
            }
            val q = normalize(query)
            val scores = HashMap<Long, Float>()
            for (i in memories.indices) {
                val memory = memories[i]
                val text = normalize(memory.text)
                var score = 0f
                if (text == q) score += 1.0f
                else if (text.contains(q)) score += 0.6f
                var wordBoundaryHits = 0
                tokens.forEach { token ->
                    val regex = Regex("\\b${Regex.escape(token)}")
                    if (regex.containsMatchIn(text)) {
                        wordBoundaryHits++
                        score += if (token.length >= 5) 0.18f else 0.09f
                    } else if (text.contains(token)) {
                        score += 0.04f
                    }
                }
                if ((tokens.isNotEmpty()) && (wordBoundaryHits == tokens.size)) score += 0.2f
                // Mild recency boost
                score += ((memory.createdAt / 1_000_000_000.0).toFloat()) * 0.000002f
                if (score > 0f) scores[memory.id] = score.coerceAtMost(1f)
            }
            return scores
        }

        /** Pure keyword ranking (kept for compatibility). */
        @Suppress("unused")
        fun rank(memories: List<MemoryEntity>, query: String, limit: Int): List<MemoryEntity> {
            if (memories.isEmpty()) return emptyList()
            val scores = rankScores(memories, query)
            return memories.asSequence()
                .filter { memory -> (scores[memory.id] ?: 0f) > 0f }
                .sortedByDescending { memory -> scores[memory.id] ?: 0f }
                .take(limit)
                .toList()
        }

        /** Pure vector similarity search using cosine similarity (≥0.5 = related). */
        @Suppress("unused")
        fun vectorSearch(memories: List<MemoryEntity>, queryEmbedding: FloatArray, limit: Int): List<MemoryEntity> {
            if (memories.isEmpty()) return emptyList()
            return memories.asSequence()
                .mapNotNull { memory ->
                    val vec = EmbeddingEngine.decode(memory.embedding) ?: return@mapNotNull null
                    memory to EmbeddingEngine.cosineSimilarity(queryEmbedding, vec)
                }
                .filter { it.second > 0.5f }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
                .toList()
        }

        private fun tokenize(text: String): Set<String> = normalize(text)
            .split(Regex("[^a-z0-9_+#.-]+"))
            .filter { it.length >= 2 }
            .toSet()
    }
}
