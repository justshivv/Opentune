package com.opentune.data.lossless

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LosslessHeaderTest {
    /** A FLAC STREAMINFO header: [bits]-bit, [rate] Hz stereo, [seconds] long. */
    private fun flac(bits: Int = 16, rate: Long = 44_100, seconds: Int = 180) = ByteArray(42).apply {
        "fLaC".toByteArray().copyInto(this); this[4] = 0x80.toByte(); this[7] = 34
        val packed = (rate shl 44) or (1L shl 41) or ((bits - 1).toLong() shl 36) or (rate * seconds)
        for (i in 0..7) this[18 + i] = (packed ushr (56 - i * 8)).toByte()
    }

    /** An ID3v2 tag [body] bytes long (syncsafe size), as a tagged file starts. */
    private fun id3(body: Int) = ByteArray(10 + body).apply {
        "ID3".toByteArray().copyInto(this); this[3] = 4
        this[6] = ((body shr 21) and 0x7f).toByte(); this[7] = ((body shr 14) and 0x7f).toByte()
        this[8] = ((body shr 7) and 0x7f).toByte(); this[9] = (body and 0x7f).toByte()
    }

    /** What [LosslessHeader.read] makes of a file that is [file], fetched by prefix like a server would. */
    private fun read(file: ByteArray, failing: Boolean = false): LosslessHeader = runBlocking {
        LosslessHeader.read("https://cdn.example/file") { _, size, _ ->
            if (failing) throw java.io.IOException("reset")
            file.copyOf(minOf(size, file.size))
        }
    }

    @Test fun plainFlacIsRead() {
        val header = read(flac(24, 96_000)) as LosslessHeader.Flac
        assertEquals(24, header.info.bits); assertEquals(96_000, header.info.sampleRate)
    }

    @Test fun aFlacBehindAnId3TagIsReadPastTheTag() {
        val header = read(id3(300_000) + flac(24, 48_000))
        assertTrue(header is LosslessHeader.Flac)
        assertEquals(48_000, (header as LosslessHeader.Flac).info.sampleRate)
    }

    @Test fun anMp3IsTurnedDownWithOrWithoutItsTag() {
        val frame = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64) + ByteArray(60)
        assertEquals(LosslessHeader.NotLossless, read(frame))
        assertEquals(LosslessHeader.NotLossless, read(id3(2_000) + frame))
    }

    @Test fun anErrorPageIsNotAudio() {
        assertEquals(LosslessHeader.NotLossless, read("<html>not audio</html>".toByteArray()))
        assertEquals(LosslessHeader.NotLossless, read("""{"error":"x"}""".toByteArray()))
    }

    @Test fun mp4WithFlacIsLosslessAndMp4WithAacIsNot() {
        fun mp4(entry: String) = ByteArray(200).also { b ->
            byteArrayOf(0, 0, 0, 24).copyInto(b); "ftypisom".toByteArray().copyInto(b, 4)
            entry.toByteArray().copyInto(b, 120)
        }
        assertTrue(read(mp4("fLaC")) is LosslessHeader.Mp4)
        assertEquals(LosslessHeader.NotLossless, read(mp4("mp4a")))
    }

    @Test fun aFileThatCouldNotBeFetchedIsNotARefusal() {
        assertEquals(LosslessHeader.Unchecked, read(flac(), failing = true))
    }

    @Test fun voiceRateFlacIsNoUpgrade() {
        assertEquals(LosslessHeader.NotLossless, read(flac(16, 22_050)))
    }

    @Test fun theFileMayRunToItsRowsLengthButNeverFarFromTheSong() {
        // Same as the song, or as the row it was listed under, to three seconds.
        assertTrue(LosslessHeader.sameRecording(180_000, 188_000, 181_000))
        assertTrue(LosslessHeader.sameRecording(180_000, 188_000, 187_000))
        // As long as neither: another file.
        assertFalse(LosslessHeader.sameRecording(180_000, 188_000, 200_000))
        // The row already passed the matcher, so a file as long as its row stands, video intro or not.
        assertTrue(LosslessHeader.sameRecording(240_000, 200_000, 200_500))
        // With no length on the row, the file answers to the song, to half a minute.
        assertTrue(LosslessHeader.sameRecording(180_000, 0, 200_000))
        assertFalse(LosslessHeader.sameRecording(180_000, 0, 215_000))
    }
}
