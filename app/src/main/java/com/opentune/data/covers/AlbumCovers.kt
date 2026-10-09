package com.opentune.data.covers

import android.content.ContentResolver
import android.content.Context
import androidx.core.net.toUri
import android.os.SystemClock
import com.opentune.BuildConfig
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
 * Square album covers from MusicBrainz and the Cover Art Archive, for songs
 * whose own artwork is a video frame (a YouTube upload rather than a
 * catalogue track) or missing (a local file without embedded art).
 *
 * A cover is only taken from a confident match: a MusicBrainz recording
 * scoring [MIN_SCORE] or more whose artist matches. Results, including "no
 * cover", are kept on disk. MusicBrainz asks for at most one request a
 * second and a User-Agent naming the app; both are kept.
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
        val f = File(context.filesDir, "album-covers.json")
        file = f
        scope.launch {
            runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), f.readText()) }
                .getOrNull()?.let(cache::putAll)
        }
    }

    /**
     * Whether [song]'s own artwork could do with a proper cover: a video
     * frame, none, or a local file's album-art address (MediaStore gives one
     * even when the file has no art; [coverFor] checks).
     */
    fun wants(song: Song): Boolean {
        val art = song.thumbnailUrl ?: return true
        return "ytimg.com" in art || art.startsWith(LOCAL_ART)
    }

    /** A local file's album art, if the phone actually has an image for it. */
    private suspend fun hasLocalArt(uri: String): Boolean = withContext(Dispatchers.IO) {
        val resolver = resolver ?: return@withContext true
        runCatching { resolver.openInputStream(uri.toUri())?.use { it.read() >= 0 } ?: false }.getOrDefault(false)
    }

    /** A Cover Art Archive image for [song], or null when there's no confident match. */
    suspend fun coverFor(song: Song): String? {
        if (!wants(song) || song.artist.isBlank() || song.title.isBlank()) return null
        if (song.thumbnailUrl?.startsWith(LOCAL_ART) == true && hasLocalArt(song.thumbnailUrl)) return null
        val cleaned = TrackNameCleaner.clean(song.title, song.artist)
        val artist = primaryArtist(cleaned.artist)
        val key = "${artist.lowercase(Locale.ROOT)}|${cleaned.title.lowercase(Locale.ROOT)}"
        cache[key]?.let { return it.ifEmpty { null } }
        val found = runCatching { lookup(cleaned.title, artist) }
            .onFailure { Log.w(TAG, "lookup failed for $key", it) }
            .getOrElse { return null } // a network failure isn't a "no", so it isn't cached
        cache[key] = found ?: NONE
        save()
        return found
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
            val cover = "https://coverartarchive.org/release-group/$group/front-500"
            // The archive answers 404 for an album it has no front cover for.
            val exists = Http.client.newCall(Request.Builder().url(cover).head().header("User-Agent", USER_AGENT).build()).execute()
                .use { it.isSuccessful }
            if (exists) return@withContext cover
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
