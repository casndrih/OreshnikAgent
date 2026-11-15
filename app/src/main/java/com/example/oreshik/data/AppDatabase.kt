package com.example.oreshik.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val embedding: String? = null, // JSON array of floats
    val createdAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
    /** Memory class: 'fact' (durable user/project fact), 'session_digest' (summary of a past
     *  conversation), 'note' (free-form). Lets recall treat digests differently from facts. */
    @ColumnInfo(defaultValue = "fact") val kind: String = "fact",
    /** How this memory was written: 'manual' (Memory UI), 'tool' (memory.save tool call),
     *  'auto' (autonomous SessionLearner distillation). Shown in the Memory screen badge. */
    @ColumnInfo(defaultValue = "manual") val source: String = "manual",
    /** Originating session id — lets the user see (and the engine rank) where a memory came from. */
    @ColumnInfo(defaultValue = "") val sessionId: String = "",
    /** Recall counter: how often this memory was actually injected into a request. */
    @ColumnInfo(defaultValue = "0") val accessCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val lastAccessedAt: Long = 0,
    /** Importance multiplier (1..3). Reinforced whenever the memory is re-stated or recalled. */
    @ColumnInfo(defaultValue = "1.0") val weight: Float = 1f,
)

/** Progress and audit trail for periodic autonomous learning passes within each session. */
@Entity(tableName = "memory_events", indices = [Index(value = ["sessionId"], unique = true)])
data class MemoryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val exchangeCount: Int = 0,
    val factsLearned: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A reusable agent skill (Claude-Code-style): name + description form the always-visible index
 * entry, while `instructions` is the full markdown playbook the model pulls (auto-matched or via
 * the skills.use tool) when the skill is relevant to the current request.
 */
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean = true,
    /** Full instruction body (markdown). Empty for legacy rows created before v6. */
    @ColumnInfo(defaultValue = "") val instructions: String = "",
    /** Free-form grouping label used by the Skills Hub UI and the skill index. */
    @ColumnInfo(defaultValue = "general") val category: String = "general",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messagesJson: String,
    val historyJson: String,
    val summary: String? = null,
    val compressedAt: Long? = null,
    val messageCount: Int = 0,
)

/** Small drawer row; excludes serialized transcript columns that can exceed CursorWindow. */
data class SessionListItem(
    val id: String,
    val title: String,
    val updatedAt: Long,
)

@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val transport: String,
    val endpoint: String,
    val command: String,
    val arguments: String,
    val environment: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val type: String, // NVIDIA, OPENROUTER, BLUESMIND, NARAROUTER
    val baseUrl: String,
    val apiKeys: String, // JSON array of API keys
    val enabled: Boolean = true,
    val status: String = "unknown", // working, not_working, unknown
    val lastTested: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "provider_models")
