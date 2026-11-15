package com.example.oreshik

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

/** Persists the user's chosen theme mode across app restarts. */
object ThemePrefs {
    private const val PREFS = "agent_preferences"
    private const val KEY_THEME_MODE = "theme_mode"

    fun get(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    fun set(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_THEME_MODE, mode)
        }
    }
}
