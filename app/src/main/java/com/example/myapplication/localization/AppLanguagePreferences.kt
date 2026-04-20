package com.example.myapplication.localization

import android.content.Context

class AppLanguagePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val fallbackLanguage = AppLanguage.defaultFrom()

    fun selectedLanguage(): AppLanguage {
        val storedTag = preferences.getString(KEY_LANGUAGE_TAG, null)
        return AppLanguage.fromTag(storedTag) ?: fallbackLanguage
    }

    fun setSelectedLanguage(language: AppLanguage) {
        preferences.edit()
            .putString(KEY_LANGUAGE_TAG, language.languageTag)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "app_language_preferences"
        const val KEY_LANGUAGE_TAG = "selected_language_tag"
    }
}
