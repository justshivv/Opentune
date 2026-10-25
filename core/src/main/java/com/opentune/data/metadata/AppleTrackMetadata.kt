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
data class AppleTrackMetadata(
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

/**
 * The album a song was matched to in Apple's catalogue: enough to show its
 * sharp cover and to ask for its animated cover. Clean and explicit copies
 * of one album are separate albums to Apple with the same art, so either
 * stands for both.
 */
data class AppleRelease(
    val albumId: Long,
    val album: String,
    /** The cover at Apple's 100px size; any size up to the 3000px master is a change of the last path part. */
    val artworkUrl: String?,
    /** The storefront (lower case) the match came from: the animated cover is asked for there. */
    val storefront: String,
)

object AppleTrackMetadataRepository {
    private data class Entry(val value: AppleTrackMetadata?, val release: AppleRelease?, val at: Long)
    internal data class Candidate(val track: AppleTrackMetadata, val albumId: Long?, val artworkUrl: String?)
    private val gate = Mutex()
    private var lastRequest = 0L
    private val cache = object : LinkedHashMap<String, Entry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 200
    }

    // Apple's documented Search API needs no developer token. Bound requests and cache both
    // matches and misses; transient failures propagate so the sheet can offer a retry.
    suspend fun lookup(song: Song, durationMs: Long, country: String = Locale.getDefault().country): AppleTrackMetadata? =
        search(song, durationMs, country)?.value

    /** The album [song] is on, for its cover and animated cover; null unless the match is certain. */
    suspend fun release(song: Song, durationMs: Long, country: String = Locale.getDefault().country): AppleRelease? =
        search(song, durationMs, country)?.release

    private suspend fun search(song: Song, durationMs: Long, country: String): Entry? = gate.withLock {
        if (durationMs <= 0 || song.title.isBlank() || song.artist.isBlank()) return@withLock null
        val storefront = country.uppercase(Locale.ROOT).takeIf { it in Locale.getISOCountries() } ?: "US"
        val key = "${song.title}|${song.artist}|${song.albumName}|$durationMs|$storefront"
        val now = System.nanoTime() / 1_000_000
        cache[key]?.takeIf { now - it.at < 86_400_000 }?.let { return@withLock it }
        var track: AppleTrackMetadata? = null
        var release: AppleRelease? = null
        for (store in listOf(storefront, "US").distinct()) {
            val elapsed = System.nanoTime() / 1_000_000 - lastRequest
            if (lastRequest != 0L && elapsed < 3_100) delay(3_100 - elapsed)
            val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", "${song.title} ${song.artist}")
                .addQueryParameter("country", store).addQueryParameter("media", "music")
                .addQueryParameter("entity", "song").addQueryParameter("limit", "25").build()
            val response = try { LosslessHttp.text(url.toString()) }
                finally { lastRequest = System.nanoTime() / 1_000_000 }
            val found = candidates(Json.parseToJsonElement(response).jsonObject, song, durationMs)
            track = track ?: single(found)
            release = release ?: releaseOf(found, store)
            // Clean and explicit copies leave the track ambiguous everywhere; the album is then enough.
            if (track != null || release != null) break
        }
        Entry(track, release, System.nanoTime() / 1_000_000).also { cache[key] = it }
    }

    internal fun select(response: JsonObject, song: Song, durationMs: Long): AppleTrackMetadata? =
        single(candidates(response, song, durationMs))

    internal fun selectRelease(response: JsonObject, song: Song, durationMs: Long, storefront: String = "US"): AppleRelease? =
        releaseOf(candidates(response, song, durationMs), storefront)

    // A search score alone is insufficient evidence for a different edition or recording.
    private fun single(found: List<Candidate>) = found.distinctBy { it.track.id }.singleOrNull()?.track

    /** Every match on one album (by name): its art and id. Two albums is a guess, so none. */
    private fun releaseOf(found: List<Candidate>, storefront: String): AppleRelease? {
        if (found.isEmpty() || found.map { normalAlbum(it.track.album) }.distinct().size != 1) return null
        val pick = found.firstOrNull { it.albumId != null } ?: return null
        return AppleRelease(pick.albumId!!, pick.track.album, pick.artworkUrl, storefront.lowercase(Locale.ROOT))
    }

    internal fun candidates(response: JsonObject, song: Song, durationMs: Long): List<Candidate> {
        if (durationMs <= 0 || song.title.isBlank() || song.artist.isBlank()) return emptyList()
        val wantedArtists = artists(song.artist)
        return (response["results"] as? JsonArray).orEmpty().mapNotNull { item ->
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
            val track = AppleTrackMetadata(id, artist, album,
                row.text("releaseDate")?.let { runCatching { Instant.parse(it).toString().take(10) }.getOrNull() },
                row.text("primaryGenreName"), row.text("trackExplicitness")?.takeIf { it in setOf("explicit", "cleaned", "notExplicit") },
                row.positiveInt("trackNumber"), row.positiveInt("discNumber"), url.toString())
            Candidate(
                track,
                (row["collectionId"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
                row.text("artworkUrl100")?.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host.endsWith(".mzstatic.com") }?.toString(),
            )
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.positiveInt(key: String) = (get(key) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    private fun artists(value: String) = value.split(Regex("[,;&]|\\s+(?:feat\\.|ft\\.)\\s+", RegexOption.IGNORE_CASE)).map(::normal).filter(String::isNotEmpty).toSet()
    private fun normalAlbum(value: String) = normal(value.replace(Regex("(?:\\s*(?:\\(Original (?:Motion Picture )?Soundtrack\\)|- (?:Single|EP)))+$", RegexOption.IGNORE_CASE), ""))
    private fun normal(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
}
