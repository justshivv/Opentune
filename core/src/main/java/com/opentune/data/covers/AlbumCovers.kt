package com.opentune.data.covers

import android.content.ContentResolver
import android.content.Context
import androidx.core.net.toUri
import android.os.SystemClock
import com.opentune.core.BuildConfig
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.lyrics.TrackNameCleaner
import com.opentune.data.model.Song
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Sharp album covers for the playing song, from three free catalogues in
 * turn: Apple's (masters up to 3000px), Deezer's (up to 1200px) and the
 * Cover Art Archive through MusicBrainz. Video frames, missing art and
 * YouTube Music's own covers are all replaced when one of them matches.
 *
 * A cover is only taken from a confident match. Apple and Deezer need the
 * same title, credited artist and length (within 3 s), and the same album
 * when the song names one, so another edition's sleeve is never shown.
 * MusicBrainz needs a recording scoring [MIN_SCORE] or more whose artist
 * matches. Results, including "no cover", are kept on disk; a source that
 * couldn't be reached isn't a "no". MusicBrainz asks for at most one request
 * a second and a User-Agent naming the app; both are kept.
 */
object AlbumCovers {
    private const val TAG = "AlbumCovers"
    private const val MIN_SCORE = 90
    private const val MAX_ENTRIES = 3_000
    private const val SPACING_MS = 1_100L
    private const val NONE = ""
    private const val LOCAL_ART = "content://media/external/audio/albumart"
    private val USER_AGENT = "OpenTune/${BuildConfig.VERSION_NAME} ( https://github.com/justshivv/Opentune )"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private var lastRequestAt = 0L
    private val cache = ConcurrentHashMap<String, String>()
    private var file: File? = null
    /** Only for opening local files' album art; a resolver rather than a Context. */
    private var resolver: ContentResolver? = null

