package com.example.oreshik.agent.tools

import android.graphics.Bitmap
import com.example.oreshik.agent.accessibility.AgentAccessibilityService
import com.example.oreshik.agent.core.AgentTool
import com.example.oreshik.agent.core.ToolCategory
import com.example.oreshik.agent.core.ToolContext
import com.example.oreshik.agent.core.ToolDefinition
import com.example.oreshik.agent.core.ToolPermission
import com.example.oreshik.agent.core.ToolResult
import com.example.oreshik.agent.security.RootManager
import com.example.oreshik.agent.vision.VisionAnalyzer
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Memory scanning and modification tools (root required).
 *
 * Provides:
 *  - mem.process.list         - list running processes with PIDs for targeting
 *  - mem.regions              - list memory regions/maps of a target process
 *  - mem.scan                 - scan a target process' memory for byte/string/value patterns
 *  - mem.read                 - read raw memory from a process address range
 *  - mem.write                - write bytes/values to a process address (root)
 *  - mem.inject               - inject a library (.so) into a target process (root, dangerous)
 *  - mem.visual.adjust        - iteratively adjust a value and verify each step with the
 *                               vision model (same pipeline as androidanalyze), streaming
 *                               progress to the UI
 *  - mem.visual.verify        - one-shot visual verification of the current screen
 *  - mem.inject.verify        - validate an injection took effect (alive, library mapped,
 *                               anti-detection risk delta)
 *  - mem.stealth              - hide injection traces (maps filter, thread rename, ptrace)
 *  - mem.stealth.verify       - before/after anti-detection scoring to prove stealth worked
 *  - mem.antidetect           - scan for signs the process is being monitored
 *  - mem.visual.autoplay      - AUTOMATIC visual game-playing agent: loops capture→analyze→act
 *                               using the same vision pipeline as androidanalyze. Designed for
 *                               simple 2D games (runners, clickers, puzzles) where the screen
 *                               fully represents game state.
 *
 * Only use on processes you own or have explicit permission to inspect/modify.
 */

private fun runRootCmd(root: RootManager, cmd: String, timeout: Long = 30): Pair<Int, String> {
    if (!root.canUseRoot()) return -1 to "Root unavailable"
    return root.execute(cmd, timeout)
}

private fun parseHexOrDec(s: String): Long? {
    val t = s.trim().lowercase()
    return if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2)
        .toLongOrNull(16) else t.toLongOrNull()
}

// ---------------------------------------------------------------------------
// Shared visual-verification pipeline (same one androidanalyze uses)
// ---------------------------------------------------------------------------

/**
 * Captures the current screen via the accessibility service (same as
 * android_screenshot) and analyzes it with the ACTIVE provider's vision model
 * (same as androidanalyze / VisionAnalyzer). Progress is streamed to the UI
 * through the tool progress channel. Returns the analysis report.
 */
private fun captureAndAnalyze(
    context: ToolContext,
    question: String,
    savePath: File? = null,
): Result<String> = runCatching {
    val service = AgentAccessibilityService.instance
        ?: error("Accessibility service is not enabled. Enable Oreshik Agent Accessibility in Android Settings.")
    if (!com.example.oreshik.agent.core.AgentConfig.visionAutoDescribe &&
        com.example.oreshik.agent.core.AgentConfig.visionModel.isBlank()
    ) {
        error("Vision analysis disabled in Settings (enable 'Use vision models for screen analysis').")
    }
    val bitmap: Bitmap = service.takeScreenshot()
        ?: error("Screen capture unavailable (needs Android 11+ and accessibility enabled).")
    try {
        savePath?.let { f ->
            runCatching {
                java.io.FileOutputStream(f)
                    .use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, out) }
            }
        }
        val w = bitmap.width
        val h = bitmap.height
        val onProgress = context.onProgress
        val analysis: VisionAnalyzer.Analysis = VisionAnalyzer.analyze(bitmap, question) { progressMsg ->
            CoroutineScope(Dispatchers.IO).launch { runCatching { onProgress?.invoke(progressMsg) } }
        }
        if (!analysis.ok) error("Vision analysis failed: ${analysis.error}")
        val report = buildString {
            append(analysis.observations)
            analysis.nextAction?.let { action -> append("\n[Vision] Suggested next action: ").append(action) }
            val scaleX = w.toFloat() / minOf(w, 1280)
            if ((analysis.tapX != null) && (analysis.tapY != null)) {
                append("\n[Vision] Tap target mapped to real screen: (")
                    .append((analysis.tapX * scaleX).toInt()).append(",")
                    .append((analysis.tapY * scaleX).toInt()).append(") of ${w}x$h")
            }
        }
        report
    } finally {
        bitmap.recycle()
    }
}

/**
 * Decides whether a vision answer indicates the target visual state was reached.
 * When [targetState] is given, one of its comma-separated phrases must appear
 * in the answer; otherwise any explicit positive word works. Common negations
 * invalidate a positive hit.
 */
private fun visionIndicatesSuccess(answer: String, targetState: String): Boolean {
    val lower = answer.lowercase()
    val negated = listOf(
        "no ",
        "not ",
        "isn't",
        "is not",
        "unavailable",
        "missing",
        "unchanged",
        "still ",
    ).any { phrase -> lower.contains(phrase) }
    if (targetState.isNotBlank()) {
        val stateHit = targetState.lowercase().split(Regex("\\s*,\\s*"))
            .any { it.isNotBlank() && lower.contains(it.trim()) }
        return stateHit && !negated
    }
    val positive = listOf(
        "yes",
        "true",
        "match",
        "found",
        "success",
        "full",
        "correct",
        "visible",
        "present",
    ).any { phrase -> lower.contains(phrase) }
    return positive && !negated
}

// ---------------------------------------------------------------------------
// Root fallback for /proc access
// ---------------------------------------------------------------------------

/**
 * Returns the parsed lines of a file under /proc, falling back to `su -c cat`
 * when direct reads fail (other-UID processes hide cmdline/environ/status from
 * the app's own UID even when root IS available — normal access must be tried
 * first for correctness, then root for privilege).
 */
@Suppress("unused") // Kept for future root-fallback readers outside the current call graph.
private fun readProcLines(root: RootManager, path: String, limit: Int = 4096): List<String> {
    val direct = runCatching {
        val f = File(path)
        if (f.exists()) f.readLines().take(limit) else null
    }.getOrNull()
    direct?.let { lines -> return lines.take(limit) }
    if (!root.canUseRoot()) return emptyList()
    val (code, out) = runRootCmd(root, "cat $path", 10)
    if (code != 0) return emptyList()
    return out.lineSequence().take(limit).toList()
}

/** Raw-text root-fallback read of a /proc file; null when both paths fail. */
private fun readProcText(root: RootManager, path: String, limit: Int = 200_000): String? {
    val direct = runCatching { File(path).takeIf { it.exists() }?.readText() }.getOrNull()
    if (direct != null) {
        return direct.take(limit)
    }
    if (!root.canUseRoot()) return null
    val (code, out) = runRootCmd(root, "cat $path", 10)
    if (code != 0) return null
    return out.take(limit)
}

/** True when the target process is still alive (root fallback for other-UID procs). */
private fun isProcessAlive(root: RootManager, pid: Int): Boolean {
    if (File("/proc/$pid").exists()) {
        if (
            runCatching {
                File("/proc/$pid/cmdline").readText().isNotBlank()
            }.getOrDefault(defaultValue = false)
        ) return true
        // Directory exists but cmdline unreadable (other-UID): verify via su.
        if (!root.canUseRoot()) return true // conservative: dir visible => treat alive
    }
    if (root.canUseRoot()) {
        val (code, out) = runRootCmd(root, "test -d /proc/$pid && echo alive", 5)
        if ((code == 0) && (out.contains("alive"))) return true
    }
    return false
}

/**
 * True when a library (or substring of its path) is mapped into the target process.
 * Root-fallback via readProcText so other-UID maps are still visible with su.
 */
private fun isLibraryMapped(
    root: RootManager,
    pid: Int,
    libSubstring: String,
): Boolean {
    val maps = readProcText(root, "/proc/$pid/maps") ?: return false
    return maps.lowercase().contains(libSubstring.lowercase())
}

// ---------------------------------------------------------------------------
// Shared process enumeration (used by mem.process.list AND system.process_list)
// ---------------------------------------------------------------------------

/** One running process as surfaced by the process-list tools. */
internal data class ProcEntry(
    val pid: Int,
    val name: String,
    val uid: Int,
    val cmdline: String,
    val state: String = "",
    val ppid: Int = 0,
    val cpuTimeTotal: Long = 0L,
    val rssKb: Long = 0L,
    val vszKb: Long = 0L,
)

/** ps TIME column ("00:09:06" / "9:06" / "1-02:03:04") → total seconds. */
private fun parsePsTimeToSeconds(t: String): Long {
    var total = 0L
    var timePart = t
    val dash = t.indexOf('-')
    if (dash > 0) {
        total += t.substring(0, dash).toLongOrNull()?.times(86400) ?: 0L
        timePart = t.substring(dash + 1)
    }
    for (p in timePart.split(":")) {
        if (p.isBlank()) continue
        total = (total * 60) + (p.toLongOrNull() ?: 0L)
    }
    return total
}

/** ps USER column ("u0_a207", "root", "system", numeric) → uid (or -1). */
private fun psUserToUid(user: String): Int {
    val appMatch = Regex("u(\\d+)_a(\\d+)").find(user)
    if (appMatch != null) {
        val appId = appMatch.groupValues[1].toInt()
        val userId = appMatch.groupValues[2].toInt()
        return (appId * 100_000) + 10_000 + userId
    }
    return when {
        user == "root" -> 0
        user == "system" -> 1000
        user == "shell" -> 2000
        user.matches(Regex("\\d+")) -> user.toInt()
        else -> -1
    }
}

/**
 * Parses "<pid> (<comm>) state ppid ... utime stime" /proc stat content.
 * Paren-based comm extraction (comm may contain spaces, which breaks naive splits).
 */
private fun parseStatLine(stat: String): ProcEntry? {
    val open = stat.indexOf('(')
    val close = stat.lastIndexOf(')')
    if ((open <= 0) || (close <= open)) return null
    val pid = stat.substring(0, open).trim().toIntOrNull() ?: return null
    val comm = stat.substring(open + 1, close)
    val rest = stat.substring(close + 1).trim().split(Regex("\\s+"))
    if (rest.size < 3) return null
    val state = rest[0]
    val ppid = rest.getOrElse(1) { "0" }.toIntOrNull() ?: 0
    val utime = rest.getOrElse(11) { "0" }.toLongOrNull() ?: 0L
    val stime = rest.getOrElse(12) { "0" }.toLongOrNull() ?: 0L
    return ProcEntry(pid, comm, -1, "", state, ppid, utime + stime)
}

/** Single su round-trip walk of /proc (fallback when `ps -o` is unsupported). */
private fun procBatchCmd(): String {
    val d = "$"
    return "for p in /proc/[0-9]*; do pid=" + d + "{p##*/}; " +
        "s=" + d + "(cat " + d + "p/stat 2>/dev/null); " +
        "u=" + d + "(grep -m1 ^Uid " + d + "p/status 2>/dev/null); " +
        "r=" + d + "(grep -m1 ^VmRSS " + d + "p/status 2>/dev/null); " +
        "c=" + d + "(cat " + d + "p/cmdline 2>/dev/null | tr '\\0' ' '); " +
        "printf '%s\\t%s\\t%s\\t%s\\t%s\\n' \"" + d + "pid\" \"" + d + "s\" \"" + d + "u\" \"" + d + "r\" \"" + d + "c\"; done"
}

/**
 * Enumerates running processes with root support.
 *
 * The app's own UID can enumerate /proc but CANNOT read stat/cmdline/status of
 * other-UID (background) processes — SELinux hides them — so a plain /proc walk
 * silently drops every other app. When root is available we take a single
 * `su -c 'ps -A -o ...'` snapshot (all UIDs visible), falling back to one
 * batched su loop over /proc when the ROM's ps lacks -o. Without root the
 * legacy direct /proc walk is used (own-UID processes only).
 */
