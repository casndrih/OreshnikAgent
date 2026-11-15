package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.example.oreshik.agent.runtime.RuntimeManager
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Native Ghidra 12.1.2 headless suite — direct JVM integration with the bundled
 * `filesDir/runtimes/ghidra` pack, mirroring the conventions of the IDA tool file:
 *
 *  - `re.ghidra.capabilities`  — runtime/JDK/native-helper detection
 *  - `re.ghidra.analyze`       — real headless auto-analysis + section export (pipeline)
 *  - `re.ghidra.decompile`     — decompilation when the native helper can run
 *  - `re.ghidra.xref`          — references to/from an address or symbol
 *  - `re.ghidra.annotate`      — comments, renames, bookmarks, C header struct types
 *  - `re.ghidra.project`       — list / open / delete of on-device headless projects
 *  - `re.ghidra.run_script`    — agent-authored Java GhidraScripts
 *
 * On-device launch constraint (verified from `ToolPackInstaller` + `launch.sh`):
 * the pack's `analyzeHeadless`/`launch.sh` shell chain cannot run on Android because the
 * installer rewrites `#!/usr/bin/env bash` shebangs to `#!/system/bin/sh` (mksh has no
 * process substitution). Therefore this suite builds the exact command line `launch.sh`
 * would have produced and launches the bundled JDK's `java` directly:
 *
 *   java -Djava.system.class.loader=ghidra.GhidraClassLoader -Dfile.encoding=UTF-8 \
 *        -Duser.country=US -Duser.language=en -Xshare:off -Djava.awt.headless=true \
 *        -XX:ParallelGCThreads=2 -XX:CICompilerCount=2 -Xmx<mem> \
 *        -cp <ghidra>/Ghidra/Framework/Utility/lib/Utility.jar ghidra.Ghidra \
 *        ghidra.app.util.headless.AnalyzeHeadless <args…>
 *
 * (bootstrap arg convention `args[0] = launchable class` confirmed by bytecode of
 * `ghidra.GhidraLauncher.launch` in Utility.jar 12.1.2.)
 *
 * Generated scripts ([GhidraScriptSources]) are real `GhidraScript` Java sources compiled
 * by Ghidra itself on invocation (`-scriptPath` + `-postScript`). Parameters travel as
 * `KEY=value` argv (ProcessBuilder — no shell, no quoting hazards). Every output is
 * bounded; failures are reported honestly, never faked.
 */
class GhidraToolFactory(private val runtime: RuntimeManager) {

    companion object {
        private const val TAG = "GhidraTools"

        private const val PACK_ID = "ghidra"
        private const val GHIDRA_INSTALL_DIR = "ghidra"
        private const val BOOTSTRAP_JAR = "Ghidra/Framework/Utility/lib/Utility.jar"
        private const val HEADLESS_CLASS = "ghidra.app.util.headless.AnalyzeHeadless"
        private const val BOOTSTRAP_CLASS = "ghidra.Ghidra"

        const val WORKSPACE_PROJECTS_DIR = "ghidra_projects"
        const val WORKSPACE_SCRIPTS_DIR = "ghidra_scripts"
        const val WORKSPACE_OUT_DIR = "ghidra_out"

        private const val DEFAULT_MAX_MEM_MB = 1536
        private const val ABSOLUTE_MAX_MEM_MB = 4096
        private const val MAX_INLINE_OUTPUT = 512 * 1024
        private const val MAX_ENVELOPE_FILE = 16L * 1024 * 1024

        /** Generated script file names paired with their source. */
        private val GENERATED_SCRIPTS = linkedMapOf(
            "CdxGhidraPipeline.java" to GhidraScriptSources.cdxPipeline(),
            "CdxGhidraExport.java" to GhidraScriptSources.cdxExport(),
            "CdxGhidraAnnotate.java" to GhidraScriptSources.cdxAnnotate(),
            "CdxGhidraXref.java" to GhidraScriptSources.cdxXref(),
        )

        private fun timestamp(): String =
            java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
                .format(java.util.Date()) + "_" + java.util.UUID.randomUUID().toString().take(8)

        private fun safeName(base: String): String =
            base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "binary" }

