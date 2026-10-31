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

/**
 * An artist's handwritten signature: an image of ink, and how wide it is for
 * its height. [svgUrl] is the original vector when it came as one, for a
 * loader that can draw SVG; [imageUrl] is always a raster.
 */
data class ArtistSignature(val imageUrl: String, val aspect: Float, val svgUrl: String? = null)

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
        val found = parse(body, artist)
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

    internal fun parse(body: String, artist: String): ArtistSignature? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val list = root.optJSONArray("signatures") ?: return null
        val wanted = words(artist).filter { it !in SIGNATURE_WORDS }.ifEmpty { words(artist) }
        if (wanted.isEmpty()) return null
        var best: ArtistSignature? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until list.length()) {
            val sig = list.optJSONObject(i) ?: continue
            val asset = sig.optJSONObject("asset")
            val raw = asset?.optString("url")?.takeIf { it.startsWith("https://") }
                ?: sig.optJSONObject("source")?.optString("original_url")?.takeIf { it.startsWith("https://") }
                ?: continue
            // The service matches names loosely: "Drake" brings Nick Drake's, "Sia" a photo of a
            // charter signed at the SIA. Only a file named for this artist is theirs.
            val named = nameMatch(fileWords(raw), wanted) ?: continue
            val format = (asset?.optString("format").orEmpty().ifBlank { raw.substringBefore('?').substringAfterLast('.', "") }).lowercase(Locale.ROOT)
            val url = drawable(raw, format) ?: continue
            val w = asset?.optInt("width") ?: 0
            val h = asset?.optInt("height") ?: 0
            val aspect = if (w > 0 && h > 0) w.toFloat() / h else 2.5f
            val license = sig.optJSONObject("license")
            val licence = when {
                license?.optString("name").orEmpty().equals("PD-signature", ignoreCase = true) -> 3
                license?.optString("status")?.lowercase(Locale.ROOT) == "known" -> 2
                license?.optString("status")?.lowercase(Locale.ROOT) == "requires_review" -> 1
                else -> 0
            }
            val verified = when (sig.optString("verification").lowercase(Locale.ROOT)) {
                "artist_verified" -> 2
                "verified" -> 1
                else -> 0
            }
            // Wikimedia's SVGs render as clean ink on transparency; a JPEG is a scan with its paper.
            val kind = when (format) { "svg" -> 3; "png", "webp", "gif" -> 2; else -> 1 }
            val score = named * 1000 + licence * 100 + verified * 10 + kind * 3 + if (aspect >= 1.2f) 1 else 0
            if (score > bestScore) {
                bestScore = score
                best = ArtistSignature(url, aspect, svgUrl = raw.takeIf { format == "svg" })
            }
        }
        return best
    }

    /** Words that only say the file is a signature, not whose it is. */
    private val SIGNATURE_WORDS = setOf("signature", "signatures", "sig", "sign", "signed", "autograph", "autographe", "firma", "unterschrift", "handtekening", "of", "by", "s", "the")

    private val SIGNATURE_SUFFIXES = listOf("signature", "autograph", "sig")

    /** The words of [text], lower case and without accents. */
    private fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() && it != "the" }

    /** The words of the file's own name at the end of [url], without its extension or signature words. */
    internal fun fileWords(url: String): List<String> {
        val file = java.net.URLDecoder.decode(url.substringBefore('?').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
        return words(file.substringBeforeLast('.')).filter { it !in SIGNATURE_WORDS }
    }

    /**
     * How well a file's name words match the artist's: 2 for the name itself
     * ("Taylor_Swift_signature", "Ladygaga_sig"), 1 for the name and one more
     * word after it (a surname: "Sia_Furler_signature"), null for anyone else
     * (a word before the name, "Nick_Drake_sig", or more than one after it).
     */
    internal fun nameMatch(file: List<String>, artist: List<String>): Int? {
        if (file.isEmpty()) return null
        val joined = file.joinToString("")
        val name = artist.joinToString("")
        // Run together, signature word and all: "Kanyewestsignature".
        val bare = SIGNATURE_SUFFIXES.firstOrNull { joined.endsWith(it) && joined.length > it.length }?.let { joined.removeSuffix(it) }
        if (file == artist || joined == name || bare == name) return 2
        if (file.size == artist.size + 1 && file.subList(0, artist.size) == artist) return 1
        return null
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

    /**
     * A signature as white ink on clear, as ARGB pixels [width] by [height].
     *
     * Strokes on transparency keep their shape and alpha and turn white. A scan
     * on paper loses the paper: its tone is the median of a band all round the
     * edge (a stray mark in one corner doesn't move it), how grainy it is is
     * read from the same band, and only what stands clear of that grain, on the
     * ink's side of the paper (darker on light paper, lighter on dark), is ink.
     * Faint ink fades in smoothly up to the tone most of the strokes have.
     *
     * Null when what's left isn't writing: a pen line covers a small part of
     * its page, and when over [MOST_INK] of it comes out as ink it's a
     * photograph or a filled shape, better shown as the artist's name.
     */
    fun whiteInk(argb: IntArray, width: Int, height: Int): IntArray? {
        val n = width * height
        if (n == 0 || argb.size < n) return argb
        val clear = (0 until n).count { (argb[it] ushr 24) < 16 }
        if (clear * 5 >= n) return IntArray(n) { (argb[it] and 0xFF000000.toInt()) or 0x00FFFFFF }

        // The paper: the median tone of a band all round the edge.
        val band = maxOf(1, minOf(width, height) / 25)
        val edge = IntArray(256)
        for (y in 0 until height) for (x in 0 until width) {
            if (x < band || y < band || x >= width - band || y >= height - band) edge[luma(argb[y * width + x])]++
        }
        val paper = percentile(edge, 0.5f)
        val darkInk = paper >= 110
        fun lift(c: Int) = (if (darkInk) paper - luma(c) else luma(c) - paper).coerceIn(0, 255)

        // How grainy the paper is, and the tone most of the ink reaches.
        val grain = IntArray(256)
        for (y in 0 until height) for (x in 0 until width) {
            if (x < band || y < band || x >= width - band || y >= height - band) grain[lift(argb[y * width + x])]++
        }
        val all = IntArray(256)
        for (i in 0 until n) all[lift(argb[i])]++
        val low = percentile(grain, 0.9f) + 10
        val high = maxOf(percentile(all, 0.97f), low + 40)

        val out = IntArray(n) { i ->
            val t = ((lift(argb[i]) - low).toFloat() / (high - low)).coerceIn(0f, 1f)
            val a = (t * t * (3 - 2 * t) * (argb[i] ushr 24)).toInt()
            (a shl 24) or 0x00FFFFFF
        }
        val inked = out.count { (it ushr 24) >= 128 }
        return out.takeIf { inked <= n * MOST_INK }
    }

    /** The most of an image that can be ink and still be a signature. */
    private const val MOST_INK = 0.35f

    private fun luma(c: Int): Int =
        ((0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) + 0.5f).toInt().coerceIn(0, 255)

    /** The value [share] of the way up a histogram of 0..255. */
    private fun percentile(histogram: IntArray, share: Float): Int {
        val total = histogram.sum()
        if (total == 0) return 0
        val wanted = share * total
        var seen = 0
        for (v in histogram.indices) {
            seen += histogram[v]
            if (seen >= wanted) return v
        }
        return 255
    }

    private fun key(artist: String, api: String) = api + "\n" +
        Normalizer.normalize(artist.lowercase(Locale.ROOT), Normalizer.Form.NFD).filter { it.isLetterOrDigit() }
}
