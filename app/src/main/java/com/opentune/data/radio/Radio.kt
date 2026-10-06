package com.opentune.data.radio

import android.content.Context
import com.opentune.BuildConfig
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.model.Song
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Internet radio from Radio Browser (radio-browser.info), the free,
 * community-kept directory of 50,000+ stations. Stations play their own
 * stream directly; the directory is only asked what's there.
 *
 * A station plays as a queue item with the id `radio:<stationuuid>`. The
 * stations played and favourited are kept on the phone, which is also
 * where playback looks up a station's stream address.
 */
object Radio {
    private const val PREFIX = "radio:"
    private const val TAG = "Radio"
    private const val MAX_RECENT = 50
    /** How far the nearby search reaches, widening in steps when little turns up. */
    private val RADII_KM = listOf(50, 150, 400)
    const val NEAR_KM = 50
    private const val ENOUGH = 8
    /** The directory's mirrors; the first that answers is used. */
    private val SERVERS = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info", "nl1.api.radio-browser.info")

    @Serializable
    data class Station(
        val uuid: String,
        val name: String,
        val streamUrl: String,
        val favicon: String? = null,
        val homepage: String? = null,
        val tags: List<String> = emptyList(),
        val country: String? = null,
        val countryCode: String? = null,
        val codec: String? = null,
        val bitrate: Int = 0,
        val hls: Boolean = false,
        val state: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
    ) {
        val id get() = "$PREFIX$uuid"

        /** "MP3 · 128 kbps · Germany · jazz, smooth" */
        val details: String
            get() = listOfNotNull(
                codec?.takeIf { it.isNotBlank() && it != "UNKNOWN" },
                bitrate.takeIf { it > 0 }?.let { "$it kbps" },
                country?.takeIf { it.isNotBlank() },
                tags.take(3).joinToString(", ").takeIf { it.isNotBlank() },
            ).joinToString(" · ")

        /** "4.2 km · AAC · 128 kbps · Delhi", for a station found nearby. */
        fun nearbyDetails(km: Double): String = listOfNotNull(
            formatDistance(km),
            codec?.takeIf { it.isNotBlank() && it != "UNKNOWN" },
            bitrate.takeIf { it > 0 }?.let { "$it kbps" },
            (state ?: country)?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

        fun toSong() = Song(videoId = id, title = name, artist = "Live radio", thumbnailUrl = favicon?.takeIf { it.isNotBlank() })
    }

    /** A station found around a place, with how far away it is. */
    data class Nearby(val station: Station, val distanceKm: Double)

    @Serializable
    private class Saved(val favourites: List<Station> = emptyList(), val recent: List<Station> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null
    @Volatile private var server: String? = null

    private val _favourites = MutableStateFlow<List<Station>>(emptyList())
    val favourites: StateFlow<List<Station>> = _favourites.asStateFlow()
    private val _recent = MutableStateFlow<List<Station>>(emptyList())
    val recent: StateFlow<List<Station>> = _recent.asStateFlow()

    fun init(context: Context) {
        val f = File(context.filesDir, "radio.json")
        file = f
        runCatching { json.decodeFromString(Saved.serializer(), f.readText()) }.getOrNull()?.let {
            _favourites.value = it.favourites
            _recent.value = it.recent
        }
    }

    fun isRadio(id: String) = id.startsWith(PREFIX)

    /** The stream address for a queued station, from the stations kept on the phone. */
    fun streamUrl(id: String): String? {
        val uuid = id.removePrefix(PREFIX)
        return (_recent.value + _favourites.value).firstOrNull { it.uuid == uuid }?.streamUrl
    }

    fun station(id: String): Station? {
        val uuid = id.removePrefix(PREFIX)
        return (_favourites.value + _recent.value).firstOrNull { it.uuid == uuid }
    }

    fun isFavourite(uuid: String) = _favourites.value.any { it.uuid == uuid }

    fun setFavourite(station: Station, on: Boolean) {
        _favourites.value = if (on) listOf(station) + _favourites.value.filterNot { it.uuid == station.uuid }
        else _favourites.value.filterNot { it.uuid == station.uuid }
        save()
    }

    /**
     * Remembers [station] as played, so its stream can be found when the
     * queue comes back, and tells the directory it was played (Radio
     * Browser ranks stations by these clicks and asks clients to send them).
     */
    fun played(station: Station) {
        _recent.value = (listOf(station) + _recent.value.filterNot { it.uuid == station.uuid }).take(MAX_RECENT)
        save()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { get(url("json/url/${station.uuid}")) } }.onFailure { Log.w(TAG, "click count failed", it) }
        }
    }

    suspend fun search(query: String, limit: Int = 60): List<Station> = list(
        url("json/stations/search") {
            addQueryParameter("name", query)
            addQueryParameter("limit", limit.toString())
            addQueryParameter("hidebroken", "true")
            addQueryParameter("order", "clickcount")
            addQueryParameter("reverse", "true")
        },
    )

    suspend fun byTag(tag: String, limit: Int = 60): List<Station> = list(
        url("json/stations/bytagexact/$tag") {
            addQueryParameter("limit", limit.toString())
            addQueryParameter("hidebroken", "true")
            addQueryParameter("order", "clickcount")
            addQueryParameter("reverse", "true")
        },
    )

    suspend fun popular(limit: Int = 60): List<Station> = list(
        url("json/stations/topclick/$limit") { addQueryParameter("hidebroken", "true") },
    )

