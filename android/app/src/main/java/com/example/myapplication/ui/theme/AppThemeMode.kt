package com.example.myapplication.ui.theme

import android.app.UiModeManager
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.example.myapplication.R

enum class AppThemeMode(
    @param:StringRes val labelRes: Int,
    val appCompatMode: Int,
    val frameworkNightMode: Int,
) {
    SYSTEM(
        labelRes = R.string.settings_theme_mode_system,
        appCompatMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        frameworkNightMode = UiModeManager.MODE_NIGHT_AUTO,
    ),
    LIGHT(
        labelRes = R.string.settings_theme_mode_light,
        appCompatMode = AppCompatDelegate.MODE_NIGHT_NO,
        frameworkNightMode = UiModeManager.MODE_NIGHT_NO,
    ),
    DARK(
        labelRes = R.string.settings_theme_mode_dark,
        appCompatMode = AppCompatDelegate.MODE_NIGHT_YES,
        frameworkNightMode = UiModeManager.MODE_NIGHT_YES,
    );

    companion object {
        fun fromName(name: String?): AppThemeMode? = entries.firstOrNull { it.name == name }
    }
}
