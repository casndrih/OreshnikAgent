package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.example.oreshik.agent.skills.SkillRepository
import com.example.oreshik.data.AgentDatabase
import com.example.oreshik.data.MemoryStore
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MemoryTools"

class MemorySearchTool(private val app: android.content.Context) : AgentTool {
    override val definition = ToolDefinition(
        name = "memory.search",
        category = ToolCategory.MEMORY,
        description = "Search memory by meaning/keywords. Searches this chat's memories and user-authored global memories by default; memories from other chats are included only when cross-session context is enabled in Settings.",
        permissions = setOf(ToolPermission.MEMORY),
        parameters = mapOf("query" to "string"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        if (!AgentConfig.memoryEnabled) return@withContext ToolResult(ok = false, error = "Memory is disabled in Settings.")
        val q = arguments.asJsonObject["query"]?.asString.orEmpty().trim()
        val hits = MemoryStore(app).search(
            query = q,
            limit = 20,
            sessionId = context.sessionId.orEmpty(),
            includeOtherSessions = AgentConfig.crossSessionContextEnabled,
        )
        val lines = ArrayList<String>(hits.size)
        for (i in hits.indices) {
            lines += "[${hits[i].id}] ${hits[i].text}"
        }
        AppLogger.d(TAG, "memory.search '$q' → ${hits.size} hit(s)")
        ToolResult(ok = true, lines.joinToString("\n").ifBlank { "No matching memories." })
    }
}

class MemorySaveTool(private val app: android.content.Context) : AgentTool {
    override val definition = ToolDefinition(
        name = "memory.save",
        category = ToolCategory.MEMORY,
        description = "Save a durable fact to this chat's memory (user preferences, project structure, tooling, ongoing goals). Other chats can recall it only when cross-session context is enabled in Settings. Never save passwords, API keys, tokens or sensitive secrets.",
        permissions = setOf(ToolPermission.MEMORY),
        parameters = mapOf("text" to "string"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        if (!AgentConfig.memoryEnabled) return@withContext ToolResult(ok = false, error = "Memory is disabled in Settings.")
        val text = arguments.asJsonObject["text"]?.asString?.trim().orEmpty()
        val result = MemoryStore(app).add(
            text = text,
            kind = "fact",
            source = "tool",
            sessionId = context.sessionId.orEmpty(),
        )
        val saved = result.getOrNull()
        if (saved != null) {
            AppLogger.i(TAG, "memory.save #$saved (${text.take(50)})")
            if (saved == 0L) {
                return@withContext ToolResult(ok = true, "Fact stored in this chat's memory.")
            }
            return@withContext ToolResult(ok = true, "Stored in this chat's memory as #$saved.")
        }
        val failure = result.exceptionOrNull()
        AppLogger.w(TAG, "memory.save failed: ${failure?.message}")
        return@withContext ToolResult(ok = false, error = failure?.message ?: "Unable to save memory")
    }
}

class MemoryDeleteTool(private val app: android.content.Context) : AgentTool {
    override val definition = ToolDefinition(
        name = "memory.delete",
        category = ToolCategory.MEMORY,
        description = "Delete a specific permanent long-term memory by its [#id] (ids are shown by memory.search and in the injected context).",
        permissions = setOf(ToolPermission.MEMORY),
        parameters = mapOf("id" to "integer"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement) = withContext(Dispatchers.IO) {
        val id = arguments.asJsonObject["id"]?.asLong
            ?: return@withContext ToolResult(ok = false, error = "Missing memory id")
        val deleted = if (AgentConfig.crossSessionContextEnabled) {
            AgentDatabase.get(app).memories().delete(id)
            true
        } else {
            MemoryStore(app).deleteForSession(id, context.sessionId.orEmpty())
        }
        if (!deleted) {
            return@withContext ToolResult(ok = false, error = "Memory #$id is not available in this chat. Enable cross-session context in Settings to manage memories from other chats.")
        }
        AppLogger.i(TAG, "memory.delete #$id")
        ToolResult(ok = true, "Deleted memory #$id")
    }
}

class MemoryStatsTool(private val app: android.content.Context) : AgentTool {
    override val definition = ToolDefinition(
        name = "memory.stats",
        category = ToolCategory.MEMORY,
        description = "Report memory statistics visible to this chat. When cross-session context is enabled in Settings, statistics include all chats.",
        permissions = setOf(ToolPermission.MEMORY),
        parameters = emptyMap(),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement) = withContext(Dispatchers.IO) {
        if (!AgentConfig.memoryEnabled) return@withContext ToolResult(ok = false, error = "Memory is disabled in Settings.")
        val all = if (AgentConfig.crossSessionContextEnabled) {
            AgentDatabase.get(app).memories().active()
        } else {
            MemoryStore(app).activeForSession(context.sessionId.orEmpty())
        }
        if (all.isEmpty()) return@withContext ToolResult(ok = true, "No memories are available to this chat.")
        val kindBuckets = HashMap<String, Int>()
        val sourceBuckets = HashMap<String, Int>()
        for (i in all.indices) {
            kindBuckets[all[i].kind] = (kindBuckets[all[i].kind] ?: 0) + 1
            sourceBuckets[all[i].source] = (sourceBuckets[all[i].source] ?: 0) + 1
        }
        val accessSorted = all.toMutableList()
        // Insertion sort by accessCount descending (small N; avoids lambda-destructuring hints).
        for (i in 1 until accessSorted.size) {
            val cur = accessSorted[i]
            var j = i - 1
            while ((j >= 0) && (accessSorted[j].accessCount < cur.accessCount)) {
                accessSorted[j + 1] = accessSorted[j]
                j--
            }
            accessSorted[j + 1] = cur
        }
        val mostAccessed = accessSorted.take(3)
        buildString {
            append("Long-term memory: ${all.size} item(s)")
            for ((kind, count) in kindBuckets) append("; $kind: $count")
            appendLine()
            for ((source, count) in sourceBuckets) append("source '$source': $count; ")
            appendLine()
            var hasRecalled = false
            for (i in mostAccessed.indices) {
                if (mostAccessed[i].accessCount > 0) {
                    hasRecalled = true
                    break
                }
            }
            if (hasRecalled) {
                append("Most-recalled: ")
                val recalled = ArrayList<com.example.oreshik.data.MemoryEntity>()
                for (i in mostAccessed.indices) {
                    if (mostAccessed[i].accessCount > 0) recalled += mostAccessed[i]
                }
                val parts = ArrayList<String>(recalled.size)
                for (i in recalled.indices) {
                    parts += "[#${recalled[i].id}] (recalled ${recalled[i].accessCount}x) ${recalled[i].text.take(60)}"
                }
                append(parts.joinToString("; "))
            }
        }.let { stats ->
            AppLogger.d(TAG, "memory.stats: ${all.size} item(s)")
            ToolResult(ok = true, stats)
        }
    }
}

class SkillsListTool(private val app: android.content.Context) : AgentTool {
    override val definition = ToolDefinition(
        name = "skills.list",
        category = ToolCategory.SKILLS,
        description = "List all agent skills (id, name, description, category, instruction size). " +
            "The index of ENABLED skills is already injected into every request; use this to see " +
            "disabled skills too, then activate one with skills.use.",
        permissions = setOf(ToolPermission.READ_WORKSPACE),
        parameters = emptyMap(),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement) = withContext(Dispatchers.IO) {
        val allSkills = AgentDatabase.get(app).skills().all()
        if (allSkills.isEmpty()) {
            return@withContext ToolResult(ok = true, output = "No skills defined. The user can create skills in the Skills Hub.")
        }
        val lines = ArrayList<String>(allSkills.size)
        for (skill in allSkills) {
            val status = if (skill.enabled) "on" else "off"
            val body = skill.instructions.trim()
            val size = if (body.isEmpty()) "no instructions" else "${body.length} chars of instructions"
            lines += "[skill:${skill.id}] ${skill.name} (category: ${skill.category}, enabled: $status, $size): ${skill.description.trim().replace('\n', ' ')}"
        }
        ToolResult(ok = true, output = lines.joinToString("\n"))
    }
}

class SkillActivateTool(private val app: android.content.Context) : AgentTool {
    override val definition = ToolDefinition(
        name = "skills.use",
        category = ToolCategory.SKILLS,
        description = "Activate an agent skill by its skill_id (from the skill index or skills.list) " +
            "and receive its full step-by-step instructions. Use this whenever the current task " +
            "matches an enabled skill that was not already auto-injected.",
        permissions = setOf(ToolPermission.READ_WORKSPACE),
        parameters = mapOf("skill_id" to "string (required, skill id from the skill index)"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement) = withContext(Dispatchers.IO) {
        val id = arguments.asJsonObject["skill_id"]?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (id.isEmpty()) {
            return@withContext ToolResult(ok = false, error = "Missing required parameter: skill_id")
        }
        val skill = SkillRepository.byId(app, id)
        if (skill == null) {
            val available = AgentDatabase.get(app).skills().all().joinToString(", ") { it.id }
            return@withContext ToolResult(ok = false, error = "Unknown skill '$id'. Available: ${available.ifBlank { "(none)" }}")
        }
        if (!skill.enabled) {
            return@withContext ToolResult(ok = false, error = "Skill '$id' (${skill.name}) is disabled in the Skills Hub and cannot be activated.")
        }
        AppLogger.i(TAG, "skills.use '$id' (${skill.name})")
        ToolResult(ok = true, output = SkillRepository.renderInstructions(skill))
    }
}