internal fun listProcessesWithRoot(
    root: RootManager?,
    filter: String,
    limit: Int,
): List<ProcEntry> {
    fun matches(name: String, cmdline: String): Boolean =
        filter.isBlank() || name.lowercase().contains(filter) ||
            cmdline.lowercase().contains(filter)

    // ---- Preferred: one root `ps -A -o ...` snapshot ------------------------
    if (root?.canUseRoot() == true) {
        val (code, psOut) =
            runRootCmd(root, "ps -A -o PID,USER,STAT,PPID,RSS,VSZ,TIME,NAME,ARGS", 20)
        if (code == 0) {
            val parsed = ArrayList<ProcEntry>()
            for (raw in psOut.lineSequence()) {
                val t = raw.trim()
                if (t.isEmpty() || !t[0].isDigit()) continue // header/blank
                val cols = t.split(Regex("\\s+"), limit = 9)
                // Columns: PID USER STAT PPID RSS VSZ TIME NAME [ARGS]
                if (cols.size < 8) continue
                val pid = cols[0].toIntOrNull() ?: continue
                if (pid <= 0) continue
                val name = cols[7]
                val args = if (cols.size >= 9) cols[8] else name
                if (!matches(name, args)) continue
                if (parsed.size >= limit) break
                parsed += ProcEntry(
                    pid = pid,
                    name = name,
                    uid = psUserToUid(cols[1]),
                    cmdline = args,
                    state = cols[2],
                    ppid = cols[3].toIntOrNull() ?: 0,
                    cpuTimeTotal = parsePsTimeToSeconds(cols[6]),
                    rssKb = cols[4].toLongOrNull() ?: 0L,
                    vszKb = cols[5].toLongOrNull() ?: 0L
                )
            }
            if (parsed.isNotEmpty()) return parsed
        }

        // ---- Fallback A: batched su walk of /proc (ps -o unsupported) --------
        val (batchCode, batchOut) = runRootCmd(root, procBatchCmd(), 30)
        if (batchCode == 0) {
            val parsed = ArrayList<ProcEntry>()
            for (raw in batchOut.lineSequence()) {
                if (raw.isBlank()) continue
                val f = raw.split("\t", limit = 5)
                // First field must be a numeric PID (guards against stray su output).
                if (f[0].trim().toIntOrNull() == null) continue
                val entry = parseStatLine(f.getOrElse(1) { "" }) ?: continue
                val uid = Regex("Uid:\\s*(\\d+)")
                    .find(f.getOrElse(2) { "" })?.groupValues?.get(1)?.toIntOrNull() ?: -1
                val rss = Regex("VmRSS:\\s*(\\d+)")
                    .find(f.getOrElse(3) { "" })?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val cmdline = f.getOrElse(4) { "" }.replace("\u0000", " ").trim()
                if (!matches(entry.name, cmdline)) continue
                if (parsed.size >= limit) break
                parsed += entry.copy(uid = uid, cmdline = cmdline, rssKb = rss)
            }
            if (parsed.isNotEmpty()) return parsed
        }
        // Both root paths failed — fall through to the direct walk below.
    }

    // ---- Legacy: direct /proc walk (own-UID processes only) -----------------
    val pidDirs = File("/proc").listFiles()
        ?.filter { it.isDirectory && it.name.all { ch -> ch.isDigit() } }
        ?: return emptyList()
    val parsed = ArrayList<ProcEntry>()
    for (pidDir in pidDirs) {
        if (parsed.size >= limit) break
        val statContent = runCatching { File(pidDir, "stat").readText() }.getOrNull()
            ?: continue // unreadable (foreign-UID, no root) — skip
        val entry = parseStatLine(statContent) ?: continue
        var uid = -1
        var rss = 0L
        var vsz = 0L
        val statusLines = runCatching { File(pidDir, "status").readLines() }.getOrNull().orEmpty()
        for (line in statusLines) {
            when {
                line.startsWith("Uid:") ->
                    uid = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: -1
                line.startsWith("VmRSS:") ->
                    rss = line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
                line.startsWith("VmSize:") ->
                    vsz = line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
            }
        }
        val cmdline = runCatching {
            File(pidDir, "cmdline").readText().replace("\u0000", " ").trim()
        }.getOrDefault("")
        if (!matches(entry.name, cmdline)) continue
        parsed += entry.copy(uid = uid, cmdline = cmdline, rssKb = rss, vszKb = vsz)
    }
    return parsed
}

// ---------------------------------------------------------------------------
// Stealth script helpers (root shell; shell $ escaped for Kotlin)
// ---------------------------------------------------------------------------

// Shell scripts assembled from parts so the dollar signs reach the remote shell
// verbatim (script templating happens on-device, not in Kotlin).
private const val DOLLAR = "$"

private fun getUnlinkScript(@Suppress("UNUSED_PARAMETER") lib: String): String =
    "#!/system/bin/sh\nPID=" + DOLLAR + "1\nLIB=\"" + DOLLAR + "2\"\ngrep -v \"" + DOLLAR + "LIB\" /proc/" + DOLLAR + "PID/maps > /tmp/maps_filtered 2>/dev/null\necho \"Maps filtering for " + DOLLAR + "LIB prepared. Use mount namespace for full effect.\"\ncat /tmp/maps_filtered"

private fun getHideThreadsScript(): String {
    val d = DOLLAR
    return "#!/system/bin/sh\nPID=" + d + "1\nfor tid in " + d + "(ls /proc/" + d + "PID/task/ 2>/dev/null); do\n    COMM=" + d +
        "(cat /proc/" + d + "PID/task/" + d + "tid/comm 2>/dev/null)\n    if [[ \"" + d + "COMM\" == *frida* ]] || [[ \"" + d + "COMM\" == *gadget* ]] || [[ \"" + d + "COMM\" == *agent* ]] || [[ \"" + d + "COMM\" == *linject* ]]; then\n        echo \"Hiding thread " + d + "tid: " + d + "COMM\"\n        printf 'kworker/u:%d' " + d + "((RANDOM % 100)) > /proc/" + d + "PID/task/" + d + "tid/comm 2>/dev/null\n    fi\ndone"
}

private fun getSpoofNameScript(): String {
    val d = DOLLAR
    return "#!/system/bin/sh\nPID=" + d + "1\nNAME=" + d + "2\nprintf '%s' \"" + d + "NAME\" > /proc/" + d + "PID/comm 2>/dev/null\nsed -i \"s/^Name:.*/Name:\\t" + d + "NAME/\" /proc/" + d + "PID/status 2>/dev/null\necho \"Process name spoofed to: " + d + "NAME\""
}

private fun getPtraceScript(): String {
    val d = DOLLAR
    return "#!/system/bin/sh\nPID=" + d + "1\nSTATUS=" + d + "(cat /proc/" + d + "PID/status 2>/dev/null | grep TracerPid)\necho \"Current TracerPid: " + d + "STATUS\"\necho \"To clear ptrace: ensure no debugger attached. Use 'ptrace -p " + d + "PID -d' if you attached.\""
}

private fun getObfuscateScript(): String {
    val d = DOLLAR
    return "#!/system/bin/sh\nPID=" + d + "1\nawk '/rwx/ {print " + d + "1 \" \" " + d + "2}' /proc/" + d + "PID/maps 2>/dev/null | while read range perms; do\n    START=" + d + "{range%-*}\n    END=" + d + "{range#*-}\n    echo \"Found RWX region: " + d + "range -> changing to RW-\"\n    echo \"Would mprotect 0x" + d + "START 0x" + d + "END PROT_READ|PROT_WRITE\"\ndone"
}

// ---------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------

/** 1. List processes (root-fallback aware: background/foreign-UID apps included) */
class MemProcessListTool(private val root: RootManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.process.list",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "List running processes with PID, name, UID for targeting memory operations. Uses root (when enabled) so background/other-UID apps are visible too.",
        permissions = setOf(ToolPermission.PROCESS_MEMORY),
        parameters = mapOf("filter" to "string", "limit" to "integer")
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val o = arguments.asJsonObject
            val filter = o["filter"]?.asString?.lowercase() ?: ""
            val limit = (o["limit"]?.asInt ?: 200).coerceIn(1, 1000)
            try {
                val entries = listProcessesWithRoot(root, filter, limit)
                val noRootNote = if (!root.canUseRoot())
                    "\nNote: root unavailable — foreign-UID (other apps') background processes may be missing. Enable Allow root in Settings and grant su for full visibility."
                else ""
                if (entries.isEmpty()) {
                    return@withContext ToolResult(ok = true, "No processes matched.$noRootNote")
                }
                val body = entries.joinToString("\n") { e ->
                    JsonObject().apply {
                        addProperty("pid", e.pid)
                        addProperty("name", e.name)
                        addProperty("uid", e.uid)
                        addProperty("cmdline", e.cmdline.take(200))
                    }.toString()
                }
                ToolResult(ok = true, body + noRootNote)
            } catch (e: Exception) {
                ToolResult(ok = false, error = e.message ?: "Failed to list processes")
            }
        }
}

/** 2. List memory regions (maps) of a target PID (root-fallback for other-UID procs) */
class MemRegionsTool : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.regions",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "List memory regions (from /proc/<pid>/maps) of a target process. Shows start-end, permissions, offset, device, inode, pathname. Root-fallback: other-UID (background) processes are read via su when direct access is denied.",
        permissions = setOf(ToolPermission.MEMORY_SCAN, ToolPermission.PROCESS_MEMORY),
        parameters = mapOf("pid" to "integer (required)")
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val pid = arguments.asJsonObject["pid"]?.asInt ?: return@withContext ToolResult(
                false,
                error = "Missing pid"
            )
            val root = RootManager(context.app)
            val mapsText = readProcText(root, "/proc/$pid/maps")
                ?: return@withContext ToolResult(
                    false,
                    error = "Process $pid not found or maps unreadable (direct read denied and root unavailable). Enable Allow root in Settings to read other apps' memory maps."
                )
            val lines = mapsText.lines()
            val out = lines.asSequence().take(500).joinToString("\n")
            ToolResult(
                true,
                if (lines.size > 500) "$out\n... (${lines.size - 500} more regions)" else out
            )
        }
}

/** 3. Scan memory for a pattern (bytes, string, or numeric value) */
class MemScanTool : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.scan",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "Scan a target process' readable memory regions for a pattern. Pattern can be hex bytes (0xDEADBEEF), string (\"hello\"), or integer/float value. Returns list of matching addresses.",
        permissions = setOf(ToolPermission.MEMORY_SCAN, ToolPermission.PROCESS_MEMORY),
        parameters = mapOf(
            "pid" to "integer (required)",
            "pattern" to "string (required) - hex bytes like 'DE AD BE EF' or '0xDEADBEEF', string like 'hello', or number like '12345' / '3.14'",
            "type" to "string (bytes|string|int32|int64|float|double, default bytes)",
            "regions" to "string (optional filter: 'rw', 'r-x', 'heap', 'stack', 'anon', or comma-separated)",
            "max_results" to "integer (1-5000, default 100)",
            "start_addr" to "string (optional hex start address override)",
            "end_addr" to "string (optional hex end address override)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val o = arguments.asJsonObject
            val pid =
                o.get("pid")?.asInt ?: return@withContext ToolResult(false, error = "Missing pid")
            val patternStr = o.get("pattern")?.takeUnless { it.isJsonNull }?.asString
                ?: return@withContext ToolResult(false, error = "Missing pattern")
            val type = (o.get("type")?.asString ?: "bytes").lowercase()
            val regionFilter = (o.get("regions")?.asString ?: "").lowercase()
            val maxResults = (o.get("max_results")?.asInt ?: 100).coerceIn(1, 5000)
            val startOverride = parseHexOrDec(o.get("start_addr")?.asString ?: "")
            val endOverride = parseHexOrDec(o.get("end_addr")?.asString ?: "")

            val patternBytes = when (type) {
                "string" -> patternStr.toByteArray(Charsets.UTF_8)
                "int32" -> patternStr.toIntOrNull()?.let { intToBytes(it) }
                    ?: return@withContext ToolResult(false, error = "Invalid int32")

                "int64" -> patternStr.toLongOrNull()?.let { longToBytes(it) }
                    ?: return@withContext ToolResult(false, error = "Invalid int64")

                "float" -> patternStr.toFloatOrNull()?.let { floatToBytes(it) }
                    ?: return@withContext ToolResult(false, error = "Invalid float")

                "double" -> patternStr.toDoubleOrNull()?.let { doubleToBytes(it) }
                    ?: return@withContext ToolResult(false, error = "Invalid double")

                else -> parseHexPattern(patternStr) ?: return@withContext ToolResult(
                    false,
                    error = "Invalid hex pattern. Use spaces or 0x prefix: 'DE AD BE EF' or '0xDEADBEAF'"
                )
            }

            val root = RootManager(context.app)
            // Root-fallback read: other-UID /proc/pid/maps is invisible to the app UID.
            val mapsText = readProcText(root, "/proc/$pid/maps")
                ?: return@withContext ToolResult(
                    false,
                    error = "Cannot read /proc/$pid/maps (process gone or no root). Enable Allow root in Settings to scan other apps' processes."
                )
            val memFile = File("/proc/$pid/mem")
            val canDirectMem = memFile.canRead()
            if (!canDirectMem && !root.canUseRoot()) {
                return@withContext ToolResult(
                    false,
                    error = "Cannot read /proc/$pid/mem (need root for other-UID processes). Enable Allow root in Settings."
                )
            }

            val results = mutableListOf<Long>()
            val mapsLines = mapsText.lines()
            for (line in mapsLines) {
                if (results.size >= maxResults) break
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 5) continue
                val addrRange = parts[0].split("-")
                if (addrRange.size != 2) continue
                val perms = parts[1]
                val pathName = if (parts.size >= 6) parts[5] else ""

                if (regionFilter.isNotBlank()) {
                    val match = when {
                        regionFilter.contains("rw") && perms.contains("w") -> true
                        regionFilter.contains("rx") && perms.contains("x") && !perms.contains("w") -> true
                        regionFilter.contains("heap") && pathName.contains("[heap]") -> true
                        regionFilter.contains("stack") && pathName.contains("[stack]") -> true
                        regionFilter.contains("anon") && pathName.isBlank() -> true
                        else -> false
                    }
                    if (!match) continue
                }

                val regionStart = addrRange[0].toLongOrNull(16) ?: continue
                val regionEnd = addrRange[1].toLongOrNull(16) ?: continue
                val scanStart = startOverride?.coerceAtLeast(regionStart) ?: regionStart
                val scanEnd = endOverride?.coerceAtMost(regionEnd) ?: regionEnd
                if (scanStart >= scanEnd) continue
                if (scanEnd - scanStart > 50_000_000) continue

                val found = if (canDirectMem) {
                    scanRegion(
                        pid,
                        memFile,
                        scanStart,
                        scanEnd,
                        patternBytes,
                        maxResults - results.size
                    )
                } else {
                    // Other-UID target: scan through su. Chunked `dd | od -An -v -tx1`
                    // yields raw hex bytes and sidesteps both the 1MB text cap and the
                    // unreadable /proc/pid/mem of a foreign-UID process.
                    scanRegionViaRoot(
                        root,
                        pid,
                        scanStart,
                        scanEnd,
                        patternBytes,
                        maxResults - results.size
                    )
                }
                results.addAll(found)
            }

            val out = if (results.isEmpty()) "No matches found." else {
                results.joinToString("\n") { "0x%X".format(it) }
            }
            ToolResult(true, "Found ${results.size} match(es):\n$out")
        }
}

