package com.example.oreshik.agent.tools

import com.example.oreshik.agent.accessibility.AgentAccessibilityService
import com.example.oreshik.agent.core.*
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun service() = AgentAccessibilityService.instance

class AccessibilityScreenTool : AgentTool {
    override val definition = ToolDefinition(
        name = "accessibility.screen",
        category = ToolCategory.SYSTEM,
        description = "Read the visible Android UI accessibility tree so the agent can understand the current screen.",
        permissions = setOf(ToolPermission.ACCESSIBILITY),
        parameters = mapOf("max_nodes" to "integer"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(ok = false, error = "Accessibility service is not enabled. Enable Oreshik Agent Accessibility in Android Settings.")
        ToolResult(ok = true, s.describeScreen((arguments.asJsonObject["max_nodes"]?.asInt ?: 180).coerceIn(20, 300)))
    }
}
class AccessibilityClickTool : AgentTool {
    override val definition = ToolDefinition(
        name = "accessibility.click",
        category = ToolCategory.SYSTEM,
        description = "Click a visible Android UI element by its text or content description.",
        permissions = setOf(ToolPermission.ACCESSIBILITY),
        parameters = mapOf("text" to "string"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(ok = false, error = "Accessibility service is not enabled.")
        val text = arguments.asJsonObject["text"]?.asString.orEmpty()
        val ok = s.clickText(text)
        ToolResult(ok, if (ok) "Clicked $text" else "", if (!ok) "No clickable element matched: $text" else null)
    }
}
class AccessibilityScrollTool : AgentTool {
    override val definition = ToolDefinition(
        name = "accessibility.scroll",
        category = ToolCategory.SYSTEM,
        description = "Scroll the currently active Android UI forward or backward.",
        permissions = setOf(ToolPermission.ACCESSIBILITY),
        parameters = mapOf("direction" to "string"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(ok = false, error = "Accessibility service is not enabled.")
        val forward = arguments.asJsonObject["direction"]?.asString?.lowercase() != "backward"
        val ok = s.scroll(forward)
        ToolResult(ok, if (ok) "Scrolled ${if (forward) "forward" else "backward"}." else "", if (!ok) "No scrollable node accepted the action." else null)
    }
}
class AccessibilityFocusTool : AgentTool {
    override val definition = ToolDefinition(
        name = "accessibility.focus",
        category = ToolCategory.SYSTEM,
        description = "Move accessibility focus like keyboard arrow navigation: next or previous focusable element.",
        permissions = setOf(ToolPermission.ACCESSIBILITY),
        parameters = mapOf("direction" to "string"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(ok = false, error = "Accessibility service is not enabled.")
        val next = arguments.asJsonObject["direction"]?.asString?.lowercase() != "previous"
        val ok = s.focus(if (next) 1 else -1)
        ToolResult(ok, if (ok) "Focus moved ${if (next) "next" else "previous"}." else "", if (!ok) "No focusable target." else null)
    }
}
class AccessibilityGlobalTool : AgentTool {
    override val definition = ToolDefinition(
        name = "accessibility.global",
        category = ToolCategory.SYSTEM,
        description = "Navigate the Android device using global actions: back, home, recents or notifications.",
        permissions = setOf(ToolPermission.ACCESSIBILITY),
        parameters = mapOf("action" to "string"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(ok = false, error = "Accessibility service is not enabled.")
        val action = when (arguments.asJsonObject["action"]?.asString?.lowercase()) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return@withContext ToolResult(ok = false, error = "Supported actions: back, home, recents, notifications")
        }
        val ok = s.global(action)
        ToolResult(ok, if (ok) "Global action requested." else "", if (!ok) "Android rejected the global action." else null)
    }
}
