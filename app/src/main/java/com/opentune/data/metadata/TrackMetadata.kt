package com.opentune.data.metadata

import com.opentune.BuildConfig
import com.opentune.data.lossless.LosslessHttp
import com.opentune.data.model.Song
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** One shared gate for cover lookup and recording lookup. Network failures are never cached. */
internal object MusicBrainzRequests {
    private val gate = Mutex()
    private var last = 0L
    suspend fun <T> politely(block: suspend () -> T): T = gate.withLock {
        val wait = 1_100L - (System.nanoTime() - last) / 1_000_000
        if (wait > 0) delay(wait)
        try { block() } finally { last = System.nanoTime() }
    }
}

data class TrackMetadata(
    val recordingId: String,
    val title: String,
    val credits: String,
    val isrcs: List<String>,
    val album: String?,
    val releaseDate: String?,
    val durationMs: Long,
)

internal object TrackMetadataRepository {
    private val cache = object : LinkedHashMap<String, TrackMetadata?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackMetadata?>?) = size > 200
    }
    private val lock = Mutex()
    private val headers get() = mapOf("User-Agent" to "OpenTune/${BuildConfig.VERSION_NAME} (https://github.com/justshivv/Opentune)")

    suspend fun lookup(song: Song, durationMs: Long): TrackMetadata? = lock.withLock {
        if (durationMs <= 0 || song.artist.isBlank() || song.title.isBlank()) return@withLock null
        val key = "${song.videoId}|${song.title}|${song.artist}|${song.albumName}|$durationMs"
        if (cache.containsKey(key)) return@withLock cache[key]
        val query = "recording:\"${escape(song.title)}\" AND artist:\"${escape(song.artist)}\""
        val url = "https://musicbrainz.org/ws/2/recording/".toHttpUrl().newBuilder()
            .addQueryParameter("query", query).addQueryParameter("fmt", "json")
            .addQueryParameter("limit", "10").build()
        val response = MusicBrainzRequests.politely { LosslessHttp.text(url.toString(), headers) }
        val match = select(Json.parseToJsonElement(response).jsonObject, song, durationMs)
        cache[key] = match
        match
    }

    /** Reject ambiguity, different versions and duration/artist mismatches rather than invent credits. */
    internal fun select(response: JsonObject, song: Song, durationMs: Long): TrackMetadata? {
        if (durationMs <= 0) return null
        val wantedArtists = song.artist.split(Regex("[,;&]| feat\\. | ft\\. | & ", RegexOption.IGNORE_CASE))
            .map(::normal).filter(String::isNotEmpty)
        val matches = (response["recordings"] as? JsonArray).orEmpty().mapNotNull { item ->
            val rec = item as? JsonObject ?: return@mapNotNull null
            val title = rec.text("title") ?: return@mapNotNull null
            val length = rec["length"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (normal(title) != normal(song.title) || kotlin.math.abs(length - durationMs) > 3_000) return@mapNotNull null
            val artists = (rec["artist-credit"] as? JsonArray).orEmpty().mapNotNull { a ->
                (a as? JsonObject)?.let { it.text("name") ?: (it["artist"] as? JsonObject)?.text("name") }
            }
            if (wantedArtists.isEmpty() || wantedArtists.none { it in artists.map(::normal) }) return@mapNotNull null
            val releases = (rec["releases"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val album = releases.firstOrNull { normal(it.text("title").orEmpty()) == normal(song.albumName.orEmpty()) }
                ?: if (!song.albumName.isNullOrBlank()) return@mapNotNull null else releases.firstOrNull()
            val id = rec.text("id")?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: return@mapNotNull null
            TrackMetadata(id, title, artists.joinToString(", "),
                (rec["isrcs"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                    .filter { it.matches(Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) }.distinct(),
                album?.text("title"), album?.text("date") ?: rec.text("first-release-date"), length)
        }.distinctBy { it.recordingId }
        return matches.singleOrNull()
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun normal(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
}
