package com.example.oreshik.agent.core

import com.google.gson.JsonElement

enum class ToolCategory {
    FILESYSTEM, PROCESS, NETWORK, DATABASE, REVERSE_ENGINEERING, SECURITY, SYSTEM, MEMORY, RUNTIME, GIT, BUILD, ROOT, WEB, TRAFFIC_CAPTURE, MEMORY_MANIPULATION, SKILLS
}

enum class ToolPermission {
    READ_WORKSPACE, WRITE_WORKSPACE, EXECUTE_PROCESS, EXECUTE_PYTHON,
    NETWORK, GIT, BUILD, DELETE, ACCESSIBILITY, MEMORY, ROOT, DATABASE, REVERSE_ENGINEERING,
    TRAFFIC_CAPTURE, TRAFFIC_MODIFY,
    MEMORY_SCAN, MEMORY_WRITE, PROCESS_MEMORY
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val category: ToolCategory = ToolCategory.NETWORK,
    val permissions: Set<ToolPermission>,
    val parameters: Map<String, Any?> = emptyMap(),
)

data class ToolContext(
    val workspace: java.io.File,
    val app: android.content.Context,
    val permissionManager: com.example.oreshik.agent.security.PermissionManager,
    val runtime: com.example.oreshik.agent.runtime.RuntimeManager,
    /** Optional progress channel: tools can stream short status lines to the UI. */
    val onProgress: (suspend (String) -> Unit)? = null,
    /** Session this tool call belongs to (used for session-scoped live UI events). */
    val sessionId: String? = null,
    /** Ephemeral, run-scoped evidence shared by cooperating analysis tools. */
    val collaboration: ToolCollaborationSession? = null,
    /** Restricted, permission-gated tool handoff used by collaboration workflows. */
    val invokeCollaborativeTool: (suspend (String, JsonElement) -> ToolResult)? = null,
)

data class ToolResult(
    val ok: Boolean,
    val output: String = "",
    val error: String? = null,
    val exitCode: Int? = null,
    val metadata: Map<String, Any?> = emptyMap(),
) {
    companion object {
        fun success(output: String = "", metadata: Map<String, Any?> = emptyMap()): ToolResult =
            ToolResult(ok = true, output = output, metadata = metadata)
        
        fun success(metadata: Map<String, Any?>): ToolResult =
            ToolResult(ok = true, metadata = metadata)
        
        fun failure(error: String, exitCode: Int? = null): ToolResult =
            ToolResult(ok = false, error = error, exitCode = exitCode)
    }
}

interface AgentTool {
    val definition: ToolDefinition
    suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult
}
