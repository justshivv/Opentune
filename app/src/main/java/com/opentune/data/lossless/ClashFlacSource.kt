package com.opentune.data.lossless

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** User-configured ClashFLAC Qobuz protocol. No shared accounts or project-owned relays. */
internal object ClashFlacSource {
    private data class Lookup(val track: LosslessSource.Track?)
    private val unavailableUntil = ConcurrentHashMap<String, Long>()

    internal fun baseUrl(value: String): String? = value.trim().toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    }?.toString()?.trimEnd('/')

    suspend fun resolve(identity: CatalogIdentity, endpoint: String, report: (String) -> Unit): LosslessSource.Track? {
        val base = baseUrl(endpoint) ?: return null
        if ((unavailableUntil[base] ?: 0) > now()) {
            report("ClashFLAC: server cooling down after a failed request")
            return null
        }
        return try {
            withTimeoutOrNull(8_000) {
                val search = "$base/api/qobuz/search".toHttpUrl().newBuilder()
                    .addQueryParameter("q", "${identity.title} ${identity.artist}")
                    .addQueryParameter("limit", "20").build()
                val id = match(JSONArray(LosslessHttp.text(search.toString())), identity)
                if (id == null) {
                    report("ClashFLAC: no exact recording match")
                    return@withTimeoutOrNull Lookup(null)
                }
                val data = JSONObject(LosslessHttp.text("$base/api/qobuz/stream/$id"))
                val stream = streamUrl(data, id)
                val verified = LosslessSource.verify(stream, "ClashFLAC · Qobuz", identity.durationMs)
                if (verified == null) report("ClashFLAC: no full-length FLAC verified; keeping current audio")
                Lookup(verified)
            }.let { result ->
                if (result == null) throw java.io.IOException("ClashFLAC lookup timed out")
                result.track
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (unavailableUntil.size > 16) unavailableUntil.clear()
            unavailableUntil[base] = now() + 60_000
            report(if (e is LosslessHttp.HttpFailure) "ClashFLAC: server returned HTTP ${e.code}"
                else "ClashFLAC: server unavailable")
            null
        }
    }

    internal fun match(items: JSONArray, identity: CatalogIdentity): String? {
        for (i in 0 until minOf(items.length(), 30)) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("asin")
            if (!Regex("[0-9]+").matches(id)) continue
            if (identity.matches(item.optString("title"), listOf(item.optString("artist")),
                    item.optString("album"), item.optLong("duration_sec") * 1000)) return id
        }
        return null
    }

    internal fun streamUrl(root: JSONObject, id: String): String? {
        if (root.optString("track_id") != id || root.optInt("format_id") !in setOf(6, 7, 27) ||
            root.optBoolean("preview") || root.optBoolean("sample")) return null
        // Advertised format/bit depth never establishes quality; verify() reads the FLAC header.
        return LosslessSource.directUrl(root)
    }

    private fun now() = System.nanoTime() / 1_000_000
}