    /** The most-played stations in a country, by its two-letter ISO code. */
    suspend fun inCountry(countryCode: String, limit: Int = 60): List<Station> = list(
        url("json/stations/bycountrycodeexact/${countryCode.uppercase()}") {
            addQueryParameter("limit", limit.toString())
            addQueryParameter("hidebroken", "true")
            addQueryParameter("order", "clickcount")
            addQueryParameter("reverse", "true")
        },
    )

    /**
     * Stations around a place, nearest first. The search starts at
     * [NEAR_KM] and widens until enough stations turn up. Only a rounded
     * position (two decimals, about a kilometre) leaves the phone.
     */
    suspend fun nearby(latitude: Double, longitude: Double): List<Nearby> {
        val lat = coarse(latitude)
        val lon = coarse(longitude)
        var found = emptyList<Nearby>()
        for (km in RADII_KM) {
            val body = withContext(Dispatchers.IO) {
                get(
                    url("json/stations/search") {
                        addQueryParameter("geo_lat", lat.toString())
                        addQueryParameter("geo_long", lon.toString())
                        addQueryParameter("geo_distance", (km * 1000).toString())
                        addQueryParameter("has_geo_info", "true")
                        addQueryParameter("hidebroken", "true")
                        addQueryParameter("order", "clickcount")
                        addQueryParameter("reverse", "true")
                        addQueryParameter("limit", "100")
                    },
                )
            }
            found = parseNearby(body, lat, lon)
            if (found.size >= ENOUGH) break
        }
        return found
    }

    /** Stations out of a geo search, nearest first, with their distance from ([lat], [lon]). */
    internal fun parseNearby(body: String, lat: Double, lon: Double): List<Nearby> {
        val metres = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull().orEmpty()
            .mapNotNull { e -> (e as? JsonObject)?.let { o -> o.str("stationuuid")?.let { it to o.dbl("geo_distance") } } }
            .toMap()
        return parse(body).mapNotNull { s ->
            val km = metres[s.uuid]?.div(1000)
                ?: if (s.latitude != null && s.longitude != null) distanceKm(lat, lon, s.latitude, s.longitude) else null
            km?.let { Nearby(s, it) }
        }.sortedBy { it.distanceKm }
    }

    /** Great-circle distance in kilometres. */
    internal fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * 6371.0 * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** "Under 1 km", "4.2 km", "37 km". */
    internal fun formatDistance(km: Double): String = when {
        km < 1 -> "Under 1 km"
        km < 10 -> String.format(java.util.Locale.ROOT, "%.1f km", km)
        else -> "${Math.round(km)} km"
    }

    /** A coordinate rounded to two decimals, so the directory only learns the rough area. */
    internal fun coarse(v: Double) = Math.round(v * 100) / 100.0

    private suspend fun list(url: HttpUrl): List<Station> = withContext(Dispatchers.IO) { parse(get(url)) }

    /** [path] on the current mirror, each segment encoded. */
    private suspend fun url(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
        val host = server ?: pickServer()
        return HttpUrl.Builder().scheme("https").host(host)
            .apply { path.split('/').forEach { addPathSegment(it) } }
            .apply(query)
            .build()
    }

    /** The first mirror that answers; remembered for the rest of the session. */
    private suspend fun pickServer(): String = withContext(Dispatchers.IO) {
        for (host in SERVERS.shuffled()) {
            val ok = runCatching {
                Http.client.newCall(request("https://$host/json/stats".toHttpUrl())).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) return@withContext host.also { server = it }
        }
        throw IOException("Radio Browser isn't answering")
    }

    private fun get(url: HttpUrl): String =
        Http.client.newCall(request(url)).execute().use { r ->
            if (!r.isSuccessful) {
                server = null // try another mirror next time
                throw IOException("Radio Browser answered ${r.code}")
            }
            r.body?.string().orEmpty()
        }

    // Radio Browser asks for a User-Agent naming the app.
    private fun request(url: HttpUrl) = Request.Builder().url(url).header("User-Agent", "OpenTune/${BuildConfig.VERSION_NAME}").build()

    /** Stations out of a directory answer, without the ones that have no stream. */
    internal fun parse(body: String): List<Station> {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val uuid = o.str("stationuuid") ?: return@mapNotNull null
            val stream = (o.str("url_resolved")?.takeIf { it.isNotBlank() } ?: o.str("url"))
                ?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            Station(
                uuid = uuid,
                name = o.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                streamUrl = stream,
                favicon = o.str("favicon")?.takeIf { it.startsWith("http") },
                homepage = o.str("homepage")?.takeIf { it.startsWith("http") },
                tags = o.str("tags").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                country = o.str("country"),
                countryCode = o.str("countrycode"),
                codec = o.str("codec"),
                bitrate = (o["bitrate"] as? JsonPrimitive)?.intOrNull ?: 0,
                hls = (o["hls"] as? JsonPrimitive)?.intOrNull == 1,
                state = o.str("state")?.trim()?.takeIf { it.isNotEmpty() },
                latitude = o.dbl("geo_lat"),
                longitude = o.dbl("geo_long"),
            )
        }.distinctBy { it.uuid }
    }

    private fun save() {
        val f = file ?: return
        val saved = Saved(_favourites.value, _recent.value)
        scope.launch {
            runCatching {
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(json.encodeToString(Saved.serializer(), saved))
                tmp.renameTo(f)
            }
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.dbl(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

    /** Genres offered as chips on the radio page, by Radio Browser tag. */
    val GENRES = listOf("pop", "rock", "jazz", "classical", "electronic", "hiphop", "lofi", "news", "talk", "ambient", "country", "bollywood", "chillout", "dance", "80s", "90s")
}
