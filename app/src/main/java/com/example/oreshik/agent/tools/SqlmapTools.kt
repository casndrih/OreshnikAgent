package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.AgentTool
import com.example.oreshik.agent.core.ToolCategory
import com.example.oreshik.agent.core.ToolContext
import com.example.oreshik.agent.core.ToolDefinition
import com.example.oreshik.agent.core.ToolPermission
import com.example.oreshik.agent.core.ToolResult
import com.example.oreshik.agent.runtime.RuntimeManager
import com.google.gson.JsonElement
import java.io.File
import java.net.URI

/** Report whether the official bundled sqlmap runtime pack is ready. */
class SqlmapStatusTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "runtime.sqlmap.status",
        description = "Check whether the official sqlmap engine bundled with the app is unpacked and ready in the private runtime directory.",
        category = ToolCategory.RUNTIME,
        permissions = emptySet(),
        parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val status = runtime.inventory().firstOrNull { it.id == "sqlmap" }
            ?: return ToolResult.failure("sqlmap is missing from the runtime registry")
        return ToolResult.success(
            output = if (status.ready) {
                "Official sqlmap ${status.version} is ready at ${status.root.absolutePath}."
            } else {
                "Official sqlmap ${RuntimeManager.SQLMAP_VERSION} is bundled with the app but has not been unpacked. Use runtime.sqlmap.install or run web.sqlmap."
            },
            metadata = mapOf("installed" to status.ready, "version" to status.version, "path" to status.root.absolutePath),
        )
    }
}

/** Unpack the official sqlmap wheel already shipped inside the APK. */
class SqlmapInstallTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "runtime.sqlmap.install",
        description = "Unpack the official sqlmap ${RuntimeManager.SQLMAP_VERSION} engine bundled in the APK into the app-private runtime directory. No download is needed; the upstream GPL license is preserved.",
        category = ToolCategory.RUNTIME,
        permissions = emptySet(),
        parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val result = runCatching {
            runtime.ensureSqlmapPack { step -> context.onProgress?.invoke(step) }
        }.getOrElse {
            return ToolResult.failure("sqlmap runtime setup failed: ${it.message}")
        }
        return ToolResult.success(
            output = "Bundled sqlmap ${result.version} is ready at ${result.root.absolutePath}.",
            metadata = mapOf("runtime" to result.id, "version" to result.version, "path" to result.root.absolutePath),
        )
    }
}

