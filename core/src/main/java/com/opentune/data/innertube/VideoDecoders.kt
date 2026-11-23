package com.opentune.data.innertube

import java.util.concurrent.ConcurrentHashMap

/**
 * Whether this phone can decode a music video's format. YouTube offers each
 * video in AV1, VP9 and H.264 at many sizes; ranked by bitrate alone, a phone
 * with no AV1 decoder, or none for 4K VP9, was handed a picture it can't
 * show, and the player quietly dropped the picture and went on with the
 * sound. Asked of Android's own codec list; when that can't be read (a unit
 * test, an odd ROM), every format counts as playable, as before.
 */
object VideoDecoders {
    private val answers = ConcurrentHashMap<String, Boolean>()

    /** The decoder MIME type for a YouTube format's [mimeType] (`video/mp4; codecs="avc1.640028"`), or null. */
    internal fun decoderType(mimeType: String): String? {
        val codecs = Regex("""codecs="([^"]+)"""").find(mimeType)?.groupValues?.get(1)?.trim()?.lowercase()
            ?: return when {
                mimeType.startsWith("video/webm") -> "video/x-vnd.on2.vp9"
                mimeType.startsWith("video/mp4") -> "video/avc"
                else -> null
            }
        return when {
            codecs.startsWith("avc1") || codecs.startsWith("avc3") -> "video/avc"
            codecs.startsWith("vp09") || codecs.startsWith("vp9") -> "video/x-vnd.on2.vp9"
            codecs.startsWith("vp8") -> "video/x-vnd.on2.vp8"
            codecs.startsWith("av01") -> "video/av01"
            codecs.startsWith("hev1") || codecs.startsWith("hvc1") -> "video/hevc"
            else -> null
        }
    }

    /** True when a decoder on this phone takes [mimeType] at [width] by [height] (0 when unknown). */
    fun canPlay(mimeType: String, width: Int = 0, height: Int = 0): Boolean {
        val type = decoderType(mimeType) ?: return true
        return answers.getOrPut("$type:${width}x$height") { ask(type, width, height) }
    }

    private fun ask(type: String, width: Int, height: Int): Boolean = runCatching {
        val infos = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
        // A list with no video decoders at all is one that couldn't be read, not a phone that can't play video.
        if (infos.none { !it.isEncoder && it.supportedTypes.any { t -> t.startsWith("video/") } }) return@runCatching true
        infos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(type, ignoreCase = true) } && runCatching {
                val video = info.getCapabilitiesForType(type).videoCapabilities ?: return@runCatching false
                width <= 0 || height <= 0 || video.isSizeSupported(width, height)
            }.getOrDefault(false)
        }
    }.getOrDefault(true)
}
