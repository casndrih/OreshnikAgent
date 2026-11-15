package com.example.oreshik.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.example.oreshik.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * NotificationListenerService that monitors all notifications from other apps.
 * Requires the user to grant "Notification Access" permission in system settings.
 *
 * Add this to AndroidManifest.xml:
 * <service
 *     android:name=".notification.NotificationMonitorService"
 *     android:label="@string/notification_monitor_service_label"
 *     android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
 *     android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.service.notification.NotificationListenerService" />
 *     </intent-filter>
 * </service>
 */
class NotificationMonitorService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationMonitor"
        private const val REBIND_COOLDOWN_MS = 30_000L
        private val rebindLock = Any()
        @Volatile private var lastRebindAttemptMs = 0L
        
        @Volatile var instance: NotificationMonitorService? = null
        
        fun isEnabled(context: Context): Boolean {
            return try {
                val expected = ComponentName(context, NotificationMonitorService::class.java)
                val enabledListeners = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners",
                ).orEmpty()
                enabledListeners.split(':').any { entry ->
                    ComponentName.unflattenFromString(entry.trim()) == expected
                }
            } catch (_: Exception) {
                false
            }
        }

        /** Ask Android to reconnect an enabled listener that MIUI failed to bind.
         * This does not grant access; the exact listener component must already
         * be present in Android's enabled-listener setting. */
        fun requestRebindIfNeeded(context: Context, force: Boolean = false): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isEnabled(context)) return false
            if (instance?.listenerConnected == true) return true
            val now = SystemClock.elapsedRealtime()
            synchronized(rebindLock) {
                if (!force && (now - lastRebindAttemptMs) < REBIND_COOLDOWN_MS) return false
                lastRebindAttemptMs = now
            }
            return runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context.applicationContext, NotificationMonitorService::class.java),
                )
                AppLogger.i(TAG, "Requested system rebind for enabled notification listener")
                true
            }.getOrElse {
                AppLogger.w(TAG, "System rejected notification-listener rebind request: ${it.message}")
                false
            }
        }
        
        fun requestAccess(context: Context) {
            try {
                val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to open notification access settings", e)
            }
        }
    }

    // Coroutine scope for async operations
    private val job = Job()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    
    // State flows for reactive UI
    private val _notifications = MutableStateFlow<Map<String, NotificationData>>(emptyMap())
    val notifications: LiveData<Map<String, NotificationData>> = _notifications.asLiveData()
    
    // Channel for real-time notification events
    private val eventChannel = Channel<NotificationEvent>(100)
    
    // Store active notifications for interaction
    private val activeNotifications = ConcurrentHashMap<String, StatusBarNotification>()
    
    // Pending actions for external consumers
    private val pendingActions = ConcurrentHashMap<String, (Boolean) -> Unit>()

    @Volatile
    private var listenerConnected = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLogger.i(TAG, "NotificationMonitorService created")
    }

    override fun onDestroy() {
        instance = null
        job.cancel()
        eventChannel.close()
        activeNotifications.clear()
        pendingActions.clear()
        listenerConnected = false
        AppLogger.i(TAG, "NotificationMonitorService destroyed")
        super.onDestroy()
    }

    @SuppressLint("OverrideAbstract")
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        runCatching { processNotification(sbn, isPosted = true) }
            .onFailure { AppLogger.e(TAG, "Failed to process a posted notification", it) }
    }

    @SuppressLint("OverrideAbstract")
    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        runCatching { processNotification(sbn, isPosted = false) }
            .onFailure { AppLogger.e(TAG, "Failed to process a removed notification", it) }
    }

    @SuppressLint("OverrideAbstract")
    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        AppLogger.i(TAG, "Notification listener connected")
        
        // Get current active notifications - we can't access them directly from here
        // They will be delivered via onNotificationPosted if they were posted before connection
    }

    @SuppressLint("OverrideAbstract")
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        listenerConnected = false
        AppLogger.w(TAG, "Notification listener disconnected")
        _notifications.value = emptyMap()
        activeNotifications.clear()
    }

    private fun processNotification(sbn: StatusBarNotification, isPosted: Boolean) {
        val key = sbn.key
        val pkg = sbn.packageName
        val notification = sbn.notification
        
        AppLogger.d(TAG, "Notification ${if (isPosted) "posted" else "removed"}: $pkg - $key")
        
        if (isPosted && notification != null) {
            // Store for potential interaction
            activeNotifications[key] = sbn
            
            // Extract notification data
            val data = extractNotificationData(sbn)
            
            // Update state
            scope.launch {
                val current = _notifications.value
                val updated = current.toMutableMap().apply { put(key, data) }
                _notifications.value = updated
            }
            
            // Send event
            scope.launch {
                eventChannel.trySend(NotificationEvent.Posted(data))
            }
        } else {
            // Remove from active
            activeNotifications.remove(key)
            
            // Update state
            scope.launch {
                val current = _notifications.value
                if (current.containsKey(key)) {
                    val updated = current.toMutableMap().apply { remove(key) }
                    _notifications.value = updated
                }
            }
            
            // Send event
            scope.launch {
                eventChannel.trySend(NotificationEvent.Removed(key, pkg))
            }
        }
    }

    private fun extractNotificationData(sbn: StatusBarNotification): NotificationData {
        val notification = sbn.notification
        val pkg = sbn.packageName
        val key = sbn.key
        val id = sbn.id
        val tag = sbn.tag
        val postTime = sbn.postTime
        @Suppress("DEPRECATION")
        val priority: Int = notification.priority
        
        // Extract text content
        val extras = notification.extras ?: Bundle.EMPTY
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val subText = extras.getString(Notification.EXTRA_SUB_TEXT) ?: ""
        val bigText = extras.getString(Notification.EXTRA_BIG_TEXT) ?: ""
        val summaryText = extras.getString(Notification.EXTRA_SUMMARY_TEXT) ?: ""
        
        // Get app name
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        
        // Extract actions
        val actions = mutableListOf<NotificationAction>()
        // Some OEM/system notifications expose a null actions array (observed on
        // MIUI). Normalize it once and use the same safe list for action and
        // RemoteInput extraction.
        val notificationActions = notification.actions?.filterNotNull().orEmpty()
        notificationActions.forEachIndexed { index, action ->
            actions.add(
                NotificationAction(
                    index = index,
                    title = action.title?.toString() ?: "",
                    action = action.actionIntent,
                    isContextual = false,
                )
            )
        }
        
        // Add contextual actions (dismiss, reply, etc.)
        if (notification.deleteIntent != null) {
            actions.add(NotificationAction(
                index = -1,
                title = "Dismiss",
                action = notification.deleteIntent,
                isContextual = true
            ))
        }
        
        // Check for remote input (reply action)
        val hasRemoteInput = extras.containsKey(Notification.EXTRA_REMOTE_INPUT_HISTORY) ||
            notificationActions.any { remoteAction -> !remoteAction.remoteInputs.isNullOrEmpty() }
        
        return NotificationData(
            key = key,
            packageName = pkg,
            appName = appName,
            id = id,
            tag = tag,
            postTime = postTime,
            title = title,
            text = text,
            subText = subText,
            bigText = bigText,
            summaryText = summaryText,
            actions = actions,
            hasRemoteInput = hasRemoteInput,
            isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            isGroup = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            groupKey = extras.getString("android.groupKey"),
            category = notification.category,
            priority = priority,
            color = notification.color,
            smallIcon = notification.smallIcon,
            channelId = notification.channelId,
        )
    }

    /**
     * Get a flow of notification events for real-time updates
     */
    fun getEventChannel() = eventChannel

    /**
     * Get current notifications snapshot
     */
    fun getCurrentNotifications(): Map<String, NotificationData> = _notifications.value

    /**
     * Click/dismiss a notification action
     */
    fun performAction(notificationKey: String, actionIndex: Int, callback: ((Boolean) -> Unit)? = null): Boolean {
        val sbn = activeNotifications[notificationKey] ?: return false
        val notification = sbn.notification ?: return false
        
        val actions = notification.actions
        
        if (actionIndex >= 0 && actionIndex < actions.size) {
            val action = actions[actionIndex]
            return try {
                action.actionIntent?.send()
                callback?.invoke(true)
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to perform action", e)
                callback?.invoke(false)
                false
            }
        } else if (actionIndex == -1) {
            // Dismiss action
            return try {
                notification.deleteIntent?.send()
                callback?.invoke(true)
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to dismiss notification", e)
                callback?.invoke(false)
                false
            }
        }
        
        callback?.invoke(false)
        return false
    }

    /**
     * Reply to a notification with remote input
     */
    fun replyToNotification(notificationKey: String, replyText: String, callback: ((Boolean) -> Unit)? = null): Boolean {
        val sbn = activeNotifications[notificationKey] ?: return false
        val notification = sbn.notification ?: return false
        
        // Find remote input action (remoteInputs exists on all supported API levels)
        val remoteInputAction = notification.actions.firstOrNull { candidateAction -> candidateAction.remoteInputs.isNotEmpty() }
            ?: return false

        val remoteInput = remoteInputAction.remoteInputs.firstOrNull() ?: return false

        // Create bundle with reply
        val results = Bundle()
        results.putCharSequence(remoteInput.resultKey, replyText)

        // Send the reply using the remote input action's pending intent
        return try {
            val clipData = android.content.ClipData.newPlainText("reply", replyText)
            remoteInputAction.actionIntent?.send(
                this,
                0,
                Intent().apply {
                    putExtra("android.remoteinput.results", results)
                    clipData?.also { pair -> this.clipData = pair }
                },
            )
            callback?.invoke(true)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to send reply", e)
            callback?.invoke(false)
            false
        }
    }

    /**
     * Dismiss a specific notification
     */
    fun dismissNotification(notificationKey: String): Boolean {
        val sbn = activeNotifications[notificationKey] ?: return false
        return try {
            // The (pkg, tag, id) form is the only API available to a
            // NotificationListenerService for arbitrary packages; suppress is safe.
            @Suppress("DEPRECATION")
            cancelNotification(sbn.packageName, sbn.tag, sbn.id)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to dismiss notification", e)
            false
        }
    }

    /**
     * Dismiss all notifications from a package
     */
    fun dismissAllFromPackage(packageName: String): Int {
        var count = 0
        activeNotifications.values.filter { sbn -> sbn.packageName == packageName }.forEach { sbn ->
            try {
                @Suppress("DEPRECATION")
                cancelNotification(sbn.packageName, sbn.tag, sbn.id)
                count++
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to dismiss notification from $packageName", e)
            }
        }
        return count
    }

    /**
     * Clear all notifications (if allowed)
     */
    fun clearAllNotifications(): Int {
        var count = 0
        activeNotifications.values.forEach { sbn ->
            // Only clear dismissible notifications
            if ((sbn.notification?.flags?.and(Notification.FLAG_ONGOING_EVENT) ?: 0) == 0) {
                try {
                    @Suppress("DEPRECATION")
                    cancelNotification(sbn.packageName, sbn.tag, sbn.id)
                    count++
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to clear notification", e)
                }
            }
        }
        return count
    }
}

