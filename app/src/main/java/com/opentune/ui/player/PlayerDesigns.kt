package com.opentune.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.model.PLAYER_ART_PX
import com.opentune.data.model.Song
import com.opentune.ui.components.Artwork
import com.opentune.data.model.artworkAt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Seconds of playback while [running], counted per frame; holds still otherwise. */
@Composable
private fun rememberRunningClock(running: Boolean): () -> Float {
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) seconds += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    return { seconds }
}

// ---------------------------------------------------------------- Cassette

/**
 * The song as a cassette: the cover and title on the label, and two reels
 * that turn while it plays. The tape winds from the left reel to the right
 * one as the song goes on, and a fuller reel turns slower, as on a real tape.
 */
@Composable
fun CassettePane(
    song: Song,
    isPlaying: Boolean,
    progress: () -> Float,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val clock = rememberRunningClock(isPlaying && animate)
    val accent = MaterialTheme.colorScheme.primary
    ArtworkSwipeArea(onSwipeNext = onSwipeNext, onSwipePrevious = onSwipePrevious, modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth(0.96f)
                .aspectRatio(1.58f)
                .align(Alignment.Center)
                .graphicsLayer { shadowElevation = 24.dp.toPx(); shape = RoundedCornerShape(26.dp); clip = true }
                .background(SHELL),
        ) {
            val w = maxWidth
            Canvas(Modifier.fillMaxSize()) { drawCassette(progress().coerceIn(0f, 1f), clock(), accent) }
            // The label: cover and title.
            Row(
                Modifier
                    .padding(start = w * 0.075f, end = w * 0.075f, top = w * 0.05f)
                    .fillMaxWidth()
                    .aspectRatio(4.4f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(LABEL)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(song.thumbnailUrl.artworkAt(PLAYER_ART_PX), Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true), RoundedCornerShape(6.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = INK, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = INK.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("A", color = accent, fontWeight = FontWeight.Black, fontSize = 22.sp)
            }
        }
    }
}

private val SHELL = Color(0xFF1D1B22)
private val LABEL = Color(0xFFF4F1EA)
private val INK = Color(0xFF16141A)
private val TAPE = Color(0xFF3A2A22)
private val WINDOW = Color(0xFF0E0D12)

private fun DrawScope.drawCassette(progress: Float, seconds: Float, accent: Color) {
    val w = size.width
    val h = size.height
    // Window across the middle, where the reels show.
    val win = Size(w * 0.62f, h * 0.34f)
    val winTop = Offset((w - win.width) / 2, h * 0.42f)
    drawRoundRect(WINDOW, winTop, win, CornerRadius(win.height / 2))
    val cy = winTop.y + win.height / 2
    val left = Offset(winTop.x + win.height / 2, cy)
    val right = Offset(winTop.x + win.width - win.height / 2, cy)
    val hub = win.height * 0.2f
    val full = win.height * 0.47f
    val empty = hub * 1.25f
    val rLeft = empty + (full - empty) * (1f - progress)
    val rRight = empty + (full - empty) * progress
    // The tape between the reels, along the bottom of the window.
    drawLine(TAPE, Offset(left.x, cy + rLeft), Offset(right.x, cy + rRight), strokeWidth = 3f)
    // Each reel turns at tape speed over its own radius.
    val tapeSpeed = 34f
    reel(left, rLeft, hub, seconds * tapeSpeed / rLeft * 57.3f)
    reel(right, rRight, hub, seconds * tapeSpeed / rRight * 57.3f)
    // A strip of the accent colour along the bottom edge and the screw holes.
    drawRoundRect(accent.copy(alpha = 0.85f), Offset(w * 0.075f, h * 0.83f), Size(w * 0.85f, h * 0.03f), CornerRadius(h * 0.02f))
    listOf(Offset(w * 0.04f, h * 0.06f), Offset(w * 0.96f, h * 0.06f), Offset(w * 0.04f, h * 0.94f), Offset(w * 0.96f, h * 0.94f), Offset(w * 0.5f, h * 0.93f))
        .forEach { drawCircle(Color.White.copy(alpha = 0.16f), radius = h * 0.018f, center = it) }
}

private fun DrawScope.reel(center: Offset, radius: Float, hub: Float, degrees: Float) {
    drawCircle(TAPE, radius = radius, center = center)
    drawCircle(Color.White.copy(alpha = 0.06f), radius = radius, center = center, style = Stroke(1.5f))
    drawCircle(Color(0xFFE9E5DC), radius = hub, center = center)
    rotate(degrees, center) {
        repeat(6) { i ->
            val a = i * PI.toFloat() / 3f
            drawLine(
                WINDOW,
                Offset(center.x + cos(a) * hub * 0.35f, center.y + sin(a) * hub * 0.35f),
                Offset(center.x + cos(a) * hub * 0.85f, center.y + sin(a) * hub * 0.85f),
                strokeWidth = hub * 0.18f,
                cap = StrokeCap.Round,
            )
        }
    }
    drawCircle(WINDOW, radius = hub * 0.28f, center = center)
}

// ---------------------------------------------------------------- Halo

/**
 * A round cover inside a ring of bars that rise and fall while the song
 * plays. The bars don't follow the sound (reading the audio would need the
 * microphone permission), so they're movement, not a level meter.
 */
@Composable
fun HaloPane(
    song: Song,
    isPlaying: Boolean,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val clock = rememberRunningClock(isPlaying && animate)
    val ring = MaterialTheme.colorScheme.primary
    val soft = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    ArtworkSwipeArea(onSwipeNext = onSwipeNext, onSwipePrevious = onSwipePrevious, modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(0.96f).aspectRatio(1f).align(Alignment.Center), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val t = clock()
                val r = size.minDimension / 2
                val inner = r * 0.72f
                val bars = 72
                repeat(bars) { i ->
                    val a = i / bars.toFloat() * 2f * PI.toFloat() - PI.toFloat() / 2
                    val level = haloLevel(i, t, isPlaying)
                    val from = inner + r * 0.03f
                    val to = from + r * (0.04f + 0.2f * level)
                    drawLine(soft, Offset(center.x + cos(a) * from, center.y + sin(a) * from), Offset(center.x + cos(a) * (from + r * 0.24f), center.y + sin(a) * (from + r * 0.24f)), strokeWidth = r * 0.022f, cap = StrokeCap.Round)
                    drawLine(ring.copy(alpha = 0.55f + 0.45f * level), Offset(center.x + cos(a) * from, center.y + sin(a) * from), Offset(center.x + cos(a) * to, center.y + sin(a) * to), strokeWidth = r * 0.022f, cap = StrokeCap.Round)
                }
            }
            val breathe = if (isPlaying) 1f + 0.012f * sin(clock() * 3.2f) else 1f
            Artwork(
                song.thumbnailUrl.artworkAt(PLAYER_ART_PX),
                Modifier.fillMaxSize(0.68f).graphicsLayer { scaleX = breathe; scaleY = breathe; shadowElevation = 20.dp.toPx(); shape = CircleShape; clip = true },
                CircleShape,
            )
        }
    }
}

