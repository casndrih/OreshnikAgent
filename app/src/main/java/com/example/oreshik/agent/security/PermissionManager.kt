package com.example.oreshik.agent.security

import android.content.Context
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.core.ToolPermission
import com.example.oreshik.data.AgentDatabase
import com.example.oreshik.data.SettingEntity
import com.example.oreshik.logging.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.thread

class PermissionManager(private val context: Context) {

    private companion object {
        const val TAG = "Permissions"
    }
    private val mutex = Mutex()
    private val db get() = AgentDatabase.get(context)

    suspend fun check(
        tool: String,
        permissions: Set<ToolPermission>,
        @Suppress("UNUSED_PARAMETER") destructive: Boolean = false,
    ): Boolean = mutex.withLock {
        if (permissions.isEmpty()) return@withLock true

        // 1. Hard capability gates - reject if capability is disabled in settings
        if ((permissions.contains(ToolPermission.EXECUTE_PROCESS) ||
             permissions.contains(ToolPermission.EXECUTE_PYTHON) ||
             permissions.contains(ToolPermission.GIT) ||
             permissions.contains(ToolPermission.BUILD)) && !AgentConfig.allowProcess) return@withLock false

        if (permissions.contains(ToolPermission.NETWORK) && !AgentConfig.allowNetwork) return@withLock false

        if (permissions.contains(ToolPermission.ACCESSIBILITY) && !AgentConfig.accessibilityEnabled) return@withLock false

        if (permissions.contains(ToolPermission.ROOT) && !AgentConfig.allowRoot) return@withLock false

        if (permissions.contains(ToolPermission.MEMORY) && !AgentConfig.memoryEnabled) return@withLock false

        // 2. Auto-allows for workspace file management and top-level enabled features
        var satisfiesAll = true
        for (p in permissions) {
            val allowed = when (p) {
                ToolPermission.READ_WORKSPACE -> AgentConfig.autoApproveReads
                ToolPermission.WRITE_WORKSPACE, ToolPermission.DELETE -> !AgentConfig.askBeforeWrite
                ToolPermission.EXECUTE_PROCESS, ToolPermission.EXECUTE_PYTHON, ToolPermission.GIT, ToolPermission.BUILD -> AgentConfig.allowProcess
                ToolPermission.NETWORK -> AgentConfig.allowNetwork
                ToolPermission.ROOT -> AgentConfig.allowRoot
                ToolPermission.MEMORY -> AgentConfig.memoryEnabled
                ToolPermission.ACCESSIBILITY -> AgentConfig.accessibilityEnabled
                ToolPermission.DATABASE, ToolPermission.REVERSE_ENGINEERING -> AgentConfig.allowProcess // Treat as process capabilities
                ToolPermission.TRAFFIC_CAPTURE, ToolPermission.TRAFFIC_MODIFY -> AgentConfig.allowNetwork // Traffic capture/modify rides on the network capability gate
                ToolPermission.MEMORY_SCAN, ToolPermission.MEMORY_WRITE, ToolPermission.PROCESS_MEMORY -> AgentConfig.allowRoot // Memory manipulation requires root
            }
            if (!allowed) {
                satisfiesAll = false
                break
            }
        }
        if (satisfiesAll) {
            AppLogger.d(TAG, "check: tool=$tool auto-allowed (${permissions.size} permission(s))")
            return@withLock true
        }

        // 3. Fallback to per-tool database permission overrides
        // A per-tool grant must not override the user's explicit "ask before file
        // changes" setting. Read grants remain available when auto-approval is off.
        val approved = permissions.all { permission ->
            val mustAskForWrite = AgentConfig.askBeforeWrite &&
                (permission == ToolPermission.WRITE_WORKSPACE || permission == ToolPermission.DELETE)
            !mustAskForWrite && isApprovedInDb(tool, permission)
        }
        if (!approved) AppLogger.d(TAG, "check: tool=$tool DENIED (needs ${permissions.joinToString()})")
        approved
    }

    private fun isApprovedInDb(tool: String, permission: ToolPermission): Boolean {
        val rawKey = "permission:$tool:${permission.name}"
        val dottedKey = "permission:${tool.replace('_', '.')}:${permission.name}"
        val underscoredKey = "permission:${tool.replace('.', '_')}:${permission.name}"
        return (db.settings().get(rawKey)?.value == "true") ||
               (db.settings().get(dottedKey)?.value == "true") ||
               (db.settings().get(underscoredKey)?.value == "true")
    }

    fun allow(tool: String, permissions: Set<ToolPermission>, always: Boolean) {
        if (!always) return
        thread(name = "agent-permission-write") {
            permissions.forEach { p ->
                val rawKey = "permission:$tool:${p.name}"
                val dottedKey = "permission:${tool.replace('_', '.')}:${p.name}"
                val underscoredKey = "permission:${tool.replace('.', '_')}:${p.name}"
                db.settings().put(SettingEntity(rawKey, "true"))
                db.settings().put(SettingEntity(dottedKey, "true"))
                db.settings().put(SettingEntity(underscoredKey, "true"))
            }
        }
    }

    /** Clears every stored tool permission grant (Settings → reset). */
    @Suppress("unused")
    fun revokeAll() = thread(name = "agent-permission-clear") {
        val all = db.settings().all()
        var removed = 0
        for (i in all.indices) {
            if (all[i].key.startsWith("permission:")) {
                db.settings().remove(all[i].key)
                removed++
            }
        }
        AppLogger.i(TAG, "revokeAll: removed $removed stored tool permission(s)")
    }
}
