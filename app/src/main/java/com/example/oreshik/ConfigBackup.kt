package com.example.oreshik

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.data.AgentDatabase
import com.example.oreshik.data.MemoryEntity
import com.example.oreshik.data.MemoryEventEntity
import com.example.oreshik.data.McpServerEntity
import com.example.oreshik.data.ProviderEntity
import com.example.oreshik.data.ProviderModelEntity
import com.example.oreshik.data.SessionEntity
import com.example.oreshik.data.SettingEntity
import com.example.oreshik.data.SkillEntity
import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full app-configuration backup/restore engine.
 *
 * Exports EVERYTHING the app persists into ONE self-describing JSON file:
 *  • the whole `settings` table (api_key, base_url, active model, reasoning effort,
 *    every behavior toggle, active provider id, …)
 *  • every configured provider INCLUDING its API keys + cached model catalogs
 *  • all chat sessions (full message + history JSON, summaries included)
 *  • long-term memories (incl. embeddings) + the autonomous-learning audit trail
 *  • skills and MCP server definitions
 *  • the app theme mode
 *
 * The file is written/read through SAF so no storage permission is needed, and restore
 * re-applies the theme + refreshes AgentConfig's in-memory cache so the running process
 * picks up restored values without a restart.
 *
 * SECURITY NOTE: the backup contains API keys in PLAIN TEXT. The file lives wherever
 * the user saved it (their storage, their control) — the UI warns about this.
 */
object ConfigBackup {
    private const val TAG = "ConfigBackup"

    /** Bump when the payload shape changes; restore refuses files from a newer schema. */
    const val SCHEMA_VERSION = 1

    private const val WRAPPER_KEY = "oreshikBackup"
    private const val MAX_BACKUP_BYTES = 100L * 1024L * 1024L

    /** File-name suggestion for the SAF create dialog. */
    fun suggestedFileName(): String =
        "oreshik_agent_backup_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            ".oreshik.enc"

    /** One-line summary of what a backup would contain (shown in the Settings status line). */
    fun buildSummaryLine(db: AgentDatabase): String = buildString {
        val settings = db.settings().all()
        val providers = db.providers().all()
        val providerKeys = providers.sumOf { p ->
            runCatching {
                Gson().fromJson(p.apiKeys, Array<String>::class.java).size
            }.getOrDefault(0)
        }
        append("Ready: settings=").append(settings.size)
        if (AgentConfig.apiKey.isNotBlank()) append(" • API key ✓")
        if (AgentConfig.model.isNotBlank()) append(" • model=").append(AgentConfig.model)
        append(" • providers=").append(providers.size)
        if (providerKeys > 0) append(" (").append(providerKeys).append(" keys)")
        append(" • sessions=").append(db.sessions().all().size)
        append(" • memories=").append(db.memories().all().size)
        append(" • skills=").append(db.skills().all().size)
        append(" • mcp=").append(db.mcpServers().all().size)
    }

    /**
     * Builds the full backup payload on a worker thread and streams it to the SAF uri.
     * Returns a human-readable success line (row counts) for the status view.
     */
    suspend fun writeBackup(context: Context, uri: Uri, passphrase: CharArray? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AgentDatabase.get(context)
            val gson = Gson()
            val appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            }.getOrDefault("?")

            val settings = db.settings().all()
            val sessions = db.sessions().all()
            val memories = db.memories().all()
            val memoryEvents = db.memories().allEvents()
            val skills = db.skills().all()
            val mcpServers = db.mcpServers().all()
            val providers = db.providers().all()
            val providerModels = db.providerModels().all()

            val root = JsonObject().apply {
                addProperty(WRAPPER_KEY, true)
                addProperty("schemaVersion", SCHEMA_VERSION)
                addProperty("createdAt", System.currentTimeMillis())
                addProperty("appVersion", appVersion)
                add("data", JsonObject().apply {
                    add("settings", gson.toJsonTree(settings))
                    add("sessions", gson.toJsonTree(sessions))
                    add("memories", gson.toJsonTree(memories))
                    add("memoryEvents", gson.toJsonTree(memoryEvents))
                    add("skills", gson.toJsonTree(skills))
                    add("mcpServers", gson.toJsonTree(mcpServers))
                    add("providers", gson.toJsonTree(providers))
                    add("providerModels", gson.toJsonTree(providerModels))
                    add("theme", JsonObject().apply {
                        addProperty("mode", ThemePrefs.get(context))
                    })
                })
            }

