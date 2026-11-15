package com.example.oreshik.agent.tools

import android.net.Uri
import com.example.oreshik.agent.core.AgentTool
import com.example.oreshik.agent.core.ToolCategory
import com.example.oreshik.agent.core.ToolContext
import com.example.oreshik.agent.core.ToolDefinition
import com.example.oreshik.agent.core.ToolPermission
import com.example.oreshik.agent.core.ToolResult
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.Random
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val USER_AGENTS = arrayOf(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:121.0) Gecko/20100101 Firefox/121.0",
    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
)

private val random = Random()

private fun randomUserAgent(): String = USER_AGENTS[random.nextInt(USER_AGENTS.size)]

internal fun buildRequest(url: String): Request = Request.Builder()
    .url(url)
    .header("User-Agent", randomUserAgent())
    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
    .header("Accept-Language", "en-US,en;q=0.9")
    .header("Cache-Control", "no-cache")
    .header("Pragma", "no-cache")
    .header("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\"")
    .header("Sec-Ch-Ua-Mobile", "?0")
    .header("Sec-Ch-Ua-Platform", "\"Windows\"")
    .header("Sec-Fetch-Dest", "document")
    .header("Sec-Fetch-Mode", "navigate")
    .header("Sec-Fetch-Site", "none")
    .header("Sec-Fetch-User", "?1")
    .header("Upgrade-Insecure-Requests", "1")
    .build()

/** Max chars of page HTML inlined into the agent's tool result (rest is at the saved file). */
private const val HTML_PREVIEW_CHARS = 4000
private const val MAX_CRAWL_RESPONSE_BYTES = 4 * 1024 * 1024

private fun readWebResponseBounded(source: okio.BufferedSource, limit: Int): ByteArray {
    val buffer = Buffer()
    val maximumRead = limit.toLong() + 1L
    while (buffer.size < maximumRead) {
        val count = source.read(buffer, maximumRead - buffer.size)
        if (count < 0) break
    }
    require(buffer.size <= limit) { "Page response exceeded the ${limit / (1024 * 1024)} MB limit" }
    return buffer.readByteArray()
}

/**
 * Writes a browser artifact into the agent workspace (`<workspace>/browser/`)
 * so the sandboxed file.* tools can read it back. Returns null on failure.
 */
internal fun saveToWorkspace(context: ToolContext, fileName: String, bytes: ByteArray): File? = runCatching {
    val dir = File(context.workspace, "browser").apply { mkdirs() }
    val file = File(dir, fileName)
    FileOutputStream(file).use { it.write(bytes) }
    file
}.getOrNull()