    fun init(context: Context) {
        resolver = context.applicationContext.contentResolver
        // The first file held Cover Art Archive answers only; its "none"s would hide Apple and Deezer covers.
        File(context.filesDir, "album-covers.json").delete()
        val f = File(context.filesDir, "album-covers-v2.json")
        file = f
        scope.launch {
            runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), f.readText()) }
                .getOrNull()?.let(cache::putAll)
        }
    }

    /**
     * Whether [song] is a track a catalogue could have a cover for: anything
     * but a radio station. A local file's album-art address is checked in
     * [coverFor] (MediaStore gives one even when the file has no art).
     */
    fun wants(song: Song): Boolean = !com.opentune.data.radio.Radio.isRadio(song.videoId)

    private fun keyOf(song: Song): String {
        val cleaned = TrackNameCleaner.clean(song.title, song.artist)
        return listOf(primaryArtist(cleaned.artist), cleaned.title, song.albumName.orEmpty()).joinToString("|") { it.lowercase(Locale.ROOT) }
    }

    /** A previously resolved cover, ready before the player displays anything. */
    fun cached(song: Song): String? = if (wants(song)) cache[keyOf(song)]?.ifEmpty { null } else null

    /** A local file's album art, if the phone actually has an image for it. */
    private suspend fun hasLocalArt(uri: String): Boolean = withContext(Dispatchers.IO) {
        val resolver = resolver ?: return@withContext true
        runCatching { resolver.openInputStream(uri.toUri())?.use { it.read() >= 0 } ?: false }.getOrDefault(false)
    }

    /**
     * The best cover for [song] (Apple, then Deezer, then the Cover Art
     * Archive), or null when none matches confidently. [durationMs] is the
     * player's exact length; Apple and Deezer are only asked with it.
     */
    suspend fun coverFor(song: Song, durationMs: Long = 0): String? {
        if (!wants(song) || song.artist.isBlank() || song.title.isBlank()) return null
        if (song.thumbnailUrl?.startsWith(LOCAL_ART) == true && hasLocalArt(song.thumbnailUrl)) return null
        val key = keyOf(song)
        cache[key]?.let { return it.ifEmpty { null } }
        val cleaned = TrackNameCleaner.clean(song.title, song.artist)
        val artist = primaryArtist(cleaned.artist)
        var unreachable = false
        suspend fun ask(source: String, block: suspend () -> String?): String? = runCatching { block() }
            .onFailure { unreachable = true; Log.w(TAG, "$source lookup failed for $key", it) }
            .getOrNull()
        val found = (if (durationMs > 0) ask("Apple") { apple(song, durationMs) } else null)
            ?: (if (durationMs > 0) ask("Deezer") { deezer(cleaned.title, artist, song.albumName, durationMs) } else null)
            ?: ask("MusicBrainz") { lookup(cleaned.title, artist) }
        // A source that couldn't be reached isn't a "no": only a full set of answers is kept as "none".
        if (found == null && (unreachable || durationMs <= 0)) return null
        cache[key] = found ?: NONE
        save()
        return found
    }

    /** Apple's cover for the exact recording, as its catalogue lookup matched it. */
    private suspend fun apple(song: Song, durationMs: Long): String? =
        com.opentune.data.metadata.AppleTrackMetadataRepository.release(song, durationMs)?.artworkUrl

    /** Deezer's cover for the same title, artist and length (and album, when the song names one). */
    private suspend fun deezer(title: String, artist: String, album: String?, durationMs: Long): String? = withContext(Dispatchers.IO) {
        val url = "https://api.deezer.com/search".toHttpUrl().newBuilder()
            // Plain words: the field syntax (artist:"…" track:"…") answered nothing when tested on 2026-10-24.
            .addQueryParameter("q", "$title $artist")
            .addQueryParameter("limit", "15")
            .build()
        val body = Http.client.newCall(Request.Builder().url(url).build()).execute()
            .use { r -> if (r.isSuccessful) r.body?.string() else throw java.io.IOException("Deezer answered ${r.code}") }
            ?: return@withContext null
        deezerCover(json.parseToJsonElement(body).jsonObject, title, artist, album, durationMs)
    }

    internal fun deezerCover(response: JsonObject, title: String, artist: String, album: String?, durationMs: Long): String? {
        // Over quota Deezer answers 200 with an error object: no answer, not a "no".
        if (response["error"] != null) throw java.io.IOException("Deezer: ${response["error"]}")
        val wanted = normalize(artist)
        val matches = response["data"].array().map { it.obj() }.filter { row ->
            val name = row["title_short"].string() ?: row["title"].string() ?: return@filter false
            val credited = row["artist"].obj()["name"].string()?.let(::normalize) ?: return@filter false
            val seconds = (row["duration"] as? JsonPrimitive)?.intOrNull ?: return@filter false
            normalize(TrackNameCleaner.clean(name, artist).title) == normalize(title) &&
                (credited == wanted || credited.contains(wanted) || wanted.contains(credited)) &&
                kotlin.math.abs(seconds * 1000L - durationMs) <= 3_000
        }
        val sameAlbum = album?.takeIf { it.isNotBlank() }?.let { a -> matches.filter { normalize(it["album"].obj()["title"].string().orEmpty()) == normalize(a) } }
        // With an album named, only that album's sleeve; without one, the top match's.
        val pick = (if (album.isNullOrBlank()) matches else sameAlbum.orEmpty()).firstOrNull() ?: return null
        return pick["album"].obj()["cover_xl"].string()?.takeIf { it.startsWith("https://") }
    }

    private suspend fun lookup(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val query = "recording:\"${escape(title)}\" AND artist:\"${escape(artist)}\""
        val url = "https://musicbrainz.org/ws/2/recording".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", "5")
            .build()
        val body = politely { Http.client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute() }
            // "Busy" (503) and the like are no answer at all, so they throw and aren't cached.
            .use { r -> if (r.isSuccessful) r.body?.string() else throw java.io.IOException("MusicBrainz answered ${r.code}") }
            ?: return@withContext null
        val groups = releaseGroups(json.parseToJsonElement(body).jsonObject, artist)
        for (group in groups.take(3)) {
            // The archive answers 404 for an album it has no front cover for; older covers may lack the 1200px copy.
            for (size in listOf(1200, 500)) {
                val cover = "https://coverartarchive.org/release-group/$group/front-$size"
                val exists = Http.client.newCall(Request.Builder().url(cover).head().header("User-Agent", USER_AGENT).build()).execute()
                    .use { it.isSuccessful }
                if (exists) return@withContext cover
            }
        }
        null
    }

    /**
     * Release groups of confident matches, best first: recordings scoring
     * [MIN_SCORE]+ whose credited artist matches, official albums and singles
     * before compilations.
     */
    internal fun releaseGroups(response: JsonObject, artist: String): List<String> {
        val wanted = normalize(artist)
        val out = LinkedHashSet<String>()
        response["recordings"].array().map { it.obj() }
            .filter { ((it["score"] as? JsonPrimitive)?.intOrNull ?: 0) >= MIN_SCORE }
            .filter { rec ->
                val credited = rec["artist-credit"].array().mapNotNull { it.obj()["name"].string() ?: it.obj()["artist"].obj()["name"].string() }
                credited.any { normalize(it) == wanted || normalize(it).contains(wanted) || wanted.contains(normalize(it)) }
            }
            .forEach { rec ->
                rec["releases"].array().map { it.obj() }
                    .sortedBy { rel ->
                        val group = rel["release-group"].obj()
                        val primary = group["primary-type"].string()
                        val secondary = group["secondary-types"].array().isNotEmpty()
                        val official = rel["status"].string() == "Official"
                        when {
                            official && !secondary && (primary == "Album" || primary == "Single" || primary == "EP") -> 0
                            official && !secondary -> 1
                            official -> 2
                            else -> 3
                        }
                    }
                    .mapNotNull { it["release-group"].obj()["id"].string() }
                    .forEach(out::add)
            }
        return out.toList()
    }

    /** MusicBrainz's one-request-a-second rule, across the app. */
    private suspend fun <T> politely(block: () -> T): T =
        com.opentune.data.metadata.MusicBrainzRequests.politely { block() }

    private fun save() {
        val f = file ?: return
        scope.launch {
            runCatching {
                if (cache.size > MAX_ENTRIES) cache.keys.take(cache.size - MAX_ENTRIES).forEach(cache::remove)
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(json.encodeToString(MapSerializer(String.serializer(), String.serializer()), HashMap(cache)))
                tmp.renameTo(f)
            }
        }
    }

    private fun primaryArtist(artist: String): String =
        artist.split(", ", " & ", " x ", " feat. ", " ft. ").first().trim().ifEmpty { artist }

    private fun normalize(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Lucene's special characters, escaped for a quoted phrase. */
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray).orEmpty()
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull
}
