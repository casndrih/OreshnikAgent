package com.example.oreshik.agent.skills

import android.content.Context
import com.example.oreshik.data.SkillEntity
import com.example.oreshik.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.gson.JsonParser

/**
 * Seeds the bundled professional skill catalog (assets/skills/catalog_*.json) into the
 * local skill library.
 *
 * Library model: catalog skills ship with a curated `enabled` flag (a small core is on,
 * the long tail ships disabled). Seeding is IDEMPOTENT — existing ids are never touched,
 * so user edits and enable/disable choices survive every app update.
 */
object SkillCatalogSeeder {

    private const val TAG = "Skills"
    private const val ASSET_DIR = "skills"
    private val CATALOG_FILES = (1..97).map { "catalog_%02d.json".format(it) }

    /** Parses one catalog asset; malformed entries are skipped and counted. */
    internal fun parseCatalog(json: String): Pair<List<SkillEntity>, Int> {
        val root = JsonParser.parseString(json).asJsonObject
        val arr = root.getAsJsonArray("skills")
        val skills = ArrayList<SkillEntity>(arr.size())
        var skipped = 0
        for (element in arr) {
            runCatching {
                val obj = element.asJsonObject
                val id = obj.get("id")?.asString?.trim().orEmpty()
                val name = obj.get("name")?.asString?.trim().orEmpty()
                val description = obj.get("description")?.asString?.trim().orEmpty()
                val instructions = obj.get("instructions")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val category = obj.get("category")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                    ?.ifBlank { "catalog" } ?: "catalog"
                val enabled = obj.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                if (id.isBlank() || name.isBlank() || description.isBlank()) throw IllegalArgumentException("blank required field")
                skills.add(
                    SkillEntity(
                        id = id, name = name, description = description, enabled = enabled,
                        instructions = instructions, category = category,
                        createdAt = 0L, updatedAt = 0L,
                    ),
                )
            }.onFailure { skipped++ }
        }
        return skills to skipped
    }

    /**
     * Seeds all catalog files. Returns the number of newly inserted skills.
     * Existing rows (defaults, user-created, previously seeded) are never modified.
     */
    suspend fun seedAll(context: Context): Int = withContext(Dispatchers.IO) {
        val dao = runCatching { AgentDatabaseDaoProvider.skillsDao(context) }
            .onFailure { AppLogger.w(TAG, "catalog seed: dao unavailable: ${it.message}") }
            .getOrNull() ?: return@withContext 0
        val existing = runCatching { dao.all() }.getOrDefault(emptyList())
        val knownIds = HashSet<String>(existing.size * 2)
        for (skill in existing) knownIds.add(skill.id)
        var inserted = 0
        var skippedEntries = 0
        for (fileName in CATALOG_FILES) {
            val jsonResult = runCatching {
                context.assets.open("$ASSET_DIR/$fileName").bufferedReader().use { it.readText() }
            }
            val json = jsonResult.getOrNull()
            if (json == null) {
                AppLogger.w(TAG, "catalog asset missing: $fileName (${jsonResult.exceptionOrNull()?.message})")
            } else {
                val (skills, skipped) = parseCatalog(json)
                skippedEntries += skipped
                val now = System.currentTimeMillis()
                for (skill in skills) {
                    if (knownIds.contains(skill.id)) continue
                    runCatching { dao.put(skill.copy(createdAt = now, updatedAt = now)) }
                        .onSuccess {
                            knownIds.add(skill.id)
                            inserted++
                        }
                        .onFailure { AppLogger.w(TAG, "catalog insert failed for ${skill.id}: ${it.message}") }
                }
            }
        }
        if (inserted > 0 || skippedEntries > 0) {
            AppLogger.i(TAG, "Seeded $inserted catalog skills ($skippedEntries malformed entries skipped)")
        }
        inserted
    }
}

/** Small indirection so the seeder stays test-friendly and decoupled from the DB object. */
private object AgentDatabaseDaoProvider {
    fun skillsDao(context: Context): com.example.oreshik.data.SkillDao =
        com.example.oreshik.data.AgentDatabase.get(context).skills()
}
