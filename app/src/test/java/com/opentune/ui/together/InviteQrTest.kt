package com.opentune.ui.together

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.opentune.data.together.RoomCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteQrTest {
    @Test fun theRoomLinkReadsBackFromItsQrCode() {
        val code = RoomCode.parse("K7QX-M2PA-9DTE")!!
        val qr = qrModules(code.link)
        // Small enough to scan off a phone held across a table.
        assertTrue("${qr.size} modules", qr.size in 21..41)
        assertEquals(code.link, decode(qr))
        assertEquals(code, RoomCode.parse(decode(qr)))
    }

    /** One of the codes whose best-scored mask a reader couldn't find. */
    @Test fun aCodeTheDefaultMaskHidesStillReads() {
        val link = RoomCode.parse("HQFK-FLBW-FRUU")!!.link
        assertTrue(link, link.endsWith("#code=HQFKFLBWFRUU"))
        assertEquals(link, decode(qrModules(link)))
    }

    @Test fun everyRoomFits() {
        repeat(200) {
            val code = RoomCode.generate()
            assertEquals(code.link, decode(qrModules(code.link)))
        }
    }

    /** Draws the modules as a scanner would see them, with a quiet zone, and reads them. */
    private fun decode(qr: QrModules, scale: Int = 4, quiet: Int = 4): String {
        val side = (qr.size + quiet * 2) * scale
        val pixels = IntArray(side * side) { i ->
            val x = (i % side) / scale - quiet
            val y = (i / side) / scale - quiet
            val dark = x in 0 until qr.size && y in 0 until qr.size && qr[x, y]
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }
}
