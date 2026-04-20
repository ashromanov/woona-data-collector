package com.example.myapplication.ui.theme

import android.app.UiModeManager
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

fun applyAppThemeMode(
    context: Context,
    themeMode: AppThemeMode,
) {
    context.getSystemService(UiModeManager::class.java)
        ?.setApplicationNightMode(themeMode.frameworkNightMode)
    AppCompatDelegate.setDefaultNightMode(themeMode.appCompatMode)
}
