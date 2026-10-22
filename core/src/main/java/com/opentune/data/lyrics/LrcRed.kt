package com.opentune.data.lyrics

import com.opentune.data.Http
import java.text.Normalizer
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * lrc.red, BitChord's first lyrics source: Apple-style word-by-word TTML,
 * filed by recording (ISRC). The recording is named by BiniLyrics, which
 * knows most songs' ISRC from their title, artist and length, or else by
 * Deezer's catalogue, asked at the same time; failing both, lrc.red's own
 * search is asked, and a hit only counts when its title,
 * version (live, remix, sped up…), an artist and its length (within 3 s) all
 * match, so another version's timing never gets used.
 */
internal object LrcRed {
    private const val BASE = "https://lrc.red/"
    private const val BINI = "https://lyrics-api.binimum.org/"
    private const val USER_AGENT = "OpenTune (+https://github.com/justshivv/Opentune)"
    private const val IDENTIFY_MS = 2_500L
    private val ISRC = Regex("^[A-Z]{2}[A-Z0-9]{3}\\d{7}$")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val isrc = coroutineScope {
            val bini = async { withTimeoutOrNull(IDENTIFY_MS) { runCatching { identify(cleaned.title, cleaned.artist, durationMs) }.getOrNull() } }
            val deezer = async { withTimeoutOrNull(IDENTIFY_MS) { runCatching { fromDeezer(cleaned.title, cleaned.artist, durationMs) }.getOrNull() } }
            bini.await().also { if (it != null) deezer.cancel() } ?: deezer.await()
        }
        document(isrc)?.let { url -> fetchTtml(url, durationMs)?.let { return it } }
        val hits = runCatching { search("${cleaned.title} ${cleaned.artist}".trim()) }.getOrNull().orEmpty()
        val url = document(best(hits, cleaned.title, cleaned.artist, durationMs)?.isrc) ?: return null
        if (url == document(isrc)) return null
        return fetchTtml(url, durationMs)
    }

    /** lrc.red's document for a recording, or null when [isrc] isn't one. */
    internal fun document(isrc: String?): String? {
        val code = isrc?.trim()?.uppercase()?.replace("-", "") ?: return null
        return if (ISRC.matches(code)) "${BASE}s/$code.ttml" else null
    }

    internal data class Hit(val isrc: String?, val title: String?, val artist: String?, val durationS: Double?)

    private suspend fun identify(title: String, artist: String, durationMs: Long): String? {
        val url = BINI.toHttpUrl().newBuilder()
            .addQueryParameter("track", title)
            .addQueryParameter("artist", artist)
            .apply { if (durationMs > 0) addQueryParameter("duration", (durationMs / 1000).toString()) }
            .build().toString()
        val body = get(url) ?: return null
        val results = (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.get("results") as? JsonArray ?: return null
        return (results.firstOrNull() as? JsonObject)?.get("isrc")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /** The ISRC of Deezer's copy of this recording, by the same rules as lrc.red's own search. */
    private suspend fun fromDeezer(title: String, artist: String, durationMs: Long): String? {
        val hits = com.opentune.data.metadata.Deezer.search("$title $artist".trim())
            .map { Hit(it.isrc, it.title, it.artist, it.durationS.toDouble().takeIf { s -> s > 0 }) }
        return best(hits, title, artist, durationMs)?.isrc
    }

    private suspend fun search(q: String): List<Hit> {
        val url = "${BASE}search.json".toHttpUrl().newBuilder().addQueryParameter("q", q).build().toString()
        val body = get(url) ?: return emptyList()
        val hits = (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.get("hits") as? JsonArray ?: return emptyList()
        return hits.mapNotNull { h ->
            val o = h as? JsonObject ?: return@mapNotNull null
            Hit(
                isrc = o["isrc"]?.jsonPrimitive?.contentOrNull,
                title = o["title"]?.jsonPrimitive?.contentOrNull,
                artist = o["artist"]?.jsonPrimitive?.contentOrNull,
                durationS = o["duration"]?.jsonPrimitive?.doubleOrNull,
            )
        }
    }

    private suspend fun fetchTtml(url: String, durationMs: Long): Lyrics? {
        val ttml = get(url)?.takeIf { it.contains("<tt", ignoreCase = true) } ?: return null
        val lines = Ttml.parse(ttml)
        if (lines.isEmpty()) return null
        val length = Regex("""<body\b[^>]*\bdur="([^"]+)"""").find(ttml)?.groupValues?.get(1)?.let(Ttml::time)
        return Lyrics.Synced(lines, "lrc.red", recordingMs = length?.takeIf { it > 0 && (durationMs <= 0 || abs(it - durationMs) < 10_000) } ?: 0)
    }

    private suspend fun get(url: String): String? = runInterruptible(Dispatchers.IO) {
        Http.client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json, application/ttml+xml, */*").build()).execute().use {
            if (!it.isSuccessful) null else it.body?.string()
        }
    }

    private val VERSION_WORDS = setOf(
        "live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental", "karaoke", "cappella", "acapella",
        "demo", "edit", "version", "cover", "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "extended",
    )
    private val BRACKETED = Regex("""[(\[][^)\]]*[)\]]""")
    private val ARTIST_SEPARATORS = Regex("""\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""", RegexOption.IGNORE_CASE)

    /** Lower case, accents off ("ROSÉ" is "rose"), letters and digits only. */
    private fun normalized(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun coreOf(title: String) = normalized(title.replace(BRACKETED, " ").substringBefore(" - ")).ifEmpty { normalized(title) }

    private fun versionOf(title: String): Set<String> {
        val extra = BRACKETED.findAll(title).joinToString(" ") { it.value } + " " + (if (" - " in title) title.substringAfter(" - ") else "")
        return normalized(extra).split(' ').filter { it in VERSION_WORDS }.toSet()
    }

    private fun artistsOf(artist: String) = artist.split(ARTIST_SEPARATORS).map(::normalized).filter { it.isNotEmpty() }.toSet()

    /** The hit that is this recording, or null: every test a requirement, the closest length wins. */
    internal fun best(hits: List<Hit>, title: String, artist: String, durationMs: Long): Hit? {
        val wantedTitle = coreOf(title)
        val wantedVersion = versionOf(title)
        val wantedArtists = artistsOf(artist)
        val secs = durationMs / 1000.0
        fun distance(h: Hit) = if (durationMs > 0 && h.durationS != null) abs(h.durationS - secs) else 0.0
        return hits.filter { h ->
            val t = h.title ?: return@filter false
            document(h.isrc) != null &&
                coreOf(t) == wantedTitle &&
                versionOf(t) == wantedVersion &&
                (wantedArtists.isEmpty() || artistsOf(h.artist.orEmpty()).any { it in wantedArtists }) &&
                distance(h) <= 3.0
        }.minByOrNull(::distance)
    }
}
