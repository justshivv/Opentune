/*
 * Protocol adapted from Meld's QobuzAudioProvider.
 * Metrolist Project (C) 2026, GPL-3.0; see git history for contributors.
 * OpenTune: strict catalog matching, bounded requests and FLAC-byte validation.
 */
package com.opentune.data.lossless

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Additional Qobuz routes found in Meld; also supports a compatible user-operated relay. */
internal object QobuzCatalog {
    private val unavailableUntil = ConcurrentHashMap<String, Long>()
    private val defaults = listOf("https://qobuz.kennyy.com.br", "https://trypt-hifi-dl-456461932686.us-west1.run.app")

    suspend fun resolve(identity: CatalogIdentity, hiRes: Boolean, custom: String): LosslessSource.Track? {
        val endpoints = (listOfNotNull(httpsUrl(custom)?.trimEnd('/')) + defaults).distinct()
        for (base in endpoints) {
            if ((unavailableUntil[base] ?: 0) > now()) continue
            val result = try {
                withTimeoutOrNull(8_000) {
                    val headers = mapOf("Accept" to "application/json", "User-Agent" to "Mozilla/5.0", "Referer" to "$base/")
                    val url = "$base/api/get-music".toHttpUrl().newBuilder()
                        .addQueryParameter("q", "${identity.title} ${identity.artist}").addQueryParameter("offset", "0").build()
                    val id = match(JSONObject(LosslessHttp.text(url.toString(), headers)), identity) ?: return@withTimeoutOrNull null
                    for (quality in if (hiRes) listOf(27, 7, 6) else listOf(6)) {
                        val requestUrl = "$base/api/download-music".toHttpUrl().newBuilder()
                            .addQueryParameter("track_id", id).addQueryParameter("quality", "$quality").build()
                        val root = JSONObject(LosslessHttp.text(requestUrl.toString(), headers))
                        val stream = streamUrl(root) ?: continue
                        LosslessSource.verify(stream, "Qobuz (${base.toHttpUrl().host})", identity.durationMs)?.let { return@withTimeoutOrNull it }
                    }
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                unavailableUntil[base] = now() + 3 * 60_000
                null
            }
            if (result != null) return result
        }
        return null
    }

    internal fun match(root: JSONObject, identity: CatalogIdentity): String? {
        if (!root.optBoolean("success", false)) return null
        val items = root.optJSONObject("data")?.optJSONObject("tracks")?.optJSONArray("items") ?: return null
        for (i in 0 until minOf(items.length(), 30)) {
            val track = items.optJSONObject(i) ?: continue
            val version = track.optString("version").takeUnless { it.isBlank() || it == "null" }
            val title = track.optString("title") + (version?.let { " ($it)" } ?: "")
            val artist = track.optJSONObject("performer")?.optString("name").orEmpty()
            val album = track.optJSONObject("album")?.optString("title")
            if (identity.matches(title, listOf(artist), album, track.optLong("duration") * 1000)) {
                return track.optString("id").takeIf { Regex("[0-9]+").matches(it) }
            }
        }
        return null
    }

    internal fun streamUrl(root: JSONObject): String? {
        if (!root.optBoolean("success", false) || root.optBoolean("previewDetected", false)) return null
        val data = root.optJSONObject("data") ?: return null
        if (data.optBoolean("sample", false) || data.optBoolean("preview", false)) return null
        return LosslessSource.directUrl(root)
    }

    private fun now() = System.nanoTime() / 1_000_000
}
