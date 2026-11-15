package com.example.oreshik.agent.tools

import android.content.Intent
import com.example.oreshik.agent.accessibility.AgentAccessibilityService
import com.example.oreshik.agent.core.*
import com.google.gson.JsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun service() = AgentAccessibilityService.instance

/** Shared post-action screen observation so the model always sees the resulting state. */
private fun observeAfter(action: String): String {
    val s = service()
    return if (s != null) {
        val desc = s.describeScreen(60)
        "[Screen after $action]\n$desc"
    } else "[Screen after $action: accessibility not enabled — no observation available]"
}

class AndroidReadScreenTool : AgentTool {
    override val definition = ToolDefinition(
        name = "androidreadscreen",
        category = ToolCategory.SYSTEM,
        description = "Read the visible Android UI (accessibility tree) including element text, bounds, clickable/focusable state, and the foreground app package. Use this before and after actions to understand and verify the screen.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "max_nodes" to mapOf("type" to "integer", "description" to "Maximum number of UI nodes to describe (default: 180, range 20-300)"),
            ),
            "required" to listOf<String>(),
        ),
        permissions = setOf(ToolPermission.ACCESSIBILITY),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(ok = false, error = "Accessibility service is not enabled. Enable Oreshik Agent Accessibility in Android Settings.")
        val maxNodes = (arguments.asJsonObject["max_nodes"]?.asInt ?: 180).coerceIn(20, 300)
        val desc = s.describeScreen(maxNodes)
        // Vision computer-use: append a SHORT VLM note when enabled (best-effort,
        // bounded) so the agent gets pixels + accessibility tree in one shot.
        val visionNote = if (AgentConfig.visionAutoDescribe) {
            val bmp = s.takeScreenshot()
            val note = bmp?.let { b ->
                try {
                    com.example.oreshik.agent.vision.VisionAnalyzer.describeScreen(b)
                } finally {
                    b.recycle()
                }
            } ?: ""
            if (note.isBlank()) "" else "\n$note"
        } else ""
        ToolResult(ok = true, desc + visionNote)
    }
}

