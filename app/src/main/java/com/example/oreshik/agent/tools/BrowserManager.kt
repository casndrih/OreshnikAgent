package com.example.oreshik.agent.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.example.oreshik.browser.LiveBrowser
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Thin wrapper that exposes the single LIVE in-app browser ([LiveBrowser]) to
 * agent tools.
 *
 * Historically this managed a separate HEADLESS WebView — the user only ever
 * saw static screenshots after each action. Now the agent automates the very
 * browser the user watches in the chat bubble, PC-style: one session, one
 * cookie jar, live rendering with an animated cursor.
 *
 * All WebView access is funneled through [LiveBrowser] (main-thread posted),
 * so these methods are safe to call from any thread.
 */
class BrowserManager private constructor(private val context: Context) {

    // The singleton stores ONLY the application context (see getInstance), so the
    // static reference never pins an Activity; kept for session-lifetime caching.
    @SuppressLint("StaticFieldLeak")
    companion object {
        private const val TAG = "Browser"
        @SuppressLint("StaticFieldLeak")
        @Volatile private var INSTANCE: BrowserManager? = null
        private val LOCK = Any()
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())

        // Fallback viewport when the live WebView is not attached to a bubble yet.
        private const val VIEWPORT_WIDTH = 1080
        private const val VIEWPORT_HEIGHT = 1920
        private const val MAX_FULL_PAGE_EDGE = 16000
        private const val MAX_FULL_PAGE_PIXELS = 12_000_000L
        private const val PDF_PAGE_WIDTH = 595
        private const val PDF_PAGE_HEIGHT = 842

        fun getInstance(context: Context): BrowserManager {
            INSTANCE?.let { return it }
            val appContext = context.applicationContext
            fun initializeOnMain(): BrowserManager = synchronized(LOCK) {
                INSTANCE ?: BrowserManager(appContext).also { manager ->
                    // Initialize WebView on its required UI thread before publishing the manager.
                    LiveBrowser.contextOnce = appContext
                    LiveBrowser.acquire(appContext)
                    INSTANCE = manager
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) return initializeOnMain()

            val future = CompletableFuture<BrowserManager>()
            if (!MAIN_HANDLER.post {
                    runCatching { initializeOnMain() }
                        .onSuccess { manager -> future.complete(manager) }
                        .onFailure { error -> future.completeExceptionally(error) }
                }
            ) {
                throw IllegalStateException("Main thread is unavailable for browser initialization")
            }
            return try {
                future[10, TimeUnit.SECONDS]
            } catch (e: Exception) {
                throw RuntimeException("Failed to initialize BrowserManager on main thread", e)
            }
        }

        /** Kept for API compatibility: the live browser persists for the session. */
        @Suppress("unused")
        fun destroyInstance() {
            synchronized(LOCK) { INSTANCE = null }
        }
    }

    fun navigate(url: String): Boolean {
        val ok = LiveBrowser.navigate(url)
        AppLogger.d(TAG, "navigate $url → $ok")
        return ok
    }

    fun click(selector: String): Boolean = LiveBrowser.click(selector)

    fun typeText(selector: String, text: String): Boolean = LiveBrowser.typeText(selector, text)

    fun scroll(x: Int, y: Int): Boolean = LiveBrowser.scroll(x, y)

    fun evaluateScript(script: String): JsonElement = LiveBrowser.evaluateScript(script)

    fun getHtml(): String = LiveBrowser.getHtml()

    fun goBack(): Boolean {
        val can = LiveBrowser.currentState()?.canGoBack == true
        if (can) LiveBrowser.goBack()
        return can
    }

    fun goForward(): Boolean {
        val can = LiveBrowser.currentState()?.canGoForward == true
        if (can) LiveBrowser.goForward()
        return can
    }

    fun reload(): Boolean {
        LiveBrowser.reload()
        return true
    }

    fun getCurrentUrl(): String = LiveBrowser.getCurrentUrl()

    fun getTitle(): String = LiveBrowser.getTitle()

