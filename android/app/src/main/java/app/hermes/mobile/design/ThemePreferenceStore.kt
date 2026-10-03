package app.hermes.mobile.design

import android.content.Context

enum class ThemeMode(val storedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStoredValue(value: String?): ThemeMode = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

class ThemePreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences("hermes_appearance", Context.MODE_PRIVATE)

    fun read(): ThemeMode = ThemeMode.fromStoredValue(preferences.getString("theme_mode", null))

    fun write(mode: ThemeMode) {
        preferences.edit().putString("theme_mode", mode.storedValue).apply()
    }
}

fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