        private fun bounded(text: String): String =
            if (text.length <= MAX_INLINE_OUTPUT) text
            else text.take(MAX_INLINE_OUTPUT) + "\n[output truncated at $MAX_INLINE_OUTPUT chars]"
    }

    private val gson = com.google.gson.Gson()

    // ------------------------------------------------------------------
    // Bridge — runtime pack access + headless JVM launch
    // ------------------------------------------------------------------

    internal inner class Bridge {
        fun packRoot(): File = runtime.runtimePath(PACK_ID)

        /** Ghidra install dir (contains Ghidra/); handles nested or flat pack layouts. */
        fun home(): File? {
            val root = packRoot()
            val nested = File(root, GHIDRA_INSTALL_DIR)
            if (File(nested, BOOTSTRAP_JAR).isFile) return nested
            if (File(root, BOOTSTRAP_JAR).isFile) return root
            return null
        }

        fun isInstalled(): Boolean = home() != null

        fun javaExecutable(): File? = runtime.resolveExecutable("java")

        fun version(): String {
            val h = home() ?: return ""
            val props = File(h, "Ghidra/application.properties")
            if (!props.isFile) return ""
            return runCatching {
                props.readLines().firstOrNull { it.startsWith("application.version=") }
                    ?.substringAfter('=')?.trim().orEmpty()
            }.getOrDefault("")
        }

        /** Honest native-helper availability (shipped binaries are glibc x86_64). */
        fun nativeHelperInfo(): Map<String, Any> {
            val h = home()
            val decompile = h?.let { File(it, "Ghidra/Features/Decompiler/os/linux_x86_64/decompile") }
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val x86ish = abi.contains("x86_64") || abi.contains("x86")
            return mapOf(
                "decompile_present" to (decompile?.isFile == true),
                "decompile_plausible" to (decompile?.isFile == true && x86ish),
                "abi" to abi,
            )
        }

        /** JVM args replicating analyzeHeadless + launch.properties (verified). */
        private fun vmArgs(maxMemMb: Int): List<String> = listOf(
            "-Djava.system.class.loader=ghidra.GhidraClassLoader",
            "-Dfile.encoding=UTF-8",
            "-Duser.country=US",
            "-Duser.language=en",
            "-Duser.variant=",
            "-Djdk.tls.client.protocols=TLSv1.2,TLSv1.3",
            "-Djavax.xml.accessExternalDTD=",
            "-Djavax.xml.accessExternalSchema=",
            "-Djavax.xml.accessExternalStylesheet=",
            "-Djava.awt.headless=true",
            "-XX:ParallelGCThreads=2",
            "-XX:CICompilerCount=2",
            "-Xshare:off",
            "-Xmx${maxMemMb}M",
        )

        /**
         * Launch AnalyzeHeadless directly on the bundled JDK. Mirrors
         * `analyzeHeadless → launch.sh` without any shell (impossible on Android).
         */
        suspend fun analyzeHeadless(
            context: ToolContext,
            args: List<String>,
            timeoutSeconds: Long,
            maxMemMb: Int = DEFAULT_MAX_MEM_MB,
        ): ToolResult {
            val home = home()
                ?: return ToolResult(ok = false, error = "Ghidra runtime pack is not installed (filesDir/runtimes/ghidra). Install it from Agent Tools → Runtime Manager.")
            if (javaExecutable() == null) {
                return ToolResult(ok = false, error = "Bundled JDK not found. Install the 'jdk' pack (Ghidra requires JDK 21).")
            }
            val cmd = mutableListOf<String>().apply {
                addAll(vmArgs(maxMemMb))
                add("-cp")
                add(File(home, BOOTSTRAP_JAR).absolutePath)
                add(BOOTSTRAP_CLASS)
                add(HEADLESS_CLASS)
                addAll(args)
            }
            AppLogger.i(TAG, "Headless launch (${args.size} args, timeout=${timeoutSeconds}s, mem=${maxMemMb}M)")
            val result = runManaged(context, runtime, "java", cmd, timeoutSeconds, ToolPermission.EXECUTE_PROCESS)
            return if (result.exitCode != null && result.exitCode != 0) {
                result.copy(error = ((result.error ?: "") + " | " + tail(result.output)).trim())
            } else result
        }

        /** Ensure the generated script sources exist under <workspace>/ghidra_scripts. */
        fun ensureScripts(context: ToolContext): File? {
            val dir = File(context.workspace, WORKSPACE_SCRIPTS_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) return null
            if (!dir.isDirectory) return null
            for ((name, src) in GENERATED_SCRIPTS) {
                val f = File(dir, name)
                try {
                    if (!f.isFile || f.readText(Charsets.UTF_8) != src) {
                        f.writeText(src, Charsets.UTF_8)
                    }
                    if (!f.isFile || f.readText(Charsets.UTF_8) != src) {
                        throw java.io.IOException("write verification failed")
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "script write failed for $name: ${e.message}")
                    return null
                }
            }
            return dir
        }

        fun readEnvelopeFile(f: File): JsonObject? = runCatching {
            if (!f.isFile || f.length() == 0L || f.length() > MAX_ENVELOPE_FILE) return null
            val parsed = com.google.gson.JsonParser.parseString(f.readText(Charsets.UTF_8))
            if (!parsed.isJsonObject) return null
            parsed.asJsonObject.takeIf {
                val ok = it.get("ok")
                val data = it.get("data")
                ok != null && ok.isJsonPrimitive && ok.asJsonPrimitive.isBoolean &&
                    (data == null || data.isJsonObject) && (!ok.asBoolean || data?.isJsonObject == true)
            }
        }.getOrNull()

        private fun tail(text: String, lines: Int = 12): String =
            text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(lines).joinToString("\n")
    }

    private val bridge = Bridge()

    // ------------------------------------------------------------------
    // Shared helpers for the tool classes
    // ------------------------------------------------------------------

    private fun resolveInput(workspace: File, path: String): File? {
        if (path.isBlank() || path.contains("..")) return null
        return File(workspace, path).takeIf { it.isFile && it.canRead() }
            ?: File(path).takeIf { it.isFile && it.canRead() }
    }

    private fun projectsDir(workspace: File): File = File(workspace, WORKSPACE_PROJECTS_DIR)

    private fun projectDirectory(projects: File, name: String): File? {
        if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) return null
        return runCatching {
            val root = projects.canonicalFile
            File(root, name).canonicalFile.takeIf { it.parentFile == root }
        }.getOrNull()
    }

    private fun projectNameFor(file: File): String =
        safeName(file.nameWithoutExtension) + "_proj"

    /** Runs a postScript invocation and reads the result from its caller-owned path. */
    private suspend fun runScriptInvocation(
        context: ToolContext,
        headlessArgs: List<String>,
        postScriptName: String,
        scriptArgs: List<String>,
        timeoutSeconds: Long,
        envelopeFile: File,
        maxMemMb: Int = DEFAULT_MAX_MEM_MB,
    ): Pair<ToolResult, JsonObject?> {
        if (envelopeFile.exists() && !envelopeFile.delete()) {
            return ToolResult(ok = false, error = "Unable to clear previous Ghidra result file: ${envelopeFile.absolutePath}") to null
        }
        val args = headlessArgs.toMutableList()
        args += listOf("-scriptPath", File(context.workspace, WORKSPACE_SCRIPTS_DIR).absolutePath)
        args += listOf("-postScript", postScriptName)
        args += scriptArgs
        val run = bridge.analyzeHeadless(context, args, timeoutSeconds, maxMemMb)
        return run to if (run.ok) bridge.readEnvelopeFile(envelopeFile) else null
    }

    private fun envelopeToResult(
        run: ToolResult,
        envelope: JsonObject?,
        outputHint: String,
        envelopeFile: File,
    ): ToolResult {
        if (!run.ok) return run
        if (envelope == null) {
            return ToolResult(
                ok = false,
                output = bounded(run.output),
                error = "Ghidra completed without a valid result envelope ($outputHint).",
                metadata = mapOf("envelope" to "unavailable", "envelope_file" to envelopeFile.absolutePath),
            )
        }
        val ok = envelope.get("ok")?.asBoolean ?: true
        val errorElement = envelope.get("error")
        val error = errorElement?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val data = envelope.getAsJsonObject("data")
        val json = gson.toJson(if (ok && data != null) data else envelope)
        return ToolResult(
            ok = ok,
            output = bounded(json),
            error = error,
            metadata = mapOf(
                "envelope_file" to envelopeFile.absolutePath,
                "analysis_engine" to "Ghidra headless",
            ),
        )
    }

    // ==================================================================
    // Tool classes
    // ==================================================================

    fun capabilitiesTool() = object : AgentTool {
        override val definition = ToolDefinition(
            name = "re.ghidra.capabilities",
            category = ToolCategory.REVERSE_ENGINEERING,
            description = "Detect native Ghidra headless availability: runtime pack, bundled JDK, version, native decompiler plausibility, and the generated script inventory.",
            permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.EXECUTE_PROCESS),
            parameters = emptyMap(),
        )

        override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
            withContext(Dispatchers.IO) {
                try {
                    val caps = JsonObject()
                    val ghidraInstalled = bridge.isInstalled()
                    val home = bridge.home()
                    caps.addProperty("ghidra_installed", ghidraInstalled)
                    home?.let { caps.addProperty("ghidra_home", it.absolutePath) }
                    caps.addProperty("ghidra_version", bridge.version())
                    val java = bridge.javaExecutable()
                    caps.addProperty("bundled_jdk", java?.absolutePath ?: "")
                    caps.addProperty("bundled_jdk_executable", java?.canExecute() == true)
                    val helpers = bridge.nativeHelperInfo()
                    caps.addProperty("native_decompile_present", helpers["decompile_present"] as Boolean)
                    caps.addProperty("native_decompile_plausible", helpers["decompile_plausible"] as Boolean)
                    caps.addProperty("device_abi", helpers["abi"] as String)
                    caps.addProperty("generated_scripts_dir", File(context.workspace, WORKSPACE_SCRIPTS_DIR).absolutePath)
                    caps.add("generated_scripts", gson.toJsonTree(GENERATED_SCRIPTS.keys))
                    caps.addProperty("status", when {
                        !ghidraInstalled -> "PACK_MISSING"
                        java?.canExecute() != true -> "JDK_MISSING"
                        else -> "READY"
                    })
                    ToolResult(ok = true, output = gson.toJson(JsonObject().apply {
                        add("capabilities", caps)
                    }))
                } catch (e: Exception) {
                    ToolResult(ok = false, error = "Capability detection failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
    }

    fun analyzeTool() = object : AgentTool {
        override val definition = ToolDefinition(
            name = "re.ghidra.analyze",
            category = ToolCategory.REVERSE_ENGINEERING,
            description = "Run real Ghidra headless auto-analysis on a binary (ELF/DEX/PE/Mach-O) and export sections (overview, functions, symbols, strings, imports, exports, memory_blocks, entry_points, comments, relocations, equates, bookmarks, names, data_types) as JSON. Optional filters: ADDR / SYMBOL / LIMIT. First run creates functions at code-flow targets (non-interactive pipeline).",
            permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.WRITE_WORKSPACE, ToolPermission.EXECUTE_PROCESS),
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "file_path" to mapOf("type" to "string", "description" to "Path to the binary to import/analyze"),
                    "sections" to mapOf("type" to "string", "description" to "Comma-separated export sections (default: overview,functions,symbols,strings,imports,exports,memory_blocks,entry_points)"),
                    "addr" to mapOf("type" to "string", "description" to "Address filter (0x… or symbolic)"),
                    "symbol" to mapOf("type" to "string", "description" to "Symbol name filter (substring)"),
                    "limit" to mapOf("type" to "integer", "description" to "Per-section result cap (default 200, max 20000)"),
                    "make_functions" to mapOf("type" to "boolean", "description" to "Run the call-target function creation stage (default true on first analysis)"),
                    "make_limit" to mapOf("type" to "integer", "description" to "Max functions to create in the pipeline stage (default 500)"),
                    "mode" to mapOf("type" to "string", "description" to "'import' (default, imports the file) or 'pipeline' (re-run on existing project)"),
                    "timeout_seconds" to mapOf("type" to "integer", "description" to "Headless run timeout (default 900, max 3600)"),
                    "max_mem_mb" to mapOf("type" to "integer", "description" to "JVM heap in MB (default 1536, max 4096)"),
                ),
                "required" to listOf("file_path"),
            ),
        )

        override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
            withContext(Dispatchers.IO) {
                try {
                    val o = arguments.asJsonObject
                    val filePath = o["file_path"]?.takeIf { !it.isJsonNull }?.asString
                        ?: return@withContext ToolResult(ok = false, error = "Missing required parameter: file_path")
                    val target = resolveInput(context.workspace, filePath)
                        ?: return@withContext ToolResult(ok = false, error = "File not found or unreadable: $filePath")
                    if (!bridge.isInstalled()) {
                        return@withContext ToolResult(ok = false, error = "Ghidra runtime pack is not installed. Install it from Agent Tools → Runtime Manager.")
                    }
                    if (bridge.ensureScripts(context) == null) {
                        return@withContext ToolResult(ok = false, error = "Unable to write generated scripts into ${File(context.workspace, WORKSPACE_SCRIPTS_DIR).absolutePath}")
                    }

                    val projects = projectsDir(context.workspace).apply { mkdirs() }
                    val projName = projectNameFor(target)
                    val outFile = File(File(context.workspace, WORKSPACE_OUT_DIR).apply { mkdirs() },
                        "ghidra_${target.nameWithoutExtension}_${timestamp()}.json")

                    val sections = o["sections"]?.takeIf { !it.isJsonNull }?.asString
                        ?: "overview,functions,symbols,strings,imports,exports,memory_blocks,entry_points"
                    val limit = (o["limit"]?.takeIf { !it.isJsonNull }?.asInt ?: 200).coerceIn(1, 20000)
                    val makeFunctions = o["make_functions"]?.takeIf { !it.isJsonNull }?.asBoolean ?: true
                    val makeLimit = (o["make_limit"]?.takeIf { !it.isJsonNull }?.asInt ?: 500).coerceIn(0, 5000)
                    val mode = o["mode"]?.takeIf { !it.isJsonNull }?.asString ?: "import"
                    if (mode != "import" && mode != "pipeline") {
                        return@withContext ToolResult(ok = false, error = "Unknown analysis mode: $mode (import | pipeline)")
                    }
                    val timeout = (o["timeout_seconds"]?.takeIf { !it.isJsonNull }?.asLong ?: 900L).coerceIn(30L, 3600L)
                    val maxMem = (o["max_mem_mb"]?.takeIf { !it.isJsonNull }?.asInt ?: DEFAULT_MAX_MEM_MB).coerceIn(512, ABSOLUTE_MAX_MEM_MB)

                    val headlessArgs = mutableListOf(
                        projects.absolutePath, projName,
                    )
                    if (mode == "pipeline") {
                        headlessArgs += listOf("-process", target.name)
                        headlessArgs += "-noanalysis"
                    } else {
                        headlessArgs += listOf("-import", target.absolutePath)
                    }

                    val scriptArgs = mutableListOf(
                        "SECTIONS=$sections",
                        "LIMIT=$limit",
                        "CDX_OUT=${outFile.absolutePath}",
                    )
                    o["addr"]?.takeIf { !it.isJsonNull }?.asString?.let { scriptArgs += "ADDR=$it" }
                    o["symbol"]?.takeIf { !it.isJsonNull }?.asString?.let { scriptArgs += "SYMBOL=$it" }
                    if (!makeFunctions) scriptArgs += "MAKE_LIMIT=0"
                    else scriptArgs += "MAKE_LIMIT=$makeLimit"

                    val (run, envelope) = runScriptInvocation(
                        context, headlessArgs, "CdxGhidraPipeline.java", scriptArgs, timeout, outFile, maxMem,
                    )
                    val result = envelopeToResult(run, envelope, "re-run with mode=pipeline to reuse the project", outFile)
                    return@withContext if (result.ok) result.copy(metadata = result.metadata + mapOf(
                        "project" to projName,
                        "project_dir" to projects.absolutePath,
                        "output_file" to outFile.absolutePath,
                    )) else result
                } catch (e: Exception) {
                    ToolResult(ok = false, error = "Ghidra analysis failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
    }

    fun decompileTool() = object : AgentTool {
        override val definition = ToolDefinition(
            name = "re.ghidra.decompile",
            category = ToolCategory.REVERSE_ENGINEERING,
            description = "Decompile a function at an address or by symbol using Ghidra's decompiler (native helper required; on aarch64 devices the shipped helper cannot run and the tool reports this honestly).",
            permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.WRITE_WORKSPACE, ToolPermission.EXECUTE_PROCESS),
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "file_path" to mapOf("type" to "string", "description" to "Path to the binary (imported on first use, project reused after)"),
                    "address" to mapOf("type" to "string", "description" to "Function address or symbol (0x… or name)"),
                    "len" to mapOf("type" to "integer", "description" to "Max chars of decompiled C (default 20000, max 60000)"),
                    "timeout_seconds" to mapOf("type" to "integer", "description" to "Headless run timeout (default 900, max 3600)"),
                ),
                "required" to listOf("file_path", "address"),
            ),
        )

        override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
            withContext(Dispatchers.IO) {
                try {
                    val o = arguments.asJsonObject
                    val filePath = o["file_path"]?.takeIf { !it.isJsonNull }?.asString
                        ?: return@withContext ToolResult(ok = false, error = "Missing required parameter: file_path")
                    val address = o["address"]?.takeIf { !it.isJsonNull }?.asString
                        ?: return@withContext ToolResult(ok = false, error = "Missing required parameter: address")
                    val target = resolveInput(context.workspace, filePath)
                        ?: return@withContext ToolResult(ok = false, error = "File not found or unreadable: $filePath")
                    if (!bridge.isInstalled()) {
                        return@withContext ToolResult(ok = false, error = "Ghidra runtime pack is not installed.")
                    }
                    val helpers = bridge.nativeHelperInfo()
                    if (helpers["decompile_plausible"] != true) {
                        return@withContext ToolResult(
                            ok = false,
                            error = "Native decompiler cannot run on this device (shipped helper is glibc x86_64; device ABI: ${helpers["abi"]}). Disassembly/symbol workflows remain available via re.ghidra.analyze and re.ghidra.xref.",
                        )
                    }
                    if (bridge.ensureScripts(context) == null) {
                        return@withContext ToolResult(ok = false, error = "Unable to write generated scripts.")
                    }
                    val projects = projectsDir(context.workspace).apply { mkdirs() }
                    val projName = projectNameFor(target)
                    val outFile = File(File(context.workspace, WORKSPACE_OUT_DIR).apply { mkdirs() },
                        "ghidra_dec_${target.nameWithoutExtension}_${timestamp()}.json")
                    val len = (o["len"]?.takeIf { !it.isJsonNull }?.asInt ?: 20000).coerceIn(100, 60000)
                    val timeout = (o["timeout_seconds"]?.takeIf { !it.isJsonNull }?.asLong ?: 900L).coerceIn(30L, 3600L)

                    val headlessArgs = mutableListOf(
                        projects.absolutePath, projName,
                        "-import", target.absolutePath,
                    )
                    val scriptArgs = mutableListOf(
                        "XREF=$address",
                        "DECOMPILE=1",
                        "LEN=$len",
                        "CDX_OUT=${outFile.absolutePath}",
                    )
                    val (run, envelope) = runScriptInvocation(
                        context, headlessArgs, "CdxGhidraXref.java", scriptArgs, timeout, outFile,
                    )
                    val result = envelopeToResult(run, envelope, "decompile via CdxGhidraXref", outFile)
                    val decompileError = runCatching {
                        val parsed = com.google.gson.JsonParser.parseString(result.output)
                        parsed.takeIf { it.isJsonObject }?.asJsonObject?.get("decompile_error")
                            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    }.getOrNull()
                    if (result.ok && decompileError != null) {
                        return@withContext result.copy(ok = false, error = decompileError)
                    }
                    return@withContext result
                } catch (e: Exception) {
                    ToolResult(ok = false, error = "Ghidra decompile failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
    }

    fun xrefTool() = object : AgentTool {
        override val definition = ToolDefinition(
            name = "re.ghidra.xref",
            category = ToolCategory.REVERSE_ENGINEERING,
            description = "List references to/from an address or symbol in a Ghidra-analyzed binary, with containing-function attribution and optional decompilation.",
            permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.WRITE_WORKSPACE, ToolPermission.EXECUTE_PROCESS),
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "file_path" to mapOf("type" to "string", "description" to "Path to the binary"),
                    "xref" to mapOf("type" to "string", "description" to "Address (0x…) or symbol name"),
                    "direction" to mapOf("type" to "string", "description" to "'to' (default), 'from', or 'both'"),
                    "limit" to mapOf("type" to "integer", "description" to "Max references (default 200)"),
                    "decompile" to mapOf("type" to "boolean", "description" to "Also decompile the containing function (native helper permitting)"),
                    "timeout_seconds" to mapOf("type" to "integer", "description" to "Headless run timeout (default 900, max 3600)"),
                ),
                "required" to listOf("file_path", "xref"),
            ),
        )

        override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
            withContext(Dispatchers.IO) {
                try {
                    val o = arguments.asJsonObject
                    val filePath = o["file_path"]?.takeIf { !it.isJsonNull }?.asString
                        ?: return@withContext ToolResult(ok = false, error = "Missing required parameter: file_path")
                    val xref = o["xref"]?.takeIf { !it.isJsonNull }?.asString
                        ?: return@withContext ToolResult(ok = false, error = "Missing required parameter: xref")
                    val target = resolveInput(context.workspace, filePath)
                        ?: return@withContext ToolResult(ok = false, error = "File not found or unreadable: $filePath")
                    if (!bridge.isInstalled()) {
                        return@withContext ToolResult(ok = false, error = "Ghidra runtime pack is not installed.")
                    }
                    if (bridge.ensureScripts(context) == null) {
                        return@withContext ToolResult(ok = false, error = "Unable to write generated scripts.")
                    }
                    val projects = projectsDir(context.workspace).apply { mkdirs() }
                    val projName = projectNameFor(target)
                    val outFile = File(File(context.workspace, WORKSPACE_OUT_DIR).apply { mkdirs() },
                        "ghidra_xref_${target.nameWithoutExtension}_${timestamp()}.json")
                    val direction = o["direction"]?.takeIf { !it.isJsonNull }?.asString ?: "to"
                    val limit = (o["limit"]?.takeIf { !it.isJsonNull }?.asInt ?: 200).coerceIn(1, 20000)
                    val decompile = o["decompile"]?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                    val timeout = (o["timeout_seconds"]?.takeIf { !it.isJsonNull }?.asLong ?: 900L).coerceIn(30L, 3600L)

                    val headlessArgs = mutableListOf(
                        projects.absolutePath, projName,
                        "-import", target.absolutePath,
                    )
                    val scriptArgs = mutableListOf(
                        "XREF=$xref",
                        "DIRECTION=$direction",
                        "LIMIT=$limit",
                        "CDX_OUT=${outFile.absolutePath}",
                    )
                    if (decompile) scriptArgs += "DECOMPILE=1"

                    val (run, envelope) = runScriptInvocation(
                        context, headlessArgs, "CdxGhidraXref.java", scriptArgs, timeout, outFile,
                    )
                    envelopeToResult(run, envelope, "xref via CdxGhidraXref", outFile)
                } catch (e: Exception) {
                    ToolResult(ok = false, error = "Ghidra xref failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
    }

    fun annotateTool() = object : AgentTool {
        override val definition = ToolDefinition(
            name = "re.ghidra.annotate",
            category = ToolCategory.REVERSE_ENGINEERING,
            description = "Write annotations into a Ghidra program: comments (plate/eol/pre/post/repeatable), function renames, bookmarks, and C-header struct/typedef types. SECTION drives the operation.",
            permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.WRITE_WORKSPACE, ToolPermission.EXECUTE_PROCESS),
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "file_path" to mapOf("type" to "string", "description" to "Path to the binary"),
                    "section" to mapOf("type" to "string", "description" to "'comm' | 'rename' | 'bookmark' | 'types' | 'clear'"),
                    "at" to mapOf("type" to "string", "description" to "Address (0x…) for comm/bookmark/clear"),
                    "text" to mapOf("type" to "string", "description" to "Comment text (comm) or new name (rename via TO fallback)"),
                    "type" to mapOf("type" to "string", "description" to "Comment type: plate (default), eol, pre, post, repeatable"),
                    "from" to mapOf("type" to "string", "description" to "rename: function to rename (address or symbol)"),
                    "to" to mapOf("type" to "string", "description" to "rename: new function name"),
                    "category" to mapOf("type" to "string", "description" to "bookmark category (default 'cdx')"),
                    "header" to mapOf("type" to "string", "description" to "types: file name inside ghidra_scripts/; lines use TYPE name size, STRUCT name size, or FIELD struct field offset type"),
                    "timeout_seconds" to mapOf("type" to "integer", "description" to "Headless run timeout (default 900, max 3600)"),
                ),
                "required" to listOf("file_path", "section"),
            ),
        )

        override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
            withContext(Dispatchers.IO) {
                try {
                    val o = arguments.asJsonObject
                    val filePath = o["file_path"]?.takeIf { !it.isJsonNull }?.asString
                        ?: return@withContext ToolResult(ok = false, error = "Missing required parameter: file_path")
                    val section = o["section"]?.takeIf { !it.isJsonNull }?.asString
                        ?: return@withContext ToolResult(ok = false, error = "Missing required parameter: section")
                    val target = resolveInput(context.workspace, filePath)
                        ?: return@withContext ToolResult(ok = false, error = "File not found or unreadable: $filePath")
                    if (!bridge.isInstalled()) {
                        return@withContext ToolResult(ok = false, error = "Ghidra runtime pack is not installed.")
                    }
                    if (bridge.ensureScripts(context) == null) {
                        return@withContext ToolResult(ok = false, error = "Unable to write generated scripts.")
                    }
                    val projects = projectsDir(context.workspace).apply { mkdirs() }
                    val projName = projectNameFor(target)
                    val outFile = File(File(context.workspace, WORKSPACE_OUT_DIR).apply { mkdirs() },
                        "ghidra_ann_${target.nameWithoutExtension}_${timestamp()}.json")
                    val timeout = (o["timeout_seconds"]?.takeIf { !it.isJsonNull }?.asLong ?: 900L).coerceIn(30L, 3600L)

                    val headlessArgs = mutableListOf(
                        projects.absolutePath, projName,
                        "-import", target.absolutePath,
                    )
                    val scriptArgs = mutableListOf(
                        "SECTION=$section",
                        "CDX_OUT=${outFile.absolutePath}",
                        "CDX_SCRIPT_DIR=${File(context.workspace, WORKSPACE_SCRIPTS_DIR).absolutePath}",
                    )
                    for (key in listOf("at", "text", "type", "from", "to", "category", "header")) {
                        o[key]?.takeIf { !it.isJsonNull }?.asString?.let { scriptArgs += "${key.uppercase()}=$it" }
                    }

                    val (run, envelope) = runScriptInvocation(
                        context, headlessArgs, "CdxGhidraAnnotate.java", scriptArgs, timeout, outFile,
                    )
                    envelopeToResult(run, envelope, "annotate via CdxGhidraAnnotate", outFile)
                } catch (e: Exception) {
                    ToolResult(ok = false, error = "Ghidra annotate failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
    }

    fun projectTool() = object : AgentTool {
        override val definition = ToolDefinition(
            name = "re.ghidra.project",
            category = ToolCategory.REVERSE_ENGINEERING,
            description = "Manage on-device Ghidra headless projects: list existing projects, open a binary into read-only overview export, or delete a project directory.",
            permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.WRITE_WORKSPACE, ToolPermission.EXECUTE_PROCESS),
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "action" to mapOf("type" to "string", "description" to "'list' (default) | 'open' | 'delete'"),
                    "project" to mapOf("type" to "string", "description" to "open/delete: project name as reported by list"),
                    "file_path" to mapOf("type" to "string", "description" to "open: binary path to import (project reused if present)"),
                    "timeout_seconds" to mapOf("type" to "integer", "description" to "open: headless run timeout (default 600, max 3600)"),
                ),
            ),
        )

        override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
            withContext(Dispatchers.IO) {
                try {
                    val o = arguments.asJsonObject
                    val action = o["action"]?.takeIf { !it.isJsonNull }?.asString ?: "list"
                    val projects = projectsDir(context.workspace).apply { mkdirs() }
                    when (action) {
                        "list" -> {
                            val arr = com.google.gson.JsonArray()
                            projects.listFiles()?.filter { it.isDirectory }?.forEach { d ->
                                val projFile = File(d, d.name + ".gpr")
                                val o2 = JsonObject()
                                o2.addProperty("name", d.name)
                                o2.addProperty("gpr", projFile.takeIf { it.isFile }?.absolutePath ?: "")
                                o2.addProperty("size_bytes", d.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                                arr.add(o2)
                            }
                            ToolResult(ok = true, output = gson.toJson(JsonObject().apply {
                                addProperty("projects_dir", projects.absolutePath)
                                add("projects", arr)
                            }))
                        }
                        "open" -> {
                            val projName = o["project"]?.takeIf { !it.isJsonNull }?.asString
                            val filePath = o["file_path"]?.takeIf { !it.isJsonNull }?.asString
                            if (projName.isNullOrBlank() || filePath.isNullOrBlank()) {
                                return@withContext ToolResult(ok = false, error = "open requires project + file_path")
                            }
                            val target = resolveInput(context.workspace, filePath)
                                ?: return@withContext ToolResult(ok = false, error = "File not found: $filePath")
                            if (projectDirectory(projects, projName) == null) {
                                return@withContext ToolResult(ok = false, error = "Invalid project name")
                            }
                            if (bridge.ensureScripts(context) == null) {
                                return@withContext ToolResult(ok = false, error = "Unable to write generated scripts.")
                            }
                            val outFile = File(File(context.workspace, WORKSPACE_OUT_DIR).apply { mkdirs() },
                                "ghidra_open_${timestamp()}.json")
                            val timeout = (o["timeout_seconds"]?.takeIf { !it.isJsonNull }?.asLong ?: 600L).coerceIn(30L, 3600L)
                            val headlessArgs = mutableListOf(projects.absolutePath, projName)
                            headlessArgs += listOf("-import", target.absolutePath)
                            val scriptArgs = mutableListOf(
                                "SECTIONS=overview",
                                "CDX_OUT=${outFile.absolutePath}",
                            )
                            val (run, envelope) = runScriptInvocation(
                                context, headlessArgs, "CdxGhidraExport.java", scriptArgs, timeout, outFile,
                            )
                            envelopeToResult(run, envelope, "open project", outFile)
                        }
                        "delete" -> {
                            val projName = o["project"]?.takeIf { !it.isJsonNull }?.asString
                                ?: return@withContext ToolResult(ok = false, error = "delete requires project")
                            val dir = projectDirectory(projects, projName)
                                ?: return@withContext ToolResult(ok = false, error = "Invalid project name")
                            if (!dir.isDirectory) {
                                return@withContext ToolResult(ok = false, error = "Project not found: $projName")
                            }
                            val deleted = dir.deleteRecursively()
                            ToolResult(ok = deleted, output = if (deleted) "deleted $projName" else "delete failed",
                                metadata = mapOf("project" to projName))
                        }
                        else -> ToolResult(ok = false, error = "Unknown action: $action (list | open | delete)")
                    }
                } catch (e: Exception) {
                    ToolResult(ok = false, error = "Ghidra project operation failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
    }

    fun runScriptTool() = object : AgentTool {
        override val definition = ToolDefinition(
            name = "re.ghidra.run_script",
            category = ToolCategory.REVERSE_ENGINEERING,
            description = "Execute an agent-authored GhidraScript (.java) placed in <workspace>/ghidra_scripts/. The script receives KEY=value args and should write its JSON result to CDX_OUT; the envelope is returned.",
            permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.WRITE_WORKSPACE, ToolPermission.EXECUTE_PROCESS),
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "file_path" to mapOf("type" to "string", "description" to "Binary to import (optional for program-independent scripts; pass empty string to skip import)"),
                    "script" to mapOf("type" to "string", "description" to "Script file name in ghidra_scripts/ (e.g. MyScript.java)"),
                    "script_args" to mapOf("type" to "string", "description" to "Space-separated KEY=value args forwarded to the script"),
                    "timeout_seconds" to mapOf("type" to "integer", "description" to "Headless run timeout (default 600, max 3600)"),
                ),
                "required" to listOf("script"),
            ),
        )

        override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
            withContext(Dispatchers.IO) {
                try {
                    val o = arguments.asJsonObject
                    val scriptName = o["script"]?.takeIf { !it.isJsonNull }?.asString
                        ?: return@withContext ToolResult(ok = false, error = "Missing required parameter: script")
                    if (!scriptName.matches(Regex("^[A-Za-z_$][A-Za-z0-9_$]*\\.java$"))) {
                        return@withContext ToolResult(ok = false, error = "Script must be a .java file name inside ghidra_scripts/ (no paths)")
                    }
                    if (scriptName in setOf(
                            "CdxGhidraPipeline.java", "CdxGhidraExport.java",
                            "CdxGhidraAnnotate.java", "CdxGhidraXref.java",
                        )) {
                        return@withContext ToolResult(ok = false, error = "Generated Ghidra scripts are reserved; choose another script name")
                    }
                    val scriptsDir = File(context.workspace, WORKSPACE_SCRIPTS_DIR)
                    val scriptFile = File(scriptsDir, scriptName)
                    if (!scriptFile.isFile) {
                        return@withContext ToolResult(ok = false, error = "Script not found: ${scriptFile.absolutePath}. Write it there first (e.g. via file.write).")
                    }
                    if (!bridge.isInstalled()) {
                        return@withContext ToolResult(ok = false, error = "Ghidra runtime pack is not installed.")
                    }
                    if (bridge.ensureScripts(context) == null) {
                        return@withContext ToolResult(ok = false, error = "Unable to write generated scripts.")
                    }
                    val timeout = (o["timeout_seconds"]?.takeIf { !it.isJsonNull }?.asLong ?: 600L).coerceIn(30L, 3600L)
                    val filePath = o["file_path"]?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    val scriptArgsRaw = o["script_args"]?.takeIf { !it.isJsonNull }?.asString.orEmpty()

                    val outFile = File(File(context.workspace, WORKSPACE_OUT_DIR).apply { mkdirs() },
                        "ghidra_run_${timestamp()}.json")
                    val headlessArgs = mutableListOf<String>()
                    if (filePath.isNotBlank()) {
                        val target = resolveInput(context.workspace, filePath)
                            ?: return@withContext ToolResult(ok = false, error = "File not found or unreadable: $filePath")
                        headlessArgs += projectsDir(context.workspace).apply { mkdirs() }.absolutePath
                        headlessArgs += projectNameFor(target)
                        headlessArgs += listOf("-import", target.absolutePath)
                    } else {
                        headlessArgs += projectsDir(context.workspace).apply { mkdirs() }.absolutePath
                        headlessArgs += "misc_${timestamp()}"
                    }
                    val providedArgs = scriptArgsRaw.trim().takeIf { it.isNotEmpty() }
                        ?.split(Regex("\\s+")) ?: emptyList()
                    if (providedArgs.any { !it.contains('=') || it.substringBefore('=').isBlank() }) {
                        return@withContext ToolResult(ok = false, error = "script_args must be whitespace-separated KEY=value tokens")
                    }
                    val scriptArgs = buildList {
                        providedArgs.filterNot { it.substringBefore('=').equals("CDX_OUT", ignoreCase = true) }
                            .forEach { add(it) }
                        add("CDX_OUT=${outFile.absolutePath}")
                    }
                    val (run, envelope) = runScriptInvocation(
                        context, headlessArgs, scriptName, scriptArgs, timeout, outFile,
                    )
                    envelopeToResult(run, envelope, "user script run", outFile)
                } catch (e: Exception) {
                    ToolResult(ok = false, error = "Ghidra run_script failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
    }
}