/**
 * Data class representing a notification
 */
data class NotificationData(
    val key: String,
    val packageName: String,
    val appName: String,
    val id: Int,
    val tag: String?,
    val postTime: Long,
    val title: String,
    val text: String,
    val subText: String,
    val bigText: String,
    val summaryText: String,
    val actions: List<NotificationAction>,
    val hasRemoteInput: Boolean,
    val isOngoing: Boolean,
    val isGroup: Boolean,
    val groupKey: String?,
    val category: String?,
    val priority: Int,
    val color: Int,
    val smallIcon: android.graphics.drawable.Icon?,
    val channelId: String?
) {
    /** Best display text across big/text/sub variants (shown when title is empty). */
    @Suppress("unused")
    val displayText: String
        get() = bigText.ifBlank { text.ifBlank { subText } }

    @Suppress("unused")
    val isDismissible: Boolean
        get() = !isOngoing

    @Suppress("unused")
    val canReply: Boolean
        get() = hasRemoteInput && actions.any { action -> action.title.lowercase().contains("reply") }
}

/**
 * Notification action that can be performed
 */
data class NotificationAction(
    val index: Int,
    val title: String,
    val action: android.app.PendingIntent?,
    val isContextual: Boolean
)

/**
 * Events emitted by the notification monitor
 */
