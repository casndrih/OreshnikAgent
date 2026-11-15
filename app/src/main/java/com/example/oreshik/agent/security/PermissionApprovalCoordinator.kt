package com.example.oreshik.agent.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.oreshik.MainActivity
import com.example.oreshik.R
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.core.ToolPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class PermissionApprovalRequest(
    val id: String,
    val sessionId: String,
    val tool: String,
    val permissions: Set<ToolPermission>,
    val alwaysAllowAvailable: Boolean,
)

/**
 * Holds permission requests while the service waits for a decision from the UI.
 * Requests are exposed as state so an approval remains visible if the Activity
 * briefly stops collecting events or is reopened from the notification.
 */
object PermissionApprovalCoordinator {
    private const val CHANNEL_ID = "tool_permission_approvals"
    private const val CHANNEL_NAME = "Agent permission requests"
    private const val NOTIFICATION_TAG = "tool_permission"
    private const val APPROVAL_TIMEOUT_MS = 120_000L

    private data class Pending(
        val request: PermissionApprovalRequest,
        val decision: CompletableDeferred<PermissionBroker.Decision>,
        val notificationId: Int,
    )

    private val lock = Any()
    private val pending = linkedMapOf<String, Pending>()
    private val _requests = MutableStateFlow<List<PermissionApprovalRequest>>(emptyList())
    val requests: StateFlow<List<PermissionApprovalRequest>> = _requests.asStateFlow()

    suspend fun request(
        context: Context,
        sessionId: String,
        tool: String,
        permissions: Set<ToolPermission>,
    ): PermissionBroker.Decision {
        val app = context.applicationContext
        val id = UUID.randomUUID().toString()
        val needsPerChangeApproval = AgentConfig.askBeforeWrite &&
            permissions.any { it == ToolPermission.WRITE_WORKSPACE || it == ToolPermission.DELETE }
        val request = PermissionApprovalRequest(
            id = id,
            sessionId = sessionId,
            tool = tool,
            permissions = permissions,
            alwaysAllowAvailable = !needsPerChangeApproval,
        )
        val item = Pending(
            request = request,
            decision = CompletableDeferred(),
            notificationId = id.hashCode() and Int.MAX_VALUE,
        )
        synchronized(lock) {
            pending[id] = item
            publishPendingLocked()
        }
        showNotification(app, item)

        return try {
            withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { item.decision.await() }
                ?: PermissionBroker.Decision.DENY
        } finally {
            synchronized(lock) {
                pending.remove(id)
                publishPendingLocked()
            }
            cancelNotification(app, item.notificationId)
        }
    }

    fun resolve(id: String, decision: PermissionBroker.Decision) {
        val item = synchronized(lock) {
            pending.remove(id).also { publishPendingLocked() }
        } ?: return
        item.decision.complete(decision)
        com.example.oreshik.logging.AppLogger.i(
            "PermissionApproval",
            "tool=${item.request.tool} session=${item.request.sessionId} → $decision",
        )
    }

    private fun publishPendingLocked() {
        _requests.value = pending.values.map { it.request }
    }

    private fun showNotification(context: Context, pending: Pending) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
            )
            val request = pending.request
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                data = "oreshik://permission/${request.id}".toUri()
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                pending.notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Agent needs approval")
                .setContentText("${request.tool} is waiting for permission")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Open the app to review the requested action: ${request.tool}",
                    ),
                )
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, pending.notificationId, notification)
        }
    }

    private fun cancelNotification(context: Context, notificationId: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, notificationId) }
    }
}
