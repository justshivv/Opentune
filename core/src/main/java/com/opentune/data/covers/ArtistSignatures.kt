package com.opentune.data.covers

import com.opentune.data.DebugLog
import com.opentune.data.Http
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** An artist's handwritten signature: an image of ink, and how wide it is for its height. */
data class ArtistSignature(val imageUrl: String, val aspect: Float)

/**
 * Artists' signatures from an artist-signature service, the kind BitChord
 * reads (github.com/itsmeadarsh2008/artist-signature): `GET /v1/signatures`
 * with the artist's name answers with the signatures it knows. The images
 * come from public archives such as Wikimedia Commons, each with its licence.
 *
 * The best one is taken: a known licence first, then a raster the app can
 * draw (an SVG on Wikimedia is asked for as Wikimedia's own PNG of it), then
 * a wide one, since a signature is a line of writing. Answers, misses
 * included, are kept for the session; a failed request isn't, so it's tried
 * again next visit. An ambiguous name (several artists) reads as none.
 */
object ArtistSignatures {
    private const val TAG = "ArtistSignatures"
    const val DEFAULT_API = "https://proud-cascade-1654.de.deplexo.com"
    /** How wide an SVG from Wikimedia is asked for as a PNG: one of the widths Wikimedia renders. */
    private const val RENDER_WIDTH = 960

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Optional>()
    private class Optional(val value: ArtistSignature?)

    fun cached(artist: String, api: String): ArtistSignature? = cache[key(artist, api)]?.value

    /** The signature for [artist] from the service at [api], or null. Never throws. */
    suspend fun signatureFor(artist: String, api: String): ArtistSignature? {
        val base = normalize(api) ?: return null
        val key = key(artist, base)
        if (artist.isBlank()) return null
        cache[key]?.let { return it.value }
        val body = withContext(Dispatchers.IO) {
            runCatching {
                val url = "$base/v1/signatures?artist=" + URLEncoder.encode(artist.trim(), "UTF-8")
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    when {
                        r.isSuccessful -> r.body?.string()
                        r.code == 404 -> "{}"
                        else -> null
                    }
                }
            }.onFailure { DebugLog.w(TAG, "Lookup for $artist failed", it) }.getOrNull()
        } ?: return null
        val found = parse(body)
        cache[key] = Optional(found)
        return found
    }

    /** Whether the service at [api] answers its health check. */
    suspend fun reachable(api: String): Boolean = withContext(Dispatchers.IO) {
        val base = normalize(api) ?: return@withContext false
        runCatching { Http.client.newCall(Request.Builder().url("$base/v1/health").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** An http(s) address without the slash at its end, or null for anything else. */
    fun normalize(api: String): String? = api.trim().trimEnd('/').takeIf { it.startsWith("https://") || it.startsWith("http://") }

    fun clear() = cache.clear()

    internal fun parse(body: String): ArtistSignature? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val list = root.optJSONArray("signatures") ?: return null
        var best: ArtistSignature? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until list.length()) {
            val sig = list.optJSONObject(i) ?: continue
            val asset = sig.optJSONObject("asset")
            val raw = asset?.optString("url")?.takeIf { it.startsWith("https://") }
                ?: sig.optJSONObject("source")?.optString("original_url")?.takeIf { it.startsWith("https://") }
                ?: continue
            val format = (asset?.optString("format").orEmpty().ifBlank { raw.substringBefore('?').substringAfterLast('.', "") }).lowercase(Locale.ROOT)
            val url = drawable(raw, format) ?: continue
            val w = asset?.optInt("width") ?: 0
            val h = asset?.optInt("height") ?: 0
            val aspect = if (w > 0 && h > 0) w.toFloat() / h else 2.5f
            val licence = when (sig.optJSONObject("license")?.optString("status")?.lowercase(Locale.ROOT)) {
                "known" -> 2
                "requires_review" -> 1
                else -> 0
            }
            val verified = when (sig.optString("verification").lowercase(Locale.ROOT)) {
                "artist_verified" -> 2
                "verified" -> 1
                else -> 0
            }
            // A raster as served beats a rendered SVG, which beats nothing; wide reads as a whole signature.
            val kind = if (format == "svg") 1 else 2
            val score = licence * 100 + verified * 10 + kind * 3 + if (aspect >= 1.4f) 1 else 0
            if (score > bestScore) {
                bestScore = score
                best = ArtistSignature(url, aspect)
            }
        }
        return best
    }

    /**
     * An address the app's image loader can draw: rasters as they are, and an
     * SVG on Wikimedia Commons as the PNG Wikimedia renders of it. Other SVGs
     * are skipped, as there's no SVG decoder here.
     */
    internal fun drawable(url: String, format: String): String? {
        if (format != "svg") return url
        val m = Regex("""^https://upload\.wikimedia\.org/wikipedia/commons/([0-9a-f])/([0-9a-f]{2})/([^?]+\.svg)""").find(url) ?: return null
        val (a, ab, file) = m.destructured
        return "https://upload.wikimedia.org/wikipedia/commons/thumb/$a/$ab/$file/${RENDER_WIDTH}px-$file.png"
    }

    private fun key(artist: String, api: String) = api + "\n" +
        Normalizer.normalize(artist.lowercase(Locale.ROOT), Normalizer.Form.NFD).filter { it.isLetterOrDigit() }
}