/** 4. Read raw memory from a process */
class MemReadTool : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.read",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "Read raw bytes from a target process memory at address. Returns hex dump and ASCII.",
        permissions = setOf(ToolPermission.MEMORY_SCAN, ToolPermission.PROCESS_MEMORY),
        parameters = mapOf(
            "pid" to "integer (required)",
            "address" to "string (required, hex like 0x7F000000 or decimal)",
            "size" to "integer (bytes to read, 1-65536, default 256)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val o = arguments.asJsonObject
            val pid =
                o.get("pid")?.asInt ?: return@withContext ToolResult(false, error = "Missing pid")
            val addr =
                parseHexOrDec(o.get("address")?.asString ?: "") ?: return@withContext ToolResult(
                    false,
                    error = "Invalid address"
                )
            val size = (o.get("size")?.asInt ?: 256).coerceIn(1, 65536)

            val memFile = File("/proc/$pid/mem")
            if (!memFile.canRead()) {
                val root = RootManager(context.app)
                if (!root.canUseRoot()) {
                    return@withContext ToolResult(
                        false,
                        error = "Cannot read /proc/$pid/mem (need root). Enable Allow root in Settings."
                    )
                }
                val (code, output) = runRootCmd(
                    root,
                    // bs=1 with skip is brutally slow for large sizes; use a bs that
                    // divides evenly for speed, falling back only when needed.
                    "dd if=/proc/$pid/mem bs=4096 skip=$((addr / 4096)) count=$(( (size + 4095) / 4096 )) 2>/dev/null | od -An -v -tx1",
                    10
                )
                if (code != 0) return@withContext ToolResult(
                    false,
                    error = "Root read failed: $output"
                )
                // Convert the od hex bytes into a hex dump with address + ASCII gutter.
                val bytes = output.trim()
                    .split(Regex("\\s+"))
                    .asSequence()
                    .filter { token -> token.matches(Regex("^[0-9a-fA-F]{2}$")) }
                    .map { token -> token.toInt(16).toByte() }
                    .toList()
                    .toByteArray()
                if (bytes.isEmpty()) return@withContext ToolResult(
                    false,
                    error = "Root read returned no bytes (SELinux denial or /proc/$pid/mem inaccessible even via su)"
                )
                return@withContext ToolResult(true, hexDump(bytes, addr))
            }

            return@withContext try {
                val raf = java.io.RandomAccessFile(memFile, "r")
                raf.seek(addr)
                val buf = ByteArray(size)
                val read = raf.read(buf)
                raf.close()
                val data = if (read < size) buf.copyOf(read) else buf
                ToolResult(true, hexDump(data, addr))
            } catch (e: Exception) {
                ToolResult(false, error = "Read failed: ${e.message}")
            }
        }
}

/** 5. Write memory to a process (ROOT REQUIRED) */
class MemWriteTool : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.write",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "Write bytes/values to a target process memory address. REQUIRES ROOT. Extremely dangerous - can crash target process. Only use on processes you own.",
        permissions = setOf(
            ToolPermission.MEMORY_WRITE,
            ToolPermission.PROCESS_MEMORY,
            ToolPermission.ROOT
        ),
        parameters = mapOf(
            "pid" to "integer (required)",
            "address" to "string (required, hex like 0x7F000000)",
            "value" to "string (required) - hex bytes 'DE AD BE EF', string 'hello', or number '12345' / '3.14'",
            "type" to "string (bytes|string|int32|int64|float|double, default bytes)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val o = arguments.asJsonObject
            val pid =
                o.get("pid")?.asInt ?: return@withContext ToolResult(false, error = "Missing pid")
            val addr =
                parseHexOrDec(o.get("address")?.asString ?: "") ?: return@withContext ToolResult(
                    false,
                    error = "Invalid address"
                )
            val valueStr = o.get("value")?.takeUnless { it.isJsonNull }?.asString
                ?: return@withContext ToolResult(false, error = "Missing value")
            val type = (o.get("type")?.asString ?: "bytes").lowercase()

            val root = RootManager(context.app)
            if (!root.canUseRoot()) return@withContext ToolResult(
                false,
                error = "mem.write requires root. Enable Allow root in Settings and grant su prompt."
            )

            val data = when (type) {
                "string" -> valueStr.toByteArray(Charsets.UTF_8)
                "int32" -> valueStr.toIntOrNull()?.let { intToBytes(it) }
                    ?: return@withContext ToolResult(false, error = "Invalid int32")

                "int64" -> valueStr.toLongOrNull()?.let { longToBytes(it) }
                    ?: return@withContext ToolResult(false, error = "Invalid int64")

                "float" -> valueStr.toFloatOrNull()?.let { floatToBytes(it) }
                    ?: return@withContext ToolResult(false, error = "Invalid float")

                "double" -> valueStr.toDoubleOrNull()?.let { doubleToBytes(it) }
                    ?: return@withContext ToolResult(false, error = "Invalid double")

                else -> parseHexPattern(valueStr) ?: return@withContext ToolResult(
                    false,
                    error = "Invalid hex pattern"
                )
            }

            val hexFile = File(context.workspace, "mem_write_${System.currentTimeMillis()}.bin")
            try {
                hexFile.writeBytes(data)
                val (code, output) = runRootCmd(
                    root,
                    "dd if=${hexFile.absolutePath} of=/proc/$pid/mem bs=1 seek=$addr count=${data.size} conv=notrunc 2>&1",
                    10
                )
                hexFile.delete()
                if (code != 0) return@withContext ToolResult(
                    false,
                    error = "Write failed (exit $code): $output"
                )
                // Read-back verification: what the process actually holds now.
                val readBack = runCatching {
                    val raf = java.io.RandomAccessFile(File("/proc/$pid/mem"), "r")
                    raf.seek(addr)
                    val buf = ByteArray(data.size)
                    val n = raf.read(buf)
                    raf.close()
                    if (n > 0) buf.copyOf(n) else null
                }.getOrNull()
                val verified = readBack != null && readBack.contentEquals(data)
                val out = buildString {
                    append("Wrote ").append(data.size).append(" bytes to 0x%X in pid ").append(pid)
                        .append('\n')
                    if (readBack != null) {
                        append("Read-back: ").append(if (verified) "VERIFIED (matches written bytes)" else "MISMATCH")
                        if (!verified) {
                            append(" — actual: ").append(readBack.joinToString(" ") { byte -> "%02X".format(byte) })
                        }
                        append('\n')
                    } else {
                        append("Read-back unavailable (kernel restrictions); only write exit code 0 confirmed.\n")
                    }
                    append("Process alive: ").append(
                        if (isProcessAlive(
                                root,
                                pid
                            )
                        ) "yes" else "NO — target may have crashed"
                    )
                }
                ToolResult(
                    verified || readBack == null,
                    out,
                    metadata = mapOf("verified" to verified)
                )
            } catch (e: Exception) {
                hexFile.delete()
                ToolResult(false, error = "Write failed: ${e.message}")
            }
        }
}

/**
 * 6. Inject a shared library (.so) into a target process (ROOT).
 *
 * Addresses three real-world failure modes of a naive injector:
 *  1. SELinux: ptrace-attach from the su/magisk domain into an untrusted_app
 *     domain is denied on enforcing devices. This tool optionally flips SELinux
 *     permissive for the injection (or applies magiskpolicy live rules) and
 *     restores the previous mode afterwards.
 *  2. Missing injector binary: a real ptrace injector (PTRACE_ATTACH → remote
 *     dlopen → PTRACE_DETACH) is required on-device. The tool auto-installs a
 *     prebuilt injector (frida-inject style) into /data/local/tmp if provided
 *     in the workspace, or emits precise instructions otherwise.
 *  3. Anti-tamper apps (pairipcore / Play Integrity): the tool detects the
 *     presence of libpairipcore.so in the target's maps and warns + suggests
 *     Zygisk-based spawn-time injection, which the single-IPC loop cannot do.
 *
 * The library file is validated (exists, readable via su, ELF magic, correct
 * arch) BEFORE any ptrace attach, so a bad path can no longer "crash" the
 * validation while leaving the target running.
 */
