package com.example.oreshik

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** Keeps the background chat notification and per-session response notices in sync. */
internal object ChatNotifications {
    private const val CHANNEL_ID = "agent_responses"
    private const val CURRENT_CHANNEL_ID = "current_chat"
    private const val PREFS = "chat_notification_state"
    private const val KEY_CURRENT_SESSION = "current_session_id"
    private const val KEY_CURRENT_TITLE = "current_session_title"
    private const val KEY_APP_BACKGROUND = "app_background"
    private const val DELETED_PREFIX = "deleted_"
    private const val CURRENT_TAG = "current_chat"
    private const val CURRENT_ID = 7103
    private const val RESPONSE_ID = 7102

    fun setCurrentChat(context: Context, sessionId: String, title: String) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_CURRENT_SESSION, null)
        prefs.edit {
            putString(KEY_CURRENT_SESSION, sessionId)
            putString(KEY_CURRENT_TITLE, title)
        }
        cancelResponse(app, sessionId)
        if (!previous.isNullOrBlank() && previous != sessionId) {
            cancelCurrent(app)
            cancelResponse(app, previous)
        }
    }

    fun setAppBackgrounded(context: Context, backgrounded: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_APP_BACKGROUND, backgrounded)
        }
        if (!backgrounded) cancelCurrent(app)
    }

    fun isAppBackgrounded(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_APP_BACKGROUND, false)

    fun currentTitle(context: Context, sessionId: String, fallback: String): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getString(KEY_CURRENT_SESSION, null) == sessionId) {
            prefs.getString(KEY_CURRENT_TITLE, null).orEmpty().ifBlank { fallback }
        } else fallback
    }

    fun isCurrentSession(context: Context, sessionId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CURRENT_SESSION, null) == sessionId

    @SuppressLint("MissingPermission")
    fun postCurrentChat(context: Context, sessionId: String, title: String, preview: String) {
        val app = context.applicationContext
        if (!canPostNotifications(app) || isDeleted(app, sessionId) || !isCurrentSession(app, sessionId)) return
        cancelResponse(app, sessionId)
        ensureCurrentChannel(app)
        NotificationManagerCompat.from(app).notify(
            CURRENT_TAG,
            CURRENT_ID,
            buildNotification(app, CURRENT_CHANNEL_ID, sessionId, title, preview, ongoing = false),
        )
    }

    @SuppressLint("MissingPermission")
    fun postResponse(context: Context, sessionId: String, title: String, body: String) {
        val app = context.applicationContext
        if (!canPostNotifications(app) || isDeleted(app, sessionId)) return
        if (isAppBackgrounded(app) && isCurrentSession(app, sessionId)) {
            postCurrentChat(app, sessionId, title, body)
            return
        }
        ensureChannel(app)
        NotificationManagerCompat.from(app).notify(
            "agent_response_$sessionId",
            RESPONSE_ID,
            buildNotification(app, CHANNEL_ID, sessionId, "$title • Response ready", body, ongoing = false),
        )
    }

    /** Mark first so a completing service run cannot race and recreate a deleted chat notice. */
    fun cancelDeletedSession(context: Context, sessionId: String) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(DELETED_PREFIX + sessionId, true)
        }
        cancelSessionNotifications(app, sessionId)
    }

    fun cancelSessionNotifications(context: Context, sessionId: String) {
        val app = context.applicationContext
        cancelResponse(app, sessionId)
        if (isCurrentSession(app, sessionId)) cancelCurrent(app)
    }

    fun cancelCurrent(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(CURRENT_TAG, CURRENT_ID) }
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isDeleted(context: Context, sessionId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(DELETED_PREFIX + sessionId, false)

    private fun cancelResponse(context: Context, sessionId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel("agent_response_$sessionId", RESPONSE_ID) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent responses", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Current chat and completed agent responses."
            },
        )
    }

    private fun ensureCurrentChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CURRENT_CHANNEL_ID, "Current chat", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A quiet preview of the chat currently open in the app."
            },
        )
    }

    private fun buildNotification(
        context: Context,
        channelId: String,
        sessionId: String,
        title: String,
        body: String,
        ongoing: Boolean,
    ) = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(body.replace('\n', ' ').take(180).ifBlank { "Open the current chat" })
        .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(3500)))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setOnlyAlertOnce(true)
        .setAutoCancel(!ongoing)
        .setOngoing(ongoing)
        .setContentIntent(openChatIntent(context, sessionId))
        .build()

    private fun openChatIntent(context: Context, sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = "oreshik://chat/$sessionId".toUri()
            putExtra(AgentExecutionService.EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
