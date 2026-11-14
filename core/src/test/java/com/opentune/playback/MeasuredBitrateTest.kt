package com.opentune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A song's bitrate measured from its audio, and which figure is shown. */
class MeasuredBitrateTest {
    @Test fun measuredFromBytesAndSpan() {
        // A FLAC stream: 2.25 MB of frames over 20 s is 900 kbps.
        assertEquals(900, MeasuredBitrate.kbpsOf(2_250_000, 20_000_000))
        // Too short a stretch says nothing yet.
        assertNull(MeasuredBitrate.kbpsOf(100_000, 500_000))
    }

    @Test fun bestFigureFirst() {
        val flac = AudioFormatInfo("FLAC", null, 96_000, 2, 24, mime = "audio/flac")
        // The measurement beats a missing or stated figure.
        assertEquals(2_811, bestKbps(flac, 2_811, null))
        assertEquals(2_811, bestKbps(flac.copy(bitrateKbps = 3_000), 2_811, 3_100))
        // Nothing measured yet: what the stream says, then the source.
        assertEquals(3_000, bestKbps(flac.copy(bitrateKbps = 3_000), null, 3_100))
        assertEquals(3_100, bestKbps(flac, null, 3_100))
        // Uncompressed PCM is exact from its format: 24-bit, 96 kHz, stereo.
        assertEquals(4_608, bestKbps(AudioFormatInfo("RAW", null, 96_000, 2, 24, mime = "audio/raw"), null, null))
        assertNull(bestKbps(flac, null, null))
    }

    /** Frames of [kbps] audio, 100 ms each, from [fromUs] for [seconds]; returns what was published. */
    private fun BitrateSettler.feed(fromUs: Long, seconds: Int, kbps: (Long) -> Int): List<Int> {
        val shown = mutableListOf<Int>()
        for (i in 0 until seconds * 10) {
            val t = fromUs + i * 100_000L
            frame(kbps(t) * 100 / 8, t)?.let(shown::add)
        }
        return shown
    }

    @Test fun aSteadyStreamIsShownOnceItSettlesAndThenStaysPut() {
        val settler = BitrateSettler()
        val shown = settler.feed(0, 30) { 160 }
        // One figure, published once, a few seconds in, never changed after.
        assertEquals(listOf(160), shown)
        assertEquals(160, settler.settled)
    }

    @Test fun aVariableStreamStillSettlesByTwelveSeconds() {
        val settler = BitrateSettler()
        // FLAC that swings ±20% second by second still gives one figure, its average, by twelve seconds.
        val shown = settler.feed(0, 30) { t -> if ((t / 1_000_000L) % 2 == 0L) 1_080 else 720 }
        assertEquals(1, shown.size)
        assertEquals(true, shown.single() in 850..950)
    }

    @Test fun seeksKeepTheFigureAndANewStreamMeasuresAgain() {
        val settler = BitrateSettler()
        settler.feed(0, 20) { 256 }
        settler.seeked()
        // After the seek the louder part plays, but the figure shown stays.
        assertEquals(emptyList<Int>(), settler.feed(120_000_000, 20) { 400 })
        assertEquals(256, settler.settled)
        settler.reset()
        assertNull(settler.settled)
        assertEquals(listOf(900), settler.feed(0, 20) { 900 })
    }

    @Test fun aShortSongIsMeasuredAtItsEnd() {
        val settler = BitrateSettler()
        assertEquals(emptyList<Int>(), settler.feed(0, 3) { t -> if (t < 1_500_000) 300 else 100 })
        assertEquals(true, settler.ended() != null)
        assertNull(settler.ended())
    }
}
