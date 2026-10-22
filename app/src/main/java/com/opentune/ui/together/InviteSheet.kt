package com.opentune.ui.together

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.opentune.data.together.RoomCode
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.rememberHaptics
import kotlinx.coroutines.delay

/**
 * How to bring friends in: the room link to share, and a QR code of it with
 * the code beside it, for someone in the same room to scan or type.
 */
@Composable
internal fun InviteSheet(code: RoomCode, hosting: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    FloatingCard(onDismiss = onDismiss, title = if (hosting) "Invite friends to your room" else "Invite friends to this room") {
        Column(Modifier.padding(horizontal = 4.dp)) {
            SheetButton(
                "Share link",
                onClick = { shareRoom(context, code) },
                tone = SheetTone.Primary,
                icon = Icons.Rounded.Link,
                modifier = Modifier.heightIn(min = 56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Scan to join", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Point a phone camera here to join.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    // One group a line: big enough to read across a room.
                    Text(
                        code.pretty.replace('-', '\n'),
                        Modifier.semantics { contentDescription = "Room code ${code.pretty}" },
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        lineHeight = 32.sp,
                        letterSpacing = 4.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    CopyPill(code)
                }
                Spacer(Modifier.width(16.dp))
                // White under the code whatever the theme: scanners want dark on light.
                Box(
                    Modifier.size(140.dp).clip(RoundedCornerShape(22.dp)).background(Color.White).padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) { QrCode(code.link, Modifier.fillMaxSize()) }
            }
        }
    }
}

/** Copies the code, and says so in place for a moment. */
@Composable
private fun CopyPill(code: RoomCode) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1_600); copied = false } }
    Row(
        Modifier.clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(role = Role.Button, onClickLabel = "Copy the room code") {
                haptics.tick()
                copyCode(context, code, toast = false)
                copied = true
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
        Text(if (copied) "Copied" else "Copy code", style = MaterialTheme.typography.labelLarge)
    }
}

/** [text] as a QR code, dark modules on whatever is behind it, edge to edge. */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier, color: Color = Color.Black) {
    val qr = remember(text) { qrModules(text) }
    Canvas(modifier.semantics { contentDescription = "QR code of the room link" }) {
        val cell = size.minDimension / qr.size
        // A rectangle per dark run along a row, so neighbours meet without hairline gaps.
        for (y in 0 until qr.size) {
            var x = 0
            while (x < qr.size) {
                if (!qr[x, y]) { x++; continue }
                val start = x
                while (x < qr.size && qr[x, y]) x++
                drawRect(color, Offset(start * cell, y * cell), Size((x - start) * cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** A QR code's modules, true for dark, without the quiet zone around it. */
internal class QrModules(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int) = dark[y * size + x]
}

/**
 * Encodes [text] at medium error correction, which still reads off a scuffed
 * or dim screen. The mask the encoder scores best can, about once in a
 * thousand codes, leave a pattern a scanner mistakes for a corner mark; each
 * code is read back once here, and if it doesn't read, the first other mask
 * that does is used.
 */
internal fun qrModules(text: String): QrModules {
    fun modules(mask: Int?): QrModules {
        val hints = mask?.let { mapOf(com.google.zxing.EncodeHintType.QR_MASK_PATTERN to it) }
        val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, hints).matrix
        val n = matrix.width
        return QrModules(n, BooleanArray(n * n) { matrix.get(it % n, it / n).toInt() == 1 })
    }
    val best = modules(null)
    if (readBack(best) == text) return best
    return (0 until 8).asSequence().map(::modules).firstOrNull { readBack(it) == text } ?: best
}

/** What a scanner reads from [qr], drawn plainly with a quiet zone, or null. */
internal fun readBack(qr: QrModules, scale: Int = 4, quiet: Int = 4): String? {
    val side = (qr.size + quiet * 2) * scale
    val pixels = IntArray(side * side) { i ->
        val x = (i % side) / scale - quiet
        val y = (i / side) / scale - quiet
        if (x in 0 until qr.size && y in 0 until qr.size && qr[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(side, side, pixels)))
    return runCatching { com.google.zxing.qrcode.QRCodeReader().decode(bitmap).text }.getOrNull()
}

/** Opens the share sheet with the room's link and code. */
internal fun shareRoom(context: Context, code: RoomCode) {
    val text = "Listen with me on OpenTune. Room code: ${code.pretty}\n${code.link}"
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite to your room"))
}

internal fun copyCode(context: Context, code: RoomCode, toast: Boolean = true) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("OpenTune room", code.pretty))
    if (toast) com.opentune.data.Pills.done("Code copied", context)
}
