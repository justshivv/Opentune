package com.opentune.data.download

import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import java.io.File
import java.io.IOException

/** Use the same container sniffers as playback before marking a download complete. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object DownloadAudio {
    fun validate(file: File, expected: com.opentune.data.addons.AddonStream? = null) {
        if (expected?.codec == "flac") {
            val header = ByteArray(42).also { bytes -> java.io.DataInputStream(file.inputStream()).use { it.readFully(bytes) } }
            val actual = com.opentune.data.lossless.LosslessSource.flacInfo(header)
                ?: throw IOException("The download did not contain the promised FLAC audio")
            if ((expected.bits != null && expected.bits != actual.bits) ||
                (expected.sampleRate != null && expected.sampleRate != actual.sampleRate))
                throw IOException("The downloaded FLAC quality changed; trying another source")
        }
        if (file.length() == 0L) throw IOException("The server returned empty audio")
        val extractors = arrayOf(Mp4Extractor(), FragmentedMp4Extractor(), MatroskaExtractor(), androidx.media3.extractor.flac.FlacExtractor(), androidx.media3.extractor.wav.WavExtractor(), androidx.media3.extractor.ogg.OggExtractor())
        try {
            val playable = extractors.any { extractor ->
                file.inputStream().buffered().use { stream ->
                    val input = DefaultExtractorInput({ buffer, offset, length -> stream.read(buffer, offset, length) }, 0, file.length())
                    try { extractor.sniff(input) } catch (_: IOException) { false }
                }
            }
            if (!playable) throw IOException("The server returned unreadable audio. Please retry the download.")
        } finally {
            extractors.forEach { it.release() }
        }
    }
}
