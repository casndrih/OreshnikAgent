package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.AgentTool
import com.example.oreshik.agent.core.ToolCategory
import com.example.oreshik.agent.core.ToolContext
import com.example.oreshik.agent.core.ToolDefinition
import com.example.oreshik.agent.core.ToolPermission
import com.example.oreshik.agent.core.ToolResult
import com.example.oreshik.agent.security.RootManager
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.File
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Traffic capture, inspection and modification toolkit.
 *
 * Two complementary layers:
 *  1. Client-side HTTP capture — fire requests, inspect status/headers/body,
 *     replay them with modifications, repeat (replay attacks / flakiness checks).
 *     Also dumps HTTP/DNS evidence from the device log for this app (or everything
 *     when root is available) and shows per-interface/UID counters.
 *  2. Root packet capture — tcpdump .pcap capture of on-device traffic when a
 *     root provider and a tcpdump binary are present.
 *
 * Only inspect or modify traffic for systems you are authorized to test.
 */

/** Shared capture HTTP client: browser-like redirect behavior, generous timeouts. */
internal val trafficHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true) // browser-like redirect behavior (Java API: no named args)
    .followSslRedirects(true)
    .build()

private val textMimeRegex = Regex(
    "^(text/|application/(json|xml|javascript|x-www-form-urlencoded|graphql|soap\\+xml|yaml|x-yaml|toml|x-ndjson|vnd\\.api\\+json|ecmascript|xhtml))",
    RegexOption.IGNORE_CASE,
)

private val binaryMimeRegex = Regex(
    "^(image/|video/|audio/|application/(octet-stream|zip|pdf|gzip|x-gzip|vnd\\.|font|wasm|x-msdownload|x-tar))",
    RegexOption.IGNORE_CASE,
)

private const val MAX_HTTP_BODY_BYTES = 1_000_000L
private const val MAX_DECOMPRESSED_HTTP_BODY_BYTES = 4 * 1024 * 1024

private val exchangeCounter = AtomicInteger(0)

// ---------------------------------------------------------------------------
// Shared request/response machinery
// ---------------------------------------------------------------------------

internal class HttpExchange(
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String?,
    val status: Int,
    val statusMessage: String,
    val responseHeaders: Map<String, String>,
    val responseContentType: String,
    val responseBytes: ByteArray,
    val protocol: String,
    val durationMs: Long,
) {
    fun responseText(): String? = decodeBody(responseBytes, responseContentType)
}

/** Fire an HTTP request and capture the full exchange (bounded to 1 MB of body). */
internal fun executeHttpExchange(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String?
): Result<HttpExchange> {
    val trimmedUrl = url.trim()
    if (trimmedUrl.isBlank()) return Result.failure(IllegalArgumentException("Missing url"))
    if ((!trimmedUrl.startsWith("http:" + "//")) && (!trimmedUrl.startsWith("https:" + "//"))) {
        return Result.failure(IllegalArgumentException("URL must start with http:" + "// or https:" + "//"))
    }
    val effectiveMethod = method.trim().uppercase().ifBlank { "GET" }
    if (effectiveMethod !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
        return Result.failure(IllegalArgumentException("Unsupported method: $effectiveMethod"))
    }

    val builder = try {
        Request.Builder().url(trimmedUrl)
    } catch (e: Exception) {
        return Result.failure(IllegalArgumentException("Invalid URL: ${e.message}"))
    }
    headers.forEach { (k, v) -> runCatching { builder.header(k.trim(), v) } }

    val contentTypeHeader = headers.entries.firstOrNull { header -> header.key.equals("Content-Type", ignoreCase = true) }
    val mediaType = contentTypeHeader?.value?.let { contentType -> runCatching { contentType.toMediaType() }.getOrNull() }

    val hasBody = effectiveMethod !in setOf("GET", "HEAD", "OPTIONS")
    val requestBody = when {
        !hasBody -> null
        body != null -> body.toRequestBody(mediaType)
        else -> ByteArray(0).toRequestBody(null)
    }

    return try {
        when (effectiveMethod) {
            "POST" -> builder.post(requestBody ?: ByteArray(0).toRequestBody(null))
            "PUT" -> builder.put(requestBody ?: ByteArray(0).toRequestBody(null))
            "PATCH" -> builder.patch(requestBody ?: ByteArray(0).toRequestBody(null))
            "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
            "HEAD" -> builder.head()
            "OPTIONS" -> builder.method("OPTIONS", null)
            else -> builder.get()
        }
        val request = builder.build()
        val started = System.nanoTime()
        trafficHttp.newCall(request).execute().use { response ->
            val source = response.body?.source()
            val bytes = if ((effectiveMethod == "HEAD") || (source == null)) ByteArray(0) else source.readBounded(MAX_HTTP_BODY_BYTES)
            val durationMs = (System.nanoTime() - started) / 1_000_000
            val responseHeadersMap = LinkedHashMap<String, String>()
            response.headers.forEach { (name, value) -> responseHeadersMap[name] = value }
            Result.success(
                HttpExchange(
                    method = effectiveMethod,
                    url = request.url.toString(),
                    requestHeaders = headers,
                    requestBody = if (hasBody) body else null,
                    status = response.code,
                    statusMessage = response.message,
                    responseHeaders = responseHeadersMap,
                    responseContentType = response.header("Content-Type") ?: "application/octet-stream",
                    responseBytes = bytes,
                    protocol = response.protocol.toString(),
                    durationMs = durationMs,
                )
            )
        }
    } catch (e: Exception) {
        AppLogger.w("TrafficTools", "HTTP exchange failed: $effectiveMethod $trimmedUrl: ${e.message}")
        Result.failure(e)
    }
}

