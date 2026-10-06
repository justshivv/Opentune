package com.opentune.data.spotify

import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * Public Spotify playlists, albums and songs by link, with no sign-in.
 *
 * The track list is read from Spotify's own embed page (the player that
 * websites put on their pages), which any visitor can load. It names each
 * song, its artists and length; nothing is streamed from Spotify. The embed
 * shows a playlist's first 100 songs, so longer playlists come over in part.
 */
object Spotify {
    private const val TAG = "Spotify"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    /** The most songs the embed page lists for a playlist. */
    const val EMBED_LIMIT = 100

    enum class Kind { PLAYLIST, ALBUM, TRACK }

    data class Link(val kind: Kind, val id: String)

    data class Track(val title: String, val artists: List<String>, val album: String?, val durationMs: Long)

    data class Collection(
        val link: Link,
        val name: String,
        /** Who made the playlist, or the album's artist. */
        val subtitle: String,
        val imageUrl: String?,
        val tracks: List<Track>,
    ) {
        /** A playlist cut off at the embed's limit; the rest isn't visible without signing in. */
        val maybeTruncated get() = link.kind == Kind.PLAYLIST && tracks.size >= EMBED_LIMIT
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val PATH = Regex("""open\.spotify\.com/(?:intl-[a-z]{2}(?:-[a-zA-Z]{2})?/)?(?:embed/)?(playlist|album|track)/([A-Za-z0-9]{22})""")
    private val URI = Regex("""spotify:(playlist|album|track):([A-Za-z0-9]{22})""")
    private val SHORT = Regex("""https?://(?:spotify\.link|spotify\.app\.link)/\S+""")
    private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** The playlist, album or song a pasted or shared text points at, without going online. */
    fun parse(text: String): Link? {
        val m = PATH.find(text) ?: URI.find(text) ?: return null
        return Link(Kind.valueOf(m.groupValues[1].uppercase()), m.groupValues[2])
    }

    /** Whether [text] holds something [resolve] might turn into a link (including spotify.link short links). */
    fun looksLikeSpotify(text: String) = parse(text) != null || SHORT.containsMatchIn(text)

    /** [parse], following a spotify.link short link to where it leads. */
    suspend fun resolve(text: String): Link? = parse(text) ?: withContext(Dispatchers.IO) {
        val short = SHORT.find(text)?.value ?: return@withContext null
        runCatching {
            Http.client.newCall(Request.Builder().url(short).header("User-Agent", UA).build()).execute().use { r ->
                // OkHttp follows the redirects; the final address, or the page's own links, name the target.
                parse(r.request.url.toString()) ?: parse(r.body?.string().orEmpty())
            }
        }.onFailure { Log.w(TAG, "short link failed", it) }.getOrNull()
    }

    /** The name, cover and songs behind [link]. */
    suspend fun load(link: Link): Collection = withContext(Dispatchers.IO) {
        val url = "https://open.spotify.com/embed/${link.kind.name.lowercase()}/${link.id}"
        val html = Http.client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (r.code == 404) throw IOException("Spotify doesn't have this, or it's private")
            if (!r.isSuccessful) throw IOException("Spotify answered ${r.code}")
            r.body?.string().orEmpty()
        }
        parseEmbed(html, link) ?: throw IOException("Couldn't read this from Spotify. Private playlists can't be imported.")
    }

    /** The collection described by an embed page, or null if the page isn't one. */
    internal fun parseEmbed(html: String, link: Link): Collection? {
        val data = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
        val entity = runCatching {
            json.parseToJsonElement(data).jsonObject.o("props").o("pageProps").o("state").o("data").o("entity")
        }.getOrNull() ?: return null
        val name = entity.str("name") ?: entity.str("title") ?: return null
        val subtitle = entity.str("subtitle").orEmpty()
        val image = (entity.o("coverArt")?.get("sources") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("url") }
        val tracks = if (link.kind == Kind.TRACK) {
            listOf(Track(name, artists(subtitle), null, (entity["duration"] as? JsonPrimitive)?.longOrNull ?: 0))
        } else {
            (entity["trackList"] as? JsonArray).orEmpty().mapNotNull { t -> track(t, album = name.takeIf { link.kind == Kind.ALBUM }) }
        }
        return Collection(link, name, subtitle, image, tracks)
    }

    private fun track(e: JsonElement, album: String?): Track? {
        val o = e as? JsonObject ?: return null
        if ((o.str("entityType") ?: "track") != "track") return null // podcast episodes and the like
        val title = o.str("title")?.takeIf { it.isNotBlank() } ?: return null
        return Track(title, artists(o.str("subtitle").orEmpty()), album, (o["duration"] as? JsonPrimitive)?.longOrNull ?: 0)
    }

    /** "KAROL G, Judeline, rusowsky" as three names. */
    internal fun artists(subtitle: String) = subtitle.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun JsonObject?.o(key: String): JsonObject? = this?.get(key) as? JsonObject
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
