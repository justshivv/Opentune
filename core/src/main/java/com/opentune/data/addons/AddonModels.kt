package com.opentune.data.addons

import org.json.JSONObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** Fixed messages only: a server's response can contain signed URLs and credentials. */
enum class AddonStreamError(val description: String) {
    NO_STREAM("source could not resolve this recording"),
    ENCRYPTED("encrypted audio is not supported"),
    INVALID_URL("missing or invalid HTTPS audio URL"),
    UNSUPPORTED_CODEC("missing or unsupported audio codec"),
}
class AddonStreamException(val reason: AddonStreamError) : IllegalArgumentException(reason.description)

/** HTTP add-on wire protocol; no downloaded code is evaluated. */
data class AddonManifest(val id: String, val name: String, val version: String,
    val downloads: Boolean = true, val outputRequired: Boolean = false,
    val defaults: Map<String, String> = emptyMap(), val qualities: List<String> = emptyList()) {
    fun parameters(tier: String): Map<String, String> = defaults + ("quality" to matchTier(tier, qualities))
}
data class AddonTrack(val id: String, val title: String, val artist: String, val album: String?,
    val durationMs: Long, val artwork: String?, val streamUrl: String?, val format: String)
data class AddonStream(val url: String, val codec: String, val transport: String?, val bits: Int?,
    val sampleRate: Int?, val kbps: Int?, val addonId: String = "", val provider: String = "",
    /** The add-on's id for the track and the tier it was asked at: what a new link for the same file is asked with. */
    val trackId: String = "", val tier: String = "") {
    val lossless get() = codec in setOf("flac", "alac", "pcm", "wav")
    val mimeType get() = when (transport) { "hls" -> "application/x-mpegURL"; "dash" -> "application/dash+xml"; else -> null }
    val extension get() = when (codec) { "flac" -> "flac"; "alac", "aac" -> "m4a"; "mp3" -> "mp3"; "opus" -> "ogg"; "pcm", "wav" -> "wav"; else -> null }
}
private fun JSONObject.text(key: String): String = opt(key)?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()