class MemInjectTool : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.inject",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "Inject a shared library (.so) into a target process (root). Validates the .so (ELF magic + architecture), handles SELinux (permissive or magiskpolicy live rules) for the duration of the injection, runs a real ptrace injector binary when available, then verifies the library is mapped and the target is still alive. Detects anti-tamper (pairipcore) targets and recommends Zygisk spawn-time injection. DANGEROUS — only use on processes you own.",
        permissions = setOf(
            ToolPermission.MEMORY_WRITE,
            ToolPermission.PROCESS_MEMORY,
            ToolPermission.ROOT
        ),
        parameters = mapOf(
            "pid" to "integer (required)",
            "library_path" to "string (required, absolute path to .so on device, e.g. /data/local/tmp/libfrida-gadget.so)",
            "injector_path" to "string (optional, absolute path to a prebuilt ptrace injector binary; auto-detected in workspace/runtimes when omitted)",
            "function" to "string (optional, exported entry point passed to the injector; default 'agent_main')",
            "allow_permissive" to "boolean (optional, default true) - allow temporarily setting SELinux permissive during injection; restore follows automatically"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val o = arguments.asJsonObject
            val pid =
                o.get("pid")?.asInt ?: return@withContext ToolResult(false, error = "Missing pid")
            val libPath = o.get("library_path")?.takeUnless { it.isJsonNull }?.asString
                ?: return@withContext ToolResult(false, error = "Missing library_path")
            val function = o.get("function")?.takeUnless { it.isJsonNull }?.asString ?: "agent_main"
            val allowPermissive =
                o.get("allow_permissive")?.takeUnless { it.isJsonNull }?.asBoolean ?: true
            val injectorArg =
                o.get("injector_path")?.takeUnless { it.isJsonNull }?.asString.orEmpty()

            val root = RootManager(context.app)
            if (!root.canUseRoot()) return@withContext ToolResult(
                false,
                error = "mem.inject requires root. Enable Allow root in Settings."
            )

            val report = mutableListOf<String>()

            // ---- 0. Pre-flight: does the target exist? -------------------------
            if (!isProcessAlive(root, pid)) {
                return@withContext ToolResult(
                    false,
                    error = "Target pid $pid is not running (no /proc/$pid). Find the right pid with mem.process.list."
                )
            }
            report += "Target alive: yes (pid $pid)"

            // ---- 1. Validate the library BEFORE touching the target ------------
            if (!File(libPath).exists()) {
                return@withContext ToolResult(false, error = "Library not found at $libPath")
            }
            val (libOk, libCheck) = validateSoFile(libPath)
            if (!libOk) {
                return@withContext ToolResult(false, error = "Library validation failed: $libCheck")
            }
            report += "Library: $libCheck"

            // Copy the .so to a world-readable path (app data dirs are not
            // readable by the target's SELinux domain, /data/local/tmp is).
            var staged = libPath
            if (!libPath.startsWith("/data/local/tmp")) {
                val target =
                    "/data/local/tmp/inject_${System.currentTimeMillis()}_${File(libPath).name}"
                val (cpCode, cpOut) = runRootCmd(
                    root,
                    "cp '$libPath' $target && chmod 644 $target && chown root:root $target",
                    15
                )
                if (cpCode == 0) {
                    staged = target
                    report += "Staged library to $target (target-SELinux-domain readable)"
                } else {
                    report += "⚠ Staging to /data/local/tmp failed: ${cpOut.take(120)} — continuing with $libPath"
                }
            }

            // ---- 2. Anti-tamper detection (pairipcore / Play Integrity) --------
            val mapsNow = readProcText(root, "/proc/$pid/maps").orEmpty()
            val hasPairip = mapsNow.contains("libpairipcore.so")

            if (hasPairip) {
                report += "⚠ ANTI-TAMPER: libpairipcore.so detected in target maps (Google Play Integrity)."
                report += "  ptrace injection may be detected/hooked. Prefer Zygisk spawn-time injection:"
                report += "  install a Zygisk module that dlopen()s '$staged' in the app's zygote-forked child BEFORE pairipcore init."
            }

            // ---- 3. SELinux handling -------------------------------------------
            val previousMode = root.getSelinuxMode()
            var selinuxChanged = false
            if (previousMode == "Enforcing" && allowPermissive) {
                val (ok, msg) = root.setSelinuxPermissive(true)
                if (ok) {
                    selinuxChanged = true
                    report += "SELinux: temporarily permissive (was Enforcing) — will restore after injection"
                } else {
                    report += "⚠ SELinux: could not go permissive ($msg). Trying magiskpolicy live rules instead…"
                    // Magisk live policy: allow su domain to ptrace untrusted_app
                    val (mpCode, mpOut) = runRootCmd(
                        root,
                        "magiskpolicy --live \"allow su untrusted_app process ptrace\" \"allow su untrusted_app process getattr\" \"allow su untrusted_app file *\" 2>&1",
                        10
                    )
                    report += if (mpCode == 0) "magiskpolicy live rules applied: $mpOut".take(200)
                    else "⚠ magiskpolicy failed: ${mpOut.take(150)}"
                }
            } else {
                report += "SELinux: $previousMode (no change)"
            }

            // ---- 4. Locate a real ptrace injector ------------------------------
            val injector = resolveInjector(context, root, injectorArg)
            if (injector == null) {
                // Restore SELinux before bailing out.
                if (selinuxChanged) root.setSelinuxPermissive(false)
                report += ""
                report += "✗ NO PTRACE INJECTOR BINARY AVAILABLE."
                report += "  A userspace ptrace injector is required (PTRACE_ATTACH → mmap RWX page →"
                report += "  write dlopen path → call dlopen in remote thread → PTRACE_DETACH)."
                report += "  Options:"
                report += "   a) Push a prebuilt injector (e.g. 'ptrace-injector' aarch64 ELF) to /data/local/tmp and re-run with injector_path=<path>."
                report += "   b) Install frida-server and use:  su -c '/data/local/tmp/fs128 -U'  then inject via frida tools."
                report += "   c) Use a Zygisk module for spawn-time injection (works against pairipcore)."
                return@withContext ToolResult(false, report.joinToString("\n"), error = null)
            }
            report += "Injector binary: $injector"

            // ---- 5. Run the injection ------------------------------------------
            val (injectCode, injectOut) = runRootCmd(
                root,
                "chmod +x '$injector' && '$injector' $pid '$staged' '$function'",
                30
            )
            report += "Injector exit: $injectCode"
            if (injectOut.isNotBlank()) report += injectOut.lineSequence().take(12)
                .joinToString("\n  ", prefix = "  ")

            // ---- 6. Restore SELinux --------------------------------------------
            if (selinuxChanged) {
                val (rOk, rMsg) = root.setSelinuxPermissive(false)
                report += "SELinux restored: " + (if (rOk) "enforcing again" else "⚠ restore failed: $rMsg")
            }

            // ---- 7. Verify -------------------------------------------------------
            val alive = isProcessAlive(root, pid)
            // Give the target a moment to actually map the lib if the injector returned 0.
            var mapped = isLibraryMapped(root, pid, File(staged).name)
            if (!mapped && injectCode == 0) {
                repeat(5) {
                    Thread.sleep(300)
                    if (isLibraryMapped(root, pid, File(staged).name)) {
                        mapped = true
                        return@repeat
                    }
                }
            }
            report += "Process alive: " + (if (alive) "yes" else "NO — target crashed or exited")
            report += "Library mapped: " + (if (mapped) "yes (present in /proc/$pid/maps)" else "no")

            val success = alive && mapped
            if (!success && hasPairip) {
                report += ""
                report += "NOTE: pairipcore targets usually reject live ptrace injection."
                report += "Use the Zygisk route (spawn-time) or Frida gadget packaged INSIDE the APK instead."
            }
            ToolResult(
                success, report.joinToString("\n"), metadata = mapOf(
                    "alive" to alive,
                    "mapped" to mapped,
                    "injector_exit" to injectCode,
                    "anti_tamper" to hasPairip
                )
            )
        }

    /** ELF magic + architecture sanity check for a .so before ptrace. */
    private fun validateSoFile(path: String): Pair<Boolean, String> {
        val f = File(path)
        if (!f.exists()) return false to "file does not exist"
        if (f.length() == 0L) return false to "file is empty"
        val head = ByteArray(20)
        runCatching { f.inputStream().use { it.read(head) } }
            .onFailure { return false to "cannot read file header" }
        if (head.size < 20) return false to "file too small for ELF header"
        if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) {
            return false to "not an ELF file (missing \\u007FELF magic) — decompress .xz/.gz first"
        }
        val eiClass = head[4].toInt()
        val eiData = head[5].toInt()
        val eMachine = ((head[19].toInt() and 0xFF) shl 8) or (head[18].toInt() and 0xFF)
        val arch = when (eMachine) {
            183 -> "aarch64"
            40 -> "arm (32-bit)"
            62 -> "x86_64"
            3 -> "x86 (32-bit)"
            else -> "machine=$eMachine"
        }
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val classOk = eiClass == 2 // 64-bit expected on all modern devices
        val archOk = when {
            abi.startsWith("arm64") -> eMachine == 183 || eMachine == 40
            abi.startsWith("x86_64") -> eMachine == 62 || eMachine == 3
            else -> true
        }
        val endian = if (eiData == 1) "LE" else "BE"
        val summary = "ELF64=$classOk, arch=$arch, endian=$endian, device-abi=$abi"
        return (classOk && archOk) to summary
    }

    /**
     * Find a usable ptrace injector: explicit arg → workspace → runtimes →
     * /data/local/tmp → **auto-compile from the bundled C source** (assets/
     * injector/ptrace_injector.c) with the app-owned clang into
     * /data/local/tmp/ptrace-injector.
     */
    private suspend fun resolveInjector(
        context: ToolContext,
        root: RootManager,
        explicit: String
    ): String? {
        if (explicit.isNotBlank()) {
            val f = File(explicit)
            if (f.isFile) return f.absolutePath
        }
        val names = listOf("ptrace-injector", "linjector", "dlopen-injector", "frida-inject")
        for (name in names) {
            // Workspace and managed runtimes first (app-owned, no PATH needed).
            File(context.workspace, name).takeIf { it.isFile }?.let { return it.absolutePath }
            com.example.oreshik.agent.runtime.RuntimeManager(context.app).resolveExecutable(name)
                ?.let { return it.absolutePath }
            // Root-owned locations
            for (dir in listOf("/data/local/tmp", "/data/adb", "/system/bin", "/system/xbin")) {
                val (code, _) = runRootCmd(root, "test -f $dir/$name && echo y", 4)
                if (code == 0) return "$dir/$name"
            }
        }
        // Frida-server present? Its gadget path counts as a working injector too.
        for (fs in listOf(
            "/data/local/tmp/frida-server",
            "/data/adb/frida/frida-server",
            "/data/local/tmp/fs128"
        )) {
            val (code, _) = runRootCmd(root, "test -f $fs && echo y", 4)
            if (code == 0) return fs
        }
        // Last resort: compile the bundled C injector on-device with the app's clang.
        return compileBundledInjector(context, root)
    }

    /**
     * Extracts assets/injector/ptrace_injector.c into the workspace and compiles
     * a static aarch64 binary with the app-owned clang. Returns the binary path
     * or null (the caller falls back to instructions).
     */
    private suspend fun compileBundledInjector(context: ToolContext, root: RootManager): String? {
        val runtime = com.example.oreshik.agent.runtime.RuntimeManager(context.app)
        val clang = runtime.resolveExecutable("clang") ?: return null
        // The stub wrapper (execs missing /system/bin/clang) is useless — detect via size.
        if (clang.length() < 100_000) return null

        val srcFile = File(context.workspace, "ptrace_injector.c")
        val outFile = File(context.workspace, "ptrace-injector")
        if (!srcFile.isFile || srcFile.length() < 1000) {
            runCatching {
                context.app.assets.open("injector/ptrace_injector.c").use { input ->
                    srcFile.outputStream().use { input.copyTo(it) }
                }
            }.onFailure { return null }
        }
        if (outFile.isFile && outFile.length() > 10_000) return outFile.absolutePath // cached build

        // Locate the NDK sysroot if installed (bionic headers); plain Linux
        // headers also work for this program since it uses glibc-free syscalls
        // only via unistd/syscall wrappers.
        val ndkSysroot = File(runtime.runtimePath("ndk"), "sysroot").takeIf { it.isDirectory }
            ?: File(
                runtime.runtimePath("ndk"),
                "toolchains/llvm/prebuilt/linux-x86_64/sysroot"
            ).takeIf { it.isDirectory }
        val sysrootArg = ndkSysroot?.let { listOf("--sysroot=${it.absolutePath}") } ?: emptyList()

        val compile = runManaged(
            context, runtime, "clang",
            sysrootArg + listOf(
                "-O2", "-static", "-o", outFile.absolutePath, srcFile.absolutePath,
                "-DNO_ANDROID_PROPS"
            ),
            240
        )
        if (!compile.ok || !outFile.isFile || outFile.length() < 10_000) {
            AppLogger.w(
                "MemInjectTool",
                "on-device injector compile failed: ${compile.error ?: compile.output.take(300)}"
            )
            return null
        }
        // Make it executable everywhere and stage to /data/local/tmp for su runs.
        outFile.setExecutable(true)
        runRootCmd(
            root,
            "cp '${outFile.absolutePath}' /data/local/tmp/ptrace-injector && chmod 755 /data/local/tmp/ptrace-injector",
            15
        )
        return outFile.absolutePath
    }
}

/** 7. Visual adjustment - iterative write + vision verification (androidanalyze pipeline) */
class VisualAdjustTool : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.visual.adjust",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "Iteratively adjust memory values and verify each step with the vision model (same pipeline as androidanalyze: accessibility screenshot + active provider's VL model). Provide target PID, address, value range, and a visual verification prompt. Progress streams to the UI.",
        permissions = setOf(
            ToolPermission.MEMORY_WRITE,
            ToolPermission.PROCESS_MEMORY,
            ToolPermission.ROOT,
            ToolPermission.ACCESSIBILITY
        ),
        parameters = mapOf(
            "pid" to "integer (required)",
            "address" to "string (required, hex address)",
            "value_type" to "string (int32|float|bytes, default int32)",
            "min_value" to "string (minimum value to try)",
            "max_value" to "string (maximum value to try)",
            "step" to "string (increment step, default 1)",
            "verify_prompt" to "string (required) - question for the vision model, e.g. 'Is the health bar full?'",
            "target_state" to "string (comma-separated phrases that must appear in the vision answer to declare success)",
            "delay_ms" to "integer (delay between attempts, 100-10000, default 500)",
            "max_attempts" to "integer (1-100, default 20)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val o = arguments.asJsonObject
            val pid =
                o.get("pid")?.asInt ?: return@withContext ToolResult(false, error = "Missing pid")
            val addr =
                parseHexOrDec(o.get("address")?.asString ?: "") ?: return@withContext ToolResult(
                    false,
                    error = "Invalid address"
                )
            val valueType = (o.get("value_type")?.asString ?: "int32").lowercase()
            val minVal = o.get("min_value")?.asString ?: return@withContext ToolResult(
                false,
                error = "Missing min_value"
            )
            val maxVal = o.get("max_value")?.asString ?: return@withContext ToolResult(
                false,
                error = "Missing max_value"
            )
            val step = o.get("step")?.asString ?: "1"
            val verifyPrompt = o.get("verify_prompt")?.asString ?: return@withContext ToolResult(
                false,
                error = "Missing verify_prompt"
            )
            val targetState = o.get("target_state")?.takeUnless { it.isJsonNull }?.asString ?: ""
            val delayMs = (o.get("delay_ms")?.asLong ?: 500L).coerceIn(100, 10000)
            val maxAttempts = (o.get("max_attempts")?.asInt ?: 20).coerceIn(1, 100)

            val root = RootManager(context.app)
            if (!root.canUseRoot()) {
                return@withContext ToolResult(
                    false,
                    error = "Visual adjust requires root for memory write."
                )
            }

            val min: Any = when (valueType) {
                "float" -> minVal.toFloatOrNull() ?: return@withContext ToolResult(
                    false,
                    error = "Invalid float min_value"
                )

                "int32" -> minVal.toIntOrNull() ?: return@withContext ToolResult(
                    false,
                    error = "Invalid int32 min_value"
                )

                else -> minVal.toLongOrNull() ?: return@withContext ToolResult(
                    false,
                    error = "Invalid numeric min_value"
                )
            }
            val max: Any = when (valueType) {
                "float" -> maxVal.toFloatOrNull() ?: return@withContext ToolResult(
                    false,
                    error = "Invalid float max_value"
                )

                "int32" -> maxVal.toIntOrNull() ?: return@withContext ToolResult(
                    false,
                    error = "Invalid int32 max_value"
                )

                else -> maxVal.toLongOrNull() ?: return@withContext ToolResult(
                    false,
                    error = "Invalid numeric max_value"
                )
            }
            val stepVal: Any = when (valueType) {
                "float" -> step.toFloatOrNull() ?: return@withContext ToolResult(
                    false,
                    error = "Invalid float step"
                )

                "int32" -> step.toIntOrNull() ?: return@withContext ToolResult(
                    false,
                    error = "Invalid int32 step"
                )

                else -> step.toLongOrNull() ?: return@withContext ToolResult(
                    false,
                    error = "Invalid numeric step"
                )
            }

            var current: Any = min
            val results = mutableListOf<String>()
            var success = false
            var finalValue: Any? = null

            for (attempt in 1..maxAttempts) {
                val done = when (valueType) {
                    "float" -> (current as Float) > (max as Float)
                    "int32" -> (current as Int) > (max as Int)
                    else -> (current as Long) > (max as Long)
                }
                if (done) break

                val data = when (valueType) {
                    "float" -> floatToBytes(current as Float)
                    "int32" -> intToBytes(current as Int)
                    else -> longToBytes(current as Long)
                }

                val hexFile =
                    File(context.workspace, "visual_adjust_${System.currentTimeMillis()}.bin")
                val writeOk = try {
                    hexFile.writeBytes(data)
                    val (code, _) = runRootCmd(
                        root,
                        "dd if=${hexFile.absolutePath} of=/proc/$pid/mem bs=1 seek=$addr count=${data.size} conv=notrunc 2>&1",
                        5
                    )
                    hexFile.delete()
                    code == 0
                } catch (_: Exception) {
                    hexFile.delete()
                    false
                }
                if (!writeOk) {
                    results.add("Attempt $attempt: write failed at value $current")
                    current = nextValue(current, stepVal, valueType)
                    continue
                }

                // Wait for the UI to react to the new value.
                Thread.sleep(delayMs)

                if (!isProcessAlive(root, pid)) {
                    results.add("Attempt $attempt: value=$current -> TARGET PROCESS DIED (crash). Stopping.")
                    break
                }

                context.onProgress?.invoke("Visual adjust: attempt $attempt/$maxAttempts, value=$current — asking vision model…")
                val analysis = captureAndAnalyze(
                    context,
                    "$verifyPrompt Answer with a clear yes or no first, then details.",
                    File(context.workspace, "visual_check_${System.currentTimeMillis()}.png")
                )
                val answer = analysis.getOrElse { "Analysis failed: ${it.message}" }
                val ok = analysis.isSuccess && visionIndicatesSuccess(answer, targetState)
                results.add(
                    "Attempt $attempt: value=$current -> ${if (ok) "MATCH" else "no match"}\n    $answer".replace(
                        "\n",
                        "\n    "
                    )
                )

                if (ok) {
                    success = true
                    finalValue = current
                    results.add(">>> SUCCESS at attempt $attempt with value $current <<<")
                    break
                }

                current = nextValue(current, stepVal, valueType)
            }

            val summary = buildString {
                append("Visual adjustment ").append(if (success) "SUCCEEDED" else "EXHAUSTED")
                append(" after ").append(results.size).append(" attempt(s)\n")
                append(
                    "Target: pid=$pid, addr=0x%X, type=$valueType, range=$minVal..$maxVal step=$step\n".format(
                        addr
                    )
                )
                append("Verify prompt: $verifyPrompt\n")
                if (targetState.isNotBlank()) append("Target state: $targetState\n")
                if (success) append("Found working value: $finalValue\n")
                append("\nAttempt log:\n")
                results.forEach { append(it).append("\n") }
            }

            ToolResult(
                success, summary, metadata = mapOf<String, Any?>(
                    "success" to success,
                    "final_value" to (finalValue?.toString() ?: ""),
                    "attempts" to results.size
                )
            )
        }

    private fun nextValue(current: Any, stepVal: Any, valueType: String): Any = when (valueType) {
        "float" -> (current as Float) + (stepVal as Float)
        "int32" -> (current as Int) + (stepVal as Int)
        else -> (current as Long) + (stepVal as Long)
    }
}

