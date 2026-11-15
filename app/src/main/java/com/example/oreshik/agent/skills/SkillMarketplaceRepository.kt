package com.example.oreshik.agent.skills

import com.example.oreshik.data.SkillEntity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Online skill discovery backed by the open skills.sh index and public GitHub repositories.
 * Remote skills are installed DISABLED and are never executed as code by this importer.
 */
class SkillMarketplaceRepository {
    data class RemoteSkill(
        val id: String,
        val name: String,
        val source: String,
        val installs: Long,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun search(query: String, limit: Int = 100): Result<List<RemoteSkill>> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.length < 2) return@withContext Result.failure(IllegalArgumentException("Enter at least two search characters."))
        runCatching {
            val url = "https://skills.sh/api/search?q=" + URLEncoder.encode(clean, "UTF-8")
            val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) { "Skills catalogue HTTP ${response.code}: ${body.take(200)}" }
                val array = JsonParser.parseString(body).asJsonObject.getAsJsonArray("skills")
                    ?: throw IllegalStateException("Skills catalogue returned no results array.")
                val results = ArrayList<RemoteSkill>()
                for (item in array) {
                    if (!item.isJsonObject) continue
                    val obj = item.asJsonObject
                    val id = obj.string("id").trim()
                    val name = obj.string("name").trim()
                    val source = normalizeSource(obj.string("source"))
                    if (id.isBlank() || name.isBlank() || source == null) continue
                    results.add(RemoteSkill(id, name, source, obj.long("installs")))
                    if (results.size >= limit.coerceIn(1, 100)) break
                }
                results
            }
        }
    }

    suspend fun download(skill: RemoteSkill): Result<SkillEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val source = normalizeSource(skill.source)
                ?: throw IllegalArgumentException("Only public GitHub skill repositories are supported.")
            val (owner, repo) = source.split("/")
            val metadata = getJson("https://api.github.com/repos/$owner/$repo")
            val branch = metadata.string("default_branch").ifBlank { "main" }
            val tree = getJson("https://api.github.com/repos/$owner/$repo/git/trees/${encodePath(branch)}?recursive=1")
            val entries = tree.getAsJsonArray("tree")
                ?: throw IllegalStateException("GitHub repository tree was not returned.")
            val candidates = ArrayList<Pair<String, Int>>()
            for (entry in entries) {
                if (!entry.isJsonObject) continue
                val path = entry.asJsonObject.string("path")
                if (!path.endsWith("SKILL.md", ignoreCase = true)) continue
                val parent = path.substringBeforeLast('/').substringAfterLast('/')
                val score = when {
                    parent.equals(skill.id, true) -> 100
                    parent.equals(skill.name, true) -> 95
                    path.equals("SKILL.md", true) -> 90
                    parent.replace('_','-').equals(skill.id, true) -> 85
                    else -> 0
                }
                if (score > 0) candidates.add(path to score)
            }
            val selected = candidates.maxByOrNull { it.second }?.first
                ?: throw IllegalStateException("No matching SKILL.md was found in $source for ${skill.name}.")
            val rawUrl = "https://raw.githubusercontent.com/$owner/$repo/${encodePath(branch)}/" +
                selected.split("/").joinToString("/") { encodePath(it) }
            val request = Request.Builder().url(rawUrl).header("Accept", "text/plain").get().build()
            val markdown = http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { "Skill download HTTP ${response.code}" }
                check(text.toByteArray(Charsets.UTF_8).size <= MAX_SKILL_BYTES) { "Skill file exceeds the 100 KB safety limit." }
                text
            }
            val parsed = parseSkill(markdown, skill.name)
            val stableId = "community_" + sha256("$source/$selected").take(24)
            SkillEntity(
                id = stableId,
                name = parsed.first.take(120),
                description = parsed.second.take(600).ifBlank { "Community skill from $source" },
                enabled = false,
                instructions = parsed.third,
                category = "Community • $source",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun getJson(url: String): JsonObject {
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").get().build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "GitHub HTTP ${response.code}: ${body.take(180)}" }
            JsonParser.parseString(body).asJsonObject
        }
    }

    private fun parseSkill(markdown: String, fallbackName: String): Triple<String, String, String> {
        val normalized = markdown.removePrefix("\uFEFF")
        require(normalized.startsWith("---")) { "SKILL.md must start with YAML frontmatter." }
        val end = normalized.indexOf("\n---", startIndex = 3)
        require(end in 0..(MAX_FRONTMATTER_CHARS)) { "SKILL.md has invalid or oversized YAML frontmatter." }
        val front = normalized.substring(3, end)
        fun field(key: String): String {
            val match = Regex("(?m)^\\s*${Regex.escape(key)}\\s*:\\s*(.*?)\\s*$").find(front) ?: return ""
            return match.groupValues[1].trim().trim('"', '\'')
        }
        val name = field("name").ifBlank { fallbackName }
        val description = field("description")
        require(name.isNotBlank() && description.isNotBlank()) { "SKILL.md frontmatter needs name and description." }
        val instructions = normalized.substring(end + 4).trim()
        require(instructions.isNotBlank()) { "SKILL.md has no instruction body." }
        return Triple(name, description, instructions)
    }

    private fun normalizeSource(value: String): String? {
        val clean = value.trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/')
        if (!Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$").matches(clean)) return null
        return clean
    }

    private fun encodePath(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrDefault("") }.orEmpty()
    private fun JsonObject.long(key: String): Long =
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asLong }.getOrDefault(0L) } ?: 0L

    private companion object {
        const val MAX_SKILL_BYTES = 100 * 1024
        const val MAX_FRONTMATTER_CHARS = 8 * 1024
    }
}
