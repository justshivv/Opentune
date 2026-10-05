package com.opentune.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.activeIndex
import com.opentune.ui.LyricsState
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.Placeholder
import com.opentune.ui.theme.LyricsTextStyle
import kotlin.math.abs
import kotlinx.coroutines.delay

@Composable
fun LyricsView(
    state: LyricsState,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    synced: Boolean = true,
    blur: Boolean = true,
) {
    Box(modifier) {
        when (state) {
            is LyricsState.Loading -> Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                listOf(0.8f, 0.6f, 0.9f, 0.5f, 0.7f).forEach {
                    Placeholder(Modifier.fillMaxWidth(it).height(28.dp), MaterialTheme.shapes.small)
                }
            }
            is LyricsState.NotFound -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                MessageState(Icons.Rounded.Lyrics, "No lyrics for this one", message = "LRCLIB doesn't have lyrics for this track yet.")
            }
            is LyricsState.Found -> when (val lyrics = state.lyrics) {
                is Lyrics.Synced ->
                    if (synced) SyncedLyrics(lyrics.lines, position, onSeek, blur)
                    else PlainLyrics(lyrics.lines.joinToString("\n") { it.text }, note = null)
                is Lyrics.Plain -> PlainLyrics(lyrics.text)
            }
        }
    }
}

@Composable
private fun PlainLyrics(text: String, note: String? = "These lyrics aren't synced to the music.") {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        Text(text, style = LyricsTextStyle.copy(fontSize = LyricsTextStyle.fontSize * 0.8f, lineHeight = LyricsTextStyle.lineHeight * 0.85f))
        Credit()
    }
}

/**
 * Line-by-line lyrics that follow the music. The current line is bright and
 * full size and its words light up as they're sung; the rest are dimmed,
 * slightly smaller and (on Android 12+) softly blurred by distance. The list
 * glides to keep the current line about a third of the way down. Scrolling by
 * hand pauses that for a few seconds; tapping a line jumps the song there.
 */
@Composable
private fun SyncedLyrics(lines: List<LyricLine>, position: () -> Long, onSeek: (Long) -> Unit, blur: Boolean) {
    val listState = rememberLazyListState()
    val active by remember(lines) { derivedStateOf { lines.activeIndex(position()) } }
    var following by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }

    // A drag we didn't start means the listener wants to look around.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) following = false
        }
    }
    LaunchedEffect(following, listState.isScrollInProgress) {
        if (!following && !listState.isScrollInProgress) {
            delay(RESUME_FOLLOW_MS)
            following = true
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val anchor = maxHeight * 0.3f
        LaunchedEffect(active, following) {
            if (!following) return@LaunchedEffect
            autoScrolling = true
            // +1 for the intro item ahead of the first line.
            glideTo(listState, (active + 1).coerceAtLeast(0))
            autoScrolling = false
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = anchor, bottom = maxHeight * 0.6f),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "intro") {
                val firstStart = lines.firstOrNull()?.startMs ?: 0L
                val waiting by remember(firstStart) { derivedStateOf { firstStart > 2_500 && position() < firstStart - 300 } }
                IntroDots(visible = waiting)
            }
            itemsIndexed(lines, key = { i, line -> "$i:${line.startMs}" }) { i, line ->
                LyricLineView(
                    line = line,
                    distance = i - active,
                    isActive = i == active,
                    blurEnabled = blur && following,
                    position = position,
                    onClick = {
                        onSeek(line.startMs)
                        following = true
                    },
                )
            }
            item(key = "credit") { Credit() }
        }

        AnimatedVisibility(
            visible = !following,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        ) {
            AssistChip(
                onClick = { following = true },
                label = { Text("Back to the music") },
                leadingIcon = { Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

/** Scroll [index] to the top of the content area (the anchor), smoothly when it's near. */
private suspend fun glideTo(state: LazyListState, index: Int) {
    val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item != null) {
        state.animateScrollBy(item.offset.toFloat(), tween(durationMillis = 650, easing = FastOutSlowInEasing))
    } else {
        state.scrollToItem(index)
    }
}

@Composable
private fun LyricLineView(
    line: LyricLine,
    distance: Int,
    isActive: Boolean,
    blurEnabled: Boolean,
    position: () -> Long,
    onClick: () -> Unit,
) {
    val bright = MaterialTheme.colorScheme.onSurface
    val dim = bright.copy(alpha = 0.32f)
    val scale by animateFloatAsState(
        if (isActive) 1f else 0.93f,
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "lineScale",
    )
    val alpha by animateFloatAsState(if (isActive) 1f else if (distance < 0) 0.7f else 0.85f, tween(400), label = "lineAlpha")
    val blur by animateDpAsState(
        if (!blurEnabled || isActive) 0.dp else (minOf(abs(distance), 4) * 1.1f).dp,
        tween(500),
        label = "lineBlur",
    )

    Text(
        text = if (isActive) litWords(line, position(), dim, bright) else AnnotatedString(line.text),
        style = LyricsTextStyle,
        color = dim,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 10.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > 0.dp) Modifier.blur(blur) else Modifier),
    )
}

/**
 * The active line with each word lit by how far through it the song is, so
 * the highlight sweeps along instead of jumping a word at a time.
 */
private fun litWords(line: LyricLine, positionMs: Long, dim: Color, bright: Color): AnnotatedString =
    buildAnnotatedString {
        line.words.forEach { word ->
            val span = (word.endMs - word.startMs).coerceAtLeast(1)
            val progress = ((positionMs - word.startMs).toFloat() / span).coerceIn(0f, 1f)
            withStyle(SpanStyle(color = lerp(dim, bright, easeOut(progress)))) { append(word.text) }
        }
        if (line.words.isEmpty()) append(line.text)
    }

private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

/** Three dots breathing in sequence while an intro plays. */
@Composable
private fun IntroDots(visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val transition = rememberInfiniteTransition(label = "intro")
        Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { i ->
                val s by transition.animateFloat(
                    0.6f,
                    1f,
                    infiniteRepeatable(tween(600, delayMillis = i * 200), RepeatMode.Reverse),
                    label = "dot",
                )
                Box(
                    Modifier
                        .size(14.dp)
                        .graphicsLayer { scaleX = s; scaleY = s; alpha = s }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface),
                )
            }
        }
    }
}

@Composable
private fun Credit() {
    Spacer(Modifier.height(24.dp))
    Text(
        "Lyrics from LRCLIB",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val RESUME_FOLLOW_MS = 3_000L