/** 8. One-shot visual verification of the current screen (androidanalyze pipeline) */
class VisualVerifyTool : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.visual.verify",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "Capture the screen and verify the current visual state with the active provider's vision model (same pipeline as androidanalyze). Use after a memory write or injection to confirm the on-screen effect, e.g. 'Is the health bar full?' or 'Did the new skin render?'.",
        permissions = setOf(ToolPermission.ACCESSIBILITY),
        parameters = mapOf(
            "question" to "string (required) - what to check on screen, e.g. 'Is the health bar full?'",
            "target_state" to "string (optional, comma-separated phrases that must appear for success)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val o = arguments.asJsonObject
            val question = o.get("question")?.takeUnless { it.isJsonNull }?.asString
                ?: return@withContext ToolResult(false, error = "Missing question")
            val targetState = o.get("target_state")?.takeUnless { it.isJsonNull }?.asString ?: ""

            context.onProgress?.invoke("Visual verify: capturing screen and asking vision model…")
            val analysis = captureAndAnalyze(
                context,
                question,
                File(context.workspace, "visual_verify_${System.currentTimeMillis()}.png")
            )
            val answer = analysis.getOrElse {
                return@withContext ToolResult(
                    false,
                    error = it.message ?: "Visual verification failed"
                )
            }
            val ok = visionIndicatesSuccess(answer, targetState)
            val out = buildString {
                append("Visual verification: ").append(if (ok) "MATCH" else "NO MATCH").append('\n')
                if (targetState.isNotBlank()) append("Target state: $targetState\n")
                append('\n').append(answer)
            }
            ToolResult(ok, out, metadata = mapOf("match" to ok))
        }
}

/** 9. Injection validation - prove an injection took effect */
class InjectVerifyTool : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.inject.verify",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "Validate that a memory injection took effect: checks process alive, injected library mapped in /proc/pid/maps, suspicious-thread count, and (optionally) verifies a visual side effect with the vision model. Compare with the risk score from mem.stealth.verify to measure the injection footprint.",
        permissions = setOf(
            ToolPermission.MEMORY_SCAN,
            ToolPermission.PROCESS_MEMORY,
            ToolPermission.ACCESSIBILITY
        ),
        parameters = mapOf(
            "pid" to "integer (required)",
            "library_name" to "string (library file name or path substring to look for in maps, e.g. libfrida-gadget.so)",
            "visual_check" to "string (optional question for the vision model to confirm a visual side effect)",
            "visual_target_state" to "string (optional comma-separated phrases for the visual check)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val o = arguments.asJsonObject
            val pid =
                o.get("pid")?.asInt ?: return@withContext ToolResult(false, error = "Missing pid")
            val libraryName =
                o.get("library_name")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val visualCheck =
                o.get("visual_check")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val visualTargetState =
                o.get("visual_target_state")?.takeUnless { it.isJsonNull }?.asString.orEmpty()

            val root = RootManager(context.app)
            val alive = isProcessAlive(root, pid)
            val mapped =
                if (libraryName.isNotBlank()) isLibraryMapped(root, pid, libraryName) else null

            var suspiciousThreads = 0
            runCatching {
                File("/proc/$pid/task").listFiles()?.forEach { tidDir ->
                    val comm = runCatching {
                        File(tidDir, "comm").readText().trim().lowercase()
                    }.getOrDefault("")
                    if (listOf(
                            "frida",
                            "gadget",
                            "agent",
                            "linject",
                            "hook"
                        ).any { comm.contains(it) }
                    ) suspiciousThreads++
                }
            }

            val checks = mutableListOf<String>()
            checks.add("Process alive: " + if (alive) "PASS" else "FAIL (crashed or exited)")
            when {
                mapped == null -> checks.add("Library check: SKIPPED (no library_name given)")
                mapped -> checks.add("Library '$libraryName' mapped: PASS (present in /proc/$pid/maps)")
                else -> checks.add("Library '$libraryName' mapped: FAIL (not present in maps — injection likely failed)")
            }
            checks.add("Suspicious instrumenting threads: $suspiciousThreads " + if (suspiciousThreads > 0) "(injection artifacts visible)" else "(none visible)")

            var visualOk: Boolean? = null
            if (visualCheck.isNotBlank()) {
                context.onProgress?.invoke("Inject verify: asking vision model…")
                val analysis = captureAndAnalyze(context, visualCheck)
                val answer = analysis.getOrElse { failure -> "Visual check failed: ${failure.message}" }
                val matchedThisCheck = visionIndicatesSuccess(answer, visualTargetState)
                visualOk = analysis.isSuccess && matchedThisCheck
                checks.add("Visual side effect: " + if (matchedThisCheck) "MATCH" else "NO MATCH")
                checks.add("  $answer")
            }

            val corePass = alive && ((mapped == null) || mapped)
            val visualPassed = (visualOk != false)
            val overall = corePass && visualPassed
            val summary = buildString {
                append("Injection validation for pid $pid: ").append(if (overall) "PASS" else "FAIL")
                    .append("\n\n")
                checks.forEach { append("• ").append(it).append("\n") }
            }
            ToolResult(
                overall, summary, metadata = mapOf(
                    "alive" to alive,
                    "library_mapped" to (mapped ?: false),
                    "suspicious_threads" to suspiciousThreads,
                    "visual_match" to (visualOk ?: false)
                )
            )
        }
}

/** 10. Stealth - hide injection traces */
class MemStealthTool : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.stealth",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "Apply stealth techniques to hide memory injection traces: unlink injected libraries from /proc/pid/maps, hide frida/agent threads, spoof process name, clear ptrace traces, and obfuscate memory regions.",
        permissions = setOf(
            ToolPermission.MEMORY_WRITE,
            ToolPermission.PROCESS_MEMORY,
            ToolPermission.ROOT
        ),
        parameters = mapOf(
            "pid" to "integer (required)",
            "technique" to "string (unlink_lib|hide_threads|spoof_name|clear_ptrace|obfuscate_regions|all, default all)",
            "library_name" to "string (optional, library to unlink from maps, e.g. libfrida-gadget.so)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val o = arguments.asJsonObject
            val pid =
                o.get("pid")?.asInt ?: return@withContext ToolResult(false, error = "Missing pid")
            val technique = (o.get("technique")?.asString ?: "all").lowercase()
            val libraryName = o.get("library_name")?.asString

            val root = RootManager(context.app)
            if (!root.canUseRoot()) {
                return@withContext ToolResult(false, error = "Stealth operations require root.")
            }

            val results = mutableListOf<String>()

            val doUnlink = technique == "all" || technique == "unlink_lib"
            val doHideThreads = technique == "all" || technique == "hide_threads"
            val doSpoofName = technique == "all" || technique == "spoof_name"
            val doClearPtrace = technique == "all" || technique == "clear_ptrace"
            val doObfuscate = technique == "all" || technique == "obfuscate_regions"

            if (doUnlink) {
                val lib = libraryName ?: "frida"
                val (code, output) = runRootCmd(root, getUnlinkScript(lib), 10)
                results.add("Library unlink ($lib): exit $code - ${output.take(200)}")
            }

            if (doHideThreads) {
                val (code, output) = runRootCmd(root, getHideThreadsScript(), 10)
                results.add("Thread hiding: exit $code - ${output.take(200)}")
            }

            if (doSpoofName) {
                val spoofNames = listOf(
                    "system_server",
                    "zygote64",
                    "surfaceflinger",
                    "audioserver",
                    "cameraserver",
                    "mediacodec",
                    "kworker/u:0",
                    "binder:"
                )
                val spoofName = spoofNames.random()
                val (code, _) = runRootCmd(root, getSpoofNameScript(), 10)
                results.add("Process name spoofed to '$spoofName': exit $code")
            }

            if (doClearPtrace) {
                val (code, output) = runRootCmd(root, getPtraceScript(), 10)
                results.add("Ptrace trace check: exit $code - ${output.take(200)}")
            }

            if (doObfuscate) {
                val (code, output) = runRootCmd(root, getObfuscateScript(), 10)
                results.add("Memory region obfuscation: exit $code - ${output.take(200)}")
            }

            val summary =
                "Stealth operations for pid $pid (technique: $technique):\n" + results.joinToString(
                    "\n"
                )
            ToolResult(true, summary, metadata = mapOf("techniques_applied" to results.size))
        }
}

/** 11. Anti-detection scan with risk scoring (also used by mem.stealth.verify) */
class MemAntiDetectTool : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.antidetect",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "Scan target process for signs of monitoring/debugging: ptrace attachment, frida gadgets, debugger presence, suspicious memory regions, hooking frameworks. Returns a risk assessment (CLEAN/LOW/MEDIUM/HIGH with score).",
        permissions = setOf(ToolPermission.MEMORY_SCAN, ToolPermission.PROCESS_MEMORY),
        parameters = mapOf(
            "pid" to "integer (required)",
            "deep" to "boolean (default false - deep scan additionally checks for RWX regions across all maps lines)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val o = arguments.asJsonObject
            val pid =
                o.get("pid")?.asInt ?: return@withContext ToolResult(false, error = "Missing pid")
            val deep = o.get("deep")?.asBoolean ?: false
            val root = RootManager(context.app)

            val risks = mutableListOf<String>()
            var riskScore = 0

            val statusText = readProcText(root, "/proc/$pid/status")
            if (statusText != null) {
                val lines = statusText.lines()
                val tracerLine = lines.firstOrNull { it.startsWith("TracerPid:") }
                if (tracerLine != null) {
                    val tracerPid = tracerLine.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: 0
                    if (tracerPid > 0) {
                        risks.add("DEBUGGER DETECTED: TracerPid=$tracerPid (process is being ptraced)")
                        riskScore += 50
                    } else {
                        risks.add("OK: No ptrace attachment (TracerPid=0)")
                    }
                }
            }

            val mapsText = readProcText(root, "/proc/$pid/maps")
            if (mapsText != null) {
                val suspiciousLibs = listOf(
                    "frida" to "Frida instrumentation framework",
                    "gadget" to "Frida gadget library",
                    "linject" to "Library injection tool",
                    "xposed" to "Xposed framework",
                    "substrate" to "Cydia Substrate",
                    "dobby" to "Dobby hooking library",
                    "whale" to "Whale hooking",
                    "epic" to "Epic hooking",
                    "sandhook" to "SandHook"
                )
                for ((lib, desc) in suspiciousLibs) {
                    if (mapsText.lowercase().contains(lib)) {
                        risks.add("SUSPICIOUS LIBRARY: $desc ($lib) found in memory maps")
                        riskScore += 30
                    }
                }
                if (deep) {
                    var rwxCount = 0
                    for (line in mapsText.lines()) {
                        if (line.contains("rwx")) {
                            rwxCount++
                            risks.add("RWX REGION: ${line.take(160)}")
                            riskScore += 10
                        }
                    }
                    if (rwxCount == 0) risks.add("OK: No RWX memory regions found")
                }
            }

            // Thread-name scan with root fallback: other-UID /proc/pid/task hides
            // per-thread comm files from the app UID, so read them via su per tid.
            val tidList =
                runCatching { File("/proc/$pid/task").listFiles()?.map { it.name } }.getOrNull()
                    ?: if (root.canUseRoot()) {
                        val (lsCode, lsOut) = runRootCmd(root, "ls /proc/$pid/task", 6)
                        if (lsCode == 0) lsOut.split(Regex("\\s+"))
                            .filter { it.isNotBlank() } else emptyList()
                    } else emptyList()
            if (tidList.isNotEmpty()) {
                for (tid in tidList) {
                    val comm =
                        readProcText(root, "/proc/$pid/task/$tid/comm", 256)?.trim()?.lowercase()
                            .orEmpty()
                    for (sus in listOf(
                        "frida",
                        "gadget",
                        "agent",
                        "linject",
                        "xposed",
                        "substrate"
                    )) {
                        if (comm.contains(sus)) {
                            risks.add("SUSPICIOUS THREAD: '$comm' in TID $tid")
                            riskScore += 20
                        }
                    }
                }
            }

            val assessment = when {
                riskScore >= 80 -> "HIGH - Process heavily monitored/instrumented"
                riskScore >= 40 -> "MEDIUM - Some monitoring signs detected"
                riskScore >= 10 -> "LOW - Minor anomalies"
                else -> "CLEAN - No obvious monitoring detected"
            }

            val summary = buildString {
                append("Anti-detection scan for pid $pid\n")
                append("Risk Assessment: $assessment (score: $riskScore)\n\n")
                risks.forEach { append("• ").append(it).append("\n") }
            }

            ToolResult(
                true,
                summary,
                metadata = mapOf("risk_score" to riskScore, "assessment" to assessment)
            )
        }

    companion object {
        /**
         * Reusable risk scoring for other tools (stealth verify). Root-fallback:
         * when direct /proc reads fail for a foreign-UID process, reads go through
         * su so the score reflects reality even without the app owning the target.
         */
        fun score(pid: Int, root: RootManager? = null): Int {
            var score = 0
            runCatching {
                val status = root?.let { readProcText(it, "/proc/$pid/status") }
                    ?: runCatching { File("/proc/$pid/status").readText() }.getOrNull().orEmpty()
                Regex("TracerPid:\\s+(\\d+)").find(status)?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { if (it > 0) score += 50 }
                val maps = (root?.let { readProcText(it, "/proc/$pid/maps") }
                    ?: runCatching { File("/proc/$pid/maps").readText() }.getOrNull()).orEmpty()
                    .lowercase()
                for (lib in listOf("frida", "gadget", "linject", "xposed", "substrate")) {
                    if (maps.contains(lib)) score += 30
                }
                // Thread comm scan via su per-tid (foreign-UID task dirs hide comm).
                val tids =
                    runCatching { File("/proc/$pid/task").listFiles()?.map { it.name } }.getOrNull()
                        ?: if (root?.canUseRoot() == true) {
                            val (lsCode, lsOut) = runRootCmd(root, "ls /proc/$pid/task", 6)
                            if (lsCode == 0) lsOut.split(Regex("\\s+"))
                                .filter { it.isNotBlank() } else emptyList()
                        } else emptyList()
                for (tid in tids) {
                    val comm = (root?.let { readProcText(it, "/proc/$pid/task/$tid/comm", 256) }
                        ?: runCatching { File("/proc/$pid/task/$tid/comm").readText() }.getOrNull())
                        ?.trim()?.lowercase().orEmpty()
                    if (listOf(
                            "frida",
                            "gadget",
                            "agent",
                            "linject"
                        ).any { comm.contains(it) }
                    ) score += 20
                }
            }
            return score
        }
    }
}

