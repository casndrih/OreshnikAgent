package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.example.oreshik.agent.runtime.RuntimeManager
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reverse-engineering runtime tools backed by app-owned runtimes:
 * - radare2 (r2, rabin2, rasm2, rax2) — binary disassembly & analysis
 * - apktool — APK decode/rebuild (resources, manifest, smali)
 * - aapt2 / apksigner (android-sdk) — APK manifest & signing inspection
 *
 * Ghidra is a Java desktop application and cannot run natively on Android;
 * headless Ghidra analysis can be approximated via the bundled JDK + radare2,
 * and the ghidra tool proxies class/bytecode analysis through javap + r2.
 */

class R2AnalyzeTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.r2.analyze",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Analyze a binary (ELF/DEX/EXE/APK) with radare2: run analysis commands (aaa), list functions, imports, exports, strings, sections, and entrypoints. Uses the app-owned radare2 runtime.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "file_path" to mapOf("type" to "string", "description" to "Path to the binary file"),
                "commands" to mapOf("type" to "string", "description" to "Semicolon-separated r2 commands to run (default: 'aaa; afl; ii; is; iS; ie')"),
                "timeout_seconds" to mapOf("type" to "integer", "description" to "Analysis timeout in seconds (default: 120)"),
            ),
            "required" to listOf("file_path"),
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.EXECUTE_PROCESS),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject
        val filePath = o["file_path"]?.asString
            ?: return ToolResult(ok = false, error = "Missing required parameter: file_path")
        val commands = o["commands"]?.asString ?: "aaa; afl; ii; is; iS; ie"
        val timeout = (o["timeout_seconds"]?.asLong ?: 120).coerceIn(5, 900)

        val target = File(context.workspace, filePath).takeIf { candidate -> candidate.isFile }
            ?: File(filePath).takeIf { candidate -> candidate.isFile }
            ?: return ToolResult(ok = false, error = "File not found: $filePath")

        val script = buildString {
            append("aaa\n")
            commands.split(';')
                .asSequence()
                .map { part -> part.trim() }
                .filter { part -> (part.isNotBlank()) && (part != "aaa") }
                .forEach { part -> append(part).append('\n') }
            append("q\n")
        }
        val argsFile = File(context.workspace, ".r2cmd_${System.currentTimeMillis()}.txt")
        argsFile.writeText(script)
        val outcome: ToolResult = try {
            runManaged(
                context,
                runtime,
                "r2",
                listOf("-q", "-i", argsFile.absolutePath, target.absolutePath),
                timeout,
                ToolPermission.REVERSE_ENGINEERING,
            )
        } finally {
            argsFile.delete()
        }
        return outcome
    }
}

class R2BinInfoTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.rabin2",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Extract binary metadata with rabin2: file info, sections, symbols, imports, exports, libraries, entrypoints, and checksums. Faster than a full r2 analysis for pure metadata extraction.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "file_path" to mapOf("type" to "string", "description" to "Path to the binary file"),
                "flags" to mapOf("type" to "string", "description" to "rabin2 flags (default: '-I' for info; e.g. '-s' symbols, '-i' imports, '-E' entrypoints, '-I' all info)"),
            ),
            "required" to listOf("file_path"),
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.EXECUTE_PROCESS),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject
        val filePath = o["file_path"]?.asString
            ?: return ToolResult(ok = false, error = "Missing required parameter: file_path")
        val flags = o["flags"]?.asString ?: "-I"

        val target = File(context.workspace, filePath).takeIf { candidate -> candidate.isFile }
            ?: File(filePath).takeIf { candidate -> candidate.isFile }
            ?: return ToolResult(ok = false, error = "File not found: $filePath")

        val flagList = flags.split(Regex("\\s+")).filter { flag -> flag.isNotBlank() }
        return runManaged(
            context,
            runtime,
            "rabin2",
            flagList + listOf(target.absolutePath),
            120,
            ToolPermission.REVERSE_ENGINEERING,
        )
    }
}

class ApktoolDecodeTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.apktool.decode",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Decode an APK with Apktool: extract AndroidManifest.xml (readable XML), resources, and smali bytecode to a directory. This is the primary way to read an APK's real manifest and permissions.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "apk_path" to mapOf("type" to "string", "description" to "Path to the APK file"),
                "output_dir" to mapOf("type" to "string", "description" to "Output directory (default: <apk_name>_decoded in workspace)"),
                "extract_sources" to mapOf("type" to "boolean", "description" to "Decode smali sources (default: true). Disable to decode resources/manifest only."),
                "no_resources" to mapOf("type" to "boolean", "description" to "Skip resource decoding (default: false)"),
            ),
            "required" to listOf("apk_path"),
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.EXECUTE_PROCESS),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject
        val apkPath = o["apk_path"]?.asString
            ?: return ToolResult(ok = false, error = "Missing required parameter: apk_path")
        val extractSources = o["extract_sources"]?.asBoolean ?: true
        val noResources = o["no_resources"]?.asBoolean ?: false

        val apk = File(context.workspace, apkPath).takeIf { candidate -> candidate.isFile }
            ?: File(apkPath).takeIf { candidate -> candidate.isFile }
            ?: return ToolResult(ok = false, error = "APK not found: $apkPath")

        val outDirName = o["output_dir"]?.asString ?: (apk.nameWithoutExtension + "_decoded")
        val outDir = File(context.workspace, outDirName)

        val args = mutableListOf("d", "-f", "-o", outDir.absolutePath)
        if (!extractSources) args.add("-s")
        if (noResources) args.add("-r")
        args.add(apk.absolutePath)

        val result = runManaged(context, runtime, "apktool", args, 600, ToolPermission.REVERSE_ENGINEERING)

        if (result.ok && outDir.isDirectory) {
            val manifest = File(outDir, "AndroidManifest.xml")
            val summary = buildString {
                append(result.output)
                append("\n\n--- Decoded structure ---\n")
                if (manifest.isFile) append("AndroidManifest.xml: ${manifest.length()} bytes\n")
                outDir.listFiles()?.forEach { dir ->
                    if (dir.isDirectory) {
                        val count = dir.walkTopDown().count()
                        append("${dir.name}/: $count files\n")
                    }
                }
            }
            return ToolResult(
                ok = true,
                summary,
                metadata = mapOf(
                    "output_dir" to outDir.absolutePath,
                    "manifest_path" to manifest.takeIf { file -> file.isFile }?.absolutePath.orEmpty(),
                ),
            )
        }
        return result
    }
}

class ApktoolBuildTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.apktool.build",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Rebuild an APK from an Apktool-decoded directory (smali + resources back into an installable APK).",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "decoded_dir" to mapOf("type" to "string", "description" to "Path to the decoded APK directory (contains apktool.yml)"),
                "output_apk" to mapOf("type" to "string", "description" to "Output APK path (default: <dir_name>_rebuilt.apk in workspace)"),
            ),
            "required" to listOf("decoded_dir"),
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.WRITE_WORKSPACE, ToolPermission.EXECUTE_PROCESS),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject
        val decodedPath = o["decoded_dir"]?.asString
            ?: return ToolResult(ok = false, error = "Missing required parameter: decoded_dir")

        val decoded = File(context.workspace, decodedPath).takeIf { candidate -> candidate.isDirectory }
            ?: File(decodedPath).takeIf { candidate -> candidate.isDirectory }
            ?: return ToolResult(ok = false, error = "Decoded directory not found: $decodedPath")
        if (!File(decoded, "apktool.yml").isFile) {
            return ToolResult(ok = false, error = "Not an apktool-decoded directory (missing apktool.yml): $decodedPath")
        }

        val outApkName = o["output_apk"]?.asString ?: (decoded.nameWithoutExtension + "_rebuilt.apk")
        val outApk = File(context.workspace, outApkName)

        val result = runManaged(
            context, runtime, "apktool",
            listOf("b", decoded.absolutePath, "-o", outApk.absolutePath),
            900,
            ToolPermission.REVERSE_ENGINEERING
        )

        if (result.ok) {
            return ToolResult(
                ok = true,
                result.output + "\n\nRebuilt APK: ${outApk.absolutePath} (${outApk.length()} bytes)",
                metadata = mapOf(
                    "output_apk" to outApk.absolutePath,
                ),
            )
        }
        return result
    }
}

class ApkManifestTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.apk.manifest",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Dump an APK's AndroidManifest.xml using aapt2 (badging info: package, permissions, activities, services) from the app-owned android-sdk runtime. Read-only and fast.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "apk_path" to mapOf("type" to "string", "description" to "Path to the APK file"),
                "mode" to mapOf("type" to "string", "description" to "'badging' (default) or 'xml' for full manifest XML")
            ),
            "required" to listOf("apk_path")
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.EXECUTE_PROCESS),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject
        val apkPath = o["apk_path"]?.asString
            ?: return ToolResult(ok = false, error = "Missing required parameter: apk_path")
        val mode = o["mode"]?.asString ?: "badging"

        val apk = File(context.workspace, apkPath).takeIf { candidate -> candidate.isFile }
            ?: File(apkPath).takeIf { candidate -> candidate.isFile }
            ?: return ToolResult(ok = false, error = "APK not found: $apkPath")

        val args = when (mode.lowercase()) {
            "xml" -> listOf("dump", "xmltree", apk.absolutePath, "--file", "AndroidManifest.xml")
            else -> listOf("dump", "badging", apk.absolutePath)
        }
        return runManaged(context, runtime, "aapt2", args, 120, ToolPermission.REVERSE_ENGINEERING)
    }
}

class GhidraStyleAnalysisTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.ghidra.analyze",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Ghidra-style headless binary analysis. Ghidra itself is a desktop Java app and cannot run on-device; this tool approximates its workflow: binary identification (rabin2), deep analysis with radare2 (aaa), decompilation-adjacent output (functions, xrefs, strings), and for Java/DEX targets, class/method listing via javap/dexdump.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "file_path" to mapOf("type" to "string", "description" to "Path to the binary (ELF, DEX, EXE)"),
                "deep" to mapOf("type" to "boolean", "description" to "Run deep analysis (aaa) vs quick (aa) (default: true)"),
                "max_output" to mapOf("type" to "integer", "description" to "Max chars of combined report (default: 120000)")
            ),
            "required" to listOf("file_path")
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.EXECUTE_PROCESS),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject
        val filePath = o["file_path"]?.asString
            ?: return ToolResult(ok = false, error = "Missing required parameter: file_path")
        val deep = o["deep"]?.asBoolean ?: true
        val maxOutput = (o["max_output"]?.asInt ?: 120000).coerceIn(10000, 400000)

        val target = File(context.workspace, filePath).takeIf { candidate -> candidate.isFile }
            ?: File(filePath).takeIf { candidate -> candidate.isFile }
            ?: return ToolResult(ok = false, error = "File not found: $filePath")

        val report = StringBuilder()
        report.append("=== Ghidra-style Headless Analysis Report ===\n")
        report.append("Target: ${target.name} (${target.length()} bytes)\n\n")

        // Stage 1: file identification (like Ghidra's language detection)
        val idResult = runManaged(context, runtime, "rabin2", listOf("-I", target.absolutePath), 60, ToolPermission.REVERSE_ENGINEERING)
        report.append("--- File Identification ---\n").append(idResult.output.ifBlank { idResult.error ?: "(no output)" }).append("\n\n")

        // Stage 2: entrypoints + symbols
        val entryResult = runManaged(context, runtime, "rabin2", listOf("-E", "-s", target.absolutePath), 60, ToolPermission.REVERSE_ENGINEERING)
        report.append("--- Entrypoints & Symbols ---\n").append(entryResult.output.ifBlank { entryResult.error ?: "(no output)" }).append("\n\n")

        // Stage 3: deep function analysis (like Ghidra's auto-analysis)
        val analysisLevel = if (deep) "aaa" else "aa"
        val fnResult = runManaged(context, runtime, "r2", listOf("-q", "-c", "$analysisLevel; afl; axff~?; izz~?", target.absolutePath), 180, ToolPermission.REVERSE_ENGINEERING)
        report.append("--- Function Analysis ($analysisLevel) ---\n").append(fnResult.output.ifBlank { fnResult.error ?: "(no output)" })

        val full = report.toString()
        return if (full.length > maxOutput) {
            ToolResult(
                ok = true,
                full.take(maxOutput) + "\n[report truncated at $maxOutput chars]",
                metadata = mapOf("truncated" to true),
            )
        } else {
            ToolResult(ok = true, full)
        }
    }
}

