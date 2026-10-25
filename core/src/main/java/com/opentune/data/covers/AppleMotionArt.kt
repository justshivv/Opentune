package com.opentune.data.covers

import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.metadata.AppleTrackMetadataRepository
import com.opentune.data.metadata.AppleWebToken
import com.opentune.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Apple Music's animated album covers: the looping clips Apple shows on
 * album pages, `editorialVideo` in its catalogue. The album comes from the
 * same strict match as the sharp cover ([AppleTrackMetadataRepository.release]:
 * same title, artist and length within 3 s), so a clip is only ever shown
 * for the album the song is really on. Each is an HLS stream.
 */
object AppleMotionArt {
    private const val TAG = "AppleMotionArt"
    private const val AMP = "https://amp-api.music.apple.com/v1/catalog"
    private const val MISS_TTL_MS = 6 * 60 * 60_000L

    /** An HLS playlist: the square clip first, the next one as the retry. */
    data class Motion(val url: String, val fallbackUrl: String?)

    private data class Entry(val motion: Motion?, val at: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 300
    }

    /** The animated cover of the album [song] is on, or null when Apple has none or the match isn't certain. */
    suspend fun forSong(song: Song, durationMs: Long): Motion? {
        val release = runCatching { AppleTrackMetadataRepository.release(song, durationMs) }
            .onFailure { Log.w(TAG, "catalogue match failed", it) }.getOrNull() ?: return null
        val key = "${release.storefront}/${release.albumId}"
        synchronized(cache) { cache[key] }?.let { e ->
            if (e.motion != null || System.currentTimeMillis() - e.at < MISS_TTL_MS) return e.motion
        }
        val url = "$AMP/${release.storefront}/albums/${release.albumId}".toHttpUrl().newBuilder()
            .addQueryParameter("extend", "editorialVideo").build().toString()
        val body = runCatching { read(url) }.onFailure { Log.w(TAG, "album read failed", it) }.getOrNull() ?: return null
        val motion = motionOf(json.parseToJsonElement(body).jsonObject)
        synchronized(cache) { cache[key] = Entry(motion, System.currentTimeMillis()) }
        return motion
    }

    /** One catalogue read, with a fresh token once if Apple refuses the one held. */
    private suspend fun read(url: String): String? {
        repeat(2) {
            val bearer = AppleWebToken.get() ?: return null
            val (code, body) = runInterruptible(Dispatchers.IO) {
                Http.client.newCall(AppleWebToken.request(url, bearer)).execute().use { it.code to it.body?.string() }
            }
            when {
                code == 401 || code == 403 -> AppleWebToken.forget()
                code == 404 -> return null
                code in 200..299 -> return body
                else -> throw java.io.IOException("Apple answered $code")
            }
        }
        return null
    }

    /** The clip on an album read (`data[0].attributes.editorialVideo`). */
    internal fun motionOf(response: JsonObject): Motion? {
        val album = (response["data"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val attributes = album["attributes"] as? JsonObject ?: return null
        return motionUrls(attributes["editorialVideo"] as? JsonObject)
    }

    /**
     * The square clip first: it fills the square cover without cropping. The
     * tall one is the same clip framed for a phone and only worth having as
     * the retry. Only Apple's own https hosts are taken.
     */
    internal fun motionUrls(video: JsonObject?): Motion? {
        video ?: return null
        fun link(key: String): String? {
            val asset = video[key] as? JsonObject ?: return null
            return listOf("video", "videoUrl", "hlsUrl", "url").firstNotNullOfOrNull { k ->
                (asset[k] as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull()
                    ?.takeIf { it.isHttps && (it.host == "apple.com" || it.host.endsWith(".apple.com")) }?.toString()
            }
        }
        val square = link("motionDetailSquare") ?: link("motionSquareVideo1x1")
        val raw = link("motionDetailRaw")
        val tall = link("motionDetailTall") ?: link("motionTallVideo3x4")
        val url = square ?: raw ?: tall ?: return null
        return Motion(url, listOf(square, raw, tall).firstOrNull { it != null && it != url })
    }
}