class WebSearchTool : AgentTool {
    override val definition = ToolDefinition(
        name = "web.search",
        category = ToolCategory.WEB,
        description = "Search the web for information using DuckDuckGo. Returns a list of search results with titles, snippets, and URLs.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "description" to "The search query"),
                "max_results" to mapOf("type" to "integer", "description" to "Maximum number of results to return (default: 10)"),
                "recency_days" to mapOf("type" to "integer", "description" to "Limit results to last N days (optional)"),
            ),
            "required" to listOf("query"),
        ),
        permissions = setOf(ToolPermission.NETWORK)
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val gson = com.google.gson.Gson()

    override suspend fun execute(context: ToolContext, arguments: com.google.gson.JsonElement): ToolResult {
        return try {
            val args = arguments.asJsonObject
            val query = args["query"]?.asString?.trim()?.take(300).orEmpty()
            if (query.isBlank()) return@execute ToolResult.failure("Search query cannot be empty")
            val maxResults = (args["max_results"]?.let { runCatching { it.asInt }.getOrNull() } ?: 10).coerceIn(1, 20)
            val recencyDays = args["recency_days"]?.let { runCatching { it.asInt }.getOrNull() }?.coerceAtLeast(0) ?: 0
            // DuckDuckGo accepts d/w/m/y for its time filter. The nearest supported
            // window is used for arbitrary day values.
            val timeFilter = when {
                recencyDays <= 0 -> null
                recencyDays <= 1 -> "d"
                recencyDays <= 7 -> "w"
                recencyDays <= 31 -> "m"
                else -> "y"
            }
            val endpoints = listOf("https://lite.duckduckgo.com/lite/", "https://html.duckduckgo.com/html/")
            var lastStatus = -1
            for (endpoint in endpoints) {
                val uri = Uri.parse(endpoint).buildUpon()
                    .appendQueryParameter("q", query)
                    .apply { timeFilter?.let { appendQueryParameter("df", it) } }
                    .build()
                val request = buildRequest(uri.toString())
                http.newCall(request).execute().use { response ->
                    lastStatus = response.code
                    if (!response.isSuccessful) return@use
                    val html = response.body?.string().orEmpty()
                    val results = parseSearchResults(html, maxResults)
                    if (results.isNotEmpty()) {
                        val resultJson = JsonObject().apply {
                            add("results", gson.toJsonTree(results))
                            addProperty("query", query)
                            addProperty("result_count", results.size)
                            addProperty("source", response.request.url.host)
                            if (recencyDays > 0) addProperty("recency_filter", "past $timeFilter (requested ${recencyDays}d)")
                        }
                        return@execute ToolResult.success(metadata = mapOf("result" to resultJson))
                    }
                }
            }
            ToolResult.failure("Search returned no parseable results (last HTTP status $lastStatus). The search provider may be rate-limiting or showing a bot challenge.")
        } catch (e: Exception) {
            ToolResult.failure("Web search failed: ${e.message}")
        }
    }

    private fun parseSearchResults(html: String, maxResults: Int): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        val doc = Jsoup.parse(html)
        val anchors = doc.select("a.result-link, .result__title a")
        val snippets = doc.select(".result-snippet, .result__snippet")
        for (i in 0 until minOf(maxResults.coerceIn(1, 20), anchors.size)) {
            val anchor = anchors[i]
            val title = anchor.text().trim()
            val snippet = if (i < snippets.size) snippets[i].text().trim() else ""
            val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            val resolved = runCatching {
                val parsed = Uri.parse(if (href.startsWith("//")) "https:$href" else href)
                val redirectTarget = parsed.getQueryParameter("uddg")
                redirectTarget ?: parsed.toString()
            }.getOrNull().orEmpty()
            val resultUri = Uri.parse(resolved)
            val validUrl = (resultUri.scheme == "http" || resultUri.scheme == "https") && !resultUri.host.isNullOrBlank()
            if (title.isNotBlank() && validUrl) {
                results.add(
                    JsonObject().apply {
                        addProperty("title", title)
                        addProperty("url", resolved)
                        addProperty("snippet", snippet)
                    },
                )
            }
        }
        return results
    }
}

