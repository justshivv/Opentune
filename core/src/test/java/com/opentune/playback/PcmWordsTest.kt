package com.opentune.playback

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** The exclusive USB path's sample rewrite must give the DAC exactly the song's own values. */
class PcmWordsTest {
    private fun rewrite(input: ByteBuffer, carrier: Int, target: Int, channels: Int = 2): ByteBuffer {
        val into = ByteBuffer.allocate(PcmWords.size(input.remaining(), carrier, target, channels))
        return PcmWords.rewrite(input, carrier, target, channels, into).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun read24(b: ByteBuffer): Int = (b.get().toInt() and 0xFF) or ((b.get().toInt() and 0xFF) shl 8) or (b.get().toInt() shl 16)

    @Test fun every24BitValueComesBackExactlyThroughFloat() {
        // What the decoder makes, the precision sink carries as float, and the DAC gets as 24-bit.
        val values = intArrayOf(0, 1, -1, 8_388_607, -8_388_608, 123_456, -654_321, 4_194_304)
        val floats = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
        values.forEach { floats.putFloat(it / 8_388_608f) }
        floats.flip()
        val out = rewrite(floats, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_24BIT)
        assertEquals(values.size * 3, out.remaining())
        values.forEach { assertEquals(it, read24(out)) }
    }

    @Test fun a24BitValueInA32BitWordIsTheSameValueShifted() {
        val floats = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        floats.putFloat(-654_321 / 8_388_608f).putFloat(8_388_607 / 8_388_608f).flip()
        val out = rewrite(floats, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT)
        assertEquals(-654_321 shl 8, out.getInt())
        assertEquals(8_388_607 shl 8, out.getInt())
    }

    @Test fun sixteenBitIsPaddedNotScaled() {
        val shorts = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
        shorts.putShort(Short.MIN_VALUE).putShort(12_345).flip()
        val wide = rewrite(shorts.duplicate().order(ByteOrder.nativeOrder()), C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT)
        assertEquals(Short.MIN_VALUE.toInt() shl 8, read24(wide))
        assertEquals(12_345 shl 8, read24(wide))
        val word = rewrite(shorts, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_32BIT)
        assertEquals(Short.MIN_VALUE.toInt() shl 16, word.getInt())
        assertEquals(12_345 shl 16, word.getInt())
    }

    @Test fun aPartialFrameIsDroppedNotMisread() {
        val shorts = ByteBuffer.allocate(6).order(ByteOrder.nativeOrder())
        shorts.putShort(1).putShort(2).putShort(3).flip()
        val out = rewrite(shorts, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_32BIT)
        assertEquals(8, out.remaining())
    }

    @Test fun aTwentyFourBitSongIsNeverSentAsSixteen() {
        assertEquals(listOf(C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT), BitPerfectUsb.carriers(C.ENCODING_PCM_24BIT))
        assertEquals(C.ENCODING_PCM_16BIT, BitPerfectUsb.carriers(C.ENCODING_PCM_16BIT).first())
    }
}
