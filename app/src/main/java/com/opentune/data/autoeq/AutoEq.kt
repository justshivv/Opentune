package com.opentune.data.autoeq

import android.content.Context
import com.opentune.data.Http
import com.opentune.data.settings.FilterType
import com.opentune.data.settings.HeadphoneEq
import com.opentune.data.settings.ParametricFilter
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Headphone corrections from AutoEq (github.com/jaakkopasanen/AutoEq, MIT):
 * measured frequency responses of thousands of headphones and earphones,
 * each with a parametric EQ that brings it to a neutral target.
 *
 * The list of models comes from the project's results index, downloaded
 * once and kept for [INDEX_MAX_AGE_MS]; a model's filters are fetched when
 * it's picked.
 */
object AutoEq {
    private const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
    private const val INDEX_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** One measured model: its name, who measured it, and where its files are. */
    data class Entry(val name: String, val source: String, val path: String)

    private var dir: File? = null
    @Volatile private var entries: List<Entry>? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "autoeq").apply { mkdirs() }
    }

    /** Every model AutoEq has a correction for. */
    suspend fun index(): List<Entry> = withContext(Dispatchers.IO) {
        entries?.let { return@withContext it }
        val file = dir?.let { File(it, "INDEX.md") }
        val fresh = file?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < INDEX_MAX_AGE_MS }
        val text = fresh?.readText() ?: runCatching { fetch("$BASE/INDEX.md") }.getOrElse { e ->
            // Offline: an old copy beats none.
            file?.takeIf { it.exists() }?.readText() ?: throw e
        }.also { t -> if (fresh == null) file?.writeText(t) }
        parseIndex(text).also { entries = it }
    }

    /** Models whose name contains every word of [query], closest first. */
    fun search(all: List<Entry>, query: String, limit: Int = 60): List<Entry> {
        val words = query.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { e -> val n = e.name.lowercase(Locale.ROOT); words.all { it in n } }
            .sortedBy { it.name.length }
            .take(limit)
            .toList()
    }

    /** [entry]'s parametric correction. */
    suspend fun load(entry: Entry): HeadphoneEq = withContext(Dispatchers.IO) {
        parseParametric(fetch(profileUrl(entry)), entry.name, entry.source)
    }

    /** `<path>/<model> ParametricEQ.txt`, each segment encoded properly. */
    internal fun profileUrl(entry: Entry): String {
        val segments = entry.path.removePrefix("./").split('/').map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        return BASE.toHttpUrl().newBuilder()
            .apply { segments.forEach { addPathSegment(it) } }
            .addPathSegment("${segments.last()} ParametricEQ.txt")
            .build()
            .toString()
    }

    /** "- [Name](./source/rig/Name%20Encoded) by source on rig" lines. */
    internal fun parseIndex(text: String): List<Entry> = text.lineSequence().mapNotNull { line ->
        val m = INDEX_LINE.find(line) ?: return@mapNotNull null
        Entry(m.groupValues[1], m.groupValues[3].substringBefore(" on ").trim(), m.groupValues[2])
    }.toList()

    /**
     * AutoEq's ParametricEQ.txt:
     * `Preamp: -6.1 dB` then lines like `Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70`.
     * Filters that are OFF, or of a type the DSP has no section for, are skipped.
     */
    internal fun parseParametric(text: String, name: String, source: String): HeadphoneEq {
        val preamp = PREAMP.find(text)?.groupValues?.get(1)?.toFloatOrNull()
            ?: throw IOException("No preamp in the AutoEq profile")
        val filters = FILTER.findAll(text).mapNotNull { m ->
            val type = when (m.groupValues[1]) {
                "PK" -> FilterType.PEAK
                "LSC", "LS" -> FilterType.LOW_SHELF
                "HSC", "HS" -> FilterType.HIGH_SHELF
                else -> return@mapNotNull null
            }
            ParametricFilter(type, m.groupValues[2].toFloat(), m.groupValues[3].toFloat(), m.groupValues[4].toFloat())
        }.toList()
        if (filters.isEmpty()) throw IOException("No filters in the AutoEq profile")
        return HeadphoneEq(name, source, preamp, filters)
    }

    private fun fetch(url: String): String =
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("AutoEq answered ${r.code}")
            r.body?.string() ?: throw IOException("Empty answer from AutoEq")
        }

    /** Up to the last ") by ": a few model names have unbalanced brackets. */
    private val INDEX_LINE = Regex("""^- \[(.+?)]\((\./.*)\) by (.+)$""")
    private val PREAMP = Regex("""Preamp:\s*(-?\d+(?:\.\d+)?)\s*dB""")
    private val FILTER = Regex("""Filter\s+\d+:\s*ON\s+(\w+)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz\s+Gain\s+(-?\d+(?:\.\d+)?)\s*dB\s+Q\s+(\d+(?:\.\d+)?)""")
}
