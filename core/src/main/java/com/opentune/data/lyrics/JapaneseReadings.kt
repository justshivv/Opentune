package com.opentune.data.lyrics

import com.opentune.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Romaji for Japanese lyrics, kanji and all. A kanji's reading depends on
 * the word it's in (世界 is "sekai", 界 alone isn't), which takes a full
 * dictionary the phone doesn't have, so the lines are read by Google's
 * transliteration, a few dozen lines a request. The lines go together,
 * joined by a mark it leaves alone, and come back split on it; a batch
 * that comes back with a different number of lines is dropped, and its
 * lines keep the reading [Romanizer] gives offline.
 *
 * Remembered for the songs played lately, so a song read once isn't asked
 * for again.
 */
object JapaneseReadings {
    private const val URL = "https://translate.googleapis.com/translate_a/single"
    private const val SEPARATOR = " ◆ "
    private const val BATCH_CHARS = 1_200
    private const val REMEMBERED = 48
    private val json = Json { ignoreUnknownKeys = true }
    private val remembered = object : LinkedHashMap<Int, List<String?>>(REMEMBERED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<String?>>?) = size > REMEMBERED
    }

    /** Whether [texts] are Japanese with kanji in them: the case the offline reading leaves half done. */
    fun wanted(texts: List<String>): Boolean = texts.any { t -> t.any(::isKana) } && texts.any { t -> t.any(::isHan) }

    /**
     * Each line in romaji, or null where a line is already Latin, blank, or
     * couldn't be read. Null for the whole list when nothing could be fetched.
     */
    suspend fun lines(texts: List<String>): List<String?>? = withContext(Dispatchers.IO) {
        val key = texts.hashCode()
        synchronized(remembered) { remembered[key] }?.let { return@withContext it }
        val out = arrayOfNulls<String>(texts.size)
        var fetchedAny = false
        val wanted = texts.indices.filter { texts[it].any(::needsReading) }
        for (batch in batches(wanted.map { it to texts[it].trim() })) {
            val read = runCatching { fetch(batch.map { it.second }) }.getOrNull() ?: continue
            if (read.size != batch.size) continue
            fetchedAny = true
            batch.forEachIndexed { i, (index, original) -> out[index] = read[i].takeIf { it.isNotBlank() && it != original } }
        }
        if (!fetchedAny) return@withContext null
        out.toList().also { synchronized(remembered) { remembered[key] = it } }
    }

    private fun batches(lines: List<Pair<Int, String>>): List<List<Pair<Int, String>>> {
        val all = mutableListOf<List<Pair<Int, String>>>()
        var current = mutableListOf<Pair<Int, String>>()
        var chars = 0
        for (line in lines) {
            if (current.isNotEmpty() && chars + line.second.length > BATCH_CHARS) {
                all += current
                current = mutableListOf()
                chars = 0
            }
            current += line
            chars += line.second.length + SEPARATOR.length
        }
        if (current.isNotEmpty()) all += current
        return all
    }

    private fun fetch(lines: List<String>): List<String>? {
        val url = URL.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "ja")
            .addQueryParameter("tl", "en")
            .addQueryParameter("dt", "rm")
            .addQueryParameter("q", lines.joinToString(SEPARATOR) { it.replace("◆", " ") })
            .build()
        val body = Http.client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return null
        return parse(body)?.split("◆")?.map { it.trim() }
    }

    /** The romaji in a response: the fourth field of each sentence in the first list. */
    internal fun parse(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
        val sentences = root.firstOrNull() as? JsonArray ?: return null
        return sentences.mapNotNull { (it as? JsonArray)?.getOrNull(3)?.let { v -> (v as? JsonPrimitive)?.contentOrNull } }
            .joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun isKana(c: Char) = Character.UnicodeScript.of(c.code).let { it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA }
    private fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
    private fun needsReading(c: Char) = isKana(c) || isHan(c)
}
