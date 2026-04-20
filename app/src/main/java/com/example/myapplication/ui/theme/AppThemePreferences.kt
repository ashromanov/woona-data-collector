package com.example.myapplication.ui.theme

import android.content.Context

class AppThemePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun selectedThemeMode(): AppThemeMode {
        val storedModeName = preferences.getString(KEY_THEME_MODE, null)
        return AppThemeMode.fromName(storedModeName) ?: AppThemeMode.SYSTEM
    }

    fun setSelectedThemeMode(themeMode: AppThemeMode) {
        preferences.edit()
            .putString(KEY_THEME_MODE, themeMode.name)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "app_theme_preferences"
        const val KEY_THEME_MODE = "selected_theme_mode"
    }
}