            val json = gson.toJson(root)
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open the selected file for writing")

            AppLogger.i(TAG, "backup written: settings=${settings.size} sessions=${sessions.size} " +
                "memories=${memories.size} skills=${skills.size} mcp=${mcpServers.size} " +
                "providers=${providers.size} providerModels=${providerModels.size}")

            "Backup saved:\n" + listOf(
                "settings" to settings.size,
                "sessions" to sessions.size,
                "memories" to memories.size,
                "skills" to skills.size,
                "mcpServers" to mcpServers.size,
                "providers" to providers.size,
                "providerModels" to providerModels.size,
            ).joinToString(" • ") { (k, v) -> "$k=$v" }
        }.onFailure { AppLogger.e(TAG, "backup failed: ${it.message}", it) }
    }

    /**
     * Restores the full configuration from a backup file SAF uri. DESTRUCTIVE: every
     * table present in the backup is wiped and re-inserted. All writes run inside a
     * single Room transaction — a failed restore leaves the current data untouched.
     * Re-applies the theme and refreshes the AgentConfig cache afterwards.
     */
    suspend fun restore(context: Context, uri: Uri, passphrase: CharArray? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.use { readBounded(it) }
                ?.toString(Charsets.UTF_8) ?: error("Could not read the selected file")
            val root = Gson().fromJson(raw, JsonObject::class.java)
                ?: error("Not a Oreshik Agent backup file")
            // The wrapper marker is written as a boolean primitive (schema metadata then
            // lives at the root level), but an object-shaped wrapper is tolerated too.
            // Never call getAsJsonObject() on it: that method CASTS the stored element
            // and throws ClassCastException when the value is a JsonPrimitive.
            val wrapperEl = root.get(WRAPPER_KEY) ?: error("Not a Oreshik Agent backup file")
            val meta = if (wrapperEl.isJsonObject) wrapperEl.asJsonObject else root
            val schemaVersion = meta.get("schemaVersion")?.takeIf { !it.isJsonNull }?.asInt
                ?: error("Backup file is missing its schema version")
            require(schemaVersion <= SCHEMA_VERSION) {
                "Backup schema v$schemaVersion is newer than this app supports (v$SCHEMA_VERSION)"
            }
            val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: error("Backup file has no data section")
            val gson = Gson()
            val db = AgentDatabase.get(context)
            var restored = 0
            val restoredThemeMode = data.get("theme")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("mode")?.takeIf { !it.isJsonNull }?.asInt
            val restoredMemoryEvents = data.getAsJsonArray("memoryEvents")?.map { element ->
                val row = gson.fromJson(element, MemoryEventEntity::class.java)
                    ?: error("Backup contains an invalid memory event row")
                require(row.sessionId.isNotBlank()) { "Backup contains a memory event without a session id" }
                row
            }

            AgentConfig.withWritesPaused {
                db.runInTransaction {
                    // 1) Settings: the whole table is authoritative for every toggle, API
                    //    key and endpoint. Cleared then re-inserted from the file.
                    val settings = data.getAsJsonArray("settings")
                    if (settings != null) {
                        db.settings().clear()
                        for (el in settings) {
                            val row = gson.fromJson(el, SettingEntity::class.java)
                                ?: error("Backup contains an invalid setting row")
                            require(row.key.isNotBlank()) { "Backup contains a setting without a key" }
                            db.settings().put(SettingEntity(row.key, row.value))
                            restored++
                        }
                    }
    
                    // 2) Sessions: wipe + re-insert (chat rows carry full message/history JSON).
                    val sessions = data.getAsJsonArray("sessions")
                    if (sessions != null) {
                        db.sessions().clear()
                        for (el in sessions) {
                            val row = gson.fromJson(el, SessionEntity::class.java)
                                ?: error("Backup contains an invalid session row")
                            require(row.id.isNotBlank()) { "Backup contains a session without an id" }
                            db.sessions().put(row)
                            restored++
                        }
                    }
    
                    // 4) Memories: wipe + re-insert (embeddings ride along, so recall keeps working).
                    val memories = data.getAsJsonArray("memories")
                    if (memories != null) {
                        db.memories().clear()
                        for (el in memories) {
                            val row = gson.fromJson(el, MemoryEntity::class.java)
                                ?: error("Backup contains an invalid memory row")
                            require(row.text.isNotBlank()) { "Backup contains an empty memory row" }
                            db.memories().insert(row)
                            restored++
                        }
                    }
    
                    // 5) Skills + MCP servers (replace-all).
                    val skills = data.getAsJsonArray("skills")
                    if (skills != null) {
                        db.skills().clearAll()
                        for (el in skills) {
                            val row = gson.fromJson(el, SkillEntity::class.java)
                                ?: error("Backup contains an invalid skill row")
                            require(row.id.isNotBlank()) { "Backup contains a skill without an id" }
                            db.skills().put(row)
                            restored++
                        }
                    }
                    val mcp = data.getAsJsonArray("mcpServers")
                    if (mcp != null) {
                        db.mcpServers().clearAll()
                        for (el in mcp) {
                            val row = gson.fromJson(el, McpServerEntity::class.java)
                                ?: error("Backup contains an invalid MCP server row")
                            require(row.name.isNotBlank()) { "Backup contains an MCP server without a name" }
                            db.mcpServers().put(row)
                            restored++
                        }
                    }
    
                    // 6) Providers (+ their API keys) and cached model catalogs.
                    val providers = data.getAsJsonArray("providers")
                    if (providers != null) {
                        db.providers().clearAll()
                        for (el in providers) {
                            val row = gson.fromJson(el, ProviderEntity::class.java)
                                ?: error("Backup contains an invalid provider row")
                            require(row.name.isNotBlank()) { "Backup contains a provider without a name" }
                            db.providers().put(row)
                            restored++
                        }
                    }
                    val providerModels = data.getAsJsonArray("providerModels")
                    if (providerModels != null) {
                        db.providerModels().clearAll()
                        for (el in providerModels) {
                            val row = gson.fromJson(el, ProviderModelEntity::class.java)
                                ?: error("Backup contains an invalid provider model row")
                            require(row.modelId.isNotBlank()) { "Backup contains a provider model without an id" }
                            db.providerModels().put(row)
                            restored++
                        }
                    }
                    // Replace the audit trail inside the same transaction. For an older
                    // backup that omitted it, clear stale events from the previous data.
                    db.memories().clearEvents()
                    restoredMemoryEvents?.forEach(db.memories()::insertEvent)
                }

                // Re-apply the restored theme only after the database transaction commits.
                restoredThemeMode?.let { ThemePrefs.set(context, it) }
                AppCompatDelegate.setDefaultNightMode(ThemePrefs.get(context))
                AgentConfig.refreshCache(context)
            }

            val createdAt = meta.get("createdAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val created = if (createdAt > 0) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(createdAt))
            } else {
                "unknown date"
            }
            "Restored $restored rows from backup created $created. Keys, model and toggles are active immediately; restored chats appear in the session list."
        }.also { r ->
            r.onSuccess { AppLogger.i(TAG, it) }
                .onFailure { AppLogger.e(TAG, "restore failed: ${it.message}", it) }
        }
    }


    private fun isEncryptedBackup(bytes: ByteArray): Boolean =
        bytes.size > ENCRYPTED_MAGIC.size &&
            bytes.copyOfRange(0, ENCRYPTED_MAGIC.size).contentEquals(ENCRYPTED_MAGIC)

    /** PBKDF2-HMAC-SHA256 + AES-256-GCM authenticated encryption. */
    private fun encryptBackup(plain: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.size >= 10) { "Use a backup passphrase of at least 10 characters" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(GCM_IV_BYTES).also(SecureRandom()::nextBytes)
        val key = deriveBackupKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain)
        return ENCRYPTED_MAGIC + salt + iv + encrypted
    }

    private fun decryptBackup(payload: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "Backup passphrase is required" }
        val headerSize = ENCRYPTED_MAGIC.size + SALT_BYTES + GCM_IV_BYTES
        require(payload.size > headerSize) { "Encrypted backup is truncated" }
        val saltStart = ENCRYPTED_MAGIC.size
        val ivStart = saltStart + SALT_BYTES
        val salt = payload.copyOfRange(saltStart, ivStart)
        val iv = payload.copyOfRange(ivStart, headerSize)
        val ciphertext = payload.copyOfRange(headerSize, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveBackupKey(passphrase, salt), GCMParameterSpec(128, iv))
        return try {
            cipher.doFinal(ciphertext)
        } catch (_: javax.crypto.AEADBadTagException) {
            throw IllegalArgumentException("Incorrect passphrase or backup file was modified")
        }
    }

    private fun deriveBackupKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_BACKUP_BYTES) {
                "Backup file exceeds the 100 MB import limit"
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