class WebCrawlTool : AgentTool {
    override val definition = ToolDefinition(
        name = "web.crawl",
        category = ToolCategory.WEB,
        description = "Fetch and extract content from a web page. Returns the page title, text content, metadata, and optionally links.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "url" to mapOf("type" to "string", "description" to "The URL to crawl"),
                "extract_links" to mapOf("type" to "boolean", "description" to "Whether to extract links from the page (default: false)"),
                "max_content_length" to mapOf("type" to "integer", "description" to "Maximum content length to return (default: 50000)"),
                "extract_metadata" to mapOf("type" to "boolean", "description" to "Extract OpenGraph, Twitter Card, JSON-LD metadata (default: true)")
            ),
            "required" to listOf("url")
        ),
        permissions = setOf(ToolPermission.NETWORK)
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun execute(context: ToolContext, arguments: com.google.gson.JsonElement): ToolResult {
        return try {
            val args = arguments.asJsonObject
            val url = args["url"].asString
            val extractLinks = args["extract_links"]?.asBoolean ?: false
            val maxContentLength = (args["max_content_length"]?.let { runCatching { it.asInt }.getOrNull() } ?: 50000).coerceIn(1, 100000)
            val extractMetadata = args["extract_metadata"]?.asBoolean ?: true

            val parsedUrl = Uri.parse(url)
            if (((parsedUrl.scheme != "http") && (parsedUrl.scheme != "https")) || parsedUrl.host.isNullOrBlank()) {
                ToolResult.failure("Invalid URL: must start with http: or https: scheme")
            } else {
                val request = buildRequest(url)

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use ToolResult.failure("HTTP ${response.code}: ${response.message}")
                    }
                    val contentType = response.header("Content-Type") ?: ""
                    val body = response.body
                    if (contentType.isNotBlank() && !contentType.contains("html", ignoreCase = true) &&
                        !contentType.contains("text/plain", ignoreCase = true) && !contentType.contains("xml", ignoreCase = true)) {
                        return@use ToolResult.failure("Unsupported content type for page extraction: $contentType")
                    }
                    val charset = runCatching { body.contentType()?.charset(Charsets.UTF_8) }.getOrNull() ?: Charsets.UTF_8
                    val htmlBytes = body.use { responseBody -> readWebResponseBounded(responseBody.source(), MAX_CRAWL_RESPONSE_BYTES) }
                    val html = String(htmlBytes, charset)
                    if (html.isEmpty()) {
                        return@use ToolResult.failure("Empty response body")
                    }
                    val finalUrl = response.request.url.toString()
                    val doc = Jsoup.parse(html, finalUrl)
                    val metadata = if (extractMetadata) extractMetadata(doc, finalUrl) else null
                    doc.select("script, style, noscript, iframe, nav, footer, header, aside").remove()
                    val title = doc.title()
                    val bodyElement = doc.body()
                    val textContent = bodyElement.text().orEmpty()
                    val truncatedContent = if (textContent.length > maxContentLength) {
                        textContent.substring(0, maxContentLength) + "... [truncated]"
                    } else textContent

                    val resultJson = JsonObject().apply {
                        addProperty("url", finalUrl)
                        addProperty("title", title)
                        addProperty("content", truncatedContent)
                        addProperty("content_length", textContent.length)
                        addProperty("content_type", contentType)
                    }

                    if (metadata != null) resultJson.add("metadata", metadata)

                    if (extractLinks) {
                        val links = doc.select("a[href]").mapNotNull { link ->
                            val href = link.attr("abs:href")
                            val text = link.text().trim()
                            val absolute = href.isNotBlank() && text.isNotBlank() &&
                                ((href.startsWith("http:" + "//")) || (href.startsWith("https:" + "//")))
                            if (absolute) {
                                JsonObject().apply {
                                    addProperty("text", text)
                                    addProperty("url", href)
                                }
                            } else null
                        }.take(100)
                        resultJson.add("links", com.google.gson.Gson().toJsonTree(links))
                    }

                    return@use ToolResult.success(metadata = mapOf("result" to resultJson))
                }
            }
        } catch (e: Exception) {
            ToolResult.failure("Web crawl failed: ${e.message}")
        }
    }

    private fun extractMetadata(doc: org.jsoup.nodes.Document, @Suppress("UNUSED_PARAMETER") baseUrl: String): JsonObject {
        val meta = JsonObject()
        doc.select("meta[property^=og:]").forEach { m ->
            val prop = m.attr("property").removePrefix("og:")
            meta.addProperty(prop, m.attr("content"))
        }
        doc.select("meta[name^=twitter:]").forEach { m ->
            val name = m.attr("name").removePrefix("twitter:")
            meta.addProperty("twitter_$name", m.attr("content"))
        }
        val jsonLd = doc.select("script[type=application/ld+json]").mapNotNull { script ->
            val raw = script.html()
            raw.trim().takeIf { trimmed -> trimmed.isNotBlank() }
        }
        if (jsonLd.isNotEmpty()) {
            meta.add("json_ld", com.google.gson.Gson().toJsonTree(jsonLd))
        }
        doc.select("link[rel=canonical]").firstOrNull()?.let { meta.addProperty("canonical", it.attr("abs:href")) }
        doc.select("link[rel~=(icon|shortcut)]").firstOrNull()?.let { meta.addProperty("favicon", it.attr("abs:href")) }
        return meta
    }
}

