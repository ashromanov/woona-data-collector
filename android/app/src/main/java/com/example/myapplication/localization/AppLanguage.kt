package com.example.myapplication.localization

import androidx.annotation.StringRes
import com.example.myapplication.R

enum class AppLanguage(
    val languageTag: String,
    @param:StringRes val displayNameRes: Int,
) {
    ENGLISH("en", R.string.language_english),
    RUSSIAN("ru", R.string.language_russian),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage? = entries.firstOrNull { it.languageTag == tag }

        fun defaultFrom(): AppLanguage {
            return ENGLISH
        }
    }
}