class AndroidScreenshotTool : AgentTool {
    override val definition = ToolDefinition(
        name = "android_screenshot",
        category = ToolCategory.SYSTEM,
        description = "Capture the current device screen as an image. Requires Android 11+ and the Oreshik Agent Accessibility service enabled. Returns the saved file path.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "format" to mapOf("type" to "string", "enum" to listOf("png", "jpeg"), "description" to "Image format (default: png)"),
                "quality" to mapOf("type" to "integer", "description" to "JPEG quality 1-100 (default: 90; ignored for PNG)")
            ),
            "required" to listOf<String>(),
        ),
        permissions = setOf(ToolPermission.ACCESSIBILITY),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(ok = false, error = "Accessibility service is not enabled. Enable Oreshik Agent Accessibility in Android Settings.")
        val format = arguments.asJsonObject["format"]?.asString?.lowercase()
        val compressFormat = if (format == "jpeg" || format == "jpg") android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG
        val quality = (arguments.asJsonObject["quality"]?.asInt ?: 90).coerceIn(1, 100)
        val bitmap = s.takeScreenshot()
            ?: return@withContext ToolResult(ok = false, error = "Screen capture unavailable. Requires Android 11+ with accessibility enabled, or the capture was rate-limited (wait a moment and retry).")
        val dir = context.workspace
        val ext = if (compressFormat == android.graphics.Bitmap.CompressFormat.JPEG) "jpg" else "png"
        val file = java.io.File(dir, "screen_${System.currentTimeMillis()}.$ext")
        try {
            check(dir.exists() || dir.mkdirs()) { "Could not create screenshot directory" }
            @Suppress("BlockingMethodInNonBlockingContext")
            java.io.FileOutputStream(file).use { out ->
                check(bitmap.compress(compressFormat, quality, out)) { "Could not encode screenshot" }
            }
            // Vision computer-use: append a VLM description when enabled (best-effort).
            val visionNote = if (AgentConfig.visionAutoDescribe) {
                "\n" + com.example.oreshik.agent.vision.VisionAnalyzer.describeScreen(bitmap)
            } else ""
            ToolResult(true, "Screen captured to ${file.path}$visionNote", metadata = mapOf("path" to file.absolutePath))
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * Vision computer use (VL models) via the ACTIVE provider: sends a fresh
 * screenshot to the provider's vision-language model and returns what is
 * visible plus a suggested next action (with pixel tap target). Additive to
 * the accessibility tree — when the tree is sparse (custom views, canvas apps,
 * games), this is how the agent still "sees" the screen. Progress is streamed
 * to the UI so the user always sees what vision is doing.
 */
class AndroidAnalyzeScreenTool : AgentTool {
    override val definition = ToolDefinition(
        name = "androidanalyze",
        category = ToolCategory.SYSTEM,
        description = "Capture the screen and analyze it with the active provider's vision model (sees pixels, not just the accessibility tree). Returns observations and a suggested next action (tap coordinates in real screen pixels). Use when androidreadscreen gives too little information (custom-drawn UI, images, games) or to visually verify the result of an action.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "question" to mapOf("type" to "string", "description" to "What to look for or what to accomplish on screen, e.g. 'find the Send button' (optional)"),
                "screenshot_path" to mapOf("type" to "string", "description" to "Optional path to a screenshot already saved inside the current workspace; analyzes that exact captured frame instead of taking a new one")
            ),
            "required" to listOf<String>(),
        ),
        permissions = setOf(ToolPermission.ACCESSIBILITY),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val s = service() ?: return@withContext ToolResult(ok = false, error = "Accessibility service is not enabled.")
        if ((!AgentConfig.visionAutoDescribe) && (AgentConfig.visionModel.isBlank())) {
            return@withContext ToolResult(ok = false, error = "Vision analysis disabled in Settings (enable 'Use vision models for screen analysis').")
        }
        val question = arguments.asJsonObject["question"]?.asString.orEmpty().ifBlank {
            "Identify the main interactive elements and the single best next action."
        }
        val screenshotPath = arguments.asJsonObject["screenshot_path"]?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val bitmap = if (screenshotPath.isBlank()) {
            s.takeScreenshot()
                ?: return@withContext ToolResult(false, error = "Screen capture unavailable (needs Android 11+ and accessibility enabled).")
        } else {
            val workspaceRoot = context.workspace.canonicalFile
            val requested = java.io.File(screenshotPath)
            val savedImage = (if (requested.isAbsolute) requested else java.io.File(workspaceRoot, screenshotPath)).canonicalFile
            if (savedImage != workspaceRoot && !savedImage.path.startsWith(workspaceRoot.path + java.io.File.separator)) {
                return@withContext ToolResult(false, error = "screenshot_path must point inside the current workspace.")
            }
            if (!savedImage.isFile) return@withContext ToolResult(false, error = "Saved screenshot not found: ${savedImage.name}")
            android.graphics.BitmapFactory.decodeFile(savedImage.absolutePath)
                ?: return@withContext ToolResult(false, error = "Unable to decode the saved screenshot.")
        }
        val w = bitmap.width
        val h = bitmap.height
        val onProgress = context.onProgress
        val a = try {
            com.example.oreshik.agent.vision.VisionAnalyzer.analyze(bitmap, question) { msg ->
                // Stream vision progress to the UI so it's never silent. The progress
                // channel is suspend; launch on the engine's IO dispatcher (fire-and-forget).
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { onProgress?.invoke(msg) }
                }
            }
        } finally {
            bitmap.recycle()
        }
        if (!a.ok) return@withContext ToolResult(ok = false, error = "Vision analysis failed: ${a.error}")
        // The VLM saw an image with its largest dimension capped at 1280; map
        // its tap target back to the original screen's pixel coordinates.
        val largestDimension = maxOf(w, h)
        val coordinateScale = largestDimension.toFloat() / minOf(largestDimension, 1280)
        val realX = a.tapX?.let { (it * coordinateScale).toInt().coerceIn(0, w - 1) }
        val realY = a.tapY?.let { y -> (y * coordinateScale).toInt().coerceIn(0, h - 1) }
        val report = buildString {
            append(a.report)
            if ((realX != null) && (realY != null)) {
                append("\n[Vision] Tap target mapped to real screen: ($realX,$realY) of ${w}x$h")
            }
        }
        ToolResult(
            ok = true,
            report,
            metadata = buildMap {
                put("observations", a.observations)
                a.nextAction?.let { action -> put("next_action", action) }
                if ((realX != null) && (realY != null)) {
                    put("tap_x", realX)
                    put("tap_y", realY)
                }
            },
        )
    }
}

