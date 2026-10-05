package com.opentune.data.sponsorblock

import com.opentune.BuildConfig
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * SponsorBlock (sponsor.ajay.app): segments of YouTube videos that viewers
 * have marked, most usefully "music_offtopic", the talking, skits and
 * intros in a music video that aren't the song.
 *
 * Lookups go by the first four characters of the video id's SHA-256, so the
 * server never learns which video is playing; the matching one is picked
 * out of the answer here. Data is CC BY-NC-SA 4.0 from sponsor.ajay.app.
 */
object SponsorBlock {
    private const val API = "https://sponsor.ajay.app/api/skipSegments"
    private const val TAG = "SponsorBlock"
    private const val CACHE_SIZE = 200

    data class Segment(val startMs: Long, val endMs: Long, val category: String, val uuid: String, val videoDurationMs: Long)

    /** A category as the settings list it, and as the "skipped" message names one. */
    data class Category(val label: String, val one: String)

    /** Every skippable category the API knows, by API name. */
    val CATEGORIES = linkedMapOf(
        "music_offtopic" to Category("Non-music sections", "non-music section"),
        "sponsor" to Category("Sponsors", "sponsor"),
        "selfpromo" to Category("Self-promotion", "self-promotion"),
        "interaction" to Category("Subscribe and like reminders", "reminder"),
        "intro" to Category("Intros", "intro"),
        "outro" to Category("Endcards and credits", "endcard"),
        "preview" to Category("Previews and recaps", "preview"),
        "filler" to Category("Filler and jokes", "filler"),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, List<Segment>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Segment>>?) = size > CACHE_SIZE
    })

    /** Skippable segments of [videoId] in [categories]; empty when there are none or the lookup fails. */
    suspend fun segments(videoId: String, categories: Set<String>): List<Segment> {
        if (categories.isEmpty()) return emptyList()
        val key = "$videoId|${categories.sorted().joinToString(",")}"
        cache[key]?.let { return it }
        val found = withContext(Dispatchers.IO) {
            runCatching { fetch(videoId, categories) }
                .onFailure { Log.w(TAG, "lookup failed", it) }
                .getOrNull()
        } ?: return emptyList() // not cached: try again next time
        cache[key] = found
        return found
    }

    private fun fetch(videoId: String, categories: Set<String>): List<Segment> {
        val url = "$API/${hashPrefix(videoId)}".toHttpUrl().newBuilder()
            .addQueryParameter("categories", categories.joinToString(",", "[", "]") { "\"$it\"" })
            .addQueryParameter("actionTypes", "[\"skip\"]")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "OpenTune/${BuildConfig.VERSION_NAME}").build()
        return Http.client.newCall(request).execute().use { r ->
            when {
                r.code == 404 -> emptyList() // nothing marked for any video with this prefix
                !r.isSuccessful -> throw java.io.IOException("SponsorBlock answered ${r.code}")
                else -> parse(r.body?.string().orEmpty(), videoId)
            }
        }
    }

    internal fun hashPrefix(videoId: String): String =
        MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(4)

    /** The segments for [videoId] out of a hash-prefix answer, in order. */
    internal fun parse(body: String, videoId: String): List<Segment> {
        val videos = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        val video = videos.firstOrNull { (it as? JsonObject)?.str("videoID") == videoId } as? JsonObject ?: return emptyList()
        val segments = video["segments"] as? JsonArray ?: return emptyList()
        return segments.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if ((o.str("actionType") ?: "skip") != "skip") return@mapNotNull null
            val span = o["segment"] as? JsonArray ?: return@mapNotNull null
            val start = (span.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val end = (span.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            Segment(
                startMs = (start * 1000).toLong(),
                endMs = (end * 1000).toLong(),
                category = o.str("category").orEmpty(),
                uuid = o.str("UUID") ?: "$start-$end",
                videoDurationMs = ((o["videoDuration"] as? JsonPrimitive)?.doubleOrNull ?: 0.0).times(1000).toLong(),
            )
        }.sortedBy { it.startMs }
    }

    /**
     * Whether [segment] was marked on the same upload that's playing: a
     * segment's own video length within two seconds of the player's, or
     * not given. Guards against a different cut sharing the id's segments.
     */
    fun fits(segment: Segment, playingDurationMs: Long): Boolean =
        segment.videoDurationMs <= 0 || playingDurationMs <= 0 || kotlin.math.abs(segment.videoDurationMs - playingDurationMs) <= 2_000

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