private fun BufferedSource.readBounded(max: Long): ByteArray {
    val buffer = okio.Buffer()
    var remaining = max
    while (remaining > 0) {
        val read = read(buffer, remaining)
        if (read == -1L) break
        remaining -= read
    }
    return buffer.readByteArray()
}

private fun java.io.InputStream.readBounded(max: Int): Pair<ByteArray, Boolean> {
    val output = ByteArrayOutputStream(minOf(max, 32 * 1024))
    val buffer = ByteArray(8192)
    var total = 0
    var truncated = false
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        val take = minOf(count, (max - total).coerceAtLeast(0))
        if (take > 0) {
            output.write(buffer, 0, take)
            total += take
        }
        if (take < count) {
            truncated = true
            break
        }
    }
    return output.toByteArray() to truncated
}

/** Decompress when needed and decode as UTF-8 for textual payloads; null for binary. */
internal fun decodeBody(bytes: ByteArray, contentType: String): String? {
    if (bytes.isEmpty()) return null
    val lower = contentType.lowercase()
    var truncated = false
    val decompressed = when {
        lower.contains("gzip") -> runCatching {
            GZIPInputStream(bytes.inputStream()).use { input ->
                val (decoded, wasTruncated) = input.readBounded(MAX_DECOMPRESSED_HTTP_BODY_BYTES)
                truncated = wasTruncated
                decoded
            }
        }.getOrNull() ?: bytes
        lower.contains("deflate") -> runCatching {
            InflaterInputStream(bytes.inputStream()).use { input ->
                val (decoded, wasTruncated) = input.readBounded(MAX_DECOMPRESSED_HTTP_BODY_BYTES)
                truncated = wasTruncated
                decoded
            }
        }.getOrNull() ?: bytes
        else -> bytes
    }
    val looksTextual = textMimeRegex.containsMatchIn(lower) || lower.isBlank() ||
        (lower.startsWith("application/") && !binaryMimeRegex.containsMatchIn(lower))
    if ((!looksTextual) && (decompressed.size > 64)) {
        val head = decompressed.take(256)
        val printable = head.count { b -> b.toInt() == 0x09 || b.toInt() == 0x0A || b.toInt() == 0x0D || b.toInt() in 0x20..0x7E }
        if (printable < head.size * 0.8) return null
    }
    val text = String(decompressed, Charsets.UTF_8)
    return if (truncated) {
        text + "\n[response body truncated after " + MAX_DECOMPRESSED_HTTP_BODY_BYTES + " decompressed bytes]"
    } else {
        text
    }
}

internal fun truncate(s: String, max: Int): String =
    if (s.length <= max) s else s.substring(0, max) + "\n[truncated ${s.length - max} chars]"

internal fun HttpExchange.renderDump(maxBodyChars: Int = 20000): String = buildString {
    append("=== ").append(method).append(' ').append(url).append('\n')
    append("HTTP/").append(protocol).append(' ').append(status).append(' ')
        .append(statusMessage.ifBlank { if (status < 400) "OK" else "Error" })
        .append("  (").append(durationMs).append(" ms)\n")
    append("\n-- response headers --\n")
    responseHeaders.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
    append("\n-- response body --\n")
    if (responseBytes.isEmpty()) {
        append("(empty body)\n")
    } else {
        val text = responseText()
        if (text != null) {
            append(truncate(text, maxBodyChars)).append('\n')
        } else {
            append("(binary ").append(responseContentType).append(", ").append(responseBytes.size)
                .append(" bytes; head: ")
                .append(responseBytes.asSequence().take(32).joinToString(" ") { byte -> "%02x".format(byte) })
                .append(")\n")
        }
    }
}