object AddonProtocol {
    fun url(raw: String): HttpUrl = requireNotNull(raw.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
    }) { "Enter an HTTPS add-on URL without a username, password or fragment." }
    fun normalize(raw: String): String {
        val input = url(raw.trim())
        val segments = input.encodedPath.trimEnd('/').split('/').toMutableList()
        if (segments.lastOrNull()?.endsWith(".json", true) == true) segments.removeAt(segments.lastIndex)
        return input.newBuilder().encodedPath(segments.joinToString("/").ifBlank { "/" }.trimEnd('/') + "/").build().toString()
    }
    fun endpoint(base: String, path: List<String>, params: Map<String, String> = emptyMap()): String =
        url(base).newBuilder().apply { path.forEach(::addPathSegment); params.forEach { (key, value) -> setQueryParameter(key, value) } }.build().toString()
    fun manifest(text: String): AddonManifest {
        val root = JSONObject(text)
        val id = root.text("id").trim().take(200)
        require(id.isNotBlank()) { "This URL did not return an add-on manifest." }
        val resources = root.optJSONArray("resources")
        require(resources == null || resources.length() == 0 || (0 until resources.length()).any { resources.optString(it).equals("search", true) }) { "This add-on does not support searching music." }
        val defaults = linkedMapOf<String, String>(); val qualities = mutableListOf<String>()
        root.optJSONArray("settings")?.let { array -> for (i in 0 until minOf(array.length(), 32)) {
            val setting = array.optJSONObject(i) ?: continue
            val key = setting.text("key").take(80).takeIf(String::isNotBlank) ?: continue
            val value = setting.opt("default")
            if (value is String || value is Number || value is Boolean) defaults[key] = value.toString().take(500)
            if (key == "quality") setting.optJSONArray("options")?.let { options -> for (j in 0 until minOf(options.length(), 32)) {
                options.optJSONObject(j)?.opt("value")?.takeIf { it is String || it is Number }?.toString()?.let(qualities::add)
            } }
        } }
        return AddonManifest(id, root.text("name").ifBlank { id }.take(120), root.text("version").take(40),
            flag(root.opt("allowDownloads")) ?: true, flag(root.opt("checkValidLossless")) ?: false, defaults, qualities)
    }
    fun search(text: String): List<AddonTrack> {
        val tracks = JSONObject(text).optJSONArray("tracks") ?: error("The add-on returned an invalid search response.")
        return (0 until minOf(tracks.length(), 100)).mapNotNull { i ->
            val t = tracks.optJSONObject(i) ?: return@mapNotNull null
            val id = t.text("id"); val title = t.text("title")
            if (id.isBlank() || id.length > 1000 || title.isBlank()) return@mapNotNull null
            AddonTrack(id, title.take(500), t.text("artist").take(500), t.text("album").takeIf(String::isNotBlank),
                (t.optDouble("duration", 0.0).takeIf { it.isFinite() && it in 0.0..86400.0 }?.times(1000)?.toLong() ?: 0),
                t.text("artworkURL").ifBlank { t.text("albumArtworkURL") }.takeIf(String::isNotBlank),
                t.text("streamURL").takeIf(String::isNotBlank), t.text("format"))
        }
    }
    fun stream(text: String): AddonStream {
        val r = JSONObject(text)
        if (r.text("error").isNotBlank()) throw AddonStreamException(AddonStreamError.NO_STREAM)
        val encrypted = r.opt("encrypted")
        if (encrypted != null && encrypted != JSONObject.NULL && encrypted.toString().lowercase(Locale.ROOT) !in setOf("false", "none", "", "0"))
            throw AddonStreamException(AddonStreamError.ENCRYPTED)
        val address = try { url(r.text("url")).toString() }
            catch (_: IllegalArgumentException) { throw AddonStreamException(AddonStreamError.INVALID_URL) }
        val description = listOf("format", "quality", "streamQuality", "audioQuality").joinToString(" ") { r.text(it) }.lowercase()
        val codec = codecHint(r, address)
        if (codec !in AUDIO_CODECS) throw AddonStreamException(AddonStreamError.UNSUPPORTED_CODEC)
        val kind = r.text("manifest").ifBlank { r.text("mediaType") }.ifBlank { r.text("format") }.lowercase()
        val transport = when {
            kind in setOf("hls", "m3u8", "application/x-mpegurl", "application/vnd.apple.mpegurl") || url(address).encodedPath.endsWith(".m3u8", true) -> "hls"
            kind in setOf("dash", "mpd", "application/dash+xml") || url(address).encodedPath.endsWith(".mpd", true) -> "dash"
            else -> null
        }
        fun number(key: String) = r.optDouble(key, 0.0).takeIf { it.isFinite() && it > 0 }
        val rate = number("sampleRate") ?: Regex("([\\d.]+)\\s*khz").find(description)?.groupValues?.get(1)?.toDoubleOrNull()
        val bits = number("bitDepth")?.toInt() ?: Regex("(\\d{1,2})\\s*-?\\s*bit").find(description)?.groupValues?.get(1)?.toIntOrNull()
        val bitrate = number("bitrate")
        return AddonStream(address, codec, transport, bits?.takeIf { it in 8..32 }, rate?.let { if (it < 1000) (it * 1000).toInt() else it.toInt() }?.takeIf { it in 8000..768000 }, bitrate?.let { if (it > 3000) (it / 1000).toInt() else it.toInt() })
    }

    private val AUDIO_CODECS = setOf("flac", "alac", "wav", "pcm", "aac", "mp3", "opus")
    private fun normalizedCodec(value: String): String = value.trim().lowercase(Locale.ROOT)
        .substringBefore(';').trim().removePrefix("audio/").removePrefix("x-")
        .let { if (it == "mpeg") "mp3" else it }

    /** A quality label is only a hint; AddonResolver still measures the FLAC header and duration. */
    private fun codecHint(r: JSONObject, address: String): String {
        // Explicit codec/format metadata beats a marketing label, including an unsupported codec.
        normalizedCodec(r.text("codec").ifBlank { r.text("fileCodec") }).takeIf(String::isNotBlank)?.let { return it }
        for (key in listOf("container", "format", "mimeType")) {
            normalizedCodec(r.text(key)).takeIf { it in AUDIO_CODECS }?.let { return it }
        }
        // Use a concrete file extension before a vague lossless claim; never treat an MP3 as FLAC.
        url(address).pathSegments.last().substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it in AUDIO_CODECS }?.let { return it }
        for (key in listOf("quality", "streamQuality", "audioQuality", "format")) {
            val label = r.text(key).trim().lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' ')
            if (Regex("^(?:hi res\\s+)?(?:flac|lossless)\\b").containsMatchIn(label)) return "flac"
            if (Regex("^alac\\b").containsMatchIn(label)) return "alac"
        }
        return ""
    }
    fun flag(value: Any?): Boolean? = when (value?.toString()?.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null }
}

/** Same tier negotiation as BitChord: exact option first, then a semantic match. */
fun matchTier(tier: String, options: List<String>): String {
    options.firstOrNull { it.equals(tier, true) }?.let { return it }
    if (tier == "HI_RES_LOSSLESS") {
        return options.firstOrNull { option -> listOf("hi_res", "hi-res", "hires", "master", "max", "best").any { it in option.lowercase() } }
            ?: matchTier("LOSSLESS", options)
    }
    val words = when (tier.uppercase()) { "LOSSLESS" -> listOf("lossless", "flac", "hi_res", "hi-res", "hires", "master"); "LOW" -> listOf("low", "128", "96", "64"); else -> listOf("high", "320", "256") }
    return options.firstOrNull { option -> words.any { it in option.lowercase() } } ?: options.firstOrNull() ?: tier
}
