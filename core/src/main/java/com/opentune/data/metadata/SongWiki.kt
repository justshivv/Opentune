package com.opentune.data.metadata

import com.opentune.data.Http
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * A song's Wikipedia article, from Wikipedia's public REST API: its lead paragraph and the
 * page it's from. Only an article that is plainly about this song counts: its title is the
 * song's (a "(… song)" note after it is fine), and Wikipedia's own one-line description
 * calls it a song or single and names one of its artists. Anything less is left out rather
 * than show the story of some other song with the same name.
 */
object SongWiki {
    data class Article(val text: String, val url: String)

    private const val SEARCH = "https://en.wikipedia.org/w/rest.php/v1/search/page"
    private const val SUMMARY = "https://en.wikipedia.org/api/rest_v1/page/summary/"
    private val json = Json { ignoreUnknownKeys = true }

    /** The article about [title] by [artist], or null when there's none Wikipedia is sure of. */
    suspend fun article(title: String, artist: String): Article? {
        val bare = bareTitle(title)
        if (bare.isBlank() || artist.isBlank()) return null
        val searchUrl = SEARCH.toHttpUrl().newBuilder()
            .addQueryParameter("q", "$bare ${artist.split(", ", " & ").first()} song")
            .addQueryParameter("limit", "6").build().toString()
        val key = get(searchUrl)?.let { pickPage(it, title, artist) } ?: return null
        val summary = get(SUMMARY + java.net.URLEncoder.encode(key, "UTF-8").replace("+", "%20")) ?: return null
        return parseSummary(summary)
    }

    /** The key of the search result that is this song's own article. */
    internal fun pickPage(body: String, title: String, artist: String): String? {
        val pages = (runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()?.get("pages") as? JsonArray) ?: return null
        val wanted = normal(bareTitle(title))
        val names = artistNames(artist)
        if (wanted.isEmpty() || names.isEmpty()) return null
        return pages.firstNotNullOfOrNull { item ->
            val page = item as? JsonObject ?: return@firstNotNullOfOrNull null
            val key = page.text("key") ?: return@firstNotNullOfOrNull null
            val pageTitle = page.text("title") ?: return@firstNotNullOfOrNull null
            val description = page.text("description").orEmpty()
            // "Song", or "Song (Artist song)": the note in brackets is Wikipedia telling namesakes apart.
            val note = Regex("""\s*\(([^)]*)\)\s*$""").find(pageTitle)?.groupValues?.get(1).orEmpty()
            val head = pageTitle.replace(Regex("""\s*\([^)]*\)\s*$"""), "")
            if (normal(head) != wanted) return@firstNotNullOfOrNull null
            val about = "$description $note".lowercase(Locale.ROOT)
            if (!Regex("""\b(song|single)\b""").containsMatchIn(about)) return@firstNotNullOfOrNull null
            if (names.none { normal(about).contains(it) }) return@firstNotNullOfOrNull null
            key
        }
    }

    /** The lead of a page summary answer, with the address to read the rest. */
    internal fun parseSummary(body: String): Article? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if (root.text("type") == "disambiguation") return null
        val text = root.text("extract")?.trim()?.takeIf { it.length >= 40 } ?: return null
        val url = ((root["content_urls"] as? JsonObject)?.get("mobile") as? JsonObject)?.text("page")
            ?: ((root["content_urls"] as? JsonObject)?.get("desktop") as? JsonObject)?.text("page")
            ?: return null
        if (!url.startsWith("https://en.m.wikipedia.org/") && !url.startsWith("https://en.wikipedia.org/")) return null
        return Article(text, url)
    }

    /** The title without what YouTube adds to it: `(Official Video)`, `(From "Film")`, `[Lyric Video]`. */
    fun bareTitle(title: String): String =
        title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").replace(Regex("""\s+-\s+.*$"""), "").trim()

    private fun artistNames(artist: String): List<String> =
        artist.split(Regex("""\s*,\s*|\s+&\s+|\s+x\s+|\s+(?:feat\.?|ft\.?|featuring|with)\s+""", RegexOption.IGNORE_CASE))
            .map(::normal).filter { it.length >= 2 }

    private fun normal(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    private suspend fun get(url: String): String? = runInterruptible(Dispatchers.IO) {
        runCatching {
            // Wikimedia asks every client to say who it is.
            Http.client.newCall(Request.Builder().url(url).header("User-Agent", "NepTune (+https://github.com/justshivv/Opentune)").build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()
    }
}
