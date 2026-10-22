package com.opentune.data.metadata

import com.opentune.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Deezer's public catalogue (developers.deezer.com): a track's title,
 * artist, length, ISRC and a 1000px cover, by search or by id. No key is
 * needed; nothing is played from Deezer.
 */
object Deezer {
    private const val API = "https://api.deezer.com"
    private val json = Json { ignoreUnknownKeys = true }

    data class Track(val id: Long, val title: String, val artist: String, val durationS: Long, val isrc: String?, val coverUrl: String?)

    suspend fun search(query: String, limit: Int = 10): List<Track> {
        val url = "$API/search".toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("limit", limit.toString()).build().toString()
        val body = get(url) ?: return emptyList()
        return parseSearch(body)
    }

    suspend fun track(id: Long): Track? = get("$API/track/$id")?.let { body ->
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()?.let(::trackOf)
    }

    internal fun parseSearch(body: String): List<Track> {
        val data = (runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull())?.get("data") as? JsonArray ?: return emptyList()
        return data.mapNotNull { (it as? JsonObject)?.let(::trackOf) }
    }

    private fun trackOf(o: JsonObject): Track? {
        // An error comes back as {"error":{…}} with status 200.
        if (o["error"] != null) return null
        val id = o["id"]?.jsonPrimitive?.longOrNull ?: return null
        val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val artist = (o["artist"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
        val cover = (o["album"] as? JsonObject)?.get("cover_xl")?.jsonPrimitive?.contentOrNull
        return Track(id, title, artist, o["duration"]?.jsonPrimitive?.longOrNull ?: 0, o["isrc"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }, cover)
    }

    private suspend fun get(url: String): String? = runInterruptible(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()
    }
}
