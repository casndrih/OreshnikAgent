package com.example.oreshik.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.annotation.RequiresApi
import com.example.oreshik.logging.AppLogger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// Lint: this service exists to automate the user's own device at their explicit
// request (they enable it in system settings); it is the app's core purpose.
@SuppressLint("AccessibilityPolicy")
class AgentAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: AgentAccessibilityService? = null

        /** True while the system has this accessibility service bound. */
        @JvmStatic fun isBound(): Boolean = instance != null
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            serviceInfo = serviceInfo.apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                notificationTimeout = 100
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                // Note: screenshot capability comes from the XML attribute
                // android:canTakeScreenshot (API 30+); it cannot be set here —
                // capability constants overlap flag bits and would corrupt flags.
            }
        }
        instance = this
        // Initialize the PC-style cursor overlay (needs the service's overlay permission)
        runCatching { AgentCursorOverlay.init(this) }
        // MIUI bypass: start the keep-alive foreground service (METHOD 4) and apply
        // the root battery/PowerKeeper fixes once per connection. Both are no-ops on
        // non-rooted devices or when the user keeps root disabled in Agent Settings.
        runCatching {
            AccessibilityKeepAliveService.start(this)
            kotlin.concurrent.thread(name = "acc-root-fix") {
                runCatching {
                    com.example.oreshik.agent.security.AccessibilityRootFixer.applyAll(applicationContext)
                }.onFailure {
                    AppLogger.w("AgentAccessibility", "Root fix pass failed", it)
                }
            }
        }.onFailure {
            AppLogger.w("AgentAccessibility", "Keep-alive start failed", it)
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* State is read on demand; avoid retaining AccessibilityNodeInfo instances. */ }
    override fun onInterrupt() {}
    override fun onDestroy() {
        if (instance === this) {
            instance = null
            AgentCursorOverlay.destroy()
        }
        super.onDestroy()
    }

    /** Package currently owning the active accessibility window, if one is available. */
    fun activePackageName(): String? = rootInActiveWindow?.packageName?.toString()

    // ------------------------------------------------------------------
    // Screen description (textual — used when the model has no vision)
    // ------------------------------------------------------------------

    fun describeScreen(maxNodes: Int = 180): String {
        val root = rootInActiveWindow ?: return "No active accessibility window."
        val out = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if ((node == null) || (out.length > 30000) || (count >= maxNodes)) return
            count++
            val cls = node.className?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isFocusable) {
                val b = Rect(); node.getBoundsInScreen(b)
                out.append("  ".repeat(depth.coerceAtMost(8))).append(cls.substringAfterLast('.'))
                    .append(if (text.isNotBlank()) " text=\"${text.replace('"',' ')}\"" else "")
                    .append(if (desc.isNotBlank()) " desc=\"${desc.replace('"',' ')}\"" else "")
                    .append(" [${b.centerX()},${b.centerY()}]")
                    .append(if (node.isClickable) " clickable" else "")
                    .append(if (node.isFocusable) " focusable" else "").append('\n')
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        val pkg = root.packageName?.toString().orEmpty()
        val header = if (pkg.isNotBlank()) "Foreground app: $pkg\n" else ""
        return (header + out.toString()).ifBlank { "Active window contains no readable nodes." }
    }

    // ------------------------------------------------------------------
    // Gestures (android:canPerformGestures="true" in service config)
    // ------------------------------------------------------------------

    private fun dispatchGestureAwaiting(gesture: GestureDescription, timeoutMs: Long): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppLogger.w("AgentAccessibility", "Gesture wait cannot block the main thread")
            return false
        }
        val done = CompletableFuture<Boolean>()
        val ok = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    done.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.complete(false)
                }
            },
            null,
        )
        if (!ok) {
            AppLogger.w("AgentAccessibility", "Gesture dispatch rejected by system")
            return false
        }
        return try {
            done.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            false
        }
    }

    fun tap(x: Float, y: Float): Boolean {
        if (!isOnScreen(x, y)) return false
        AgentCursorOverlay.animateTap(x, y)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGestureAwaiting(gesture, 3000)
    }

    fun longPress(x: Float, y: Float, durationMs: Long = 700): Boolean {
        if (!isOnScreen(x, y)) return false
        val boundedDuration = durationMs.coerceIn(300, 5000)
        AgentCursorOverlay.moveTo(x, y)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, boundedDuration))
            .build()
        return dispatchGestureAwaiting(gesture, boundedDuration + 3000)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean {
        if (!isOnScreen(x1, y1) || !isOnScreen(x2, y2)) return false
        val boundedDuration = durationMs.coerceIn(80, 5000)
        AgentCursorOverlay.animateSwipe(x1, y1, x2, y2, boundedDuration)
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, boundedDuration))
            .build()
        return dispatchGestureAwaiting(gesture, boundedDuration + 3000)
    }

    private fun isOnScreen(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f) return false
        val (width, height) = screenSize()
        return x < width.toFloat() && y < height.toFloat()
    }

    fun screenSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    // ------------------------------------------------------------------
    // Node-level actions
    // ------------------------------------------------------------------

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.any { clickNode(it) }
    }

    /** Click by text OR content description with exact-match preference. */
    fun clickElement(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (text.isBlank()) return false
        val matches = root.findAccessibilityNodeInfosByText(text)
        // Exact matches first (text or content description)…
        val exact = matches.filter { it.text?.toString() == text || it.contentDescription?.toString() == text }
        if (exact.any { clickNode(it) }) return true
        // …then the broader substring matches from the original query.
        // (The previous code re-filtered the already-exact list, so the
        // fallback could never fire on real screens.)
        if (matches.any { clickNode(it) }) return true
        // Last resort: case-insensitive scan of visible text.
        val lower = text.lowercase()
        val root2 = rootInActiveWindow ?: return false
        val relaxed = root2.findAccessibilityNodeInfosByText(text).filter {
            it.text?.toString()?.lowercase()?.contains(lower) == true ||
                it.contentDescription?.toString()?.lowercase()?.contains(lower) == true
        }
        return relaxed.any { clickNode(it) }
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false
        var node: AccessibilityNodeInfo? = target
        while (node != null) {
            if (node.isEditable) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
                if (ok) return true
            }
            node = node.parent
        }
        return false
    }

    /** Clicks the currently focused node (used by interactive flows). */
    @Suppress("unused")
    fun clickFocused(): Boolean = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let(::clickNode) == true || rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(::clickNode) == true
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // Show the cursor animating a click on the element's real screen position
        // (like a PC mouse click) — PC-style visual feedback for computer use.
        val b = Rect()
        node.getBoundsInScreen(b)
        if (b.width() > 0 && b.height() > 0) {
            AgentCursorOverlay.animateTap(b.centerX().toFloat(), b.centerY().toFloat())
        }
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
        }
        return false
    }
    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return findScrollable(root)?.performAction(action) == true
    }
    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) findScrollable(node.getChild(i))?.let { return it }
        return null
    }
    fun focus(direction: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(n: AccessibilityNodeInfo?) { if (n == null) return; if (n.isFocusable && n.isVisibleToUser) nodes += n; for (i in 0 until n.childCount) collect(n.getChild(i)) }
        collect(root)
        if (nodes.isEmpty()) return false
        val current = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val index = current?.let { nodes.indexOfFirst { n -> n == it } } ?: if (direction > 0) -1 else nodes.size
        val target = nodes.getOrNull((index + direction).coerceIn(0, nodes.lastIndex)) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }
    fun global(action: Int): Boolean = performGlobalAction(action)

    // ------------------------------------------------------------------
    // Screenshot (API 30+, no MediaProjection consent required)
    // ------------------------------------------------------------------

    /**
     * Capture the current screen via the full strategy chain (accessibility API
     * first, root screencap fallback). See the ScreenCaptureManager docs for why.
     */
    fun takeScreenshot(): Bitmap? =
        com.example.oreshik.agent.capture.ScreenCaptureManager.capture(this).bitmap

    /** Accessibility-API-only capture; used by ScreenCaptureManager as strategy 1. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun takeScreenshotViaAccessibility(): Bitmap? {
        val done = CompletableFuture<Bitmap?>()
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    if (hw != null) {
                        // Copy to a software bitmap so it can be compressed/scaled safely.
                        done.complete(hw.copy(Bitmap.Config.ARGB_8888, false))
                        hw.recycle()
                    } else {
                        AppLogger.w("AgentAccessibility", "Screenshot: wrapHardwareBuffer returned null")
                        done.complete(null)
                    }
                } catch (e: Exception) {
                    AppLogger.w("AgentAccessibility", "Screenshot: bitmap copy failed: ${e.message}")
                    done.complete(null)
                } finally {
                    runCatching { screenshot.hardwareBuffer.close() }
                }
            }
            override fun onFailure(errorCode: Int) {
                // ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR=1, NO_ACCESSIBILITY_ACCESS=2,
                // INVALID_DISPLAY=3 (per AccessibilityService docs).
                AppLogger.w("AgentAccessibility", "Screenshot FAILED with errorCode=$errorCode (isAccessibilityServiceBound=${instance != null})")
                done.complete(null)
            }
        }
        try {
            takeScreenshot(1, mainExecutor, callback)
        } catch (e: Exception) {
            // MIUI throws synchronously when the capability is gated.
            AppLogger.w("AgentAccessibility", "Screenshot: takeScreenshot() threw: ${e.message}")
            return null
        }
        val a11yResult = try { done.get(5, TimeUnit.SECONDS) } catch (e: Exception) {
            AppLogger.w("AgentAccessibility", "Screenshot: timed out or interrupted: ${e.message}")
            null
        }
        return a11yResult
    }
}