class AndroidTapTool : AgentTool {
    override val definition = ToolDefinition(
        name = "android_tap",
        category = ToolCategory.SYSTEM,
        description = "Tap the screen at exact pixel coordinates (x, y). Use androidreadscreen first to find the right coordinates.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "x" to mapOf("type" to "integer", "description" to "X coordinate in pixels"),
                "y" to mapOf("type" to "integer", "description" to "Y coordinate in pixels")
            ),
            "required" to listOf("x", "y")
        ),
        permissions = setOf(ToolPermission.ACCESSIBILITY),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(false, error = "Accessibility service is not enabled.")
        val args = arguments.asJsonObject
        val x = args["x"]?.asInt ?: return@withContext ToolResult(ok = false, error = "Missing x coordinate")
        val y = args.get("y")?.asInt ?: return@withContext ToolResult(false, error = "Missing y coordinate")
        val (w, h) = s.screenSize()
        if (x !in 0 until w || y !in 0 until h) return@withContext ToolResult(ok = false, error = "Coordinates ($x,$y) outside screen bounds ${w}x$h")
        val ok = s.tap(x.toFloat(), y.toFloat())
        ToolResult(ok, if (ok) observeAfter("tap($x,$y)") else "", if (!ok) "Android rejected the tap gesture." else null)
    }
}

class AndroidTapTextTool : AgentTool {
    override val definition = ToolDefinition(
        name = "androidtaptext",
        category = ToolCategory.SYSTEM,
        description = "Tap a visible Android UI element by matching its visible text or content description.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "text" to mapOf("type" to "string", "description" to "Visible text or content description of the element to tap")
            ),
            "required" to listOf("text")
        ),
        permissions = setOf(ToolPermission.ACCESSIBILITY),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(false, error = "Accessibility service is not enabled.")
        val text = arguments.asJsonObject.get("text")?.asString.orEmpty()
        if (text.isBlank()) return@withContext ToolResult(false, error = "Missing text")
        val ok = s.clickElement(text)
        ToolResult(ok, if (ok) observeAfter("tap \"$text\"") else "", if (!ok) "No clickable element matched: $text" else null)
    }
}

class AndroidClickElementTool : AgentTool {
    override val definition = ToolDefinition(
        name = "androidclickelement",
        category = ToolCategory.SYSTEM,
        description = "Click a visible Android UI element by its text or content description. Prefers exact matches, falls back to substring matches. Walks up the view hierarchy to find the nearest clickable container.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "text" to mapOf("type" to "string", "description" to "Text or content description of the element to click")
            ),
            "required" to listOf("text")
        ),
        permissions = setOf(ToolPermission.ACCESSIBILITY),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(false, error = "Accessibility service is not enabled.")
        val text = arguments.asJsonObject.get("text")?.asString.orEmpty()
        if (text.isBlank()) return@withContext ToolResult(false, error = "Missing text")
        val ok = s.clickElement(text)
        ToolResult(ok, if (ok) observeAfter("click \"$text\"") else "", if (!ok) "No clickable element matched: $text" else null)
    }
}

class AndroidTypeTextTool : AgentTool {
    override val definition = ToolDefinition(
        name = "androidtypetext",
        category = ToolCategory.SYSTEM,
        description = "Type text into the currently focused editable Android UI element (text field). Focus an input first (e.g. by tapping it).",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "text" to mapOf("type" to "string", "description" to "Text to type into the focused field")
            ),
            "required" to listOf("text")
        ),
        permissions = setOf(ToolPermission.ACCESSIBILITY),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(false, error = "Accessibility service is not enabled.")
        val text = arguments.asJsonObject.get("text")?.asString ?: return@withContext ToolResult(false, error = "Missing text")
        val ok = s.typeText(text)
        ToolResult(ok, if (ok) observeAfter("type text") else "", if (!ok) "No editable focused element. Tap the input field first." else null)
    }
}

