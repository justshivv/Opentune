package com.opentune.data.lyrics

import com.opentune.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Romaji for Japanese lyrics, kanji and all. A kanji's reading depends on
 * the word it's in (世界 is "sekai", 界 alone isn't), which takes a full
 * dictionary the phone doesn't have, so the lines are read by Google's
 * transliteration, a few dozen lines a request. The lines go together,
 * joined by a mark it leaves alone, and come back split on it. A batch
 * that comes back with a different number of lines is asked for again a
 * line at a time; a line that still can't be read keeps the reading
 * [Romanizer] gives offline.
 *
 * Remembered for the songs played lately, once every line has had its
 * answer, so a song read once isn't asked for again, and one read only in
 * part is.
 */
object JapaneseReadings {
    private const val URL = "https://translate.googleapis.com/translate_a/single"
    private const val SEPARATOR = " ◆ "
    /** Short enough that a batch always goes through: it was sent in the address, and long ones were refused. */
    private const val BATCH_CHARS = 500
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
        var complete = true
        val wanted = texts.indices.filter { texts[it].any(::needsReading) }
        for (batch in batches(wanted.map { it to joined(texts[it]) })) {
            val read = runCatching { fetch(batch.map { it.second }) }.getOrNull()
            if (read == null) { complete = false; continue }
            // Lines merged or split on the way back: each one asked for on its own, rather
            // than the whole batch left in kanji.
            val readings = if (read.size == batch.size) read else batch.map { (_, line) ->
                runCatching { fetch(listOf(line)) }.getOrNull()?.singleOrNull().also { if (it == null) complete = false }
            }
            fetchedAny = true
            batch.forEachIndexed { i, (index, original) -> out[index] = readings[i]?.takeIf { it.isNotBlank() && it != original } }
        }
        if (!fetchedAny) return@withContext null
        out.toList().also { if (complete) synchronized(remembered) { remembered[key] = it } }
    }

    /**
     * [line] with the spaces between Japanese characters taken out. Sources
     * that time each syllable send "繰 り 返す 出会い と 別 れ に"; read like
     * that, every piece was a word of its own (別 alone is "betsu", 的 alone
     * "mato") instead of 別れ "wakare" and 個人的 "kojinteki". Japanese has
     * no spaces between words, so none are lost; spaces next to Latin words
     * stay.
     */
    internal fun joined(line: String): String = JAPANESE_GAP.replace(line.trim(), "")

    private val JAPANESE_GAP = Regex("""(?<=[\p{IsHan}\p{IsHiragana}\p{IsKatakana}ー々〆])\s+(?=[\p{IsHan}\p{IsHiragana}\p{IsKatakana}ー々〆])""")

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
            .build()
        val q = lines.joinToString(SEPARATOR) { it.replace("◆", " ") }
        fun read(request: Request) = Http.client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        // The lines in the body: in the address, each kanji took nine characters. Refused
        // that way, the address as before.
        val body = read(Request.Builder().url(url).post(FormBody.Builder().add("q", q).build()).build())
            ?: read(Request.Builder().url(url.newBuilder().addQueryParameter("q", q).build()).build())
            ?: return null
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
