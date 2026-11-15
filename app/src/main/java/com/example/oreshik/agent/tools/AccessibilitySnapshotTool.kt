package com.example.oreshik.agent.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.oreshik.agent.accessibility.AgentAccessibilityService
import com.example.oreshik.agent.core.*
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** App-private screen baselines and bounded pixel-difference comparison for UI regression checks. */
class AccessibilitySnapshotTool : AgentTool {
    private data class SnapshotMeta(val id: String, val label: String, val width: Int, val height: Int, val sha256: String, val createdAt: Long)

    override val definition = ToolDefinition(
        name = "accessibility.snapshot",
        category = ToolCategory.SYSTEM,
        description = "Capture app-private UI screenshots, list baselines, and compare two snapshots using a bounded pixel-difference report. Uses the enabled accessibility screenshot service.",
        permissions = setOf(ToolPermission.ACCESSIBILITY),
        parameters = mapOf(
            "action" to "string (capture, list, compare)",
            "label" to "string (capture label)",
            "baseline_id" to "string (compare)",
            "current_id" to "string (compare)",
        ),
    )

    private fun directory(context: ToolContext) = File(context.app.filesDir, "ui-snapshots").apply { mkdirs() }
    private fun validId(id: String) = id.matches(Regex("^[0-9]{10,}_[A-Za-z0-9_-]{1,48}$"))
    private fun image(context: ToolContext, id: String): File {
        require(validId(id)) { "Invalid snapshot ID." }
        val root = directory(context).canonicalFile
        return File(root, "$id.png").canonicalFile.also {
            require(it.parentFile == root) { "Snapshot path escaped app storage." }
        }
    }

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val args = arguments.asJsonObject
        when (args["action"]?.asString?.lowercase()?.trim() ?: "capture") {
            "capture" -> runCatching {
                val service = AgentAccessibilityService.instance ?: error("Accessibility service is not enabled.")
                val captured = service.takeScreenshot() ?: error("Android did not provide a screenshot.")
                val scaled = try {
                    val ratio = minOf(1.0, 1280.0 / captured.width, 720.0 / captured.height)
                    if (ratio < 1.0) Bitmap.createScaledBitmap(
                        captured,
                        (captured.width * ratio).toInt().coerceAtLeast(1),
                        (captured.height * ratio).toInt().coerceAtLeast(1),
                        true,
                    ) else captured
                } catch (failure: Exception) {
                    captured.recycle()
                    throw failure
                }
                if (scaled !== captured) captured.recycle()
                try {
                    val label = args["label"]?.asString.orEmpty()
                        .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                        .take(48).ifBlank { "baseline" }
                    val id = "${System.currentTimeMillis()}_$label"
                    val file = image(context, id)
                    FileOutputStream(file).use { out ->
                        check(scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encoding failed." }
                        out.flush()
                    }
                    val digest = sha256(file.readBytes())
                    val meta = SnapshotMeta(id, label, scaled.width, scaled.height, digest, System.currentTimeMillis())
                    File(directory(context), "$id.json").writeText(Gson().toJson(meta), Charsets.UTF_8)
                    directory(context).listFiles()?.filter { it.extension == "png" }
                        ?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { old ->
                            old.delete()
                            File(directory(context), old.nameWithoutExtension + ".json").delete()
                        }
                    ToolResult.success("Captured UI snapshot $id · ${scaled.width}×${scaled.height} · SHA-256 $digest")
                } finally {
                    scaled.recycle()
                }
            }.getOrElse { ToolResult.failure("UI snapshot capture failed: ${it.message}") }

            "list" -> runCatching {
                val files = directory(context).listFiles()?.filter { it.isFile && it.extension == "png" }
                    ?.sortedByDescending { it.lastModified() }.orEmpty()
                ToolResult.success(files.joinToString("\n") { file ->
                    val meta = File(directory(context), file.nameWithoutExtension + ".json")
                    val details = runCatching { Gson().fromJson(meta.readText(), SnapshotMeta::class.java) }.getOrNull()
                    "${file.nameWithoutExtension} · ${details?.width ?: "?"}×${details?.height ?: "?"} · ${file.length()} bytes"
                }.ifBlank { "No UI snapshots yet." })
            }.getOrElse { ToolResult.failure("Could not list UI snapshots: ${it.message}") }

            "compare" -> runCatching {
                val baselineId = args["baseline_id"]?.asString.orEmpty()
                val currentId = args["current_id"]?.asString.orEmpty()
                require(baselineId.isNotBlank() && currentId.isNotBlank() && baselineId != currentId) {
                    "Provide two different baseline_id and current_id values."
                }
                val baselineFile = image(context, baselineId)
                val currentFile = image(context, currentId)
                require(baselineFile.isFile && currentFile.isFile) { "One or both snapshots were not found." }
                val baseline = BitmapFactory.decodeFile(baselineFile.absolutePath) ?: error("Could not decode baseline image.")
                val current = BitmapFactory.decodeFile(currentFile.absolutePath) ?: run {
                    baseline.recycle()
                    error("Could not decode current image.")
                }
                try {
                    require(baseline.width == current.width && baseline.height == current.height) {
                        "Snapshot dimensions differ (${baseline.width}×${baseline.height} vs ${current.width}×${current.height}); capture at the same display resolution."
                    }
                    val pixels = baseline.width.toLong() * baseline.height
                    require(pixels <= 1_000_000L) { "Comparison exceeds the 1-megapixel safety limit." }
                    val count = pixels.toInt()
                    val a = IntArray(count)
                    val b = IntArray(count)
                    baseline.getPixels(a, 0, baseline.width, 0, 0, baseline.width, baseline.height)
                    current.getPixels(b, 0, current.width, 0, 0, current.width, current.height)
                    var changed = 0L
                    var totalDifference = 0L
                    for (index in 0 until count) {
                        val ca = a[index]
                        val cb = b[index]
                        val delta = kotlin.math.abs(android.graphics.Color.red(ca) - android.graphics.Color.red(cb)) +
                            kotlin.math.abs(android.graphics.Color.green(ca) - android.graphics.Color.green(cb)) +
                            kotlin.math.abs(android.graphics.Color.blue(ca) - android.graphics.Color.blue(cb))
                        totalDifference += delta
                        if (delta >= 48) changed++
                    }
                    val percent = changed * 100.0 / count.coerceAtLeast(1)
                    val mean = totalDifference.toDouble() / (count.coerceAtLeast(1) * 3.0)
                    ToolResult.success(
                        "UI snapshot comparison\nBaseline: $baselineId\nCurrent: $currentId\n" +
                            "Resolution: ${baseline.width}×${baseline.height}\n" +
                            "Pixels changed (RGB delta ≥ 48): ${"%.2f".format(java.util.Locale.US, percent)}%\n" +
                            "Mean absolute channel difference: ${"%.2f".format(java.util.Locale.US, mean)} / 255",
                    )
                } finally {
                    baseline.recycle()
                    current.recycle()
                }
            }.getOrElse { ToolResult.failure("UI snapshot comparison failed: ${it.message}") }

            else -> ToolResult.failure("Supported actions: capture, list, compare.")
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
