package com.example.oreshik.agent.tools

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.oreshik.agent.core.*
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Direct device-operation tools exposed to the agent model so spoken requests like
 * "send a text message to John" can be executed without fragile UI automation.
 */
class SendSmsTool : AgentTool {
    override val definition = ToolDefinition(
        name = "android.sms.send",
        category = ToolCategory.SYSTEM,
        description = "Send a text message (SMS) to a contact. Requires the SEND_SMS permission. " +
            "Provide a phone number (preferred) or a contact display name plus the message body.",
        permissions = setOf(ToolPermission.ACCESSIBILITY),
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "phone" to mapOf("type" to "string", "description" to "Phone number of the recipient"),
                "contact" to mapOf("type" to "string", "description" to "Contact display name, used when phone is not provided"),
                "message" to mapOf("type" to "string", "description" to "Message body to send"),
            ),
            "required" to listOf<String>(),
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val args = arguments.asJsonObject
        val phone = args["phone"]?.asString.orEmpty().trim()
        val contact = args["contact"]?.asString.orEmpty().trim()
        val message = args["message"]?.asString.orEmpty().trim()

        if (message.isBlank()) return@withContext ToolResult(ok = false, error = "Missing message body")
        if (phone.isBlank() && contact.isBlank()) {
            return@withContext ToolResult(ok = false, error = "Provide a phone number or contact name")
        }

        val app = context.app

        if (ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            // Open the app details page so the user can grant Send SMS manually.
            // We report the failure so the agent can ask the user to confirm/grant.
            app.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .apply { data = "package:${app.packageName}".toUri() },
            )
            AppLogger.w("DeviceActionTools", "SEND_SMS permission missing; opened app settings")
            return@withContext ToolResult(
                ok = false,
                error = "The SEND_SMS permission is not granted. Opened app permission settings - grant SMS permission, then ask me again.",
            )
        }

        // Resolve a contact only after checking its separately guarded permission.
        var number = phone
        if (number.isBlank()) {
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                app.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .apply { data = "package:${app.packageName}".toUri() },
                )
                return@withContext ToolResult(
                    ok = false,
                    error = "The READ_CONTACTS permission is not granted. Opened app permission settings - grant Contacts permission, then ask me again.",
                )
            }
            number = runCatching { resolveContact(app, contact) }.getOrNull().orEmpty()
            if (number.isBlank()) {
                return@withContext ToolResult(ok = false, error = "No contact found matching \"$contact\"")
            }
        }

        try {
            @Suppress("DEPRECATION")
            val smsManager = android.telephony.SmsManager.getDefault()
            // Long messages are divided into multipart SMS automatically.
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            else smsManager.sendTextMessage(number, null, message, null, null)

            val result = JsonObject()
            result.addProperty("ok", true)
            result.addProperty("to", number)
            result.addProperty("message", message)
            AppLogger.i("DeviceActionTools", "SMS sent to $number")
            ToolResult(ok = true, result.toString())
        } catch (e: Exception) {
            AppLogger.e("DeviceActionTools", "Failed to send SMS", e)
            ToolResult(ok = false, error = "Failed to send SMS: ${e.message}")
        }
    }

    private fun resolveContact(app: android.content.Context, name: String): String {
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        app.contentResolver.query(
            uri,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val numberIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
            val nameIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val display = cursor.getString(nameIdx).orEmpty()
                val n = cursor.getString(numberIdx).orEmpty()
                if (display.equals(name, ignoreCase = true)) return n
            }
            // Fuzzy fallback: name contained in a contact
            if (cursor.moveToFirst()) do {
                val display = cursor.getString(nameIdx).orEmpty()
                val n = cursor.getString(numberIdx).orEmpty()
                if (display.contains(name, ignoreCase = true)) return n
            } while (cursor.moveToNext())
        }
        return ""
    }
}

/**
 * Opens the Android app-details settings page for an installed app so the user can
 * uninstall or force-stop it. Spoken "uninstall this app" requests end here for the
 * final confirmation step, which Android itself performs.
 */
class OpenAppSettingsTool : AgentTool {
    override val definition = ToolDefinition(
        name = "android.app_settings",
        category = ToolCategory.SYSTEM,
        description = "Open the Android system settings page for an installed app (by package name or display name) " +
            "where it can be uninstalled or force-stopped.",
        permissions = setOf(ToolPermission.ACCESSIBILITY),
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "package" to mapOf("type" to "string", "description" to "Package name of the app"),
                "name" to mapOf("type" to "string", "description" to "Display name of the app (used when package is not provided)"),
            ),
            "required" to listOf<String>(),
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.Default) {
        val args = arguments.asJsonObject
        val pkg = args["package"]?.asString.orEmpty()
        val name = args["name"]?.asString.orEmpty()
        val pm = context.app.packageManager

        val resolvedPackage = when {
            pkg.isNotBlank() -> pkg
            name.isNotBlank() -> {
                val launches = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                launches.firstOrNull { info -> info.loadLabel(pm).toString().equals(name, ignoreCase = true) }?.activityInfo?.packageName
                    ?: launches.firstOrNull { info -> info.activityInfo.packageName.contains(name, ignoreCase = true) }?.activityInfo?.packageName
                    ?: return@withContext ToolResult(ok = false, error = "No installed app matches name: $name")
            }
            else -> return@withContext ToolResult(ok = false, error = "Provide either package or name")
        }

        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$resolvedPackage".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return@withContext try {
            context.app.startActivity(intent)
            ToolResult(ok = true, "Opened Android settings for $resolvedPackage (uninstall / force-stop available).")
        } catch (e: Exception) {
            ToolResult(ok = false, error = "Failed to open settings for $resolvedPackage: ${e.message}")
        }
    }
}
