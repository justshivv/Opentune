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
 * A cover for a playlist made in the app. With four or more different song
 * covers, they make a 2×2 collage; with fewer, a soft gradient in colours
 * drawn from the playlist's name, so the same playlist always looks the
 * same. A large cover carries the name; a small one its first letter.
 */
@Composable
fun PlaylistCover(name: String, covers: List<String?>, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(14.dp)) {
    val distinct = remember(covers) { covers.filterNotNull().distinct().take(4) }
    val (from, to) = remember(name) { gradientFor(name) }
    BoxWithConstraints(modifier.clip(shape)) {
        val large = maxWidth >= 120.dp
        if (distinct.size >= 4) {
            val half = maxWidth / 2
            Column {
                for (r in 0..1) Row {
                    for (c in 0..1) Artwork(distinct[r * 2 + c].artworkAt(CARD_ART_PX), Modifier.size(half), RectangleShape)
                }
            }
            if (large) {
                // A shade at the foot for the name to sit on.
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))))
            }
        } else {
            Canvas(Modifier.matchParentSize()) {
                drawRect(Brush.linearGradient(listOf(from, to), start = Offset.Zero, end = Offset(size.width, size.height)))
                // Two soft lights so it isn't flat.
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), Offset(size.width * 0.2f, size.height * 0.15f), size.minDimension * 0.7f), size.minDimension * 0.7f, Offset(size.width * 0.2f, size.height * 0.15f))
                drawCircle(Brush.radialGradient(listOf(to.copy(alpha = 0.9f), Color.Transparent), Offset(size.width * 0.95f, size.height), size.minDimension * 0.8f), size.minDimension * 0.8f, Offset(size.width * 0.95f, size.height))
            }
            if (!large) {
                Text(
                    name.trim().firstOrNull()?.uppercase() ?: "♪",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (maxWidth.value * 0.42f).sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        if (large) {
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