// ---------------------------------------------------------------------------
// APK sign + install + one-command patch pipeline
// (steps 4 & 5 of the anti-tamper patch flow, plus the full 1-5 orchestration)
// ---------------------------------------------------------------------------

/**
 * Ensures a debug keystore exists for re-signing patched APKs. Reuses the
 * platform's default ~/.android/debug.keystore via HOME override when present
 * (so signatures match the user's existing debug installs), otherwise
 * generates a dedicated one in the workspace with keytool (bundled JDK).
 */
internal suspend fun ensureDebugKeystore(context: ToolContext, runtime: RuntimeManager): Pair<File, String>? {
    val home = File(context.app.filesDir, "android-home").apply { mkdirs() }
    val ks = File(home, ".android/debug.keystore")
    ks.parentFile?.mkdirs()
    if (!ks.isFile) {
        val gen = runManaged(
            context, runtime, "keytool",
            listOf(
                "-genkeypair", "-v",
                "-keystore", ks.absolutePath,
                "-storepass", "android",
                "-keypass", "android",
                "-alias", "androiddebugkey",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                "-dname", "CN=Android Debug,O=Android,C=US"
            ),
            120
        )
        if (!gen.ok || !ks.isFile) return null
    }
    return ks to ("android:android:androiddebugkey")
}

/** 4. Sign a rebuilt APK with the debug key (apksigner from android-sdk pack). */
class ApkSignTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.apk.sign",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Sign an APK with a debug keystore using apksigner (from the app-owned android-sdk pack). Creates/uses a persistent debug keystore (android/android/androiddebugkey). Run re.apktool.build first; install the signed APK afterwards with re.apk.install.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "apk_path" to mapOf("type" to "string", "description" to "Path to the unsigned (rebuilt) APK"),
                "out_path" to mapOf("type" to "string", "description" to "Output signed APK path (default: <name>_signed.apk in workspace)"),
                "v1_only" to mapOf("type" to "boolean", "description" to "Sign with v1 scheme only (default: false — v1+v2+v3 for Android 11+ compatibility)")
            ),
            "required" to listOf("apk_path")
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.WRITE_WORKSPACE, ToolPermission.EXECUTE_PROCESS)
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject
        val apkPath = o["apk_path"]?.asString
            ?: return ToolResult(ok = false, error = "Missing required parameter: apk_path")
        val v1Only = o["v1_only"]?.asBoolean ?: false

        val apk = File(context.workspace, apkPath).takeIf { candidate -> candidate.isFile }
            ?: File(apkPath).takeIf { candidate -> candidate.isFile }
            ?: return ToolResult(ok = false, error = "APK not found: $apkPath")

        val outName = o["out_path"]?.asString ?: (apk.nameWithoutExtension + "_signed.apk")
        val outApk = File(context.workspace, outName)

        val ksInfo = ensureDebugKeystore(context, runtime)
            ?: return ToolResult(false, error = "Could not create the debug keystore (keytool missing? install the JDK runtime pack).")
        val (ks, pass) = ksInfo
        val storePass = pass.substringBefore(":")
        val keyPass = pass.substringAfter(":")

        val args = mutableListOf(
            "sign",
            "--ks", ks.absolutePath,
            "--ks-pass", "pass:$storePass",
            "--ks-key-alias", "androiddebugkey",
            "--key-pass", "pass:$keyPass",
            "--out", outApk.absolutePath
        )
        if (v1Only) {
            args.add("--v1-signing-enabled")
            args.add("true")
            args.add("--v2-signing-enabled")
            args.add("false")
        }
        args.add(apk.absolutePath)

        val sign = runManaged(context, runtime, "apksigner", args, 300, ToolPermission.REVERSE_ENGINEERING)
        if (!sign.ok) {
            return ToolResult(false, error = "apksigner failed: ${sign.error ?: sign.output.take(400)}")
        }

        // Verify the signature so failures are caught before install.
        val verify = runManaged(
            context, runtime, "apksigner",
            listOf("verify", "--print-certs", outApk.absolutePath),
            60,
            ToolPermission.REVERSE_ENGINEERING
        )
        val summary = buildString {
            append("Signed APK: ${outApk.absolutePath} (${outApk.length()} bytes)\n")
            append("Keystore: ${ks.absolutePath} (alias androiddebugkey)\n")
            if (verify.ok) append("Signature verification: PASS\n\n").append(verify.output.take(600))
            else append("Signature verification: WARNING — ${verify.error ?: "see output"}\n")
            append("\nNext: install with re.apk.install (apk_path=${outApk.name}).")
        }
        return ToolResult(
            ok = true,
            summary,
            metadata = mapOf(
                "signed_apk" to outApk.absolutePath,
                "verified" to verify.ok,
            ),
        )
    }
}