sealed class NotificationEvent {
    data class Posted(val notification: NotificationData) : NotificationEvent()
    data class Removed(val key: String, val packageName: String) : NotificationEvent()

    @Suppress("unused")
    data class Updated(val notification: NotificationData) : NotificationEvent()

    @Suppress("unused")
    object Connected : NotificationEvent()

    @Suppress("unused")
    object Disconnected : NotificationEvent()
}

/**
 * Helper class to manage notification listener from Activities/ViewModels.
 * Public API surface for UI hosts that need programmatic notification control.
 */
@Suppress("unused")
class NotificationManagerHelper(private val context: Context) {
    
    private val job = Job()
    private val scope = CoroutineScope(job + Dispatchers.Main)
    
    fun isNotificationAccessEnabled(): Boolean {
        return NotificationMonitorService.isEnabled(context)
    }
    
    fun requestNotificationAccess() {
        NotificationMonitorService.requestAccess(context)
    }
    
    fun getService(): NotificationMonitorService? = NotificationMonitorService.instance
    
    fun getCurrentNotifications(): Map<String, NotificationData> = getService()?.getCurrentNotifications() ?: emptyMap()
    
    fun observeNotifications(onChange: (Map<String, NotificationData>) -> Unit) {
        scope.launch {
            getService()?.notifications?.observeForever { notifications ->
                onChange(notifications)
            }
        }
    }
    
    fun observeEvents(onEvent: (NotificationEvent) -> Unit) {
        scope.launch(Dispatchers.IO) {
            getService()?.getEventChannel()?.let { channel ->
                for (event in channel) {
                    withContext(Dispatchers.Main) {
                        onEvent(event)
                    }
                }
            }
        }
    }
    
    fun performAction(key: String, actionIndex: Int, callback: ((Boolean) -> Unit)? = null): Boolean {
        return getService()?.performAction(key, actionIndex, callback) ?: false
    }
    
    fun reply(key: String, text: String, callback: ((Boolean) -> Unit)? = null): Boolean {
        return getService()?.replyToNotification(key, text, callback) ?: false
    }
    
    fun dismiss(key: String): Boolean {
        return getService()?.dismissNotification(key) ?: false
    }
    
    fun dismissAllFromPackage(packageName: String): Int {
        return getService()?.dismissAllFromPackage(packageName) ?: 0
    }
    
    fun clearAll(): Int {
        return getService()?.clearAllNotifications() ?: 0
    }
    
    fun shutdown() {
        job.cancel()
    }
}
