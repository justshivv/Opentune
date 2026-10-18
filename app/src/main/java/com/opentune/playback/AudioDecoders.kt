package com.opentune.playback

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** Keep FLAC on Android's software decoder when available. Vendor audio
 * decoders can mishandle FLAC block sizes/bit depths while accepting the file.
 * Preserve the other candidates so initialization failure can still fall back.
 */
@UnstableApi
internal object AudioDecoders {
    val selector = MediaCodecSelector { mime, secure, tunneling ->
        val candidates = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
        if (mime == MimeTypes.AUDIO_FLAC && !secure && !tunneling)
            candidates.sortedBy { flacPriority(it.name) }
        else candidates
    }

    internal fun flacPriority(name: String): Int = when (name.lowercase()) {
        "c2.android.flac.decoder", "omx.google.flac.decoder" -> 0
        else -> 1
    }
}
