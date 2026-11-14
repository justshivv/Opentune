package com.opentune.i18n

import android.content.Context
import android.content.res.Resources
import android.util.Log
import org.json.JSONObject
import java.util.Locale

/**
 * The app's words in the chosen language.
 *
 * Every text is looked up by its English wording, so the English in the code is both the
 * key and what shows when a language has no translation for it. Translations live in
 * `assets/i18n/<tag>.json`, one object per language mapping English to the translation.
 * A text with values in it is written with numbered places, `"{0} songs"`, and filled
 * by [f], so a language can put the number wherever its grammar wants it.
 *
 * Screens translate as they draw (the app's own `Text` and `Icon` call [t]), so changing
 * the language takes effect on the next frame drawn; nothing holds on to an old wording.
 */
object Tr {
    /** The languages there are translations for, by the tag their file is named after. */
    val TAGS = listOf(
        "ar", "bn", "cs", "de", "el", "es", "fa", "fr", "gu", "hi", "hu", "id", "it", "ja", "kn", "ko", "ml", "mr", "ms",
        "nl", "pa", "pl", "pt", "ro", "ru", "sv", "ta", "te", "th", "tr", "uk", "ur", "vi", "zh-CN", "zh-TW",
    )

    @Volatile private var table: Map<String, String> = emptyMap()
    @Volatile var language: String = "en"
        private set
    private var context: Context? = null

    /**
     * Uses [tag] from now on ("" for the phone's own language). Reads its file once;
     * a language without one, or English, shows the English wording.
     */
    fun use(context: Context, tag: String) {
        this.context = context.applicationContext
        val resolved = resolve(tag.ifBlank { systemTag() })
        if (resolved == language && (resolved == "en" || table.isNotEmpty())) return
        table = if (resolved == "en") emptyMap() else load(context, resolved)
        language = resolved
    }

    /** [english] in the current language. */
    fun t(english: String): String = if (table.isEmpty()) english else table[english] ?: english

    /** [template] in the current language, with `{0}`, `{1}`… replaced by [args] in order. */
    fun f(template: String, vararg args: Any?): String = fill(t(template), args)

    internal fun fill(text: String, args: Array<out Any?>): String {
        if (args.isEmpty() || '{' !in text) return text
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '{') {
                val close = text.indexOf('}', i + 1)
                val index = if (close > i + 1) text.substring(i + 1, close).toIntOrNull() else null
                if (index != null && index in args.indices) {
                    out.append(args[index]); i = close + 1; continue
                }
            }
            out.append(c); i++
        }
        return out.toString()
    }

    /** The file [tag] would be read from: the tag itself, its language alone, or English. */
    internal fun resolve(tag: String): String {
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        val lang = when (locale.language) { "in" -> "id"; "iw" -> "he"; else -> locale.language }
        if (lang.isBlank() || lang == "en") return "en"
        if (lang == "zh") {
            val traditional = locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO") ||
                tag.equals("zh-TW", true)
            return if (traditional) "zh-TW" else "zh-CN"
        }
        return if (lang in TAGS) lang else "en"
    }

    private fun systemTag(): String = Resources.getSystem().configuration.locales[0].toLanguageTag()

    private fun load(context: Context, tag: String): Map<String, String> = runCatching {
        val text = context.assets.open("i18n/$tag.json").bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        HashMap<String, String>(json.length() * 2).apply {
            json.keys().forEach { key -> json.optString(key).takeIf { it.isNotEmpty() }?.let { put(key, it) } }
        }
    }.onFailure { Log.w("Tr", "No translations for $tag", it) }.getOrDefault(emptyMap())

    /** For tests: translations given directly. */
    internal fun useTable(tag: String, entries: Map<String, String>) { table = entries; language = tag }
}