data class ProviderModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val providerId: Int,
    val modelId: String,
    val modelName: String,
    val modelType: String, // chat, vision, embedding
    val enabled: Boolean = true,
    val status: String = "unknown",
    val lastTested: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings") fun all(): List<SettingEntity>
    @Query("SELECT * FROM settings WHERE `key` = :key LIMIT 1") fun get(key: String): SettingEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(item: SettingEntity)
    @Query("DELETE FROM settings WHERE `key` = :key") fun remove(key: String)
    /** Replace-all support for config backup/restore. */
    @Query("DELETE FROM settings") fun clear()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE enabled = 1 ORDER BY createdAt DESC") fun active(): List<MemoryEntity>
    /** Memories owned by this session. User-authored global memories are queried separately. */
    @Query("SELECT * FROM memories WHERE enabled = 1 AND sessionId = :sessionId ORDER BY createdAt DESC") fun activeForSession(sessionId: String): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE enabled = 1 AND sessionId = '' AND source = 'manual' ORDER BY createdAt DESC") fun activeGlobalManual(): List<MemoryEntity>
    /** Backup support: export every row, including disabled ones. */
    @Query("SELECT * FROM memories") fun all(): List<MemoryEntity>
    @Insert fun insert(item: MemoryEntity): Long
    @Update fun update(item: MemoryEntity)
    @Query("DELETE FROM memories WHERE id = :id") fun delete(id: Long)
    @Query("DELETE FROM memories") fun clear()
    @Query("SELECT * FROM memories WHERE enabled = 1 AND embedding IS NOT NULL ORDER BY createdAt DESC") fun withEmbeddings(): List<MemoryEntity>
    /** Most recent digests across chats; used only when cross-session context is enabled. */
    @Query("SELECT * FROM memories WHERE enabled = 1 AND kind = 'session_digest' ORDER BY createdAt DESC LIMIT :limit") fun recentDigests(limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE enabled = 1 AND kind = 'session_digest' AND sessionId = :sessionId ORDER BY createdAt DESC LIMIT :limit") fun recentDigestsForSession(sessionId: String, limit: Int): List<MemoryEntity>
    @Query("DELETE FROM memories WHERE id = :id AND sessionId = :sessionId") fun deleteOwned(id: Long, sessionId: String): Int
    @Query("DELETE FROM memories WHERE id = :id AND sessionId = '' AND source = 'manual'") fun deleteGlobalManual(id: Long): Int
    /** Reinforcement: a recall hit bumps accessCount + lastAccessedAt. */
    @Query("UPDATE memories SET accessCount = accessCount + 1, lastAccessedAt = :now WHERE id IN (:ids)") fun touch(ids: List<Long>, now: Long)
    /** Importance bump when a memory is re-stated (deduped by semantic near-match). */
    @Query("UPDATE memories SET weight = MIN(weight + :delta, 3.0), lastAccessedAt = :now WHERE id = :id") fun reinforce(id: Long, delta: Float, now: Long)
    @Query("SELECT * FROM memory_events WHERE sessionId = :sessionId LIMIT 1") fun getEvent(sessionId: String): MemoryEventEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertEvent(item: MemoryEventEntity)
    @Query("DELETE FROM memory_events") fun clearEvents()
    /** Backup support: export the whole learning audit trail. */
    @Query("SELECT * FROM memory_events") fun allEvents(): List<MemoryEventEntity>
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC LIMIT :limit") fun recent(limit: Int = 50): List<SessionEntity>
    @Query("SELECT id, title, updatedAt FROM sessions ORDER BY updatedAt DESC LIMIT :limit") fun recentListItems(limit: Int = 50): List<SessionListItem>
    /** Backup support: export every session, ignoring the recent cap. */
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC") fun all(): List<SessionEntity>
    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1") fun get(id: String): SessionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(item: SessionEntity)
    /** Writes an Activity snapshot only if the row has not changed since it was loaded. */
    @Transaction
    fun putIfUnchanged(item: SessionEntity, expectedUpdatedAt: Long): Boolean {
        val current = get(item.id)
        if (current == null && expectedUpdatedAt > 0L) return false
        if (current != null && current.updatedAt != expectedUpdatedAt) return false
        put(item)
        return true
    }
    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id") fun rename(id: String, title: String, updatedAt: Long)
    @Query("DELETE FROM sessions WHERE id = :id") fun delete(id: String)
    @Query("DELETE FROM sessions") fun clear()
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY name") fun all(): List<SkillEntity>
    @Query("SELECT * FROM skills WHERE enabled = 1 ORDER BY name") fun enabledSkills(): List<SkillEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(item: SkillEntity)
    @Query("UPDATE skills SET enabled = :enabled WHERE id = :id") fun setEnabled(id: String, enabled: Boolean)
    @Query("DELETE FROM skills WHERE id = :id") fun delete(id: String)
    /** Backup/restore replace-all. */
    @Query("DELETE FROM skills") fun clearAll()
}

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY name") fun all(): List<McpServerEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(item: McpServerEntity)
    /** Backup/restore replace-all. */
    @Query("DELETE FROM mcp_servers") fun clearAll()
    @Query("UPDATE mcp_servers SET enabled = :enabled WHERE id = :id") fun setEnabled(id: Int, enabled: Boolean)
    @Query("DELETE FROM mcp_servers WHERE id = :id") fun delete(id: Int)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY name") fun all(): List<ProviderEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(item: ProviderEntity)
    /** Backup/restore replace-all. */
    @Query("DELETE FROM providers") fun clearAll()
    @Query("UPDATE providers SET enabled = :enabled WHERE id = :id") fun setEnabled(id: Int, enabled: Boolean)
    @Query("UPDATE providers SET status = :status, lastTested = :lastTested, updatedAt = :updatedAt WHERE id = :id")
    fun updateStatus(id: Int, status: String, lastTested: Long, updatedAt: Long)
    @Query("DELETE FROM providers WHERE id = :id") fun delete(id: Int)
}

