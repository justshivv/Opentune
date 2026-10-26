package com.opentune.ui.theme

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

enum class AppLanguage(val tag: String, val label: String) {
    SYSTEM("", "System language"), ENGLISH("en", "English"), HINDI("hi", "हिन्दी"), PUNJABI("pa", "ਪੰਜਾਬੀ"),
}

object AppLanguages {
    fun selected(context: Context): AppLanguage {
        val tag = if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
            else context.getSharedPreferences("app_language", 0).getString("tag", "").orEmpty()
        return AppLanguage.entries.firstOrNull { it.tag == tag.substringBefore(',') } ?: AppLanguage.SYSTEM
    }

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val language = selected(context)
        if (language == AppLanguage.SYSTEM) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language.tag))
        return context.createConfigurationContext(configuration)
    }

    fun select(context: Context, language: AppLanguage) {
        context.getSharedPreferences("app_language", 0).edit().putString("tag", language.tag).apply()
        com.opentune.data.MusicRepository.refreshHomeRecommendations()
        com.opentune.ui.ScreenCache.clear()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        } else context.activity()?.recreate()
    }

    private fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