class AndroidSwipeTool : AgentTool {
    override val definition = ToolDefinition(
        name = "android_swipe",
        category = ToolCategory.SYSTEM,
        description = "Swipe/drag from one screen point to another over a configurable duration.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "x1" to mapOf("type" to "integer", "description" to "Start X in pixels"),
                "y1" to mapOf("type" to "integer", "description" to "Start Y in pixels"),
                "x2" to mapOf("type" to "integer", "description" to "End X in pixels"),
                "y2" to mapOf("type" to "integer", "description" to "End Y in pixels"),
                "duration_ms" to mapOf("type" to "integer", "description" to "Gesture duration in ms (default: 350)")
            ),
            "required" to listOf("x1", "y1", "x2", "y2")
        ),
        permissions = setOf(ToolPermission.ACCESSIBILITY),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(false, error = "Accessibility service is not enabled.")
        val args = arguments.asJsonObject
        val x1 = args.get("x1")?.asInt ?: return@withContext ToolResult(false, error = "Missing x1")
        val y1 = args.get("y1")?.asInt ?: return@withContext ToolResult(false, error = "Missing y1")
        val x2 = args.get("x2")?.asInt ?: return@withContext ToolResult(false, error = "Missing x2")
        val y2 = args.get("y2")?.asInt ?: return@withContext ToolResult(false, error = "Missing y2")
        val duration = (args.get("duration_ms")?.asInt ?: 350).coerceIn(80, 5000)
        val (width, height) = s.screenSize()
        if (x1 !in 0 until width || x2 !in 0 until width || y1 !in 0 until height || y2 !in 0 until height) {
            return@withContext ToolResult(false, error = "Swipe coordinates must be inside screen bounds ${width}x$height")
        }
        val ok = s.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration.toLong())
        ToolResult(ok, if (ok) observeAfter("swipe ($x1,$y1)->($x2,$y2)") else "", if (!ok) "Android rejected the swipe gesture." else null)
    }
}

class AndroidScrollTool : AgentTool {
    override val definition = ToolDefinition(
        name = "android_scroll",
        category = ToolCategory.SYSTEM,
        description = "Scroll the active Android UI. Supports direction (forward/backward), or an explicit pixel swipe.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "direction" to mapOf("type" to "string", "description" to "Scroll direction: forward or backward (default: forward)"),
                "amount" to mapOf("type" to "integer", "description" to "Optional pixel distance for a gesture scroll instead of node scroll")
            ),
            "required" to listOf<String>(),
        ),
        permissions = setOf(ToolPermission.ACCESSIBILITY),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(false, error = "Accessibility service is not enabled.")
        val args = arguments.asJsonObject
        val forward = args.get("direction")?.asString?.lowercase() != "backward"
        val amount = args.get("amount")?.asInt ?: 0
        val ok = if (amount > 0) {
            val (w, h) = s.screenSize()
            val cy = h / 2
            val halfDistance = amount.coerceIn(1, (h - 1).coerceAtLeast(1)) / 2
            if (forward) {
                s.swipe((w / 2).toFloat(), (cy + halfDistance).toFloat(), (w / 2).toFloat(), (cy - halfDistance).toFloat())
            } else {
                s.swipe((w / 2).toFloat(), (cy - halfDistance).toFloat(), (w / 2).toFloat(), (cy + halfDistance).toFloat())
            }
        } else {
            s.scroll(forward)
        }
        ToolResult(ok, if (ok) observeAfter("scroll ${if (forward) "forward" else "backward"}") else "", if (!ok) "No scrollable node accepted the action." else null)
    }
}

