package com.example.oreshik.agent.security

import android.app.AlertDialog
import android.content.Context
import android.app.Activity
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.core.ToolPermission
import com.example.oreshik.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Interactive approval dialog for foreground UI hosts. */
suspend fun requestToolPermission(
    context: Context,
    tool: String,
    permissions: Set<ToolPermission>,
): PermissionBroker.Decision = withContext(Dispatchers.Main.immediate) {
    val activity = context as? Activity
        ?: return@withContext PermissionBroker.Decision.DENY
    if (activity.isFinishing || activity.isDestroyed) {
        return@withContext PermissionBroker.Decision.DENY
    }

    suspendCancellableCoroutine { cont ->
        val names = permissions.joinToString("\n") { permission ->
            "• " + permission.name.replace('_', ' ')
        }
        val mustAskForEachWrite = AgentConfig.askBeforeWrite &&
            permissions.any {
                it == ToolPermission.WRITE_WORKSPACE || it == ToolPermission.DELETE
            }
        val builder = AlertDialog.Builder(activity)
            .setTitle("Agent permission required")
            .setMessage("The agent wants to use:\n\n$tool\n\nPermissions:\n$names")
            .setNegativeButton("Deny") { _, _ ->
                if (cont.isActive) cont.resume(PermissionBroker.Decision.DENY)
            }

        if (mustAskForEachWrite) {
            builder.setPositiveButton("Allow this change") { _, _ ->
                if (cont.isActive) cont.resume(PermissionBroker.Decision.ALLOW_ONCE)
            }
        } else {
            builder
                .setNeutralButton("Allow once") { _, _ ->
                    if (cont.isActive) cont.resume(PermissionBroker.Decision.ALLOW_ONCE)
                }
                .setPositiveButton("Always allow") { _, _ ->
                    if (cont.isActive) cont.resume(PermissionBroker.Decision.ALWAYS_ALLOW)
                }
        }

        val dialog = builder.create()
        dialog.setOnCancelListener {
            if (cont.isActive) cont.resume(PermissionBroker.Decision.DENY)
        }
        cont.invokeOnCancellation { dialog.dismiss() }
        AppLogger.i("PermissionUi", "Permission dialog shown for tool=$tool (${permissions.size} permission(s))")
        dialog.show()
    }
}