/** Structured, conservative interface to the official sqlmap command-line engine. */
class SqlmapScanTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "web.sqlmap",
        description = "Run the official sqlmap engine bundled in the app against an authorized HTTP(S) target. Defaults use low intensity (level 1, risk 1, BEU techniques, one thread); output and session files stay under the app workspace's .sqlmap directory. Console output is returned as-is.",
        category = ToolCategory.WEB,
        permissions = setOf(ToolPermission.EXECUTE_PYTHON, ToolPermission.NETWORK, ToolPermission.WRITE_WORKSPACE),
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "url" to mapOf("type" to "string", "description" to "HTTP(S) target URL, usually with a query parameter to test"),
                "authorized_target" to mapOf("type" to "boolean", "description" to "Confirm that you own the target or have permission to test it"),
                "parameter" to mapOf("type" to "string", "description" to "Optional parameter name to test (sqlmap -p)"),
                "method" to mapOf("type" to "string", "description" to "Optional HTTP method, such as POST"),
                "data" to mapOf("type" to "string", "description" to "Optional request body for form or API requests"),
                "cookie" to mapOf("type" to "string", "description" to "Optional Cookie header value for an authorized authenticated test"),
                "headers" to mapOf("type" to "string", "description" to "Optional additional HTTP headers, one per line"),
                "level" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 5, "description" to "Test level (default 1)"),
                "risk" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 3, "description" to "Test risk (default 1)"),
                "technique" to mapOf("type" to "string", "description" to "Combination of B, E, U, S, T, Q; defaults to BEU (boolean, error, union)"),
                "threads" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 5, "description" to "Concurrent HTTP requests (default 1, maximum 5)"),
                "request_timeout_seconds" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 30, "description" to "Per-request HTTP timeout (default 10 seconds)"),
            ),
            "required" to listOf("url", "authorized_target"),
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val input = arguments.asJsonObject
        val authorized = input["authorized_target"]?.asBoolean == true
        if (!authorized) return ToolResult.failure("Set authorized_target=true only when you own this target or have permission to test it.")

        val url = input["url"]?.asString?.trim().orEmpty()
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return ToolResult.failure("url must be a valid HTTP or HTTPS URL with a host")
        }

        val method = input["method"]?.asString?.trim()?.uppercase()
        if (method != null && !method.matches(Regex("^[A-Z]{3,10}$"))) {
            return ToolResult.failure("method must be an HTTP method name")
        }
        val level = input["level"]?.asInt ?: 1
        val risk = input["risk"]?.asInt ?: 1
        val threads = input["threads"]?.asInt ?: 1
        val requestTimeout = input["request_timeout_seconds"]?.asInt ?: 10
        if (level !in 1..5) return ToolResult.failure("level must be between 1 and 5")
        if (risk !in 1..3) return ToolResult.failure("risk must be between 1 and 3")
        if (threads !in 1..5) return ToolResult.failure("threads must be between 1 and 5")
        if (requestTimeout !in 1..30) return ToolResult.failure("request_timeout_seconds must be between 1 and 30")

        val technique = input["technique"]?.asString?.uppercase()?.ifBlank { "BEU" } ?: "BEU"
        if (technique.any { it !in "BEUSTQ" } || technique.toSet().size != technique.length) {
            return ToolResult.failure("technique may contain each of B, E, U, S, T, and Q at most once")
        }

        val workspaceRoot = context.workspace.canonicalFile
        val outputDir = File(workspaceRoot, ".sqlmap/output").canonicalFile
        if (!outputDir.path.startsWith(workspaceRoot.path + File.separator)) {
            return ToolResult.failure("sqlmap output directory must remain inside the app workspace")
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            return ToolResult.failure("Unable to create sqlmap output directory")
        }

        val sqlmapPack = runCatching {
            runtime.ensureSqlmapPack { step -> context.onProgress?.invoke(step) }
        }.getOrElse {
            return ToolResult.failure("Bundled sqlmap runtime setup failed: ${it.message}")
        }

        val argv = buildList {
            add("-u")
            add(url)
            add("--batch")
            add("--disable-coloring")
            add("--level=$level")
            add("--risk=$risk")
            add("--technique=$technique")
            add("--threads=$threads")
            add("--timeout=$requestTimeout")
            add("--retries=1")
            add("--output-dir=${outputDir.absolutePath}")
            input["parameter"]?.asString?.trim()?.takeIf { it.isNotEmpty() }?.let { add("-p"); add(it) }
            method?.let { add("--method=$it") }
            input["data"]?.asString?.takeIf { it.isNotEmpty() }?.let { add("--data"); add(it) }
            input["cookie"]?.asString?.takeIf { it.isNotEmpty() }?.let { add("--cookie"); add(it) }
            input["headers"]?.asString?.takeIf { it.isNotBlank() }?.let { add("--headers"); add(it) }
        }

        context.onProgress?.invoke("Running sqlmap against the authorized target…")
        val result = runtime.python.executeSqlmap(argv, workspaceRoot, sqlmapPack.root).getOrElse {
            return ToolResult.failure("sqlmap execution failed: ${it.message}")
        }
        val output = buildString {
            append(result.stdout)
            if (result.stderr.isNotBlank()) append("\nSTDERR:\n").append(result.stderr)
            if (result.error != null && result.stderr.isBlank()) append(result.error)
        }
        return ToolResult(
            ok = result.ok,
            output = output,
            error = result.error,
            exitCode = result.exitCode,
            metadata = mapOf("runtime" to "sqlmap", "version" to sqlmapPack.version, "output_directory" to outputDir.absolutePath),
        )
    }
}