class AndroidLongPressTool : AgentTool {
    override val definition = ToolDefinition(
        name = "androidlongpress",
        category = ToolCategory.SYSTEM,
        description = "Long-press the screen at exact pixel coordinates (x, y).",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "x" to mapOf("type" to "integer", "description" to "X coordinate in pixels"),
                "y" to mapOf("type" to "integer", "description" to "Y coordinate in pixels"),
                "duration_ms" to mapOf("type" to "integer", "description" to "Press duration in ms (default: 700)")
            ),
            "required" to listOf("x", "y")
        ),
        permissions = setOf(ToolPermission.ACCESSIBILITY),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(false, error = "Accessibility service is not enabled.")
        val args = arguments.asJsonObject
        val x = args["x"]?.asInt ?: return@withContext ToolResult(ok = false, error = "Missing x coordinate")
        val y = args.get("y")?.asInt ?: return@withContext ToolResult(false, error = "Missing y coordinate")
        val duration = (args.get("duration_ms")?.asInt ?: 700).coerceIn(300, 5000)
        val (width, height) = s.screenSize()
        if (x !in 0 until width || y !in 0 until height) {
            return@withContext ToolResult(false, error = "Coordinates ($x,$y) outside screen bounds ${width}x$height")
        }
        val ok = s.longPress(x.toFloat(), y.toFloat(), duration.toLong())
        ToolResult(ok, if (ok) observeAfter("long-press($x,$y)") else "", if (!ok) "Android rejected the long-press gesture." else null)
    }
}

class AndroidPressBackTool : AgentTool {
    override val definition = ToolDefinition(
        name = "androidpressback",
        category = ToolCategory.SYSTEM,
        description = "Press the Android Back button (global action).",
        parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any?>(), "required" to listOf<String>()),
        permissions = setOf(ToolPermission.ACCESSIBILITY),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(false, error = "Accessibility service is not enabled.")
        val ok = s.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        ToolResult(ok, if (ok) observeAfter("back") else "", if (!ok) "Android rejected the back action." else null)
    }
}

class AndroidPressHomeTool : AgentTool {
    override val definition = ToolDefinition(
        name = "androidpresshome",
        category = ToolCategory.SYSTEM,
        description = "Press the Android Home button (global action).",
        parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any?>(), "required" to listOf<String>()),
        permissions = setOf(ToolPermission.ACCESSIBILITY),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val s = service() ?: return@withContext ToolResult(false, error = "Accessibility service is not enabled.")
        val ok = s.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        ToolResult(ok, if (ok) observeAfter("home") else "", if (!ok) "Android rejected the home action." else null)
    }
}

class AndroidOpenAppTool : AgentTool {
    override val definition = ToolDefinition(
        name = "androidopenapp",
        category = ToolCategory.SYSTEM,
        description = "Open an installed app by package name (e.g. com.example.app) or by display name (e.g. Settings).",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "package" to mapOf("type" to "string", "description" to "Package name of the app to open"),
                "name" to mapOf("type" to "string", "description" to "Display name of the app to open (used when package is not provided)")
            ),
            "required" to listOf<String>()
        ),
        permissions = setOf(ToolPermission.ACCESSIBILITY),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val args = arguments.asJsonObject
        val pkg = args.get("package")?.asString.orEmpty()
        val name = args.get("name")?.asString.orEmpty()
        val pm = context.app.packageManager

        val resolvedPackage = when {
            pkg.isNotBlank() -> pkg
            name.isNotBlank() -> {
                // Resolve display name to package
                val launches = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                launches.firstOrNull { it.loadLabel(pm).toString().equals(name, ignoreCase = true) }?.activityInfo?.packageName
                    ?: launches.firstOrNull { it.activityInfo.packageName.contains(name, ignoreCase = true) }?.activityInfo?.packageName
                    ?: return@withContext ToolResult(false, error = "No installed app matches name: $name")
            }
            else -> return@withContext ToolResult(false, error = "Provide either package or name")
        }

        val launch = pm.getLaunchIntentForPackage(resolvedPackage)
            ?: return@withContext ToolResult(false, error = "App $resolvedPackage is not installed or has no launcher activity")

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return@withContext try {
            context.app.startActivity(launch)
            // Return as soon as Android switches to the requested app; cap the wait
            // so slow launches cannot stall the agent indefinitely.
            val deadline = android.os.SystemClock.uptimeMillis() + 1200L
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                if (service()?.activePackageName() == resolvedPackage) break
                delay(100)
            }
            ToolResult(true, observeAfter("open $resolvedPackage"))
        } catch (e: Exception) {
            ToolResult(false, error = "Failed to open $resolvedPackage: ${e.message}")
        }
    }
}