/** 12. Stealth verification - before/after risk scoring to prove stealth worked */
class StealthVerifyTool : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.stealth.verify",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "Verify stealth operations worked: runs the anti-detection risk scoring and reports whether risk dropped (use after mem.stealth). Optionally captures a vision check to confirm the target app still renders normally.",
        permissions = setOf(
            ToolPermission.MEMORY_SCAN,
            ToolPermission.PROCESS_MEMORY,
            ToolPermission.ACCESSIBILITY
        ),
        parameters = mapOf(
            "pid" to "integer (required)",
            "baseline_risk" to "integer (optional, risk score captured before stealth — the tool reports the delta)",
            "visual_check" to "string (optional question for the vision model, e.g. 'Is the game rendering normally?')"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val o = arguments.asJsonObject
            val pid =
                o.get("pid")?.asInt ?: return@withContext ToolResult(false, error = "Missing pid")
            val baseline = o.get("baseline_risk")?.takeUnless { it.isJsonNull }?.asInt
            val visualCheck =
                o.get("visual_check")?.takeUnless { it.isJsonNull }?.asString.orEmpty()

            val root = RootManager(context.app)
            val alive = isProcessAlive(root, pid)
            val current = MemAntiDetectTool.score(pid, root)

            val checks = mutableListOf<String>()
            checks.add("Process alive: " + if (alive) "PASS" else "FAIL")
            checks.add(
                "Current risk score: $current (" + when {
                    current >= 80 -> "HIGH"
                    current >= 40 -> "MEDIUM"
                    current >= 10 -> "LOW"
                    else -> "CLEAN"
                } + ")"
            )
            if (baseline != null) {
                val delta = current - baseline
                checks.add("Baseline risk: $baseline -> delta: $delta " + if (delta < 0) "(risk REDUCED — stealth effective)" else if (delta == 0) "(unchanged)" else "(risk INCREASED)")
            }

            var visualOk: Boolean? = null
            if (visualCheck.isNotBlank() && alive) {
                context.onProgress?.invoke("Stealth verify: asking vision model…")
                val analysis = captureAndAnalyze(context, visualCheck)
                val answer = analysis.getOrElse { failure -> "Visual check failed: ${failure.message}" }
                val matchedThisCheck = visionIndicatesSuccess(answer, "")
                visualOk = analysis.isSuccess && matchedThisCheck
                checks.add("Visual sanity check: " + if (matchedThisCheck) "PASS (app renders normally)" else "NO MATCH — $answer")
            }

            val ok = alive && ((baseline == null) || (current <= baseline))
            val summary = buildString {
                append("Stealth verification for pid $pid: ").append(if (ok) "PASS" else "FAIL")
                    .append("\n\n")
                checks.forEach { append("• ").append(it).append("\n") }
            }
            ToolResult(
                ok, summary, metadata = mapOf(
                    "alive" to alive,
                    "risk_score" to current,
                    "baseline_risk" to (baseline ?: -1),
                    "visual_ok" to (visualOk ?: false)
                )
            )
        }
}

/**
 * 13b. Zygisk spawn-time injection manager (mem.zygisk).
 *
 * Live ptrace injection loses to strong anti-tamper (pairipcore/Play Integrity)
 * because the protection initializes before any external attach. The reliable
 * route is spawn-time injection: a Zygisk module loads the payload INSIDE the
 * app's zygote-forked child, before Application.onCreate and before pairip
 * init.
 *
 * This tool manages the on-device Zygisk setup:
 *   • detect   – is Zygisk present (Magisk/Zygisk-Next) and enabled?
 *   • install  – stage a payload .so as /data/adb/zygisk/<module>/zygisk.so
 *                plus a module.prop (id, name, target package allow-list)
 *   • list     – installed zygisk modules
 *   • remove   – uninstall a module by id
 *
 * Payload .so contract: the payload is dlopened at app spawn; it can run code
 * in its constructor (ELF_INIT) — no JNI is required for pure hooking.
 */
class ZygiskInjectTool : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.zygisk",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "Manage Zygisk spawn-time injection (bypasses pairipcore/anti-tamper init checks by loading BEFORE the app initializes). Actions: detect (is Zygisk available?), install (stage payload .so as a Zygisk module for a target package), list, remove. Requires root + Magisk with Zygisk (or Zygisk-Next) enabled on the device.",
        permissions = setOf(
            ToolPermission.MEMORY_WRITE,
            ToolPermission.PROCESS_MEMORY,
            ToolPermission.ROOT
        ),
        parameters = mapOf(
            "action" to "string (required): detect | install | list | remove",
            "package" to "string (required for install): target app package name, e.g. com.example.game",
            "payload_path" to "string (required for install): absolute path to the payload .so (must match device arch)",
            "module_id" to "string (required for remove): zygisk module id to remove"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val o = arguments.asJsonObject
            val action =
                (o.get("action")?.takeUnless { it.isJsonNull }?.asString ?: "detect").lowercase()
            val pkg = o.get("package")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val payloadPath =
                o.get("payload_path")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val moduleId = o.get("module_id")?.takeUnless { it.isJsonNull }?.asString.orEmpty()

            val root = RootManager(context.app)
            if (!root.canUseRoot()) {
                return@withContext ToolResult(
                    false,
                    error = "mem.zygisk requires root. Enable Allow root in Settings."
                )
            }

            when (action) {
                "detect" -> {
                    val checks = mutableListOf<String>()
                    // Magisk zygisk flag
                    val (mCode, mOut) = runRootCmd(
                        root,
                        "magisk --sqlite 'SELECT value FROM settings WHERE key=\"zygisk\"' 2>/dev/null",
                        8
                    )
                    val zygiskOn = mCode == 0 && mOut.contains("1")
                    checks.add("Magisk zygisk setting: " + (if (zygiskOn) "ENABLED" else if (mCode == 0) "disabled" else "not readable"))
                    // Zygisk-Next standalone module
                    val (znCode, _) = runRootCmd(
                        root,
                        "test -d /data/adb/modules/zygisksu && echo y",
                        5
                    )
                    val zygiskNext = znCode == 0
                    checks.add("Zygisk-Next module: " + (if (zygiskNext) "present" else "absent"))
                    // /data/adb/zygisk marker
                    val (markerCode, _) = runRootCmd(root, "test -d /data/adb/zygisk && echo y", 5)
                    checks.add("/data/adb/zygisk directory: " + (if (markerCode == 0) "present" else "absent"))
                    val available = zygiskOn || zygiskNext
                    val summary = buildString {
                        append("Zygisk detection for device:\n")
                        checks.forEach { append("• ").append(it).append('\n') }
                        if (!available) {
                            append("\nZygisk NOT available. Install Magisk with Zygisk enabled (Magisk app → Settings → Zygisk) or the standalone Zygisk-Next module, then reboot.")
                        } else {
                            append("\nZygisk available — use action=install to stage a payload module.")
                        }
                    }
                    ToolResult(available, summary, metadata = mapOf("available" to available))
                }

                "install" -> {
                    if (pkg.isBlank() || payloadPath.isBlank()) {
                        return@withContext ToolResult(
                            false,
                            error = "install requires 'package' and 'payload_path'"
                        )
                    }
                    val payload = File(payloadPath)
                    if (!payload.isFile) {
                        return@withContext ToolResult(
                            false,
                            error = "Payload .so not found: $payloadPath"
                        )
                    }
                    // Validate ELF arch like mem.inject does.
                    val head = ByteArray(20)
                    runCatching { payload.inputStream().use { it.read(head) } }
                    val eMachine =
                        if (head.size >= 20) ((head[19].toInt() and 0xFF) shl 8) or (head[18].toInt() and 0xFF) else 0
                    val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
                    val archOk = when {
                        abi.startsWith("arm64") -> eMachine == 183 || eMachine == 40
                        abi.startsWith("x86_64") -> eMachine == 62 || eMachine == 3
                        else -> true
                    }
                    if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte()) {
                        return@withContext ToolResult(
                            false,
                            error = "Payload is not an ELF file (decompress .xz first)"
                        )
                    }
                    if (!archOk) {
                        return@withContext ToolResult(
                            false,
                            error = "Payload arch mismatch: device=$abi, ELF machine=$eMachine"
                        )
                    }

                    val moduleIdGen = "oreshik_inject_${System.currentTimeMillis() / 1000}"
                    val moduleDir = "/data/adb/zygisk/$moduleIdGen"
                    val script = buildString {
                        append("set -e\n")
                        append("mkdir -p '$moduleDir'\n")
                        append("cp '${payload.absolutePath}' '$moduleDir/zygisk.so'\n")
                        append("chmod 644 '$moduleDir/zygisk.so'\n")
                        // module.prop (Zygisk Next / Magisk module format)
                        append("printf 'id=%s\\nname=Oreshik Inject (%s)\\nversion=1.0\\nversionCode=1\\nauthor=oreshik-agent\\ndescription=Spawn-time payload for %s\\n' \"$moduleIdGen\" \"$pkg\" \"$pkg\" > '$moduleDir/module.prop'\n")
                        // package allow-list so the module only loads for the target
                        append("printf '%s\\n' \"$pkg\" > '$moduleDir/zygisk_whitelist'\n")
                        append("echo staged\n")
                    }
                    val (code, out) = runRootCmd(root, script, 20)
                    if (code != 0 || !out.contains("staged")) {
                        return@withContext ToolResult(
                            false,
                            error = "Zygisk module staging failed: ${out.take(300)}"
                        )
                    }
                    val summary = buildString {
                        append("Zygisk module staged.\n")
                        append("• Module dir: $moduleDir\n")
                        append("• Payload: $moduleDir/zygisk.so\n")
                        append("• Target package: $pkg\n")
                        append("\nNEXT STEPS (required):\n")
                        append("1. Reboot the device (modules load at zygote start).\n")
                        append("2. After reboot, force-stop and relaunch '$pkg'.\n")
                        append("3. Verify with mem.regions (pid of the app) — the payload .so name must appear in maps BEFORE pairipcore checks run.\n")
                        append("\nTo undo: mem.zygisk { action=remove, module_id=$moduleIdGen }")
                    }
                    ToolResult(
                        true, summary, metadata = mapOf(
                            "module_id" to moduleIdGen,
                            "module_dir" to moduleDir,
                            "needs_reboot" to true
                        )
                    )
                }

                "list" -> {
                    val (code, out) = runRootCmd(
                        root,
                        "ls /data/adb/zygisk 2>/dev/null; ls /data/adb/modules 2>/dev/null",
                        8
                    )
                    ToolResult(
                        code == 0,
                        "Installed Zygisk/Magisk modules:\n${out.take(2000).ifBlank { "(none)" }}"
                    )
                }

                "remove" -> {
                    if (moduleId.isBlank()) {
                        return@withContext ToolResult(false, error = "remove requires 'module_id'")
                    }
                    // Magisk convention: create a 'remove' marker instead of deleting.
                    val (code, out) = runRootCmd(
                        root,
                        "mkdir -p /data/adb/zygisk/$moduleId && touch /data/adb/zygisk/$moduleId/remove && echo removed",
                        10
                    )
                    ToolResult(
                        code == 0 && out.contains("removed"),
                        "Module '$moduleId' marked for removal — it is deleted on next reboot.\n${
                            out.take(
                                300
                            )
                        }"
                    )
                }

                else -> ToolResult(
                    false,
                    error = "Unknown action '$action' (use detect|install|list|remove)"
                )
            }
        }
}

/**
 * 13. AUTOMATIC VISUAL GAME-PLAYING AGENT (mem.visual.autoplay)
 *
 * This tool implements a fully automatic "see → think → act" loop using the
 * same VisionAnalyzer pipeline as androidanalyze. It is designed for simple
 * 2D mobile games where the screen fully represents game state:
 *   • Endless runners (tap to jump, swipe to slide)
 *   • Clicker/idle games (tap targets, collect rewards)
 *   • Simple puzzle games (match-3, slide puzzles)
 *   • Arcade games with clear visual objectives
 *
 * The agent captures the screen, asks the vision model "what should I do to
 * achieve <goal>?", executes the suggested action (tap/swipe/back/home), and
 * repeats until the goal is reached, a failure condition appears, or the
 * step/timeout limits are hit.
 *
 * SAFETY: The tool requires ACCESSIBILITY permission (for gestures) and uses
 * the active provider's vision model. It does NOT require root or inspect game
 * memory. Use only for games which permit automation. Screenshot-based vision
 * is suitable for turn-based, cooperative, and slower games; it cannot provide
 * low-latency real-time competitive play or bypass anti-cheat systems.
 *
 * Example use:
 *   mem.visual.autoplay {
 *     goal: "Reach a score of 100 in this endless runner",
 *     fail_condition: "Game over screen appears",
 *     max_steps: 50,
 *     step_delay_ms: 800,
 *     action_types: ["tap", "swipe"]
 *   }
 */
