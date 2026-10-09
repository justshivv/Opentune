package com.opentune.data.metadata

import com.opentune.data.lossless.LosslessHttp
import com.opentune.data.model.Song
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Catalog information only: Apple previews and catalog badges never describe our audio stream. */
internal data class AppleTrackMetadata(
    val id: Long,
    val artist: String,
    val album: String,
    val releaseDate: String?,
    val genre: String?,
    val explicitness: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val url: String,
)

internal object AppleTrackMetadataRepository {
    private data class Entry(val value: AppleTrackMetadata?, val at: Long)
    private val gate = Mutex()
    private var lastRequest = 0L
    private val cache = object : LinkedHashMap<String, Entry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 200
    }

    // Apple's documented Search API needs no developer token. Bound requests and cache both
    // matches and misses; transient failures propagate so the sheet can offer a retry.
    suspend fun lookup(song: Song, durationMs: Long, country: String = Locale.getDefault().country): AppleTrackMetadata? = gate.withLock {
        if (durationMs <= 0 || song.title.isBlank() || song.artist.isBlank()) return@withLock null
        val storefront = country.uppercase(Locale.ROOT).takeIf { it in Locale.getISOCountries() } ?: "US"
        val key = "${song.title}|${song.artist}|${song.albumName}|$durationMs|$storefront"
        val now = System.nanoTime() / 1_000_000
        cache[key]?.takeIf { now - it.at < 86_400_000 }?.let { return@withLock it.value }
        var result: AppleTrackMetadata? = null
        for (store in listOf(storefront, "US").distinct()) {
            val elapsed = System.nanoTime() / 1_000_000 - lastRequest
            if (lastRequest != 0L && elapsed < 3_100) delay(3_100 - elapsed)
            val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", "${song.title} ${song.artist}")
                .addQueryParameter("country", store).addQueryParameter("media", "music")
                .addQueryParameter("entity", "song").addQueryParameter("limit", "25").build()
            val response = try { LosslessHttp.text(url.toString()) }
                finally { lastRequest = System.nanoTime() / 1_000_000 }
            result = select(Json.parseToJsonElement(response).jsonObject, song, durationMs)
            if (result != null) break
        }
        cache[key] = Entry(result, System.nanoTime() / 1_000_000)
        result
    }

    internal fun select(response: JsonObject, song: Song, durationMs: Long): AppleTrackMetadata? {
        if (durationMs <= 0 || song.title.isBlank() || song.artist.isBlank()) return null
        val wantedArtists = artists(song.artist)
        val matches = (response["results"] as? JsonArray).orEmpty().mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            if (row.text("kind") != "song") return@mapNotNull null
            val title = row.text("trackName") ?: return@mapNotNull null
            val artist = row.text("artistName") ?: return@mapNotNull null
            val album = row.text("collectionName") ?: return@mapNotNull null
            val duration = (row["trackTimeMillis"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            if (normal(title) != normal(song.title) || duration <= 0 || kotlin.math.abs(duration - durationMs) > 3_000) return@mapNotNull null
            if (wantedArtists.isEmpty() || !artists(artist).containsAll(wantedArtists)) return@mapNotNull null
            if (!song.albumName.isNullOrBlank() && normalAlbum(album) != normalAlbum(song.albumName)) return@mapNotNull null
            val id = (row["trackId"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            val url = row.text("trackViewUrl")?.toHttpUrlOrNull()?.takeIf {
                it.isHttps && it.host in setOf("music.apple.com", "itunes.apple.com") && it.username.isEmpty() && it.password.isEmpty()
            } ?: return@mapNotNull null
            AppleTrackMetadata(id, artist, album,
                row.text("releaseDate")?.let { runCatching { Instant.parse(it).toString().take(10) }.getOrNull() },
                row.text("primaryGenreName"), row.text("trackExplicitness")?.takeIf { it in setOf("explicit", "cleaned", "notExplicit") },
                row.positiveInt("trackNumber"), row.positiveInt("discNumber"), url.toString())
        }.distinctBy { it.id }
        // A search score alone is insufficient evidence for a different edition or recording.
        return matches.singleOrNull()
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.positiveInt(key: String) = (get(key) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    private fun artists(value: String) = value.split(Regex("[,;&]|\\s+(?:feat\\.|ft\\.)\\s+", RegexOption.IGNORE_CASE)).map(::normal).filter(String::isNotEmpty).toSet()
    private fun normalAlbum(value: String) = normal(value.replace(Regex("(?:\\s*(?:\\(Original (?:Motion Picture )?Soundtrack\\)|- (?:Single|EP)))+$", RegexOption.IGNORE_CASE), ""))
    private fun normal(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
}
