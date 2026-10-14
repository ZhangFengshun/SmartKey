package dev.lovekey.open.ui

import android.content.Context

/** Non-sensitive keyboard appearance preferences, separate from encrypted model data. */
object KeyboardPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("keyboard_preferences", Context.MODE_PRIVATE)
    fun theme(context: Context): String = prefs(context).getString("theme", "dark") ?: "dark"
    fun haptics(context: Context): Boolean = prefs(context).getBoolean("haptics", true)
    fun setTheme(context: Context, value: String) { require(value in listOf("dark", "light", "lavender")); prefs(context).edit().putString("theme", value).apply() }
    fun setHaptics(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("haptics", enabled).apply() }
}
