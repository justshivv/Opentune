package com.opentune.playback

import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * OpenTune's FFmpeg build has the Dolby decoders only: it never claims another format, so the
 * phone's own decoders keep FLAC, AAC, Opus and the rest. (The native library isn't loaded on
 * the JVM, so the Dolby formats read as unsupported here; on a phone they're FFmpeg's.)
 */
class DolbyDecoderTest {
    @Test fun ffmpegClaimsNothingButDolby() {
        for (mime in listOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_AC4)) {
            assertFalse(mime, FfmpegLibrary.supportsFormat(mime))
        }
    }
}