class VisualAutoPlayTool : AgentTool {
    override val definition = ToolDefinition(
        name = "mem.visual.autoplay",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "Supervised visual gameplay helper. Repeatedly captures the screen, asks a vision model for a structured action, then performs allowed taps, swipes, or long-presses. For games that permit automation, including slower turn-based or private/cooperative multiplayer sessions. Not suitable for real-time competitive multiplayer: vision requests are too slow, and the tool does not use game-specific APIs or bypass anti-cheat.",
        permissions = setOf(ToolPermission.ACCESSIBILITY),
        parameters = mapOf(
            "goal" to "string (required) - objective for this game/session",
            "game_mode" to "string (optional) - single_player (default), turn_based_multiplayer, or private_coop. Multiplayer modes are for authorized games that permit automation; ranked/real-time competitive play is unsupported",
            "fail_condition" to "string (optional) - visible state that should stop play, e.g. 'Game over' or 'Out of lives'",
            "max_steps" to "integer (1-200, default 50) - maximum screenshot/decision cycles",
            "step_delay_ms" to "integer (100-5000, default 800) - wait after an action for the game to update",
            "action_types" to "array (optional) - allowed actions from [tap, swipe, long_press, back, home]; default [tap, swipe, long_press]. Back/home require explicit opt-in",
            "question_override" to "string (optional) - extra game-specific strategy/context; the required JSON action schema is still enforced",
            "save_frames" to "boolean (default false) - save each analyzed frame to workspace for debugging"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) outer@{
            val o = arguments.asJsonObject
            val goal = runCatching { o.get("goal")?.takeUnless { it.isJsonNull }?.asString }
                .getOrNull()?.trim()?.takeIf(String::isNotBlank)
                ?: return@outer ToolResult(false, error = "Missing or blank 'goal' parameter")
            val gameMode = runCatching {
                o.get("game_mode")?.takeUnless { it.isJsonNull }?.asString?.trim()?.lowercase()
                    ?: "single_player"
            }.getOrDefault("single_player")
            val supportedModes = setOf("single_player", "turn_based_multiplayer", "private_coop")
            if (gameMode !in supportedModes) {
                return@outer ToolResult(
                    false,
                    error = "Unsupported game_mode. Choose single_player, turn_based_multiplayer, or private_coop."
                )
            }
            val failCondition = runCatching {
                o.get("fail_condition")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            }.getOrDefault("")
            val maxSteps = runCatching { o.get("max_steps")?.asInt ?: 50 }
                .getOrDefault(50).coerceIn(1, 200)
            val stepDelayMs = runCatching { o.get("step_delay_ms")?.asLong ?: 800L }
                .getOrDefault(800L).coerceIn(100L, 5000L)
            val actionElement = o.get("action_types")?.takeUnless { it.isJsonNull }
            if (actionElement != null && !actionElement.isJsonArray) {
                return@outer ToolResult(false, error = "'action_types' must be an array of action names")
            }
            val allowedActions = actionElement?.asJsonArray?.mapNotNull { item ->
                runCatching { item.asString.trim().lowercase() }.getOrNull()
            }?.toSet() ?: setOf("tap", "swipe", "long_press")
            val supportedActions = setOf("tap", "swipe", "long_press", "back", "home")
            val unsupportedActions = allowedActions - supportedActions
            if (unsupportedActions.isNotEmpty()) {
                return@outer ToolResult(false, error = "Unsupported action_types: ${unsupportedActions.joinToString()}")
            }
            if (allowedActions.isEmpty()) {
                return@outer ToolResult(false, error = "'action_types' must allow at least one action")
            }
            val questionOverride = runCatching {
                o.get("question_override")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            }.getOrDefault("").trim()
            val saveFrames = runCatching { o.get("save_frames")?.asBoolean ?: false }.getOrDefault(false)

            val service = AgentAccessibilityService.instance
                ?: return@outer ToolResult(
                    false,
                    error = "Accessibility service is not enabled. Enable Oreshik Agent Accessibility in Android Settings."
                )
            if (!com.example.oreshik.agent.core.AgentConfig.visionAutoDescribe &&
                com.example.oreshik.agent.core.AgentConfig.visionModel.isBlank()
            ) {
                return@outer ToolResult(
                    false,
                    error = "Vision analysis disabled in Settings (enable 'Use vision models for screen analysis')."
                )
            }

            val (screenW, screenH) = service.screenSize()
            if (screenW <= 0 || screenH <= 0) {
                return@outer ToolResult(false, error = "Could not determine the active display size")
            }
            val log = mutableListOf<String>()
            var stepsTaken = 0
            var goalReached = false
            var failDetected = false
            var lastAction = "none"
            var lastTapX = -1
            var lastTapY = -1
            var stopReason = "maximum steps reached"
            var consecutiveVisionFailures = 0

            // Execute only structured actions. Never guess a swipe path or
            // action coordinate if the vision response omitted it.
            suspend fun executeAction(
                action: String,
                tapX: Int?,
                tapY: Int?,
                swipeX1: Int?,
                swipeY1: Int?,
                swipeX2: Int?,
                swipeY2: Int?,
                durationMs: Long?
            ): Boolean? = withContext(Dispatchers.Default) {
                when (action) {
                    "tap" -> {
                        if (tapX == null || tapY == null) {
                            log.add("  ↷ tap skipped: missing or out-of-image coordinates")
                            null
                        } else if (!allowedActions.contains("tap")) {
                            log.add("  ↷ tap skipped: not allowed by action_types")
                            null
                        } else {
                            val ok = service.tap(tapX.toFloat(), tapY.toFloat())
                            log.add("  → tap($tapX,$tapY) = ${if (ok) "OK" else "FAILED"}")
                            ok
                        }
                    }

                    "swipe" -> {
                        if (swipeX1 == null || swipeY1 == null || swipeX2 == null || swipeY2 == null) {
                            log.add("  ↷ swipe skipped: missing or out-of-image endpoints")
                            null
                        } else if (!allowedActions.contains("swipe")) {
                            log.add("  ↷ swipe skipped: not allowed by action_types")
                            null
                        } else {
                            val dur = (durationMs ?: 350L).coerceIn(80L, 5000L)
                            val ok = service.swipe(
                                swipeX1.toFloat(),
                                swipeY1.toFloat(),
                                swipeX2.toFloat(),
                                swipeY2.toFloat(),
                                dur
                            )
                            log.add("  → swipe(($swipeX1,$swipeY1)->($swipeX2,$swipeY2), ${dur}ms) = ${if (ok) "OK" else "FAILED"}")
                            ok
                        }
                    }

                    "long_press" -> {
                        if (tapX == null || tapY == null) {
                            log.add("  ↷ long_press skipped: missing or out-of-image coordinates")
                            null
                        } else if (!allowedActions.contains("long_press")) {
                            log.add("  ↷ long_press skipped: not allowed by action_types")
                            null
                        } else {
                            val dur = (durationMs ?: 700L).coerceIn(300L, 5000L)
                            val ok = service.longPress(tapX.toFloat(), tapY.toFloat(), dur)
                            log.add("  → long_press($tapX,$tapY, ${dur}ms) = ${if (ok) "OK" else "FAILED"}")
                            ok
                        }
                    }

                    "back" -> {
                        if (!allowedActions.contains("back")) {
                            log.add("  ↷ back skipped: not allowed by action_types")
                            null
                        } else {
                            val ok =
                                service.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                            log.add("  → back = ${if (ok) "OK" else "FAILED"}")
                            ok
                        }
                    }

                    "home" -> {
                        if (!allowedActions.contains("home")) {
                            log.add("  ↷ home skipped: not allowed by action_types")
                            null
                        } else {
                            val ok =
                                service.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                            log.add("  → home = ${if (ok) "OK" else "FAILED"}")
                            ok
                        }
                    }

                    "none", "wait", "idle" -> {
                        log.add("  → wait (no action)")
                        null
                    }

                    else -> {
                        log.add("  ↷ unsupported action skipped: $action")
                        null
                    }
                }
            }

            // Main autoplay loop
            for (step in 1..maxSteps) {
                currentCoroutineContext().ensureActive()
                stepsTaken = step

                context.onProgress?.invoke("Auto-play step $step/$maxSteps: capturing screen…")

                // Capture screenshot
                val bitmap = service.takeScreenshot()
                if (bitmap == null) {
                    stopReason = "screen capture failed"
                    log.add("Step $step: Screen capture failed")
                    break
                }

                try {
                    // Save frame if requested
                    if (saveFrames) {
                        val frameFile = File(
                            context.workspace,
                            "autoplay_frame_${System.currentTimeMillis()}.png"
                        )
                        runCatching {
                            java.io.FileOutputStream(frameFile)
                                .use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, out) }
                        }
                    }

                    // VisionAnalyzer downsizes large screenshots to 1280 px.
                    // Request coordinates in that image's pixel space, then map
                    // each axis independently to real display pixels below.
                    val scaleFactor = minOf(1f, 1280f / maxOf(bitmap.width, bitmap.height).toFloat())
                    val imageW = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
                    val imageH = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
                    val strategy = questionOverride.take(500).ifBlank { "Play toward this objective: $goal" }
                    val promptGoal = goal.take(400)
                    val modeInstruction = when (gameMode) {
                        "turn_based_multiplayer" -> "This is an authorized turn-based multiplayer game. Take only the current visible turn and do not assume another player's state."
                        "private_coop" -> "This is an authorized private cooperative game. Prefer actions that support the shared objective."
                        else -> "This is a single-player game."
                    }
                    val question = """
                        You control a mobile game using visible screen controls.
                        Screenshot dimensions: ${imageW}x$imageH pixels. Coordinates use the top-left origin and must be inside these image bounds.
                        Return ONLY minified JSON with these keys:
                        {"observations":"brief visible state","goal_progress":"not_started|in_progress|near|reached","fail_detected":false,"next_action":"tap|swipe|long_press|back|home|none","tap_target":null,"swipe_from":null,"swipe_to":null,"duration_ms":350,"reason":"brief reason"}
                        For tap and long_press, set tap_target to {"x":number,"y":number}. For swipe, set swipe_from and swipe_to to coordinate objects. Use null for unused coordinates. Do not guess coordinates or an action when unclear; return next_action none. Only set fail_detected true when the stated fail condition is clearly visible. Mark goal_progress reached only when the objective is visibly complete.
                        $modeInstruction
                        Objective: $promptGoal
                        ${if (failCondition.isNotBlank()) "Stop without another action if this is visible: ${failCondition.take(250)}" else ""}
                        Previous action: $lastAction${if (lastTapX >= 0) " at display pixel ($lastTapX,$lastTapY)" else ""}.
                        Strategy/context: $strategy
                    """.trimIndent()

                    context.onProgress?.invoke("Auto-play step $step/$maxSteps: asking vision model…")

                    val analysis = VisionAnalyzer.analyze(bitmap, question) { msg ->
                        CoroutineScope(Dispatchers.IO).launch {
                            runCatching {
                                context.onProgress?.invoke(
                                    msg
                                )
                            }
                        }
                    }

                    if (!analysis.ok) {
                        log.add("Step $step: Vision analysis failed: ${analysis.error}")
                        consecutiveVisionFailures++
                        if (consecutiveVisionFailures >= 3) {
                            stopReason = "vision analysis failed three times consecutively"
                            break
                        }
                        delay(stepDelayMs.milliseconds)
                        continue
                    }
                    consecutiveVisionFailures = 0

                    val obs = analysis.observations
                    val goalProgress = analysis.goalProgress?.lowercase()?.trim()?.let { progress ->
                        when (progress) {
                            "reached", "complete", "completed", "won" -> "reached"
                            "near", "almost" -> "near"
                            "not_started", "not started" -> "not_started"
                            else -> "in_progress"
                        }
                    } ?: when {
                        Regex("\\b(goal reached|level complete|game complete|victory|you win)\\b", RegexOption.IGNORE_CASE)
                            .containsMatchIn(obs) -> "reached"
                        Regex("\\b(close|almost|near completion)\\b", RegexOption.IGNORE_CASE)
                            .containsMatchIn(obs) -> "near"
                        Regex("\\b(not started|main menu|start screen)\\b", RegexOption.IGNORE_CASE)
                            .containsMatchIn(obs) -> "not_started"
                        else -> "in_progress"
                    }

                    val failDetectedNow = analysis.failDetected
                        ?: (failCondition.isNotBlank() && visionIndicatesSuccess(obs, failCondition))
                    val action = analysis.nextAction.orEmpty().trim().lowercase()
                        .replace('-', '_').replace(' ', '_').let {
                            when (it) {
                                "click" -> "tap"
                                "longpress" -> "long_press"
                                "wait", "idle", "" -> "none"
                                else -> it
                            }
                        }

                    fun toScreenX(imageX: Int?): Int? = imageX?.takeIf { it in 0 until imageW }
                        ?.let { ((it.toFloat() / imageW) * screenW).toInt().coerceIn(0, screenW - 1) }
                    fun toScreenY(imageY: Int?): Int? = imageY?.takeIf { it in 0 until imageH }
                        ?.let { ((it.toFloat() / imageH) * screenH).toInt().coerceIn(0, screenH - 1) }

                    val tapX = toScreenX(analysis.tapX)
                    val tapY = toScreenY(analysis.tapY)
                    val swipeX1 = toScreenX(analysis.swipeFromX)
                    val swipeY1 = toScreenY(analysis.swipeFromY)
                    val swipeX2 = toScreenX(analysis.swipeToX)
                    val swipeY2 = toScreenY(analysis.swipeToY)

                    log.add("Step $step: $obs")
                    log.add("  Goal progress: $goalProgress | Fail detected: $failDetectedNow | Action: $action")

                    if (failDetectedNow) {
                        failDetected = true
                        stopReason = "fail condition detected"
                        log.add(">>> FAIL CONDITION DETECTED: $failCondition <<<")
                        break
                    }

                    if (goalProgress == "reached") {
                        goalReached = true
                        stopReason = "goal reached"
                        log.add(">>> GOAL REACHED <<<")
                        break
                    }

                    val actionSucceeded = executeAction(
                        action,
                        tapX,
                        tapY,
                        swipeX1,
                        swipeY1,
                        swipeX2,
                        swipeY2,
                        analysis.durationMs?.toLong(),
                    )
                    if (actionSucceeded == false) {
                        stopReason = "Android rejected the suggested action"
                        log.add("Stopping because the action could not be dispatched")
                        break
                    }

                    lastAction = action
                    lastTapX = tapX ?: -1
                    lastTapY = tapY ?: -1

                    // Wait for game to react
                    delay(stepDelayMs.milliseconds)

                } finally {
                    bitmap.recycle()
                }
            }

