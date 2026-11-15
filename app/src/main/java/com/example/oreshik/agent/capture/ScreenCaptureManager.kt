package com.example.oreshik.agent.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.example.oreshik.agent.accessibility.AgentAccessibilityService
import com.example.oreshik.agent.security.RootManager
import com.example.oreshik.logging.AppLogger
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Unified screen capture for the Oreshik Agent with strategy fallback.
 *
 * Why this exists: MIUI/HyperOS builds deny the accessibility screenshot API
 * with an undocumented errorCode=4 ("capture denied") even when the service is
 * bound with canTakeScreenshot and all appops are granted. On rooted devices
 * the vendor gate is bypassed with `screencap -p <tmp>` executed via su, then
 * the PNG bytes are read back with `su -c cat` as a RAW BYTE stream (the
 * app-process text pipeline has a 1 MB cap and must not be used for images).
 *
 * Strategy order:
 *  1. AccessibilityService.takeScreenshot (API 30+, no consent prompt)
 *  2. Root screencap (file-based PNG round-trip; verified on MIUI 13)
 *
 * Never throws. [lastReport] always reflects the most recent attempt for the
 * Settings status line.
 */
object ScreenCaptureManager {

    private const val TAG = "ScreenCapture"
    private const val MAX_ROOT_SCREENSHOT_BYTES = 32 * 1024 * 1024

    enum class Strategy(val label: String) {
        ACCESSIBILITY("accessibility API"),
        ROOT("root screencap")
    }

    data class CaptureResult(
        val bitmap: Bitmap?,
        val strategy: Strategy?,
        /** null on success, otherwise a human-readable failure reason. */
        val error: String? = null,
    )

    @Volatile var lastReport: String = "Screen capture: not tested yet"; private set

    /** True once the root path has produced a decoded bitmap this process. */
    @Volatile var rootPathVerified = false; private set

    /**
     * Captures the current screen trying every available strategy.
     * Call from any thread; internally serializes to the main thread only when
     * the accessibility path needs its executor.
     */
    fun capture(context: Context): CaptureResult {
        // 1) Accessibility API path (works on stock Android 11+, MIUI usually denies).
        val svc = AgentAccessibilityService.instance
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bmp = svc.takeScreenshotViaAccessibility()
            if (bmp != null) {
                lastReport = "PASS • strategy=accessibility • ${bmp.width}x${bmp.height}"
                return CaptureResult(bmp, Strategy.ACCESSIBILITY)
            }
        }

        // 2) Root screencap path.
        val root = RootManager(context)
        if (!root.canUseRoot()) {
            lastReport = "FAIL • accessibility capture denied (MIUI errorCode=4) and root unavailable — enable 'Allow root (su) operations'"
            AppLogger.w(TAG, lastReport)
            return CaptureResult(null, null, lastReport)
        }
        val bmp2 = rootCapture(root)
        if (bmp2 != null) {
            rootPathVerified = true
            lastReport = "PASS • strategy=root screencap • ${bmp2.width}x${bmp2.height}"
            return CaptureResult(bmp2, Strategy.ROOT)
        }
        val err = "FAIL • both capture strategies unavailable (a11y denied + root screencap failed)"
        lastReport = err
        AppLogger.w(TAG, err)
        return CaptureResult(null, null, err)
    }

    /**
     * Root capture: `screencap -p <tmp>` then read the PNG back through
     * `su -c cat` as raw bytes. The file round-trip avoids both the 1 MB
     * text cap of [RootManager.execute] and device-specific raw-frame formats.
     */
    fun rootCapture(root: RootManager): Bitmap? {
        val path = "/data/local/tmp/oreshik_screen_${System.currentTimeMillis()}.png"
        return try {
            val (code, out) = root.execute("screencap -p $path", 20)
            if (code != 0) {
                AppLogger.w(TAG, "root screencap exited $code: ${out.take(120)}")
                return null
            }
            val bytes = readViaSu(root, path)
            runCatching { root.execute("rm -f $path", 5) }
            if (bytes == null) {
                AppLogger.w(TAG, "root capture: could not read $path back")
                return null
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp == null) {
                AppLogger.w(TAG, "root capture: PNG decode failed (${bytes.size} bytes)")
                null
            } else {
                AppLogger.i(TAG, "root capture OK: ${bmp.width}x${bmp.height} (${bytes.size} bytes)")
                bmp
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "root capture failed: ${e.message}")
            runCatching { root.execute("rm -f $path", 5) }
            null
        }
    }

    /** Reads [path] as raw bytes via su; null when the read failed. */
    private fun readViaSu(root: RootManager, path: String): ByteArray? = try {
        val quotedPath = "'" + path.replace("'", "'\\''") + "'"
        val p = ProcessBuilder(root.status().suPath, "-c", "cat $quotedPath")
            .redirectErrorStream(false)
            .start()
        val executor = Executors.newSingleThreadExecutor()
        val bytesFuture = executor.submit<ByteArray?> {
            p.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_ROOT_SCREENSHOT_BYTES) return@submit null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        try {
            val finished = p.waitFor(10, TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            val bytes = if (finished) {
                runCatching { bytesFuture.get(1, TimeUnit.SECONDS) }.getOrNull()
            } else {
                null
            }
            bytes?.takeIf { it.isNotEmpty() }
        } finally {
            if (p.isAlive) p.destroyForcibly()
            runCatching { p.inputStream.close() }
            bytesFuture.cancel(true)
            executor.shutdownNow()
        }
    } catch (e: Exception) {
        AppLogger.w(TAG, "readViaSu failed: ${e.message}")
        null
    }

    /** One-line capability summary for Settings. */
    fun statusLine(context: Context): String = buildString {
        append(lastReport)
        val a11y = AgentAccessibilityService.instance
        append("\nAccessibility service: ")
        append(if (a11y != null) "connected" else "NOT connected")
        append(" • Root: ")
        append(if (RootManager(context).canUseRoot()) "available" else "off")
    }
}
