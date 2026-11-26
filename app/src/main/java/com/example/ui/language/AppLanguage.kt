package com.example.ui.language

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The languages the app ships. The tag is the BCP-47 language used for resources (values-ru, values-fa). Persian is
 * written right to left: the manifest's supportsRtl and the locale's layout direction mirror the UI (build 177: the
 * complete values-fa translation could not be chosen).
 */
enum class AppLanguage(val tag: String, val nativeName: String, val flag: String) {
    ENGLISH("en", "English", "\uD83C\uDDEC\uD83C\uDDE7"),
    RUSSIAN("ru", "Русский", "\uD83C\uDDF7\uD83C\uDDFA"),
    PERSIAN("fa", "فارسی", "\uD83C\uDDEE\uD83C\uDDF7");

    /** True for a right-to-left script (Persian). */
    val rightToLeft: Boolean get() = this == PERSIAN

    companion object {
        fun fromTag(tag: String?): AppLanguage? {
            val lang = tag?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase(Locale.ROOT)
            return entries.firstOrNull { it.tag == lang }
        }

        /** First-launch highlight: the phone's language when the app ships it (Russian, Persian), otherwise English. */
        fun defaultFor(deviceLanguageTag: String?): AppLanguage = fromTag(deviceLanguageTag) ?: ENGLISH
    }
}

/**
 * Per-app language. Android 13+ keeps the choice in the system (LocaleManager, also editable from
 * the system's per-app language settings through android:localeConfig); older versions apply it by
 * wrapping each Activity's base context. The choice and the first-launch flag are kept in
 * SharedPreferences so the picker is shown only once.
 */
object LanguageStore {
    private const val PREFS = "maximus_language"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_CHOSEN = "chosen"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True until the person picks a language on the first-launch screen (or in Settings). */
    fun needsFirstLaunchChoice(context: Context): Boolean = needsChoice(prefs(context))

    internal fun needsChoice(prefs: SharedPreferences): Boolean = !prefs.getBoolean(KEY_CHOSEN, false)

    internal fun storedLanguage(prefs: SharedPreferences): AppLanguage? = AppLanguage.fromTag(prefs.getString(KEY_LANGUAGE, null))

    internal fun record(prefs: SharedPreferences, language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.tag).putBoolean(KEY_CHOSEN, true).apply()
    }

    /** The phone's own language, ignoring the app override. */
    fun deviceLanguageTag(): String = Resources.getSystem().configuration.locales[0]?.language.orEmpty()

    /** The language the UI is shown in now. */
    fun current(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val system = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (system != null && !system.isEmpty) AppLanguage.fromTag(system[0]?.language)?.let { return it }
            // Chosen English while the phone is Russian: LocaleManager may hold "en"; empty means follow the phone.
            storedLanguage(prefs(context))?.let { return it }
            return AppLanguage.defaultFor(deviceLanguageTag())
        }
        return storedLanguage(prefs(context)) ?: AppLanguage.defaultFor(deviceLanguageTag())
    }

    /**
     * Saves the choice and applies it right away. Android 13+ recreates the activity itself after the
     * LocaleManager change; older versions are recreated here when the language actually changed.
     */
    fun apply(activity: Activity, language: AppLanguage) {
        val before = current(activity)
        record(prefs(activity), language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales = LocaleList.forLanguageTags(language.tag)
        } else if (before != language) {
            activity.recreate()
        }
    }

    /** Activity base context for Android 12 and older; Android 13+ is handled by the system. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val language = storedLanguage(prefs(base)) ?: return base
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** A context whose resources use the given language, for previews such as the first-launch picker. */
    fun contextFor(context: Context, language: AppLanguage): Context {
        val config = Configuration(context.resources.configuration)
        val locale = Locale.forLanguageTag(language.tag)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }

    /** Resources in the app language for services and notifications (which are not Activities). */
    fun localized(context: Context): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context else contextFor(context, current(context))
}
