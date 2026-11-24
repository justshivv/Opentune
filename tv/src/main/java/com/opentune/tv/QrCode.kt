package com.opentune.tv

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * [text] as a QR code, black on transparent: put it on a white square, which scanners need.
 * The modules are joined into one path, made once per size, so a redraw is a single fill.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { encode(text) }
    Spacer(
        modifier
            .drawWithCache {
                val m = matrix ?: return@drawWithCache onDrawBehind { }
                // Whole pixels per module, so every module is the same size and edges stay sharp.
                val cell = kotlin.math.floor(minOf(size.width / m.width, size.height / m.height)).coerceAtLeast(1f)
                val origin = Offset((size.width - cell * m.width) / 2f, (size.height - cell * m.height) / 2f)
                val path = Path()
                for (y in 0 until m.height) {
                    var x = 0
                    while (x < m.width) {
                        if (!m.get(x, y)) { x++; continue }
                        // A run of dark modules on a row is one rectangle.
                        val start = x
                        while (x < m.width && m.get(x, y)) x++
                        path.addRect(Rect(Offset(origin.x + start * cell, origin.y + y * cell), Size((x - start) * cell, cell)))
                    }
                }
                onDrawBehind { drawPath(path, Color.Black) }
            },
    )
}

private fun encode(text: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf<EncodeHintType, Any>(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
    )
}.getOrNull()