@Dao
interface ProviderModelDao {
    @Query("SELECT * FROM provider_models WHERE providerId = :providerId ORDER BY modelName") fun byProvider(providerId: Int): List<ProviderModelEntity>
    /** Backup support: export the whole cached catalog. */
    @Query("SELECT * FROM provider_models") fun all(): List<ProviderModelEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(item: ProviderModelEntity)
    /** Backup/restore replace-all. */
    @Query("DELETE FROM provider_models") fun clearAll()
    @Query("UPDATE provider_models SET status = :status, lastTested = :lastTested WHERE id = :id")
    fun updateStatus(id: Int, status: String, lastTested: Long)
    @Query("DELETE FROM provider_models WHERE id = :id") fun delete(id: Int)
}

@Database(entities = [SettingEntity::class, MemoryEntity::class, MemoryEventEntity::class, SkillEntity::class, McpServerEntity::class, SessionEntity::class, ProviderEntity::class, ProviderModelEntity::class], version = 6, exportSchema = false)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun settings(): SettingsDao
    abstract fun memories(): MemoryDao
    abstract fun skills(): SkillDao
    abstract fun mcpServers(): McpServerDao
    abstract fun sessions(): SessionDao
    abstract fun providers(): ProviderDao
    abstract fun providerModels(): ProviderModelDao
    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, model TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, messagesJson TEXT NOT NULL, historyJson TEXT NOT NULL, summary TEXT, compressedAt INTEGER, messageCount INTEGER NOT NULL DEFAULT 0)")
            }
        }
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS mcp_servers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, transport TEXT NOT NULL, endpoint TEXT NOT NULL, command TEXT NOT NULL, arguments TEXT NOT NULL, environment TEXT NOT NULL, enabled INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS providers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, baseUrl TEXT NOT NULL, apiKeys TEXT NOT NULL, enabled INTEGER NOT NULL, status TEXT NOT NULL, lastTested INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS provider_models (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, providerId INTEGER NOT NULL, modelId TEXT NOT NULL, modelName TEXT NOT NULL, modelType TEXT NOT NULL, enabled INTEGER NOT NULL, status TEXT NOT NULL, lastTested INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Long-term memory metadata. All columns are NOT NULL with defaults so
                // existing rows stay valid without a copy-table round trip.
                db.execSQL("ALTER TABLE memories ADD COLUMN kind TEXT NOT NULL DEFAULT 'fact'")
                db.execSQL("ALTER TABLE memories ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
                db.execSQL("ALTER TABLE memories ADD COLUMN sessionId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE memories ADD COLUMN accessCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN lastAccessedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN weight REAL NOT NULL DEFAULT 1.0")
                db.execSQL("CREATE TABLE IF NOT EXISTS memory_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId TEXT NOT NULL, exchangeCount INTEGER NOT NULL DEFAULT 0, factsLearned INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memory_events_sessionId ON memory_events (sessionId)")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Skill bodies + metadata. All columns are NOT NULL with defaults so existing
                // rows (and the six seeded defaults) stay valid without a copy-table round trip.
                db.execSQL("ALTER TABLE skills ADD COLUMN instructions TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE skills ADD COLUMN category TEXT NOT NULL DEFAULT 'general'")
                db.execSQL("ALTER TABLE skills ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE skills ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
        @Volatile private var INSTANCE: AgentDatabase? = null
        fun get(context: Context): AgentDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, "oreshik_agent.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { INSTANCE = it }
        }
    }
}
