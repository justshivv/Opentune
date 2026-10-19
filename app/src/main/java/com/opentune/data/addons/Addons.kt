package com.opentune.data.addons

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.model.Song
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Addons: music catalogues other people run, added by URL. An addon is a web
 * server that answers three requests with JSON (see [AddonProtocol] and
 * docs/addons.md): what it is, what it has for a search, and where a track's
 * audio file is. Nothing from the addon runs in the app.
 *
 * Addon songs are [Song]s whose id is `addon:<addon id>:<track id>`. Their
 * audio is asked for only when they start playing, since addons may hand
 * out links that expire.
 */
object Addons {
    const val PREFIX = "addon:"
    private const val TAG = "Addons"
    private const val PREFS = "addons"
    private const val K_LIST = "list"

    private var prefs: SharedPreferences? = null
    private val _installed = MutableStateFlow<List<Addon>>(emptyList())
    val installed: StateFlow<List<Addon>> = _installed.asStateFlow()

    private val client by lazy {
        Http.client.newBuilder().callTimeout(12, TimeUnit.SECONDS).followRedirects(false).build()
    }

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _installed.value = runCatching { AddonProtocol.decodeList(p.getString(K_LIST, null)) }.getOrDefault(emptyList())
    }

    fun isAddon(id: String) = id.startsWith(PREFIX)

    /** The addon a song came from, if it's still added. */
    fun addonOf(songId: String): Addon? = AddonProtocol.splitSongId(songId)?.let { (addon, _) -> find(addon) }

    private fun find(id: String) = _installed.value.firstOrNull { it.id == id }

    /**
     * Reads the manifest at [address] (the addon's root or its manifest.json),
     * runs a test search, and adds the addon, replacing an older copy with the
     * same id.
     */
    suspend fun add(address: String): Result<Addon> = withContext(Dispatchers.IO) {
        runCatching {
            val base = AddonProtocol.baseUrl(address) ?: throw IOException("That doesn't look like an addon address")
            val manifest = AddonProtocol.parseManifest(get("$base/manifest.json"), base)
            // An addon that can't answer a search would only fail later, in the middle of one.
            AddonProtocol.parseSearch(get(AddonProtocol.searchUrl(base, "test", 1)))
            val list = _installed.value.filterNot { it.id == manifest.id } + manifest
            save(list)
            manifest
        }
    }

    fun remove(id: String) = save(_installed.value.filterNot { it.id == id })

    fun setEnabled(id: String, enabled: Boolean) = save(_installed.value.map { if (it.id == id) it.copy(enabled = enabled) else it })

    private fun save(list: List<Addon>) {
        _installed.value = list
        prefs?.edit { putString(K_LIST, AddonProtocol.encodeList(list)) }
    }

    /** Songs for [query] from every switched-on addon, each addon's on its own; one that fails is left out. */
    suspend fun search(query: String, limit: Int = 20): List<Pair<Addon, List<Song>>> = coroutineScope {
        _installed.value.filter { it.enabled }.map { addon ->
            async(Dispatchers.IO) {
                runCatching { addon to AddonProtocol.parseSearch(get(AddonProtocol.searchUrl(addon.baseUrl, query, limit))).map { it.toSong(addon) } }
                    .onFailure { Log.w(TAG, "Search on ${addon.id} failed", it) }
                    .getOrNull()
            }
        }.awaitAll().filterNotNull().filter { it.second.isNotEmpty() }
    }

    /** Where an addon song's audio is right now. */
    suspend fun stream(songId: String): AddonStream = withContext(Dispatchers.IO) {
        val (addonId, trackId) = AddonProtocol.splitSongId(songId) ?: throw IOException("Not an addon song")
        val addon = find(addonId) ?: throw IOException("The addon this song came from has been removed")
        AddonProtocol.parseStream(get(AddonProtocol.streamUrl(addon.baseUrl, trackId)))
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { r ->
            if (r.code == 404) throw IOException("The addon doesn't have that")
            if (!r.isSuccessful) throw IOException("The addon answered ${r.code}")
            val body = r.body ?: throw IOException("The addon sent nothing back")
            if (body.contentLength() > AddonProtocol.MAX_BODY) throw IOException("The addon's answer is too large")
            val bytes = body.byteStream().readNBytesCompat(AddonProtocol.MAX_BODY + 1)
            if (bytes.size > AddonProtocol.MAX_BODY) throw IOException("The addon's answer is too large")
            return bytes.decodeToString()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/** One added addon, as its manifest describes it. */
data class Addon(
    val id: String,
    val name: String,
    val baseUrl: String,
    val catalogue: String,
    val version: String? = null,
    val description: String? = null,
    val homepage: String? = null,
    val enabled: Boolean = true,
)

data class AddonTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Long? = null,
    val artwork: String? = null,
) {
    fun toSong(addon: Addon) = Song(
        videoId = AddonProtocol.songId(addon.id, id),
        title = title,
        artist = artist,
        thumbnailUrl = artwork,
        durationText = durationSeconds?.let { "%d:%02d".format(it / 60, it % 60) },
        albumName = album,
    )
}

data class AddonStream(
    val url: String,
    val mimeType: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
)

/**
 * The addon protocol, version 1, kept apart from the network so it can be
 * tested: building request URLs, and reading and checking what comes back.
 */
internal object AddonProtocol {
    const val VERSION = 1
    const val MAX_BODY = 1_000_000
    private val ID = Regex("[A-Za-z0-9._-]{1,64}")
    /** Playlists and stream manifests rather than an audio file. */
    private val NOT_A_FILE = setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl", "application/dash+xml")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun songId(addonId: String, trackId: String) = "${Addons.PREFIX}$addonId:$trackId"

    fun splitSongId(songId: String): Pair<String, String>? {
        if (!songId.startsWith(Addons.PREFIX)) return null
        val rest = songId.removePrefix(Addons.PREFIX)
        val colon = rest.indexOf(':')
        if (colon <= 0 || colon == rest.lastIndex) return null
        return rest.substring(0, colon) to rest.substring(colon + 1)
    }

    /** The addon's root for what was typed: its root or its manifest.json, https unless it's on this network. */
    fun baseUrl(address: String): String? {
        var trimmed = address.trim().trimEnd('/')
        if (trimmed.endsWith("/manifest.json")) trimmed = trimmed.removeSuffix("/manifest.json")
        if (trimmed.isEmpty()) return null
        if ("://" !in trimmed) trimmed = "https://$trimmed"
        val url = trimmed.toHttpUrlOrNull() ?: return null
        if (!allowedUrl(url.toString())) return null
        if (url.query != null || url.fragment != null) return null
        return url.toString().trimEnd('/')
    }

    /** https anywhere; plain http only to this device or a home network, for testing an addon. */
    fun allowedUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" || (parsed.scheme == "http" && isLocalHost(parsed.host))
    }

    fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host == "10.0.2.2") return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
    }

    fun searchUrl(base: String, query: String, limit: Int): String =
        base.toHttpUrlOrNull()!!.newBuilder().addPathSegment("search")
            .addQueryParameter("q", query).addQueryParameter("limit", limit.toString()).build().toString()

    fun streamUrl(base: String, trackId: String): String =
        base.toHttpUrlOrNull()!!.newBuilder().addPathSegment("stream").addPathSegment(trackId).build().toString()

    fun parseManifest(text: String, base: String): Addon {
        val o = obj(text, "manifest")
        val protocol = o.int("opentune") ?: throw IOException("The manifest doesn't say which OpenTune addon version it follows")
        if (protocol > VERSION) throw IOException("This addon needs a newer OpenTune")
        val id = o.string("id")?.takeIf { ID.matches(it) } ?: throw IOException("The manifest needs an id of letters, numbers, dots, dashes or underscores")
        val name = o.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: throw IOException("The manifest needs a name")
        val catalogue = o.string("catalogue")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IOException("The manifest needs a catalogue line saying whose music this is and why the addon may share it")
        return Addon(
            id = id,
            name = name.take(60),
            baseUrl = base,
            catalogue = catalogue.take(300),
            version = o.string("version")?.take(30),
            description = o.string("description")?.take(300),
            homepage = o.string("homepage")?.takeIf { it.startsWith("https://") },
        )
    }

    fun parseSearch(text: String): List<AddonTrack> {
        val tracks = obj(text, "search answer")["tracks"] as? JsonArray ?: throw IOException("The search answer needs a tracks list")
        return tracks.mapNotNull { e ->
            val t = e as? JsonObject ?: return@mapNotNull null
            val id = t.string("id")?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return@mapNotNull null
            val title = t.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            AddonTrack(
                id = id,
                title = title,
                artist = t.string("artist")?.trim().orEmpty(),
                album = t.string("album")?.trim()?.takeIf { it.isNotEmpty() },
                durationSeconds = t.long("duration")?.takeIf { it > 0 },
                artwork = t.string("artwork")?.takeIf(::allowedUrl),
            )
        }
    }

    fun parseStream(text: String): AddonStream {
        val o = obj(text, "stream answer")
        val url = o.string("url") ?: throw IOException("The stream answer needs a url")
        if (!allowedUrl(url)) throw IOException("The stream address must be https")
        val mime = o.string("mimeType")?.lowercase()?.substringBefore(';')?.trim()
        val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty().lowercase()
        if (mime in NOT_A_FILE || path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".m3u")) {
            throw IOException("Addons must link to an audio file, not a playlist or stream manifest")
        }
        if (mime != null && !mime.startsWith("audio/")) throw IOException("The stream isn't audio")
        if (o["encrypted"].let { it is JsonPrimitive && it.content == "true" } || o["drm"] != null) {
            throw IOException("Encrypted or DRM-protected audio isn't supported")
        }
        return AddonStream(url, mime, o.int("bitrate"), o.int("sampleRate"), o.int("bitDepth"))
    }

    fun encodeList(list: List<Addon>): String = buildJsonArray {
        list.forEach { a ->
            add(buildJsonObject {
                put("id", a.id); put("name", a.name); put("baseUrl", a.baseUrl); put("catalogue", a.catalogue)
                a.version?.let { put("version", it) }
                a.description?.let { put("description", it) }
                a.homepage?.let { put("homepage", it) }
                put("enabled", a.enabled)
            })
        }
    }.toString()

    fun decodeList(text: String?): List<Addon> {
        if (text.isNullOrBlank()) return emptyList()
        return (json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Addon(
                id = o.string("id") ?: return@mapNotNull null,
                name = o.string("name") ?: return@mapNotNull null,
                baseUrl = o.string("baseUrl") ?: return@mapNotNull null,
                catalogue = o.string("catalogue").orEmpty(),
                version = o.string("version"),
                description = o.string("description"),
                homepage = o.string("homepage"),
                enabled = o["enabled"].let { it !is JsonPrimitive || it.content != "false" },
            )
        }
    }

    private fun obj(text: String, what: String): JsonObject =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: throw IOException("The addon's $what isn't a JSON object")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()
}