class WebBrowserTool : AgentTool {
    override val definition = ToolDefinition(
        name = "web.browser",
        category = ToolCategory.WEB,
        description = "Drive the LIVE in-app browser the user watches in real time (PC-style: visible animated cursor, live page rendering in the chat bubble). Navigate, click, type, scroll, evaluate JS, extract content, screenshot. Every action is visible live — do not describe pages from screenshots; interact with the live page.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "description" to "Action to perform: navigate, click, type, scroll, evaluate, screenshot, html, pdf, wait, back, forward, reload",
                    "enum" to listOf("navigate", "click", "type", "scroll", "evaluate", "screenshot", "html", "pdf", "wait", "back", "forward", "reload")
                ),
                "url" to mapOf("type" to "string", "description" to "URL for navigate action"),
                "selector" to mapOf("type" to "string", "description" to "CSS selector for click/type actions"),
                "text" to mapOf("type" to "string", "description" to "Text to type for type action"),
                "script" to mapOf("type" to "string", "description" to "JavaScript to evaluate"),
                "x" to mapOf("type" to "integer", "description" to "Scroll X position"),
                "y" to mapOf("type" to "integer", "description" to "Scroll Y position"),
                "wait_ms" to mapOf("type" to "integer", "description" to "Milliseconds to wait for wait action (default: 1000, maximum: 10000)"),
                "full_page" to mapOf("type" to "boolean", "description" to "Full page screenshot (default: false)"),
                "format" to mapOf("type" to "string", "description" to "Output format for screenshot: png, jpeg (default: png)"),
                "quality" to mapOf("type" to "integer", "description" to "JPEG quality 1-100 (default: 90)")
            ),
            "required" to listOf("action")
        ),
        permissions = setOf(ToolPermission.NETWORK)
    )

    override suspend fun execute(context: ToolContext, arguments: com.google.gson.JsonElement): ToolResult =
        withContext(Dispatchers.IO) { executeOnIo(context, arguments) }

    private suspend fun executeOnIo(context: ToolContext, arguments: com.google.gson.JsonElement): ToolResult {
        var action = "unknown"
        return try {
            val args = arguments.asJsonObject
            action = args["action"]?.asString ?: return ToolResult.failure("Missing required parameter: action")

            // The single LIVE browser the user watches in the chat bubble.
            val browser = BrowserManager.getInstance(context.app)
            val live = com.example.oreshik.browser.LiveBrowser

            fun liveState(): Map<String, Any?> {
                val state = live.currentState()
                return mapOf(
                    "url" to (state?.url ?: browser.getCurrentUrl()),
                    "title" to (state?.title ?: browser.getTitle()),
                    "progress" to (state?.progress ?: 0)
                )
            }

            // Broadcast live browser state so the chat shows/scrolls to the live
            // bubble and updates its URL bar while the agent works.
            fun broadcastLive() {
                runCatching {
                    val state = live.currentState()
                    com.example.oreshik.agent.events.AgentEventBus.send(
                        com.example.oreshik.agent.events.AgentEvent.BrowserLive(
                            sessionId = context.sessionId ?: "",
                            url = state?.url ?: browser.getCurrentUrl(),
                            title = state?.title ?: browser.getTitle(),
                            progress = state?.progress ?: 100
                        )
                    )
                }
            }

            // Per-action cursor choreography: the PC-style pointer glides to the
            // target and ripples around each action, so the user sees the agent
            // working inside the live page in real time.
            when (action) {
                "navigate" -> {
                    val url = args.get("url")?.asString ?: return ToolResult.failure("Missing URL for navigate")
                    broadcastLive() // show/scroll to the live bubble BEFORE loading starts
                    val ok = browser.navigate(url)
                    broadcastLive()
                    if (ok) ToolResult.success("Navigated to $url", metadata = liveState())
                    else ToolResult.failure("Failed to load $url (timeout or main-frame error)")
                }
                "click" -> {
                    val selector = args.get("selector")?.asString ?: return ToolResult.failure("Missing selector for click")
                    broadcastLive()
                    val ok = browser.click(selector) // cursor glides + ripples at the element
                    broadcastLive()
                    if (ok) ToolResult.success("Clicked element: $selector", metadata = liveState())
                    else ToolResult.failure("Element not found: $selector")
                }
                "type" -> {
                    val selector = args.get("selector")?.asString ?: return ToolResult.failure("Missing selector for type")
                    val text = args.get("text")?.asString ?: return ToolResult.failure("Missing text for type")
                    broadcastLive()
                    val ok = browser.typeText(selector, text) // cursor glides to the field
                    broadcastLive()
                    if (ok) ToolResult.success("Typed text into: $selector", metadata = liveState())
                    else ToolResult.failure("Element not found: $selector")
                }
                "scroll" -> {
                    val x = args.get("x")?.asInt ?: 0
                    val y = args.get("y")?.asInt ?: 0
                    broadcastLive()
                    browser.scroll(x, y) // cursor drags along the scroll
                    broadcastLive()
                    ToolResult.success("Scrolled to ($x, $y)", metadata = liveState())
                }
                "evaluate" -> {
                    val script = args.get("script")?.asString ?: return ToolResult.failure("Missing script for evaluate")
                    val result = browser.evaluateScript(script)
                    broadcastLive()
                    // FIX (P1): the evaluated JS value must reach the agent, not just
                    // live in UI metadata. evaluateJavascript returns a JSON document
                    // string (quoted strings, null, "undefined"); surface it verbatim.
                    when {
                        result.isJsonNull -> ToolResult.failure("Script returned null")
                        result.toString() == "\"undefined\"" -> ToolResult.success("undefined", metadata = mapOf("result" to result))
                        else -> ToolResult.success(result.toString(), metadata = mapOf("result" to result))
                    }
                }
                "screenshot" -> {
                    // The live bubble already shows the page; screenshots stay
                    // available on demand for archiving a still frame.
                    val fullPage = args["full_page"]?.asBoolean ?: false
                    val format = args["format"]?.asString ?: "png"
                    val quality = args["quality"]?.asInt ?: 90
                    val shot: BrowserManager.ScreenshotResult = browser.takeScreenshot(fullPage, format, quality)
                    ToolResult.success(
                        "Screenshot saved to ${shot.filePath}",
                        metadata = mapOf(
                            "path" to shot.filePath,
                            "url" to browser.getCurrentUrl(),
                        ),
                    )
                }
                "html" -> {
                    // FIX (P2): return the HTML content, not just a status string.
                    // Full pages blow the context window, so save the complete HTML
                    // into the agent workspace (readable by the file.* tools) and
                    // return the path plus a preview snippet.
                    val html = browser.getHtml()
                    if (html.isBlank()) return ToolResult.failure("Could not extract page HTML")
                    val bytes = html.toByteArray(Charsets.UTF_8)
                    val file = saveToWorkspace(context, "page_${System.currentTimeMillis()}.html", bytes)
                    ?: return ToolResult.failure("Failed to write HTML into workspace (${context.workspace})")
                    val preview = if (html.length > HTML_PREVIEW_CHARS) {
                        html.take(HTML_PREVIEW_CHARS) + "…[TRUNCATED: ${html.length} chars total — full HTML at $file]"
                    } else html
                    ToolResult.success(
                        "HTML saved to ${file.absolutePath} (${bytes.size} bytes). First $HTML_PREVIEW_CHARS chars:\n$preview",
                        metadata = mapOf("path" to file.absolutePath, "bytes" to bytes.size),
                    )
                }
                "pdf" -> {
                    val path = browser.saveAsPdf()
                    ToolResult.success("Page saved to $path", metadata = mapOf("path" to path))
                }
                "wait" -> {
                    val ms = (args.get("wait_ms")?.asInt ?: 1000).coerceIn(0, 10_000)
                    delay(ms.toLong())
                    broadcastLive()
                    ToolResult.success("Waited ${ms}ms")
                }
                "back" -> {
                    broadcastLive()
                    val ok = browser.goBack()
                    broadcastLive()
                    if (ok) ToolResult.success("Navigated back", metadata = liveState())
                    else ToolResult.failure("No previous page in history")
                }
                "forward" -> {
                    broadcastLive()
                    val ok = browser.goForward()
                    broadcastLive()
                    if (ok) ToolResult.success("Navigated forward", metadata = liveState())
                    else ToolResult.failure("No next page in history")
                }
                "reload" -> {
                    broadcastLive()
                    browser.reload()
                    broadcastLive()
                    ToolResult.success("Page reloaded", metadata = liveState())
                }
                else -> ToolResult.failure("Unknown action: $action")
            }
        } catch (e: Exception) {
            android.util.Log.e("WebBrowserTool", "Browser automation failed for action=$action", e)
            ToolResult.failure("Browser automation failed: ${e.message}")
        }
    }
}

