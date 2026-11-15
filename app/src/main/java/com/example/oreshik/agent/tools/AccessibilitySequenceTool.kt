package com.example.oreshik.agent.tools

import com.example.oreshik.agent.accessibility.AgentAccessibilityService
import com.example.oreshik.agent.core.*
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Bounded UI-test playback using the same accessibility service as individual computer-use tools. */
class AccessibilitySequenceTool : AgentTool {
    override val definition = ToolDefinition(
        name = "accessibility.sequence",
        category = ToolCategory.SYSTEM,
        description = "Play a bounded accessibility test sequence: click_text, type, tap, long_press, swipe, scroll, focus, global and wait. Stops on the first failed action and returns a step-by-step report.",
        permissions = setOf(ToolPermission.ACCESSIBILITY),
        parameters = mapOf("steps" to "array of action objects; maximum 40 steps"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val service = AgentAccessibilityService.instance
            ?: return@withContext ToolResult.failure("Accessibility service is not enabled.")
        val steps = arguments.asJsonObject.getAsJsonArray("steps")
            ?: return@withContext ToolResult.failure("steps must be an array.")
        if (steps.size() !in 1..40) return@withContext ToolResult.failure("Provide between 1 and 40 steps.")
        val report = mutableListOf<String>()
        var completed = 0
        for ((index, element) in steps.withIndex()) {
            val step = runCatching { element.asJsonObject }.getOrNull()
                ?: return@withContext ToolResult.failure("Step ${index + 1} must be an object.")
            val action = step["action"]?.asString?.lowercase()?.trim().orEmpty()
            val ok = try {
                when (action) {
                    "click_text" -> {
                        val text = step["text"]?.asString.orEmpty()
                        require(text.isNotBlank() && text.length <= 300) { "click_text needs text (max 300 chars)." }
                        service.clickElement(text)
                    }
                    "type" -> {
                        val text = step["text"]?.asString.orEmpty()
                        require(text.length <= 4000) { "Text input exceeds 4000 characters." }
                        service.typeText(text)
                    }
                    "tap" -> service.tap(step.float("x"), step.float("y"))
                    "long_press" -> service.longPress(step.float("x"), step.float("y"), step.long("duration_ms", 700).coerceIn(300, 5000))
                    "swipe" -> service.swipe(step.float("x1"), step.float("y1"), step.float("x2"), step.float("y2"), step.long("duration_ms", 350).coerceIn(80, 5000))
                    "scroll" -> service.scroll(step["direction"]?.asString?.lowercase() != "backward")
                    "focus" -> service.focus(if (step["direction"]?.asString?.lowercase() == "previous") -1 else 1)
                    "global" -> {
                        val code = when (step["name"]?.asString?.lowercase()) {
                            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                            else -> error("global name must be back, home, recents or notifications.")
                        }
                        service.global(code)
                    }
                    "wait" -> {
                        delay(step.long("duration_ms", 500).coerceIn(50, 5000))
                        true
                    }
                    else -> error("Unsupported action '$action'.")
                }
            } catch (failure: Exception) {
                report += "${index + 1}. $action — ERROR: ${failure.message ?: failure.javaClass.simpleName}"
                return@withContext ToolResult.failure("Sequence stopped at step ${index + 1}.\n" + report.joinToString("\n"))
            }
            report += "${index + 1}. $action — ${if (ok) "OK" else "FAILED"}"
            if (!ok) return@withContext ToolResult.failure("Sequence stopped at step ${index + 1}.\n" + report.joinToString("\n"))
            completed++
            context.onProgress?.invoke("Accessibility sequence: $completed/${steps.size()} steps")
        }
        ToolResult.success("Completed $completed accessibility steps.\n" + report.joinToString("\n"))
    }

    private fun com.google.gson.JsonObject.float(name: String): Float {
        val value = get(name)?.asFloat ?: error("Missing numeric coordinate '$name'.")
        require(value.isFinite()) { "Coordinate '$name' must be finite." }
        return value
    }

    private fun com.google.gson.JsonObject.long(name: String, fallback: Long): Long =
        get(name)?.asLong ?: fallback
}