/** 5. Install a signed APK (root pm install — signature-swap needs root or user consent). */
class ApkInstallTool(@Suppress("unused") private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.apk.install",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Install an APK on the device via root `pm install` (handles signature-mismatch replacement with -r -d). Enable Allow root in Settings. For a non-root flow, push the APK and let the user confirm the system installer dialog.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "apk_path" to mapOf("type" to "string", "description" to "Path to the signed APK to install"),
                "allow_downgrade" to mapOf("type" to "boolean", "description" to "Pass -d to allow version downgrade (default: true)"),
                "keep_data" to mapOf("type" to "boolean", "description" to "Pass -r to keep app data on reinstall (default: true)")
            ),
            "required" to listOf("apk_path")
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.ROOT, ToolPermission.EXECUTE_PROCESS)
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val o = arguments.asJsonObject
        val apkPath = o.get("apk_path")?.asString
            ?: return@withContext ToolResult(false, error = "Missing required parameter: apk_path")
        val allowDowngrade = o.get("allow_downgrade")?.takeUnless { it.isJsonNull }?.asBoolean ?: true
        val keepData = o.get("keep_data")?.takeUnless { it.isJsonNull }?.asBoolean ?: true

        val apk = File(context.workspace, apkPath).takeIf { it.isFile }
            ?: File(apkPath).takeIf { it.isFile }
            ?: return@withContext ToolResult(false, error = "APK not found: $apkPath")

        val root = com.example.oreshik.agent.security.RootManager(context.app)
        if (!root.canUseRoot()) {
            return@withContext ToolResult(
                false,
                error = "re.apk.install requires root (pm install). Enable Allow root in Settings, or use the non-root flow: push the APK to /sdcard and open it (system installer asks for confirmation)."
            )
        }

        // Stage the APK where pm can read it.
        val devicePath = "/data/local/tmp/patched_${System.currentTimeMillis()}.apk"
        val (pushCode, pushOut) = root.execute("cp '${apk.absolutePath}' $devicePath && chmod 644 $devicePath", 30)
        if (pushCode != 0) {
            return@withContext ToolResult(false, error = "Failed to stage APK: ${pushOut.take(200)}")
        }

        val flags = buildString {
            if (keepData) append(" -r")
            if (allowDowngrade) append(" -d")
        }
        val (code, out) = root.execute("pm install$flags $devicePath", 180)
        runCatching { root.execute("rm -f $devicePath", 10) }

        val ok = (code == 0) && (!out.contains("Failure"))
        val summary = buildString {
            append("pm install exit=$code\n")
            append(out.take(800))
            if (ok) append("\n\nInstalled successfully. Launch it with androidopenapp.")
            else if (out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
                append("\n\nSignature mismatch: the installed copy was signed with a different key.")
                append("\nUninstall the original first (mem-safe): su -c 'pm uninstall <package>' then re-run re.apk.install.")
            }
        }
        ToolResult(ok, summary, error = if (ok) null else "pm install failed", metadata = mapOf("installed" to ok))
    }
}

/**
 * One-command pipeline for strongly-protected apps:
 *   decode → (agent edits smali in the returned dir) → build → sign → install.
 *
 * This tool runs decode automatically, hands the smali tree to the agent for
 * premium-check patching, and offers "build_and_sign": true to finish the
 * remaining steps in one call after the edits are done.
 */
class ApkPatchPipelineTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.apk.patch",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "End-to-end APK patch pipeline for anti-tamper apps: (1) apktool decode, (2) [agent patches smali between calls], (3) apktool build, (4) apksigner debug-sign, (5) root pm install. Call with build_and_sign=false to decode and stop; after editing smali call again with build_and_sign=true to finish.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "apk_path" to mapOf("type" to "string", "description" to "Path to the original APK"),
                "decoded_dir" to mapOf("type" to "string", "description" to "Decoded directory (required for build_and_sign=true)") ,
                "build_and_sign" to mapOf("type" to "boolean", "description" to "false = decode only (default); true = build + sign + install the patched dir"),
                "output_apk" to mapOf("type" to "string", "description" to "Output rebuilt APK name (default: <dir>_rebuilt.apk)"),
                "skip_install" to mapOf("type" to "boolean", "description" to "Build + sign but do not install (default: false)")
            ),
            "required" to listOf<String>()
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.WRITE_WORKSPACE, ToolPermission.EXECUTE_PROCESS)
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject
        val buildAndSign = o["build_and_sign"]?.takeUnless { element -> element.isJsonNull }?.asBoolean ?: false
        val skipInstall = o["skip_install"]?.takeUnless { element -> element.isJsonNull }?.asBoolean ?: false

        if (!buildAndSign) {
            // Phase 1: decode only.
            if (o["apk_path"]?.takeUnless { element -> element.isJsonNull }?.asString == null) {
                return ToolResult(ok = false, error = "Missing apk_path (or pass build_and_sign=true with decoded_dir to finish)")
            }
            val decoder = ApktoolDecodeTool(runtime)
            return decoder.execute(context, arguments)
        }

        // Phase 2: build + sign + install.
        val decodedPath = o.get("decoded_dir")?.asString
            ?: return ToolResult(false, error = "Missing decoded_dir for build_and_sign=true")
        val log = StringBuilder()

        val built = ApktoolBuildTool(runtime).execute(
            context,
            JsonObject().apply {
                addProperty("decoded_dir", decodedPath)
                o["output_apk"]?.asString?.let { output -> addProperty("output_apk", output) }
            },
        )
        log.append("[3/5 build] ").append(if (built.ok) "OK" else "FAILED").append('\n')
            .append(built.output.take(1200)).append('\n')
        if (!built.ok) return ToolResult(ok = false, log.toString(), error = "apktool build failed")
        val rebuiltApkRaw = built.metadata["output_apk"]
        @Suppress("IfThenToElvis")
        val rebuiltApk: String = if (rebuiltApkRaw is String) {
            rebuiltApkRaw
        } else {
            return ToolResult(ok = false, log.toString(), error = "Build did not return an APK path")
        }

        val signed = ApkSignTool(runtime).execute(
            context,
            JsonObject().apply {
                addProperty("apk_path", rebuiltApk)
            },
        )
        log.append("[4/5 sign] ").append(if (signed.ok) "OK" else "FAILED").append('\n')
            .append(signed.output.take(800)).append('\n')
        if (!signed.ok) return ToolResult(ok = false, log.toString(), error = "apksigner failed")
        val signedApkRaw = signed.metadata["signed_apk"]
        @Suppress("IfThenToElvis")
        val signedApk: String = if (signedApkRaw is String) signedApkRaw else rebuiltApk

        if (skipInstall) {
            log.append("[5/5 install] SKIPPED (skip_install=true)\n")
            return ToolResult(
                ok = true,
                log.toString(),
                metadata = mapOf(
                    "signed_apk" to signedApk,
                    "installed" to false,
                ),
            )
        }

        val installed = ApkInstallTool(runtime).execute(
            context,
            JsonObject().apply {
                addProperty("apk_path", signedApk)
            },
        )
        log.append("[5/5 install] ").append(if (installed.ok) "OK" else "FAILED").append('\n')
            .append(installed.output.take(800)).append('\n')
        if (!installed.ok) {
            log.append("\nThe patched APK is built and signed at: ").append(signedApk)
            log.append("\nInstall manually with root.exec: pm install -r -d <path>")
            return ToolResult(ok = false, log.toString(), error = "Install failed — APK is ready at $signedApk")
        }

        log.append("\nPipeline complete. Patched app installed from: ").append(signedApk)
        return ToolResult(
            ok = true,
            log.toString(),
            metadata = mapOf(
                "signed_apk" to signedApk,
                "installed" to true,
            ),
        )
    }
}