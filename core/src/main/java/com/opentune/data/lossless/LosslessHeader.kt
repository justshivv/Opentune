package com.opentune.data.lossless

import kotlin.math.abs
import kotlinx.coroutines.CancellationException

/**
 * What the start of a file an add-on called lossless really is, read from as few bytes as will
 * say. Only a file that is plainly something else (an MP3, an AAC or Opus stream) is turned down:
 * BitChord plays an add-on's lossless file on the add-on's word, and this used to refuse every file
 * that didn't open with a bare `fLaC`, which cost the FLACs that carry an ID3 tag first and the
 * FLACs some catalogues serve in an MP4.
 */
internal sealed interface LosslessHeader {
    /** A FLAC file, its STREAMINFO read. */
    data class Flac(val info: LosslessSource.FlacInfo) : LosslessHeader
    /** An MP4 (FLAC or ALAC inside), with its STREAMINFO when the first bytes held it. */
    data class Mp4(val info: com.opentune.playback.LosslessAudioPolicy.Info?) : LosslessHeader
    /** Lossless in a form not read here (Ogg FLAC, WAV), or the start couldn't be fetched just now. */
    data object Unchecked : LosslessHeader
    /** Not lossless audio at all, whatever the add-on said: a lossy format, or a page of text. */
    data object NotLossless : LosslessHeader

    companion object {
        /** STREAMINFO ends 42 bytes in: four of `fLaC`, four of block header, 34 of block. */
        private const val FLAC_HEAD = 42
        /** Enough of an MP4 for its sample description, when that comes first. */
        private const val MP4_HEAD = 65_536
        /** An ID3 tag is mostly artwork; past this the rest isn't fetched just to find what follows. */
        private const val MAX_ID3 = 4 * 1024 * 1024

        /**
         * Reads [url]'s start with [probe] (address, bytes wanted, prefix only). A failure to fetch
         * it is [Unchecked], not a refusal: a file host having a bad moment says nothing about the file.
         */
        suspend fun read(url: String, probe: suspend (String, Int, Boolean) -> ByteArray): LosslessHeader {
            val head = fetched { probe(url, FLAC_HEAD, true) } ?: return Unchecked
            return of(head) { size -> fetched { probe(url, size, true) } }
        }

        /** [head] read, with [more] for a longer start when the first bytes say to. */
        internal suspend fun of(head: ByteArray, more: suspend (Int) -> ByteArray?): LosslessHeader {
            // An error page or a JSON reply where the file should be: no audio at all.
            if (String(head, 0, minOf(head.size, 64), Charsets.ISO_8859_1).trimStart().firstOrNull() in setOf('<', '{', '[')) return NotLossless
            if (head.size < 12) return Unchecked
            fun ascii(at: Int, length: Int) = if (head.size >= at + length) String(head, at, length, Charsets.ISO_8859_1) else ""
            return when {
                // A FLAC whose STREAMINFO is below music quality (voice-rate, under 16 bits) is no upgrade.
                ascii(0, 4) == "fLaC" -> LosslessSource.flacInfo(head)?.let(::Flac) ?: NotLossless
                ascii(0, 3) == "ID3" -> {
                    // A syncsafe size (seven bits a byte), ten bytes of header before it, and ten
                    // more of footer when its flag says so. The FLAC, if it is one, starts after.
                    val size = (((head[6].toInt() and 0x7f) shl 21) or ((head[7].toInt() and 0x7f) shl 14) or
                        ((head[8].toInt() and 0x7f) shl 7) or (head[9].toInt() and 0x7f)) +
                        10 + if (head[5].toInt() and 0x10 != 0) 10 else 0
                    if (size > MAX_ID3) return Unchecked
                    val whole = more(size + FLAC_HEAD) ?: return Unchecked
                    if (whole.size < size + FLAC_HEAD) return Unchecked
                    val after = whole.copyOfRange(size, size + FLAC_HEAD)
                    when {
                        String(after, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> LosslessSource.flacInfo(after)?.let(::Flac) ?: NotLossless
                        // An ID3 tag before anything else is how an MP3 starts.
                        else -> NotLossless
                    }
                }
                ascii(4, 4) == "ftyp" -> mp4(more(MP4_HEAD) ?: head)
                ascii(0, 4) == "OggS" -> {
                    // Ogg FLAC's first packet opens with 0x7F "FLAC"; Vorbis and Opus say their own names.
                    if (head.size >= 33 && head[28] == 0x7f.toByte() && ascii(29, 4) == "FLAC") Unchecked else NotLossless
                }
                ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE" -> Unchecked
                ascii(0, 4) == "FORM" && (ascii(8, 4) == "AIFF" || ascii(8, 4) == "AIFC") -> Unchecked
                // An MPEG audio frame (MP3, or ADTS AAC) begins with eleven set bits.
                (head[0].toInt() and 0xff) == 0xff && (head[1].toInt() and 0xe0) == 0xe0 -> NotLossless
                // Matroska/WebM (1A 45 DF A3): the decoder says which codec once it plays.
                (head[0].toInt() and 0xff) == 0x1a && (head[1].toInt() and 0xff) == 0x45 &&
                    (head[2].toInt() and 0xff) == 0xdf && (head[3].toInt() and 0xff) == 0xa3 -> Unchecked
                else -> Unchecked
            }
        }

        /**
         * An MP4's sample description, where its first bytes hold it: FLAC (`dfLa`) or ALAC is
         * lossless, AAC (`mp4a`) alone is not. Where it isn't there yet (the index at the end of
         * the file), the decoder decides once it plays.
         */
        private fun mp4(bytes: ByteArray): LosslessHeader {
            com.opentune.playback.LosslessAudioPolicy.mp4StreamInfo(bytes)?.let { return Mp4(it) }
            val text = String(bytes, Charsets.ISO_8859_1)
            return when {
                "fLaC" in text || "dfLa" in text || "alac" in text -> Mp4(null)
                "mp4a" in text || "Opus" in text || "ac-3" in text || "ec-3" in text -> NotLossless
                else -> Mp4(null)
            }
        }

        /**
         * Whether a FLAC [fileMs] long is the recording asked for. With a length on the add-on's row
         * ([rowMs]), the file has to be that long or the song's length ([songMs]), to within three
         * seconds: the matcher already held the row to the song (half a minute, or a music video's
         * intro and outro for the same artist), and the row is what tells a wrong file from a master
         * a few seconds off YouTube's. With none, the file answers to the song, to half a minute.
         */
        fun sameRecording(songMs: Long, rowMs: Long, fileMs: Long): Boolean {
            if (fileMs <= 0) return false
            if (rowMs > 0) return LosslessSource.durationMatches(rowMs, fileMs) || LosslessSource.durationMatches(songMs, fileMs)
            return songMs > 0 && abs(songMs - fileMs) <= TrackMatcher.DURATION_LIMIT_SEC * 1000L
        }

        private suspend fun fetched(read: suspend () -> ByteArray): ByteArray? =
            try { read() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}