internal fun HttpExchange.toMeta(id: String): JsonObject = JsonObject().apply {
    addProperty("id", id)
    addProperty("url", url)
    addProperty("method", method)
    addProperty("status", status)
    addProperty("protocol", protocol)
    addProperty("duration_ms", durationMs)
    addProperty("content_type", responseContentType)
    addProperty("body_bytes", responseBytes.size.toLong())
}

/** Read a JSON object argument into a plain string map. */
internal fun jsonHeaders(arguments: JsonElement, key: String): Map<String, String> {
    val obj = arguments.asJsonObject[key] ?: return emptyMap()
    if (!obj.isJsonObject) return emptyMap()
    val out = LinkedHashMap<String, String>()
    obj.asJsonObject.entrySet().forEach { (name, value) ->
        val v = if (value == null || value.isJsonNull) null else runCatching { value.asString }.getOrNull()
        if (name.isNotBlank() && v != null) out[name] = v
    }
    return out
}

/** Persist a captured exchange under <workspace>/traffic/ and return the path. */
internal fun saveExchange(context: ToolContext, exchange: HttpExchange, suffix: String): String {
    return try {
        val dir = File(context.workspace, "traffic")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "${exchange.method.lowercase()}_${stamp}_$suffix.txt")
        file.writeText(exchange.renderDump(maxBodyChars = 500_000))
        file.absolutePath
    } catch (e: Exception) {
        AppLogger.w("TrafficTools", "failed to save exchange: ${e.message}")
        ""
    }
}

// ---------------------------------------------------------------------------
// Capture session store (in-memory ring of recent exchanges)
// ---------------------------------------------------------------------------

internal object TrafficCaptureStore {
    private const val MAX_ENTRIES = 100
    private val lock = Any()
    private val entries = ArrayDeque<HttpExchange>()
    private val order = ArrayDeque<String>()

    fun add(id: String, exchange: HttpExchange) = synchronized(lock) {
        entries.addLast(exchange)
        order.addLast(id)
        while (order.size > MAX_ENTRIES) {
            order.removeFirst()
            entries.removeFirst()
        }
    }

    fun ids(): List<String> = synchronized(lock) { order.toList() }
    fun byId(id: String): HttpExchange? = synchronized(lock) {
        val index = order.indexOf(id)
        if (index >= 0) entries.elementAt(index) else null
    }
    fun size(): Int = synchronized(lock) { order.size }
    fun clear() = synchronized(lock) { order.clear(); entries.clear() }
}

internal fun newCaptureId(prefix: String): String =
    "$prefix-${System.currentTimeMillis().toString(36)}-${exchangeCounter.incrementAndGet()}"

// ---------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------

