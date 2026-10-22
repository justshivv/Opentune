package com.opentune.data.links

import com.opentune.data.Http
import com.opentune.data.metadata.Deezer
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * A song link from another service (Apple Music, Deezer, Tidal, Amazon
 * Music, SoundCloud, or a universal link page), read for the song's title
 * and artist so the same song can be found on YouTube Music. A Deezer track
 * is read from Deezer's own catalogue; anything else goes to UniTune, which
 * converts links between services.
 */
object MusicLinks {
    private const val UNITUNE = "https://api.unitune.art/v1-alpha.1/links"
    private val json = Json { ignoreUnknownKeys = true }
    private val DEEZER_TRACK = Regex("""deezer\.com/(?:[a-z]{2}/)?track/(\d+)""")

    data class Found(val title: String, val artist: String)

    private val HOSTS = listOf(
        "music.apple.com", "itunes.apple.com", "deezer.com", "deezer.page.link", "link.deezer.com",
        "tidal.com", "listen.tidal.com", "music.amazon.", "soundcloud.com", "on.soundcloud.com",
        "song.link", "album.link", "odesli.co", "unitune.art", "pandora.com", "napster.com",
    )

    /** The link in [text] when it points at a service this can read, else null. */
    fun linkIn(text: String?): String? {
        val raw = text?.let { Regex("""https?://\S+""").find(it)?.value } ?: return null
        val host = runCatching { URI(raw).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return null
        return raw.takeIf { HOSTS.any { h -> if (h.endsWith(".")) host.startsWith(h) else host == h || host.endsWith(".$h") } }
    }

    /** The song [url] names, or null when neither Deezer nor UniTune knows it. */
    suspend fun resolve(url: String): Found? {
        DEEZER_TRACK.find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { id ->
            Deezer.track(id)?.let { return Found(it.title, it.artist) }
        }
        val body = get(UNITUNE.toHttpUrl().newBuilder().addQueryParameter("url", url).build().toString()) ?: return null
        return parseUniTune(body)
    }

    /** UniTune's answer, shaped as Song.link's was: the entity the link named, with its title and artist. */
    internal fun parseUniTune(body: String): Found? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val key = root["entityUniqueId"]?.jsonPrimitive?.contentOrNull ?: return null
        val entity = (root["entitiesByUniqueId"] as? JsonObject)?.get(key) as? JsonObject ?: return null
        val title = entity["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return Found(title, entity["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    private suspend fun get(url: String): String? = runInterruptible(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).header("User-Agent", "OpenTune (+https://github.com/justshivv/Opentune)").build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()
    }
}