/** How tall bar [i] is at time [t]: a few slow waves added together, so neighbours move as one. */
internal fun haloLevel(i: Int, t: Float, playing: Boolean): Float {
    if (!playing) return 0.08f
    val x = i * 0.35f
    val v = 0.5f + 0.25f * sin(x + t * 2.1f) + 0.15f * sin(x * 2.3f - t * 3.7f) + 0.1f * sin(x * 5.1f + t * 6.3f)
    return v.coerceIn(0f, 1f)
}

// ---------------------------------------------------------------- Polaroid

/** The cover as an instant photo, with the title written under it, swaying a little while the song plays. */
@Composable
fun PolaroidPane(
    song: Song,
    isPlaying: Boolean,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val clock = rememberRunningClock(isPlaying && animate)
    ArtworkSwipeArea(onSwipeNext = onSwipeNext, onSwipePrevious = onSwipePrevious, modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth(0.8f)
                .align(Alignment.Center)
                .graphicsLayer {
                    rotationZ = -2.5f + 1.6f * sin(clock() * 0.9f)
                    shadowElevation = 26.dp.toPx()
                    shape = RoundedCornerShape(6.dp)
                    clip = true
                }
                .background(PAPER)
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Artwork(song.thumbnailUrl.artworkAt(PLAYER_ART_PX), Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(2.dp))
            Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                Text(song.title, color = INK, fontSize = 22.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = INK.copy(alpha = 0.55f), fontSize = 15.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val PAPER = Color(0xFFFAF8F3)