/** 1. Capture: fire a single request and dump the full exchange. */
class HttpCaptureTool : AgentTool {
    override val definition = ToolDefinition(
        name = "http.capture",
        category = ToolCategory.TRAFFIC_CAPTURE,
        description = "Capture a single HTTP(S) request/response exchange: send the request, then return the status line, response headers and (decoded) body. Useful to inspect what an API or endpoint returns.",
        permissions = setOf(ToolPermission.NETWORK, ToolPermission.TRAFFIC_CAPTURE),
        parameters = mapOf(
            "url" to "string (required, http/https)",
            "method" to "string (GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS, default GET)",
            "headers" to "object (request headers)",
            "body" to "string (request body, optional)",
            "save_response" to "boolean (also save dump to workspace traffic/, default true)",
            "max_body_chars" to "integer (inline body limit, default 20000)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val o = arguments.asJsonObject
        val url = o["url"]?.takeUnless { jsonNull -> jsonNull.isJsonNull }?.asString
            ?: return@withContext ToolResult(false, error = "Missing url")
        val method = o.get("method")?.takeUnless { it.isJsonNull }?.asString ?: "GET"
        val headers = jsonHeaders(arguments, "headers")
        val body = o.get("body")?.takeUnless { it.isJsonNull }?.asString
        val save = o.get("save_response")?.asBoolean ?: true
        val maxBodyChars = (o.get("max_body_chars")?.asInt ?: 20000).coerceIn(200, 200_000)

        val exchange = executeHttpExchange(method, url, headers, body).getOrElse {
            return@withContext ToolResult(false, error = "HTTP capture failed: ${it.message ?: it.javaClass.simpleName}")
        }
        val id = newCaptureId("cap")
        TrafficCaptureStore.add(id, exchange)
        val savedPath = if (save) saveExchange(context, exchange, id) else ""
        val out = buildString {
            append("[capture id: ").append(id).append("]\n")
            append(exchange.renderDump(maxBodyChars))
            if (savedPath.isNotBlank()) append("\nSaved to: ").append(savedPath)
        }
        ToolResult(true, out, metadata = mapOf("capture" to exchange.toMeta(id)))
    }
}

/** 2. Modify: replay a request with header/method/body/URL modifications applied. */
class HttpCaptureModifyTool : AgentTool {
    override val definition = ToolDefinition(
        name = "http.capture.modify",
        category = ToolCategory.TRAFFIC_CAPTURE,
        description = "Send an HTTP request with modifications applied (edited headers, body, method or URL) and capture the resulting exchange. This is the active-traffic-modification tool: describe the original request, list the modifications, and the tool sends the modified version. Only modify traffic you are authorized to test.",
        permissions = setOf(ToolPermission.NETWORK, ToolPermission.TRAFFIC_CAPTURE, ToolPermission.TRAFFIC_MODIFY),
        parameters = mapOf(
            "url" to "string (required, target URL after modifications)",
            "method" to "string (HTTP method after modifications, default GET)",
            "headers" to "object (request headers after modifications)",
            "body" to "string (request body after modifications, optional)",
            "description" to "string (what was modified and why)",
            "save_response" to "boolean (default true)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val o = arguments.asJsonObject
        val url = o["url"]?.takeUnless { jsonNull -> jsonNull.isJsonNull }?.asString
            ?: return@withContext ToolResult(false, error = "Missing url")
        val method = o.get("method")?.takeUnless { it.isJsonNull }?.asString ?: "GET"
        val headers = jsonHeaders(arguments, "headers")
        val body = o.get("body")?.takeUnless { it.isJsonNull }?.asString
        val description = o.get("description")?.takeUnless { it.isJsonNull }?.asString.orEmpty()

        val exchange = executeHttpExchange(method, url, headers, body).getOrElse {
            return@withContext ToolResult(false, error = "Modified request failed: ${it.message ?: it.javaClass.simpleName}")
        }
        val id = newCaptureId("mod")
        TrafficCaptureStore.add(id, exchange)
        val savedPath = if (o.get("save_response")?.asBoolean ?: true) saveExchange(context, exchange, id) else ""
        val out = buildString {
            append("[modified request, capture id: ").append(id).append("]\n")
            if (description.isNotBlank()) append("Modification notes: ").append(description).append('\n')
            append(exchange.renderDump())
            if (savedPath.isNotBlank()) append("\nSaved to: ").append(savedPath)
        }
        ToolResult(true, out, metadata = mapOf("capture" to exchange.toMeta(id)))
    }
}

/** 3. Replay a previously captured exchange, optionally with overrides. */
class HttpReplayTool : AgentTool {
    override val definition = ToolDefinition(
        name = "http.replay",
        category = ToolCategory.TRAFFIC_CAPTURE,
        description = "Replay a previously captured HTTP exchange from this session (by capture id) with optional overrides for url, method, headers and body. Use http.capture or http.capture.modify first to obtain capture ids.",
        permissions = setOf(ToolPermission.NETWORK, ToolPermission.TRAFFIC_CAPTURE, ToolPermission.TRAFFIC_MODIFY),
        parameters = mapOf(
            "capture_id" to "string (required)",
            "url" to "string (override URL, optional)",
            "method" to "string (override method, optional)",
            "headers" to "object (override/extra headers, merged over the captured ones)",
            "remove_headers" to "array (header names to drop before replay)",
            "body" to "string (override body, optional)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val o = arguments.asJsonObject
        val captureId = o.get("capture_id")?.takeUnless { it.isJsonNull }?.asString
            ?: return@withContext ToolResult(false, error = "Missing capture_id")
        val entry = TrafficCaptureStore.byId(captureId)
            ?: return@withContext ToolResult(false, error = "Unknown capture_id '$captureId'. Known ids: ${TrafficCaptureStore.ids().joinToString()}")

        val merged = LinkedHashMap(entry.requestHeaders)
        o.get("headers")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, v) ->
            val value = if (v.isJsonNull) null else runCatching { v.asString }.getOrNull()
            if (k.isNotBlank() && value != null) merged[k] = value
        }
        o.get("remove_headers")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
            if (!el.isJsonNull) merged.remove(el.asString.trim())
        }

