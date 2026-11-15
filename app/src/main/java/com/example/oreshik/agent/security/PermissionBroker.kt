package com.example.oreshik.agent.security

import com.example.oreshik.agent.core.ToolPermission
import com.example.oreshik.logging.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PermissionBroker(private val manager: PermissionManager) {

    private companion object {
        const val TAG = "PermissionBroker"
    }
    var onApprovalRequired: (suspend (String, Set<ToolPermission>) -> Decision)? = null
    private val mutex = Mutex()

    enum class Decision { ALLOW_ONCE, ALWAYS_ALLOW, DENY }

    suspend fun require(tool: String, permissions: Set<ToolPermission>): Boolean {
        return manager.check(tool, permissions) || mutex.withLock {
            if (manager.check(tool, permissions)) return@withLock true
            val decision = onApprovalRequired?.invoke(tool, permissions) ?: Decision.DENY
            AppLogger.i(TAG, "require: tool=$tool → $decision (${permissions.size} permission(s))")
            when (decision) {
                Decision.ALLOW_ONCE -> true
                Decision.ALWAYS_ALLOW -> { manager.allow(tool, permissions, always = true); true }
                Decision.DENY -> false
            }
        }
    }
}