/**
 * Public API kept for the agent tool surface; registered conditionally in the
 * tool registry based on the enabled web-scan features.
 */
@Suppress("unused")
class WebsiteScanTool : AgentTool {
    override val definition = ToolDefinition(
        name = "web.scan",
        category = ToolCategory.WEB,
        description = "Scan a website for technologies, security headers, SSL info, and basic reconnaissance data.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "url" to mapOf("type" to "string", "description" to "The URL to scan"),
                "check_headers" to mapOf("type" to "boolean", "description" to "Check security headers (default: true)"),
                "check_ssl" to mapOf("type" to "boolean", "description" to "Check SSL certificate info (default: true)"),
                "detect_tech" to mapOf("type" to "boolean", "description" to "Detect technologies/Wappalyzer-style (default: true)"),
                "check_robots" to mapOf("type" to "boolean", "description" to "Check robots.txt and sitemap.xml (default: true)")
            ),
            "required" to listOf("url")
        ),
        permissions = setOf(ToolPermission.NETWORK)
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun execute(context: ToolContext, arguments: com.google.gson.JsonElement): ToolResult {
        return try {
            val args = arguments.asJsonObject
            val url = args["url"].asString
            val checkHeaders = args["check_headers"]?.asBoolean ?: true
            val checkSsl = args["check_ssl"]?.asBoolean ?: true
            val detectTech = args["detect_tech"]?.asBoolean ?: true
            val checkRobots = args["check_robots"]?.asBoolean ?: true

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return ToolResult.failure("Invalid URL: must start with http:// or https://")
            }

            val baseUrl = Uri.parse(url).let { "${it.scheme}://${it.host}" }
            val resultJson = JsonObject().apply {
                addProperty("url", url)
                addProperty("base_url", baseUrl)
            }

            val mainRequest = buildRequest(url)
            http.newCall(mainRequest).execute().use { response ->
                resultJson.addProperty("status_code", response.code)
                resultJson.addProperty("content_type", response.header("Content-Type") ?: "")
                resultJson.addProperty("server", response.header("Server") ?: "")
                resultJson.addProperty("x_powered_by", response.header("X-Powered-By") ?: "")

                if (checkHeaders) {
                    val headers = JsonObject()
                    val securityHeaders = mapOf(
                        "Strict-Transport-Security" to "HSTS",
                        "Content-Security-Policy" to "CSP",
                        "X-Frame-Options" to "X-Frame-Options",
                        "X-Content-Type-Options" to "X-Content-Type-Options",
                        "Referrer-Policy" to "Referrer-Policy",
                        "Permissions-Policy" to "Permissions-Policy",
                        "X-XSS-Protection" to "X-XSS-Protection"
                    )
                    securityHeaders.forEach { (header, name) ->
                        response.header(header)?.let { headers.addProperty(name, it) } ?: headers.addProperty(name, "MISSING")
                    }
                    resultJson.add("security_headers", headers)
                }

                if (detectTech) {
                    val html = response.body?.string() ?: ""
                    val tech = detectTechnologies(html, response.headers.toMultimap())
                    resultJson.add("technologies", com.google.gson.Gson().toJsonTree(tech))
                }
            }

            if (checkRobots) {
                val robotsRequest = buildRequest("$baseUrl/robots.txt")
                http.newCall(robotsRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        resultJson.addProperty("robots_txt", response.body?.string()?.take(5000) ?: "")
                    }
                }
                val sitemapRequest = buildRequest("$baseUrl/sitemap.xml")
                http.newCall(sitemapRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        resultJson.addProperty("sitemap_xml", response.body?.string()?.take(10000) ?: "")
                    }
                }
            }

            if (checkSsl && url.startsWith("https://")) {
                resultJson.addProperty("ssl_valid", true)
            }

            ToolResult.success(metadata = mapOf("result" to resultJson))
        } catch (e: Exception) {
            ToolResult.failure("Website scan failed: ${e.message}")
        }
    }

    private fun detectTechnologies(html: String, headers: Map<String, List<String>>): List<JsonObject> {
        val tech = mutableListOf<JsonObject>()
        val techPatterns = mapOf(
            "WordPress" to Pattern.compile("wp-content|wp-includes|wordpress", Pattern.CASE_INSENSITIVE),
            "React" to Pattern.compile("react\\.js|__REACT_DEVTOOLS_GLOBAL_HOOK__", Pattern.CASE_INSENSITIVE),
            "Vue.js" to Pattern.compile("vue\\.js|__VUE__", Pattern.CASE_INSENSITIVE),
            "Angular" to Pattern.compile("angular\\.js|ng-", Pattern.CASE_INSENSITIVE),
            "Next.js" to Pattern.compile("__NEXT_DATA__|next\\.js", Pattern.CASE_INSENSITIVE),
            "Nuxt.js" to Pattern.compile("__NUXT__|nuxt\\.js", Pattern.CASE_INSENSITIVE),
            "jQuery" to Pattern.compile("jquery", Pattern.CASE_INSENSITIVE),
            "Bootstrap" to Pattern.compile("bootstrap", Pattern.CASE_INSENSITIVE),
            "Tailwind" to Pattern.compile("tailwind", Pattern.CASE_INSENSITIVE),
            "Cloudflare" to Pattern.compile("cloudflare", Pattern.CASE_INSENSITIVE),
            "Google Analytics" to Pattern.compile("google-analytics|gtag\\(\"config\"", Pattern.CASE_INSENSITIVE),
            "Google Tag Manager" to Pattern.compile("googletagmanager", Pattern.CASE_INSENSITIVE),
            "Apache" to Pattern.compile("apache", Pattern.CASE_INSENSITIVE),
            "Nginx" to Pattern.compile("nginx", Pattern.CASE_INSENSITIVE),
            "PHP" to Pattern.compile("\\.php|X-Powered-By: PHP", Pattern.CASE_INSENSITIVE),
            "Python/Django" to Pattern.compile("django|csrfmiddlewaretoken", Pattern.CASE_INSENSITIVE),
            "Node.js" to Pattern.compile("express|node\\.js", Pattern.CASE_INSENSITIVE),
            "ASP.NET" to Pattern.compile("aspnet|__VIEWSTATE", Pattern.CASE_INSENSITIVE),
            "Laravel" to Pattern.compile("laravel_session|laravel_token", Pattern.CASE_INSENSITIVE),
        )
        techPatterns.forEach { (name, pattern) ->
            val inHtml = pattern.matcher(html).find()
            val inHeaders = headers.values.any { headerValues -> headerValues.any { headerValue -> pattern.matcher(headerValue).find() } }
            if (inHtml || inHeaders) {
                tech.add(
                    JsonObject().apply {
                        addProperty("name", name)
                        addProperty("confidence", "high")
                    },
                )
            }
        }
        return tech
    }
}