        val method = o.get("method")?.takeUnless { it.isJsonNull }?.asString ?: entry.method
        val url = o["url"]?.takeUnless { jsonNull -> jsonNull.isJsonNull }?.asString ?: entry.url
        val body = o.get("body")?.takeUnless { it.isJsonNull }?.asString ?: entry.requestBody

        val exchange = executeHttpExchange(method, url, merged, body).getOrElse {
            return@withContext ToolResult(false, error = "Replay failed: ${it.message ?: it.javaClass.simpleName}")
        }
        val id = newCaptureId("rep")
        TrafficCaptureStore.add(id, exchange)
        val out = buildString {
            append("[replay of ").append(captureId).append(", capture id: ").append(id).append("]\n")
            append(exchange.renderDump())
        }
        ToolResult(true, out, metadata = mapOf("capture" to exchange.toMeta(id)))
    }
}

/** 4. Repeat a captured request N times with delay (replay/race/flakiness checks). */
class HttpCaptureRepeaterTool : AgentTool {
    override val definition = ToolDefinition(
        name = "http.capture.repeat",
        category = ToolCategory.TRAFFIC_CAPTURE,
        description = "Repeat a previously captured HTTP request N times with an optional delay between attempts. Summarizes status codes and reports whether responses diverge. Authorized testing only.",
        permissions = setOf(ToolPermission.NETWORK, ToolPermission.TRAFFIC_CAPTURE, ToolPermission.TRAFFIC_MODIFY),
        parameters = mapOf(
            "capture_id" to "string (required)",
            "repeat" to "integer (1-50, default 5)",
            "delay_ms" to "integer (0-10000, default 0)",
            "report" to "string ('status' or 'full', default 'status')"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val o = arguments.asJsonObject
        val captureId = o.get("capture_id")?.takeUnless { it.isJsonNull }?.asString
            ?: return@withContext ToolResult(false, error = "Missing capture_id")
        val entry = TrafficCaptureStore.byId(captureId)
            ?: return@withContext ToolResult(false, error = "Unknown capture_id '$captureId'. Known ids: ${TrafficCaptureStore.ids().joinToString()}")

        val repeat = (o["repeat"]?.asInt ?: 5).coerceIn(1, 50)
        val delayMs = (o["delay_ms"]?.asLong ?: 0L).coerceIn(0, 10_000)
        val full = (o["report"]?.asString ?: "status").equals("full", ignoreCase = true)

        val statuses = mutableListOf<Int>()
        val firstText = entry.responseText()
        var diverged = false
        val details = StringBuilder()
        for (i in 1..repeat) {
            if ((delayMs > 0) && (i > 1)) delay(delayMs.milliseconds)
            val outcome = executeHttpExchange(entry.method, entry.url, entry.requestHeaders, entry.requestBody)
            val exchange = outcome.getOrNull()
            if (exchange == null) {
                details.append('#').append(i).append(" -> ERROR: ")
                    .append(outcome.exceptionOrNull()?.message ?: "request failed").append('\n')
                statuses.add(-1)
                continue
            }
            val id = newCaptureId("rep$i")
            TrafficCaptureStore.add(id, exchange)
            statuses.add(exchange.status)
            details.append('#').append(i).append(" -> ").append(exchange.status)
                .append(" (").append(exchange.durationMs).append(" ms, id ").append(id).append(")\n")
            if (full) {
                details.append(truncate(exchange.renderDump(maxBodyChars = 1500), 2000)).append('\n')
            } else if (!diverged) {
                val text = exchange.responseText()
                if (text != firstText) diverged = true
            }
        }
        val summary = statuses.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString(", ") { "HTTP ${it.key} x${it.value}" }
        val out = buildString {
            append("Repeated ").append(captureId).append(' ').append(repeat).append(" time(s)\n")
            append("Status summary: ").append(summary).append('\n')
            if (!full) append("Responses diverged from first capture: ").append(if (diverged) "yes" else "no").append('\n')
            append(details)
        }
        ToolResult(true, out)
    }
}

/** 5. HTTP/DNS evidence from the device log. */
class HttpLogCaptureTool : AgentTool {
    override val definition = ToolDefinition(
        name = "httplog.capture",
        category = ToolCategory.TRAFFIC_CAPTURE,
        description = "Dump recent HTTP/HTTPS/DNS activity evidence from the device log (logcat) filtered to network-related lines. Without root this only returns this app's own entries; with root it captures all apps' network logs.",
        permissions = setOf(ToolPermission.TRAFFIC_CAPTURE, ToolPermission.EXECUTE_PROCESS),
        parameters = mapOf(
            "lines" to "integer (how many recent log lines to scan, 100-5000, default 1000)",
            "filter" to "string (extra lowercase keyword filter, optional)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val o = arguments.asJsonObject
        val lines = (o.get("lines")?.asInt ?: 1000).coerceIn(100, 5000)
        val extraFilter = o.get("filter")?.takeUnless { it.isJsonNull }?.asString?.lowercase().orEmpty()

        val root = RootManager(context.app)
        val command = if (root.canUseRoot()) "${root.status().suPath.ifBlank { "su" }} -c 'logcat -d -v brief -t $lines'" else "logcat -d -v brief -t $lines"
        val (code, raw) = runShellCommand(command, timeoutSeconds = 15)
        if (code != 0 && raw.isBlank()) {
            return@withContext ToolResult(false, error = "logcat unavailable (exit $code). Root mode can read all apps; without root only this app's entries are visible.")
        }
        val networkRegex = Regex("okhttp|retrofit|http|https|ssl|tls|dns|UnknownHost|SocketTimeout|ConnectException|haproxy|nginx|certificate", RegexOption.IGNORE_CASE)
        val matched = raw.lineSequence()
            .filter { networkRegex.containsMatchIn(it) }
            .filter { extraFilter.isBlank() || it.lowercase().contains(extraFilter) }
            .toList()
        val out = buildString {
            append("mode=").append(if (root.canUseRoot()) "root (all apps)" else "app-only").append('\n')
            append("matched ").append(matched.size).append(" of ~").append(lines).append(" lines\n\n")
            matched.take(400).forEach { append(it).append('\n') }
            if (matched.size > 400) append("[truncated, ").append(matched.size - 400).append(" more lines]")
        }
        ToolResult(true, out.ifBlank { "No HTTP/DNS related log lines found." })
    }
}

/** 6. Clear the in-memory capture history. */
class HttpHistoryClearTool : AgentTool {
    override val definition = ToolDefinition(
        name = "http.history.clear",
        category = ToolCategory.TRAFFIC_CAPTURE,
        description = "Clear the in-memory HTTP capture history (all stored request/response exchanges from http.capture, http.capture.modify and http.replay).",
        permissions = setOf(ToolPermission.TRAFFIC_CAPTURE),
        parameters = emptyMap()
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val removed = TrafficCaptureStore.size()
        TrafficCaptureStore.clear()
        return ToolResult(true, "Cleared $removed captured exchange(s).")
    }
}

/** 7. List captured exchanges. */
class HttpHistoryTool : AgentTool {
    override val definition = ToolDefinition(
        name = "http.history",
        category = ToolCategory.TRAFFIC_CAPTURE,
        description = "List HTTP exchanges captured this session (capture ids, method, URL, status) and optionally print one in full.",
        permissions = setOf(ToolPermission.TRAFFIC_CAPTURE),
        parameters = mapOf(
            "capture_id" to "string (optional: print this exchange in full)",
            "limit" to "integer (list size limit, default 20)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val o = arguments.asJsonObject
        val captureId = o.get("capture_id")?.takeUnless { it.isJsonNull }?.asString
        if (captureId != null) {
            val entry = TrafficCaptureStore.byId(captureId)
                ?: return@withContext ToolResult(false, error = "Unknown capture_id '$captureId'. Known ids: ${TrafficCaptureStore.ids().joinToString()}")
            return@withContext ToolResult(true, "[capture id: $captureId]\n" + entry.renderDump())
        }
        val limit = (o.get("limit")?.asInt ?: 20).coerceIn(1, 100)
        val ids = TrafficCaptureStore.ids().takeLast(limit)
        if (ids.isEmpty()) return@withContext ToolResult(true, "No captures stored yet.")
        val out = buildString {
            append(TrafficCaptureStore.size()).append(" captured exchange(s), showing last ").append(ids.size).append(":\n")
            ids.forEach { id ->
                val e = TrafficCaptureStore.byId(id)
                if (e != null) append(id).append("  ").append(e.method).append(' ').append(e.status).append("  ").append(e.url).append('\n')
            }
        }
        ToolResult(true, out)
    }
}

/** 8. Root packet capture to .pcap. */
class TcpdumpCaptureTool : AgentTool {
    override val definition = ToolDefinition(
        name = "tcpdump.capture",
        category = ToolCategory.TRAFFIC_CAPTURE,
        description = "Capture on-device network packets to a .pcap file using tcpdump (requires root and a tcpdump binary on the device). Traffic from ALL apps is captured. Use for authorized diagnostics of this device only.",
        permissions = setOf(ToolPermission.NETWORK, ToolPermission.TRAFFIC_CAPTURE, ToolPermission.ROOT),
        parameters = mapOf(
            "duration_seconds" to "integer (5-120, default 20)",
            "interface" to "string (any/wlan0/rmnet0/lo, default any)",
            "host" to "string (IP/hostname filter, optional)",
            "port" to "integer (port filter, optional)",
            "protocol" to "string (tcp/udp/icmp/arp, optional)"
        )
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val o = arguments.asJsonObject
        val root = RootManager(context.app)
        if (!root.canUseRoot()) {
            return@withContext ToolResult(false, error = "tcpdump capture requires root. Enable Allow root in Settings and grant the su prompt. For rootless inspection use http.capture / httplog.capture / traffic.capture.info.")
        }
        val check = root.execute("command -v tcpdump || which tcpdump", 5)
        if (check.first != 0 || check.second.isBlank()) {
            return@withContext ToolResult(false, error = "tcpdump is not installed on this device. Install a tcpdump binary (e.g. via a Magisk module or push a static tcpdump into /system/xbin) and retry, or use root=false mode.")
        }

        val duration = (o.get("duration_seconds")?.asLong ?: 20L).coerceIn(5, 120)
        val iface = (o.get("interface")?.takeUnless { it.isJsonNull }?.asString ?: "any").ifBlank { "any" }
        if (!Regex("^[A-Za-z0-9]+$").matches(iface)) return@withContext ToolResult(false, error = "Invalid interface name")
        val host = o.get("host")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
        if (host.isNotBlank() && !Regex("^[A-Za-z0-9._:-]+$").matches(host)) return@withContext ToolResult(false, error = "Invalid host filter")
        val port = o.get("port")?.takeUnless { it.isJsonNull }?.asInt
        if (port != null && port !in 1..65535) return@withContext ToolResult(false, error = "Invalid port")
        val proto = o.get("protocol")?.takeUnless { it.isJsonNull }?.asString?.trim()?.lowercase().orEmpty()
        if (proto.isNotBlank() && proto !in setOf("tcp", "udp", "icmp", "arp")) return@withContext ToolResult(false, error = "Invalid protocol filter")

        val dir = File(context.workspace, "traffic")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pcap = File(dir, "capture_$stamp.pcap")

        val filters = mutableListOf<String>()
        if (proto.isNotBlank()) filters.add(proto)
        if (host.isNotBlank()) filters.add("host $host")
        if (port != null) filters.add("port $port")
        val filterExpr = filters.joinToString(" and ")

        val command = buildString {
            append("tcpdump -i ").append(iface).append(" -s 0 -w '").append(pcap.absolutePath).append('\'')
            append(" -c 200000")
            if (filterExpr.isNotBlank()) append(' ').append(filterExpr)
        }

        val (code, output) = root.execute(command, duration + 15)
        val size = if (pcap.exists()) pcap.length() else 0L
        if (size == 0L && code != 0) {
            return@withContext ToolResult(false, error = "tcpdump exited $code with no capture file. Output: ${output.take(500)}")
        }
        val out = buildString {
            append("Packet capture finished (or hit the ").append(duration).append("s window).\n")
            append("File: ").append(pcap.absolutePath).append('\n')
            append("Size: ").append(size).append(" bytes\n")
            append("Filter: ").append(filterExpr.ifBlank { "(none, all traffic)" }).append('\n')
            if (output.isNotBlank()) append("tcpdump output: ").append(truncate(output, 1000)).append('\n')
            append("Pull and open this .pcap in Wireshark for full inspection.")
        }
        ToolResult(size > 0, out, error = if (size > 0) null else "No packets captured", exitCode = code)
    }
}

/** 9. Stop a running tcpdump capture. */
class PacketCaptureStopTool : AgentTool {
    override val definition = ToolDefinition(
        name = "packet.capture.stop",
        category = ToolCategory.TRAFFIC_CAPTURE,
        description = "Stop any running tcpdump packet capture started by tcpdump.capture. The .pcap file already written stays intact. Requires root.",
        permissions = setOf(ToolPermission.TRAFFIC_CAPTURE, ToolPermission.ROOT),
        parameters = emptyMap()
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val root = RootManager(context.app)
        if (!root.canUseRoot()) return@withContext ToolResult(false, error = "Stopping a packet capture requires root.")
        // The dollar signs are passed verbatim to the remote shell (pidof expansion
        // happens there, not in Kotlin); assembled from parts to dodge template parsing.
        val dollar = "$"
        val stopScript = "for p in " + dollar + "(pidof tcpdump); do kill " + dollar + "p; done; sleep 1; pidof tcpdump || echo stopped"
        val (code, output) = root.execute(stopScript, 10)
        val stillRunning = output.lineSequence().firstOrNull()?.trim().orEmpty()
        if (stillRunning.isNotEmpty() && stillRunning != "stopped") {
            root.execute("pidof tcpdump | xargs kill -9 2>/dev/null; echo done", 5)
            ToolResult(true, "Sent SIGKILL to remaining tcpdump process(es).")
        } else {
            ToolResult(code == 0 || stillRunning == "stopped", "Packet capture stopped.")
        }
    }
}

/** 10. Info: capture capabilities, live connections and counters. */
class TrafficCaptureInfoTool : AgentTool {
    override val definition = ToolDefinition(
        name = "traffic.capture.info",
        category = ToolCategory.TRAFFIC_CAPTURE,
        description = "Report traffic-capture capabilities of this device (root availability, tcpdump presence) and show current network state: per-interface byte counters, this app's UID usage and live TCP/UDP sockets.",
        permissions = setOf(ToolPermission.TRAFFIC_CAPTURE),
        parameters = emptyMap()
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val root = RootManager(context.app)
        val rooted = root.canUseRoot()
        var tcpdump = false
        if (rooted) {
            val check = root.execute("command -v tcpdump || which tcpdump", 5)
            tcpdump = check.first == 0 && check.second.isNotBlank()
        }

        val sections = mutableListOf<String>()
        runCatching {
            val (_, dev) = runShellCommand("cat /proc/net/dev", 5)
            if (dev.isNotBlank()) sections.add("== per-interface counters (/proc/net/dev) ==\n$dev")
        }
        runCatching {
            val uid = context.app.applicationInfo.uid
            val (_, sockets) = runShellCommand("ss -tunap 2>/dev/null || netstat -tunap 2>/dev/null", 8)
            if (sockets.isNotBlank()) sections.add("== live sockets (ss/netstat) ==\n${truncate(sockets, 6000)}")
            sections.add("== this app uid ==\n$uid")
        }
        runCatching {
            val uid = context.app.applicationInfo.uid
            val (_, stats) = runShellCommand("dumpsys netstats detail 2>/dev/null | grep -E \"uid=$uid\" | head -n 20", 8)
            if (stats.isNotBlank()) sections.add("== UID $uid network stats (dumpsys netstats) ==\n$stats")
        }

        val out = buildString {
            append("root_available=").append(root.status().available)
            append(" root_enabled=").append(root.isEnabled())
            append(" tcpdump=").append(if (tcpdump) "yes" else "no").append('\n')
            append("packet_capture=").append(if (rooted && tcpdump) "available (tcpdump.capture)" else "unavailable (use http.capture / httplog.capture instead)").append("\n\n")
            sections.forEach { append(it).append("\n\n") }
        }
        ToolResult(true, out.ifBlank { "No network state could be read on this device." })
    }
}

// ---------------------------------------------------------------------------
// Shell helpers (rootless diagnostics)
// ---------------------------------------------------------------------------

private fun runShellCommand(command: String, timeoutSeconds: Long): Pair<Int, String> = try {
    val process = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
    val executor = Executors.newSingleThreadExecutor()
    val future = executor.submit<String> { process.inputStream.bufferedReader().use { it.readTextLimited(200_000) } }
    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        val partial = runCatching { future.get(2, TimeUnit.SECONDS) }.getOrDefault("")
        future.cancel(true)
        executor.shutdownNow()
        124 to partial
    } else {
        val output = runCatching { future.get(2, TimeUnit.SECONDS) }.getOrDefault("")
        executor.shutdownNow()
        process.exitValue() to output
    }
} catch (e: Exception) {
    -1 to (e.message ?: "shell command failed")
}

private fun java.io.Reader.readTextLimited(max: Int): String {
    val buf = CharArray(8192)
    val out = StringBuilder()
    var total = 0
    var truncated = false
    while (true) {
        val n = read(buf)
        if (n < 0) break
        val take = minOf(n, (max - total).coerceAtLeast(0))
        if (take > 0) out.appendRange(buf, 0, take)
        total += take
        if (take < n) truncated = true
    }
    if (truncated) out.append("\n[output truncated]")
    return out.toString()
}