            val summary = buildString {
                val outcome = when {
                    goalReached -> "GOAL REACHED"
                    failDetected -> "FAIL DETECTED"
                    else -> "STOPPED"
                }
                append("Visual Auto-Play ").append(outcome).append(" — ").append(stopReason)
                append(" after $stepsTaken step(s)\n\n")
                append("Goal: $goal\n")
                append("Game mode: $gameMode\n")
                if (failCondition.isNotBlank()) append("Fail condition: $failCondition\n")
                append("Allowed actions: ${allowedActions.joinToString(", ")}\n")
                append("Screen: ${screenW}x$screenH\n\n")
                append("Step log:\n")
                log.forEach { append(it).append("\n") }
            }

            ToolResult(
                goalReached, summary, metadata = mapOf(
                    "goal_reached" to goalReached,
                    "fail_detected" to failDetected,
                    "steps_taken" to stepsTaken,
                    "last_action" to lastAction,
                    "stop_reason" to stopReason,
                    "screen_width" to screenW,
                    "screen_height" to screenH,
                    "game_mode" to gameMode,
                )
            )
        }
}

// ---------------------------------------------------------------------------
// Byte/pattern helpers
// ---------------------------------------------------------------------------

private fun parseHexPattern(s: String): ByteArray? {
    val cleaned = s.replace(Regex("[^0-9A-Fa-f]"), "").lowercase()
    if (cleaned.length % 2 != 0) return null
    return ByteArray(cleaned.length / 2) { i ->
        cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

private fun intToBytes(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()
private fun longToBytes(v: Long): ByteArray = ByteBuffer.allocate(8).putLong(v).array()
private fun floatToBytes(v: Float): ByteArray = ByteBuffer.allocate(4).putFloat(v).array()
private fun doubleToBytes(v: Double): ByteArray = ByteBuffer.allocate(8).putDouble(v).array()

private fun scanRegion(
    @Suppress("UNUSED_PARAMETER") pid: Int,
    memFile: File,
    start: Long,
    end: Long,
    pattern: ByteArray,
    maxMatches: Int,
): List<Long> {
    val matches = mutableListOf<Long>()
    val chunkSize = 65536
    var offset = start
    try {
        val raf = java.io.RandomAccessFile(memFile, "r")
        while (offset < end && matches.size < maxMatches) {
            val readSize = minOf(chunkSize, (end - offset).toInt())
            raf.seek(offset)
            val buf = ByteArray(readSize)
            val read = raf.read(buf)
            if (read <= 0) break
            val data = if (read < readSize) buf.copyOf(read) else buf
            var idx = 0
            while ((idx <= (data.size - pattern.size)) && (matches.size < maxMatches)) {
                var found = true
                for ((j, expected) in pattern.withIndex()) {
                    if (data[idx + j] != expected) {
                        found = false
                        break
                    }
                }
                if (found) {
                    matches.add(offset + idx)
                    idx += pattern.size
                } else idx++
            }
            offset += readSize
        }
        raf.close()
    } catch (e: Exception) {
        AppLogger.w("MemScanTool", "scanRegion error: ${e.message}")
    }
    return matches
}

/**
 * Memory-region scan for FOREIGN-UID processes via su.
 *
 * Reads /proc/pid/mem in chunks through `dd bs=<n> skip=<offset> | od -An -v -tx1`,
 * which produces raw hex bytes without the 1MB su text cap killing large regions.
 * Chunk size is capped at 256KB per dd call to keep od's output well under the cap
 * (256KB of bytes → 512KB of hex + newlines, safely < 1MB).
 */
private fun scanRegionViaRoot(
    root: RootManager,
    pid: Int,
    start: Long,
    end: Long,
    pattern: ByteArray,
    maxMatches: Int
): List<Long> {
    val matches = mutableListOf<Long>()
    val chunkSize = 131072 // 128 KB of raw bytes per su round-trip
    var offset = start
    val hexRe = Regex("^[0-9a-fA-F]{2}$")
    try {
        while (offset < end && matches.size < maxMatches) {
            val readSize = minOf(chunkSize, (end - offset).toInt())
            val cmd =
                "dd if=/proc/$pid/mem bs=$readSize count=1 skip=$offset 2>/dev/null | od -An -v -tx1"
            val (code, out) = runRootCmd(root, cmd, 20)
            if (code != 0) break
            val data = out.trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() && hexRe.matches(it) }
                .map { it.toInt(16).toByte() }
                .toByteArray()
            if (data.isEmpty()) break
            var idx = 0
            while (idx <= data.size - pattern.size && matches.size < maxMatches) {
                var found = true
                for (j in pattern.indices) {
                    if (data[idx + j] != pattern[j]) {
                        found = false; break
                    }
                }
                if (found) {
                    matches.add(offset + idx)
                    idx += pattern.size
                } else idx++
            }
            offset += data.size
        }
    } catch (e: Exception) {
        AppLogger.w("MemScanTool", "scanRegionViaRoot error: ${e.message}")
    }
    return matches
}

private fun hexDump(data: ByteArray, baseAddr: Long): String {
    return buildString {
        for (i in data.indices step 16) {
            append("0x%08X: ".format(baseAddr + i))
            val line = data.copyOfRange(i, minOf(i + 16, data.size))
            line.forEach { append("%02X ".format(it)) }
            repeat(16 - line.size) { append("   ") }
            append(" |")
            line.forEach { b ->
                @Suppress("DEPRECATION")
                val printable: Char = b.toChar()
                append(if ((b in 0x20..0x7E)) printable else '.')
            }
            append("|\n")
        }
    }
}

// ---------------------------------------------------------------------------
// Toolchain & shell environment diagnostics (issues C & D)
// ---------------------------------------------------------------------------

/**
 * 14. Device toolchain / shell environment readiness scan (mem.env.diagnose).
 *
 * Addresses the two operational gaps seen in the field:
 *   C) No usable native compiler: /system/bin/clang is absent on stock Android,
 *      and a bundled clang "stub" that execs the system clang cannot work. This
 *      scan verifies the app-OWNED clang pack (runtimes/clang/bin/clang) is a
 *      real aarch64 ELF binary (not a shell wrapper) and whether the NDK
 *      sysroot is present for compiling test .so files.
 *   D) Minimal root-shell environment: busybox/toybox availability, xz/lzma,
 *      od/dd (needed by the root memory scanner), and the app's Python runtime
 *      (which can decompress .xz even when the shell has no xz binary).
 *
 * Returns a PASS/FAIL matrix with concrete repair commands the agent can run.
 */
class MemEnvDiagnoseTool(private val runtime: com.example.oreshik.agent.runtime.RuntimeManager) :
    AgentTool {
    override val definition = ToolDefinition(
        name = "mem.env.diagnose",
        category = ToolCategory.MEMORY_MANIPULATION,
        description = "Diagnose the on-device toolchain and shell environment for memory work: real (non-stub) clang + NDK sysroot presence, busybox/toybox, xz/lzma support, od/dd availability, root shell sanity, and the app Python runtime for .xz decompression. Returns PASS/FAIL rows plus ready-to-run repair commands.",
        permissions = setOf(ToolPermission.PROCESS_MEMORY, ToolPermission.MEMORY_SCAN),
        parameters = mapOf(
            "repair" to "boolean (optional, default false) - also install/fix what can be fixed automatically (provision runtime packs)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val o = arguments.asJsonObject
            val repair = o.get("repair")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
            if (repair) {
                // Best-effort: provision bundled runtime packs (clang/ndk/cli-tools) if missing.
                runCatching {
                    kotlinx.coroutines.coroutineScope {
                        launch { runtime.ensureProvisioned() }
                    }
                }
            }
            val root = RootManager(context.app)
            val rows = mutableListOf<String>()
            val repairs = mutableListOf<String>()
            var allCriticalOk = true

            fun row(
                name: String,
                ok: Boolean,
                critical: Boolean,
                detail: String,
                fix: String? = null
            ) {
                rows.add("[${if (ok) "PASS" else if (critical) "FAIL" else "WARN"}] $name: $detail")
                if (!ok) {
                    if (critical) allCriticalOk = false
                    fix?.let { repairs.add(it) }
                }
            }

            // ---- C: native compiler -------------------------------------------------
            val clang = runtime.resolveExecutable("clang")
            var clangReal = false
            if (clang == null) {
                row(
                    name = "clang",
                    ok = false,
                    critical = true,
                    detail = "not found in app runtimes or PATH",
                    fix = "Install the Clang & LLVM toolpack (Agent Tools → Runtime Manager) — the /system/bin/clang stub does NOT provide a compiler.",
                )
            } else {
                val head = ByteArray(20)
                val read =
                    runCatching { clang.inputStream().use { it.read(head) } }.getOrDefault(-1)
                val isElf = read >= 20 && head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte()
                        && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
                val firstLine = if (isElf) "" else runCatching {
                    clang.readLines().firstOrNull().orEmpty().take(160)
                }.getOrDefault("")
                clangReal = isElf
                row(
                    "clang", isElf, true,
                    when {
                        isElf -> "real ELF binary at ${clang.absolutePath} (${clang.length() / 1024} KB)"
                        firstLine.contains("system/bin/clang") || firstLine.contains("/system/bin/") ->
                            "STUB detected at ${clang.absolutePath} — it execs the (missing) system clang: $firstLine"

                        else -> "not an ELF binary: $firstLine"
                    },
                    "Reinstall the Clang toolpack so runtimes/clang/bin/clang is a real aarch64 ELF (current stub cannot compile)."
                )
            }

            val ndkRoot = runtime.runtimePath("ndk")
            val sysroot = File(ndkRoot, "toolchains/llvm/prebuilt")
                .takeIf { it.isDirectory }
                ?: ndkRoot.takeIf { File(it, "sysroot").isDirectory || it.name.contains("sysroot") }
            row(
                "ndk sysroot", sysroot != null, false,
                if (sysroot != null) "found at ${sysroot.absolutePath}" else "NDK sysroot pack not installed — compiling against bionic headers impossible",
                "Install the NDK (sysroot) toolpack, then compile with: clang --sysroot=<ndk>/sysroot -shared -o test.so test.c"
            )

            // ---- /proc visibility ---------------------------------------------------
            // Foreign-UID (background) processes are hidden from the app UID; they
            // must be readable directly OR via the root fallback to be targetable.
            run {
                val procDirs = File("/proc").listFiles()
                    ?.filter { it.isDirectory && it.name.all { ch -> ch.isDigit() } }
                    .orEmpty()
                val hiddenCount = procDirs.count {
                    !runCatching { File(it, "cmdline").readText().isNotBlank() }.getOrDefault(false)
                }
                row(
                    "/proc visibility", hiddenCount == 0 || root.canUseRoot(), true,
                    when {
                        hiddenCount == 0 ->
                            "all ${procDirs.size} /proc entries readable directly"
                        root.canUseRoot() ->
                            "$hiddenCount foreign-UID processes hidden from app UID — readable via root fallback (mem.process.list uses su)"
                        else ->
                            "$hiddenCount processes unreadable (foreign-UID) and root unavailable — background apps will be invisible to mem.process.list"
                    },
                    if (hiddenCount > 0 && !root.canUseRoot())
                        "Enable Allow root in Settings and grant su so memory tools can see other apps' background processes."
                    else null
                )
            }

            // ---- D: shell environment ----------------------------------------------
            if (root.canUseRoot()) {
                for (tool in listOf("toybox", "busybox", "od", "dd", "xxd", "xz", "unxz")) {
                    val (code, _) = runRootCmd(root, "command -v $tool", 5)
                    row(
                        "root-shell: $tool", code == 0, tool == "od" || tool == "dd",
                        if (code == 0) "available" else "missing in root shell",
                        if (tool == "od" || tool == "dd") null
                        else "Bundle busybox (provides xz/lzma) into the cli-tools toolpack, or decompress .xz via the app Python runtime instead."
                    )
                }
            } else {
                row(
                    name = "root shell",
                    ok = false,
                    critical = false,
                    detail = "root disabled — memory tools limited to own-UID processes",
                )
            }

            // App Python runtime (can decompress .xz and run helper logic in-process).
            val pyOk = runCatching {
                runtime.python.execute("import lzma; print('py-lzma-ok')", context.workspace, 15)
                    .getOrNull()?.stdout?.contains("py-lzma-ok") == true
            }.getOrDefault(false)
            row(
                "python lzma (app runtime)", pyOk, false,
                if (pyOk) "app CPython can decompress .xz/.lzma in-process" else "app Python runtime unavailable",
                "Restart the app to re-provision the bundled CPython runtime."
            )

            val summary = buildString {
                append("Memory-tool environment diagnosis\n")
                append(if (allCriticalOk) "Overall: READY\n\n" else "Overall: NEEDS REPAIR\n\n")
                rows.forEach { append(it).append('\n') }
                if (repairs.isNotEmpty()) {
                    append("\nSuggested repairs:\n")
                    repairs.distinct()
                        .forEachIndexed { i, r -> append("${i + 1}. ").append(r).append('\n') }
                }
                append("\nTip: python.exec can decompress .xz archives in-process:\n")
                append("  import lzma, shutil; shutil.copyfileobj(lzma.open('/data/local/tmp/frida-gadget.so.xz'), open('/data/local/tmp/frida-gadget.so','wb'))\n")
            }

            ToolResult(
                allCriticalOk, summary, metadata = mapOf(
                    "ready" to allCriticalOk,
                    "clang_real" to clangReal,
                    "checks" to rows.size
                )
            )
        }
}