    /**
     * Capture the LIVE page. The WebView is attached to a real window (when a
     * chat bubble hosts it), so it draws normally; when no bubble exists yet we
     * measure/layout it manually first.
     */
    fun takeScreenshot(fullPage: Boolean, format: String, quality: Int): ScreenshotResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Screenshot capture must run off the main thread" }
        val bitmap = captureBitmap(fullPage)
        var preview: Bitmap? = null
        try {
            val dir = getOutputDir()
            check(dir.exists() || dir.mkdirs()) { "Could not create browser output directory" }
            val ext = if ((format.lowercase() == "jpeg") || (format.lowercase() == "jpg")) "jpg" else "png"
            val file = File(dir, "screenshot_${System.currentTimeMillis()}.$ext")
            val compressFormat = if (ext == "jpg") CompressFormat.JPEG else CompressFormat.PNG
            FileOutputStream(file).use { out ->
                check(bitmap.compress(compressFormat, quality.coerceIn(1, 100), out)) { "Could not encode screenshot" }
            }

            preview = scaleToPreview(bitmap)
            val previewFile = File(dir, "preview_${System.currentTimeMillis()}.png")
            FileOutputStream(previewFile).use { out ->
                check(preview.compress(CompressFormat.PNG, 90, out)) { "Could not encode screenshot preview" }
            }
            return ScreenshotResult(file.absolutePath, previewFile.absolutePath)
        } finally {
            if (preview != null && preview !== bitmap && !preview.isRecycled) preview.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Small preview capture used by tools; empty string on failure. */
    @Suppress("unused")
    fun getPreviewFilePath(): String {
        return try {
            takeScreenshot(fullPage = false, format = "png", quality = 90).previewFilePath
        } catch (e: Exception) {
            AppLogger.w(TAG, "Preview capture failed: ${e.message}")
            ""
        }
    }

    /** Save the live page as PDF next to other tool outputs. */
    fun saveAsPdf(): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "PDF export must run off the main thread" }
        val currentUrl = LiveBrowser.getCurrentUrl()
        require(currentUrl.startsWith("https://") || currentUrl.startsWith("http://")) {
            "Navigate to a web page before exporting a PDF"
        }
        val bitmap = captureBitmap(fullPage = true)
        val dir = getOutputDir()
        check(dir.exists() || dir.mkdirs()) { "Could not create browser output directory" }
        val file = File(dir, "page_${System.currentTimeMillis()}.pdf")
        val document = PdfDocument()
        try {
            val sourcePageHeight = (bitmap.width.toDouble() * PDF_PAGE_HEIGHT / PDF_PAGE_WIDTH).roundToInt().coerceAtLeast(1)
            val pageCount = (bitmap.height + sourcePageHeight - 1) / sourcePageHeight
            for (pageNumber in 1..pageCount) {
                val sourceTop = (pageNumber - 1) * sourcePageHeight
                val sourceBottom = minOf(bitmap.height, sourceTop + sourcePageHeight)
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, pageNumber).create(),
                )
                page.canvas.drawColor(Color.WHITE)
                val destinationHeight = ((sourceBottom - sourceTop).toFloat() * PDF_PAGE_WIDTH / bitmap.width)
                    .roundToInt().coerceIn(1, PDF_PAGE_HEIGHT)
                page.canvas.drawBitmap(
                    bitmap,
                    Rect(0, sourceTop, bitmap.width, sourceBottom),
                    Rect(0, 0, PDF_PAGE_WIDTH, destinationHeight),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
                document.finishPage(page)
            }
            FileOutputStream(file).use(document::writeTo)
            return file.absolutePath
        } catch (error: Exception) {
            file.delete()
            throw IllegalStateException("PDF export failed: ${error.message}", error)
        } finally {
            document.close()
            bitmap.recycle()
        }
    }

    private fun captureBitmap(fullPage: Boolean): Bitmap {
        val webView = LiveBrowser.acquire(context)
        val pageSize = if (fullPage) getPageSize() else null
        val future = CompletableFuture<Bitmap>()
        val posted = MAIN_HANDLER.post {
            try {
                ensureLaidOut(webView)
                val bitmap = if (pageSize != null) {
                    captureFullPage(webView, pageSize.first, pageSize.second)
                } else {
                    captureViewport(webView)
                }
                if (!future.complete(bitmap)) bitmap.recycle()
            } catch (error: Exception) {
                future.completeExceptionally(error)
            }
        }
        check(posted) { "Main thread is unavailable for screenshot capture" }
        return try {
            future[30, TimeUnit.SECONDS]
        } catch (error: Exception) {
            future.cancel(false)
            AppLogger.e(TAG, "Screenshot capture failed: ${error.message}", error)
            throw IllegalStateException("Screenshot capture failed: ${error.message}", error)
        }
    }

    // ------------------------------------------------------------------
    // Capture helpers
    // ------------------------------------------------------------------

    /** A WebView that is not attached to a window never measures itself. */
    private fun ensureLaidOut(wv: android.webkit.WebView) {
        if (wv.width > 0 && wv.height > 0) return
        val widthSpec = View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY)
        wv.measure(widthSpec, heightSpec)
        wv.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
    }

    private fun captureViewport(wv: android.webkit.WebView): Bitmap {
        ensureLaidOut(wv)
        val w = wv.width.coerceAtLeast(1)
        val h = wv.height.coerceAtLeast(1)
        val bitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        wv.draw(canvas)
        return bitmap
    }

    private fun getPageSize(): Pair<Int, Int> {
        val dimensions = LiveBrowser.evaluateScript(
            "({width: Math.max(document.documentElement.scrollWidth, window.innerWidth), height: Math.max(document.documentElement.scrollHeight, window.innerHeight)})",
        ).requireJsonObject()
        val density = context.resources.displayMetrics.density
        val width = (dimensions.get("width")?.asInt ?: 0).let { (it * density).toInt() }
        val height = (dimensions.get("height")?.asInt ?: 0).let { (it * density).toInt() }
        val pixels = width.toLong() * height.toLong()
        require(width > 0 && height > 0 && width <= MAX_FULL_PAGE_EDGE && height <= MAX_FULL_PAGE_EDGE && pixels <= MAX_FULL_PAGE_PIXELS) {
            "Full-page screenshot is too large or page dimensions are unavailable (${width}x$height px)"
        }
        return width to height
    }

    private fun captureFullPage(wv: android.webkit.WebView, width: Int, height: Int): Bitmap {
        val originalWidth = wv.width.coerceAtLeast(1)
        val originalHeight = wv.height.coerceAtLeast(1)
        val originalLayoutParams = wv.layoutParams
        val originalLayoutParamsWidth = originalLayoutParams?.width
        val originalLayoutParamsHeight = originalLayoutParams?.height
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            originalLayoutParams?.let {
                it.width = width
                it.height = height
                wv.layoutParams = it
            }
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, width, height)
            wv.draw(Canvas(bitmap))
            return bitmap
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        } finally {
            if (originalLayoutParams != null) {
                originalLayoutParams.width = originalLayoutParamsWidth ?: ViewGroup.LayoutParams.MATCH_PARENT
                originalLayoutParams.height = originalLayoutParamsHeight ?: ViewGroup.LayoutParams.MATCH_PARENT
                wv.layoutParams = originalLayoutParams
            }
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(originalWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(originalHeight, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, originalWidth, originalHeight)
        }
    }

    private fun scaleToPreview(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        val scale = minOf(1f, 400f / bitmap.width, 4000f / bitmap.height)
        if (scale >= 1f) return bitmap
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return bitmap.scale(targetWidth, targetHeight)
    }

    private fun getOutputDir(): File {
        // FIX (P6): artifacts must live inside the agent workspace so the sandboxed
        // file.list/read/search tools can access them (previously they went to the
        // app-private Download dir, reachable only via python_exec).
        return runCatching {
            File(AgentConfig.workspaceRoot(context), "browser").apply { mkdirs() }
        }.getOrElse { getExternalOutputDir() }
    }

    /** Fallback when the workspace root is unavailable (keeps old behavior). */
    private fun getExternalOutputDir(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "browser")
        dir.mkdirs()
        return dir
    }

    private fun JsonElement.requireJsonObject(): com.google.gson.JsonObject =
        (this as? com.google.gson.JsonObject) ?: throw IllegalStateException("Could not read page dimensions")

    /**
     * Result of a screenshot operation with file path and preview file path.
     */
    data class ScreenshotResult(
        val filePath: String,
        val previewFilePath: String,
    )
}
