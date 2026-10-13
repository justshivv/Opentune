package com.opentune.data.lossless

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Resolve an exact recording without making the verified provider depend on song.link. */
internal object TidalCatalog {
    class Unavailable : java.io.IOException("Catalog search incomplete")

    internal suspend fun withLinkedFallback(linked: kotlinx.coroutines.Deferred<String?>, search: suspend () -> String?): String? {
        if (linked.isCompleted) linked.await()?.let { return it }
        return try { search() ?: linked.await() } catch (e: Unavailable) { linked.await() ?: throw e }
    }

    suspend fun find(identity: CatalogIdentity, registry: JSONObject?): String? =
        findIn(identity, LosslessRegistry.endpoints(registry, "tidal")) { url ->
            JSONObject(LosslessHttp.text(url, mapOf("X-API-Key" to LosslessRegistry.API_KEY)))
        }

    internal suspend fun findIn(identity: CatalogIdentity, endpoints: List<String>, fetch: suspend (String) -> JSONObject): String? {
        var unavailable = false
        for (base in endpoints) {
            try {
                val url = "$base/search/".toHttpUrl().newBuilder()
                    .addQueryParameter("s", "${identity.title} ${identity.artist}").build()
                val response = fetch(url.toString())
                if (items(response) == null) throw Unavailable()
                match(response, identity)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                unavailable = true
            }
        }
        if (unavailable) throw Unavailable()
        return null
    }

    private fun items(root: JSONObject): org.json.JSONArray? {
        val data = root.optJSONObject("data") ?: root
        return data.optJSONArray("items") ?: data.optJSONObject("tracks")?.optJSONArray("items")
    }

    internal fun match(root: JSONObject, identity: CatalogIdentity): String? {
        val items = items(root) ?: return null
        for (i in 0 until minOf(items.length(), 50)) {
            val track = items.optJSONObject(i) ?: continue
            val artists = track.optJSONArray("artists")?.let { values ->
                (0 until values.length()).mapNotNull { values.optJSONObject(it)?.optString("name") }
            }.orEmpty() + listOfNotNull(track.optJSONObject("artist")?.optString("name"))
            val version = track.optString("version").takeUnless { it.isBlank() || it == "null" }
            val title = track.optString("title") + (version?.let { " ($it)" } ?: "")
            if (identity.matches(title, artists, track.optJSONObject("album")?.optString("title"), track.optLong("duration") * 1000)) {
                track.optString("id").takeIf { Regex("[0-9]+").matches(it) }?.let { return it }
            }
        }
        return null
    }
}
