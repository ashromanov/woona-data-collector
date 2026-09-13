package com.example.myapplication.localization

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

class AppTextResolver(
    private val context: Context,
    private val currentLanguage: () -> AppLanguage,
) : TextResolver {
    override fun getString(
        @StringRes resId: Int,
        vararg formatArgs: Any,
    ): String {
        return getString(currentLanguage(), resId, *formatArgs)
    }

    fun getString(
        language: AppLanguage,
        @StringRes resId: Int,
        vararg formatArgs: Any,
    ): String {
        return localizedContext(language).getString(resId, *formatArgs)
    }

    private fun localizedContext(language: AppLanguage): Context {
        val locale = Locale.forLanguageTag(language.languageTag)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }
}
