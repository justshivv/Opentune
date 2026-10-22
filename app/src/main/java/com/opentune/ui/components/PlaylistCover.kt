package com.opentune.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.artworkAt

/**
 * A cover for a playlist made in the app, made from its songs' covers: four
 * or more make a 2×2 collage, two or three split the square, one fills it.
 * With no song covers yet, a cover is drawn from the playlist's name: a
 * gradient in colours picked from it, soft lights, and a sound wave whose
 * shape the name also sets, so the same playlist always looks the same and
 * two playlists rarely alike. A large cover carries the name.
 */
@Composable
fun PlaylistCover(name: String, covers: List<String?>, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(14.dp)) {
    val distinct = remember(covers) { covers.filterNotNull().distinct().take(4) }
    val (from, to) = remember(name) { gradientFor(name) }
    val wave = remember(name) { waveFor(name) }
    BoxWithConstraints(modifier.clip(shape)) {
        val large = maxWidth >= 120.dp
        val half = maxWidth / 2
        val full = maxWidth
        when (distinct.size) {
            0 -> Canvas(Modifier.matchParentSize()) {
                drawRect(Brush.linearGradient(listOf(from, to), start = Offset.Zero, end = Offset(size.width, size.height)))
                // Two soft lights so it isn't flat.
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), Offset(size.width * 0.2f, size.height * 0.15f), size.minDimension * 0.7f), size.minDimension * 0.7f, Offset(size.width * 0.2f, size.height * 0.15f))
                drawCircle(Brush.radialGradient(listOf(to.copy(alpha = 0.9f), Color.Transparent), Offset(size.width * 0.95f, size.height), size.minDimension * 0.8f), size.minDimension * 0.8f, Offset(size.width * 0.95f, size.height))
                // The name's own sound wave across the middle: rounded bars, brightest in the centre.
                val bars = wave.size
                val gap = size.width / (bars * 2f + 1f)
                val mid = size.height * (if (large) 0.42f else 0.5f)
                wave.forEachIndexed { i, level ->
                    val x = gap * (1.5f + i * 2f)
                    val h = size.height * (0.1f + 0.42f * level)
                    val centre = 1f - kotlin.math.abs(i - (bars - 1) / 2f) / (bars / 2f)
                    drawLine(
                        Color.White.copy(alpha = 0.35f + 0.5f * centre),
                        Offset(x, mid - h / 2f), Offset(x, mid + h / 2f),
                        strokeWidth = gap * 1.05f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }
            1 -> Artwork(distinct[0].artworkAt(CARD_ART_PX), Modifier.matchParentSize(), RectangleShape)
            2 -> Row {
                Artwork(distinct[0].artworkAt(CARD_ART_PX), Modifier.size(half, full), RectangleShape)
                Artwork(distinct[1].artworkAt(CARD_ART_PX), Modifier.size(half, full), RectangleShape)
            }
            3 -> Row {
                Artwork(distinct[0].artworkAt(CARD_ART_PX), Modifier.size(half, full), RectangleShape)
                Column {
                    Artwork(distinct[1].artworkAt(CARD_ART_PX), Modifier.size(half), RectangleShape)
                    Artwork(distinct[2].artworkAt(CARD_ART_PX), Modifier.size(half), RectangleShape)
                }
            }
            else -> Column {
                for (r in 0..1) Row {
                    for (c in 0..1) Artwork(distinct[r * 2 + c].artworkAt(CARD_ART_PX), Modifier.size(half), RectangleShape)
                }
            }
        }
        if (large) {
            // A shade at the foot for the name to sit on.
            if (distinct.isNotEmpty()) {
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))))
            }
            Text(
                name,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 30.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            )
        }
    }
}

/** Seven bar heights (0..1) from a name, the same every time for the same name. */
internal fun waveFor(name: String): List<Float> {
    var x = name.trim().lowercase().hashCode().toLong() and 0xFFFFFFFFL
    return List(7) {
        // A small LCG, stirred per bar.
        x = (x * 1103515245L + 12345L) and 0x7FFFFFFFL
        0.25f + 0.75f * ((x ushr 8) % 1000L).toFloat() / 1000f
    }
}

/** Two colours from a name: a hue picked from it, and its neighbour a little round the wheel. */
internal fun gradientFor(name: String): Pair<Color, Color> {
    // The name's hash, stirred so that names alike in their letters still land far apart.
    var x = name.trim().lowercase().hashCode()
    x = x xor (x ushr 16)
    x *= -0x7a143595
    x = x xor (x ushr 13)
    x *= -0x3d4d51cb
    x = x xor (x ushr 16)
    val h = (x and 0x7FFFFFFF) % 360
    return Color.hsv(h.toFloat(), 0.62f, 0.78f) to Color.hsv(((h + 48) % 360).toFloat(), 0.7f, 0.5f)
}
