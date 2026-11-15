package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.example.oreshik.agent.runtime.RuntimeManager
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal suspend fun runManaged(
    context: ToolContext,
    runtime: RuntimeManager,
    executable: String,
    args: List<String>,
    timeoutSeconds: Long,
    @Suppress("UNUSED_PARAMETER") permission: ToolPermission = ToolPermission.EXECUTE_PROCESS,
): ToolResult = withContext(Dispatchers.IO) {
    val exe = runtime.resolveExecutable(executable)
        ?: return@withContext ToolResult(
            ok = false,
            error = "$executable runtime is not installed. Install an Android-compatible runtime pack in Agent Tools → Runtime Manager.",
        )
    if (!exe.canExecute()) return@withContext ToolResult(ok = false, error = "Runtime executable is not executable: ${exe.absolutePath}")
    val command = mutableListOf(exe.absolutePath).apply { addAll(args) }
    try {
        AppLogger.d("RuntimeTools", "runManaged: $executable (${args.size} arg(s), timeout=${timeoutSeconds}s)")
        val pb = ProcessBuilder(command).directory(context.workspace).redirectErrorStream(true)
        pb.environment().putAll(runtime.environment())
        val p = pb.start()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val future = executor.submit<String> { p.inputStream.bufferedReader().use { it.readTextLimited(1_000_000) } }
        val finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            p.destroy(); if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
            val partial = runCatching { future[2, TimeUnit.SECONDS] }.getOrDefault("[process output unavailable]")
            future.cancel(true); executor.shutdownNow()
            ToolResult(ok = false, output = partial, error = "Timed out after ${timeoutSeconds}s")
        } else {
            val text = runCatching { future[2, TimeUnit.SECONDS] }.getOrDefault("[process output unavailable]")
            executor.shutdownNow()
            ToolResult(
                p.exitValue() == 0,
                output = text,
                error = if (p.exitValue() == 0) null else "$executable exited with code ${p.exitValue()}",
                exitCode = p.exitValue(),
            )
        }
    } catch (e: Exception) {
        AppLogger.e("RuntimeTools", "$executable failed: ${e.message}", e)
        ToolResult(ok = false, error = "$executable: ${e.message}")
    }
}

private fun java.io.Reader.readTextLimited(max: Int): String {
    val buf = CharArray(8192); val out = StringBuilder(); var total = 0; var truncated = false
    while (true) {
        val n = read(buf); if (n < 0) break
        val take = minOf(n, (max - total).coerceAtLeast(0))
        if (take > 0) out.appendRange(buf, 0, take)
        total += take
        if (take < n) truncated = true
    }
    if (truncated) out.append("\n[output truncated]")
    return out.toString()
}

class RuntimeInfoTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "runtime.info",
        category = ToolCategory.RUNTIME,
        description = "Report all app-owned developer runtime capabilities and versions.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS),
        parameters = emptyMap(),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val caps = runtime.capabilityReport().entries.joinToString("\n") { (k, v) -> "$k=${if (v) "READY" else "MISSING"}" }
        val inventory = runtime.inventory()
        val packLines = ArrayList<String>(inventory.size)
        for (i in inventory.indices) {
            packLines += inventory[i].id + " " + inventory[i].version + ": " + (if (inventory[i].ready) "READY" else "NOT READY")
        }
        AppLogger.d("RuntimeTools", "runtime.info: ${inventory.size} pack(s)")
        ToolResult(ok = true, "Capabilities:\n$caps\n\nRuntime packs:\n${packLines.joinToString("\n")}")
    }
}

class NodeExecTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "node.exec",
        category = ToolCategory.RUNTIME,
        description = "Run Node.js with arguments inside the project workspace.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS),
        parameters = mapOf("args" to "array", "timeout_seconds" to "integer"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject; val args = o.getAsJsonArray("args")?.map { it.asString } ?: emptyList(); val t = (o["timeout_seconds"]?.asLong ?: 120).coerceIn(1, 900)
        return runManaged(context, runtime, "node", args, t)
    }
}

class NpmExecTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "npm.exec",
        category = ToolCategory.RUNTIME,
        description = "Run the app-owned npm CLI with arguments inside the project workspace.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS, ToolPermission.NETWORK),
        parameters = mapOf("args" to "array", "timeout_seconds" to "integer"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject; val args = o.getAsJsonArray("args")?.map { it.asString } ?: emptyList(); val t = (o["timeout_seconds"]?.asLong ?: 600).coerceIn(1, 1800)
        return runManaged(context, runtime, "npm", args, t)
    }
}

class JavaExecTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "java.exec",
        category = ToolCategory.RUNTIME,
        description = "Run the app-owned Java runtime with arguments inside the project workspace.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS),
        parameters = mapOf("args" to "array", "timeout_seconds" to "integer"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject; val args = o.getAsJsonArray("args")?.map { it.asString } ?: emptyList(); val t = (o["timeout_seconds"]?.asLong ?: 120).coerceIn(1, 900)
        return runManaged(context, runtime, "java", args, t)
    }
}

class JavacExecTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "javac.exec",
        category = ToolCategory.RUNTIME,
        description = "Run the app-owned javac compiler with arguments inside the project workspace.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS, ToolPermission.BUILD),
        parameters = mapOf("args" to "array", "timeout_seconds" to "integer"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject; val args = o.getAsJsonArray("args")?.map { it.asString } ?: emptyList(); val t = (o["timeout_seconds"]?.asLong ?: 300).coerceIn(1, 1200)
        return runManaged(context, runtime, "javac", args, t)
    }
}

class GradleExecTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "gradle.exec",
        category = ToolCategory.RUNTIME,
        description = "Run the app-owned Gradle 8 engine with arguments inside the project workspace. " +
            "Prefer ./gradlew when a project wrapper is present and compatible; use gradle9.exec " +
            "when a project requires the Gradle 9 runtime.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS, ToolPermission.BUILD),
        parameters = mapOf("args" to "array", "timeout_seconds" to "integer"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject; val args = o.getAsJsonArray("args")?.map { it.asString } ?: listOf("tasks"); val t = (o["timeout_seconds"]?.asLong ?: 900).coerceIn(1, 3600)
        return runManaged(context, runtime, "gradle", args, t)
    }
}

class Gradle9ExecTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "gradle9.exec",
        category = ToolCategory.RUNTIME,
        description = "Run the app-owned Gradle 9 engine with arguments inside the project workspace. " +
            "Use this when a project requires the Gradle 9 runtime; the default gradle.exec is Gradle 8.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS, ToolPermission.BUILD),
        parameters = mapOf("args" to "array", "timeout_seconds" to "integer"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject; val args = o.getAsJsonArray("args")?.map { it.asString } ?: listOf("tasks"); val t = (o["timeout_seconds"]?.asLong ?: 900).coerceIn(1, 3600)
        return runManaged(context, runtime, "gradle9", args, t)
    }
}

class KotlinExecTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "kotlinc.exec",
        category = ToolCategory.RUNTIME,
        description = "Run the app-owned Kotlin compiler with arguments inside the project workspace.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS, ToolPermission.BUILD),
        parameters = mapOf("args" to "array", "timeout_seconds" to "integer"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject; val args = o.getAsJsonArray("args")?.map { it.asString } ?: emptyList(); val t = (o["timeout_seconds"]?.asLong ?: 600).coerceIn(1, 1800)
        return runManaged(context, runtime, "kotlinc", args, t)
    }
}

class PhpExecTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "php.exec",
        category = ToolCategory.RUNTIME,
        description = "Run the app-owned PHP interpreter with arguments inside the project workspace.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS),
        parameters = mapOf("args" to "array", "timeout_seconds" to "integer"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject; val args = o.getAsJsonArray("args")?.map { it.asString } ?: emptyList(); val t = (o["timeout_seconds"]?.asLong ?: 120).coerceIn(1, 900)
        return runManaged(context, runtime, "php", args, t)
    }
}

class ComposerExecTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "composer.exec",
        category = ToolCategory.RUNTIME,
        description = "Run the app-owned Composer PHP package manager with arguments inside the project workspace.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS, ToolPermission.NETWORK),
        parameters = mapOf("args" to "array", "timeout_seconds" to "integer"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject; val args = o.getAsJsonArray("args")?.map { it.asString } ?: emptyList(); val t = (o["timeout_seconds"]?.asLong ?: 600).coerceIn(1, 1800)
        return runManaged(context, runtime, "composer", args, t)
    }
}

class RubyExecTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "ruby.exec",
        category = ToolCategory.RUNTIME,
        description = "Run the app-owned Ruby runtime with arguments inside the project workspace.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS),
        parameters = mapOf("args" to "array", "timeout_seconds" to "integer"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject; val args = o.getAsJsonArray("args")?.map { it.asString } ?: emptyList(); val t = (o["timeout_seconds"]?.asLong ?: 120).coerceIn(1, 900)
        return runManaged(context, runtime, "ruby", args, t)
    }
}

class ClangExecTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "clang.exec",
        category = ToolCategory.RUNTIME,
        description = "Run the app-owned Clang C/C++ compiler with arguments inside the project workspace.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS, ToolPermission.BUILD),
        parameters = mapOf("args" to "array", "timeout_seconds" to "integer"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject; val args = o.getAsJsonArray("args")?.map { it.asString } ?: emptyList(); val t = (o["timeout_seconds"]?.asLong ?: 300).coerceIn(1, 1200)
        return runManaged(context, runtime, "clang", args, t)
    }
}

class MakeExecTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "make.exec",
        category = ToolCategory.RUNTIME,
        description = "Run the app-owned Make build tool with arguments inside the project workspace.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS, ToolPermission.BUILD),
        parameters = mapOf("args" to "array", "timeout_seconds" to "integer"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject; val args = o.getAsJsonArray("args")?.map { it.asString } ?: emptyList(); val t = (o["timeout_seconds"]?.asLong ?: 600).coerceIn(1, 1800)
        return runManaged(context, runtime, "make", args, t)
    }
}
