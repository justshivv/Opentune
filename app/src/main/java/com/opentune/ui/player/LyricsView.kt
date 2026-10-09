package com.opentune.ui.player

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.activeIndex
import com.opentune.ui.LyricsState
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.Placeholder
import com.opentune.ui.theme.LyricsTextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import com.opentune.data.settings.LyricsAnimation
import androidx.compose.runtime.collectAsState
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.LyricsAlign
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
    /** Long-press on a line: it's being sung now, so the song's lyrics move to match. */
    onSyncLine: ((Long) -> Unit)? = null,
    /** The song, for sharing its lines as a lyric card; no share button without it. */
    shareSong: com.opentune.data.model.Song? = null,
) {
    var sharing by remember { mutableStateOf(false) }
    Box(modifier) {
        when (state) {
            is LyricsState.Loading -> Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                listOf(0.8f, 0.6f, 0.9f, 0.5f, 0.7f).forEach {
                    Placeholder(Modifier.fillMaxWidth(it).height(28.dp), MaterialTheme.shapes.small)
                }
            }
            is LyricsState.NotFound -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                MessageState(Icons.Rounded.Lyrics, "No lyrics for this one", message = "None of your lyrics sources has this track yet. You can change them in Settings › Lyrics sources.")
            }
            is LyricsState.Found -> when (val lyrics = state.lyrics) {
                is Lyrics.Synced ->
                    if (synced) SyncedLyrics(lyrics.lines, position, onSeek, blur, lyrics.source, onSyncLine)
                    else PlainLyrics(lyrics.lines.joinToString("\n") { it.text }, lyrics.source, note = null)
                is Lyrics.Plain -> PlainLyrics(lyrics.text, lyrics.source)
            }
        }
        val found = (state as? LyricsState.Found)?.lyrics
        if (shareSong != null && found != null) {
            val lines = remember(found) {
                when (found) {
                    is Lyrics.Synced -> found.lines.map { it.text }
                    is Lyrics.Plain -> found.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
            val haptics = com.opentune.ui.components.rememberHaptics()
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                    .clickable { haptics.tick(); sharing = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.FormatQuote, "Share lyrics", Modifier.size(20.dp))
            }
            if (sharing) com.opentune.ui.share.LyricCardSheet(shareSong, lines, startAt = -1, onDismiss = { sharing = false })
        }
    }
}

@Composable
private fun PlainLyrics(text: String, source: String, note: String? = "These lyrics aren't synced to the music.") {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        val style = lyricsStyle()
        Text(text, Modifier.fillMaxWidth(), style = style.copy(fontSize = style.fontSize * 0.8f, lineHeight = style.lineHeight * 0.85f))
        Credit(source)
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
private fun SyncedLyrics(
    lines: List<LyricLine>,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    blur: Boolean,
    source: String,
    onSyncLine: ((Long) -> Unit)? = null,
) {
    val animation = AppSettings.ui.collectAsState().value.lyricsAnimation
    val listState = rememberLazyListState()
    // The position function changes with the lyrics offset; read the latest.
    val pos by rememberUpdatedState(position)
    // A line lights a moment before its timestamp, so it's there as the singer starts it.
    val active by remember(lines) { derivedStateOf { lines.activeIndex(pos() + LINE_LEAD_MS) } }
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
                val waiting by remember(firstStart) { derivedStateOf { firstStart > 2_500 && pos() < firstStart - 300 } }
                IntroDots(visible = waiting)
            }
            itemsIndexed(lines, key = { i, line -> "$i:${line.startMs}" }) { i, line ->
                LyricLineView(
                    line = line,
                    distance = i - active,
                    isActive = i == active,
                    animation = animation,
                    blurEnabled = blur && following,
                    position = position,
                    onClick = {
                        onSeek(line.startMs)
                        following = true
                    },
                    onLongClick = onSyncLine?.let { sync -> { sync(line.startMs); following = true } },
                )
            }
            item(key = "credit") { Credit(source) }
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

/** How one [LyricsAnimation] treats lines: sizes, blur per line away, slide and glow, and how words move. */
private class LineMotion(
    val activeScale: Float,
    val inactiveScale: Float,
    val blurPerLine: Float,
    val slide: Dp,
    val glow: Boolean,
    val inactiveAlpha: Float,
    val word: WordMotion = WordMotion.NONE,
)

/** What a word of the current line does while it's sung. */
internal enum class WordMotion { NONE, BOUNCE, POP, REVEAL }

/** One word's lift (dp, up is positive), scale, glow and opacity, [t] of the way through singing it. */
internal class WordPose(val lift: Float, val scale: Float, val glow: Float, val alpha: Float)

internal fun wordPose(motion: WordMotion, t: Float): WordPose {
    val p = t.coerceIn(0f, 1f)
    val bell = kotlin.math.sin(Math.PI * p).toFloat()
    return when (motion) {
        WordMotion.NONE -> WordPose(0f, 1f, 0f, 1f)
        WordMotion.BOUNCE -> WordPose(7f * bell, 1f, 0f, 1f)
        WordMotion.POP -> WordPose(1.5f * bell, 1f + 0.14f * bell, bell, 1f)
        // Not there before its time, then up from a little below.
        WordMotion.REVEAL -> if (t <= 0f) WordPose(0f, 1f, 0f, 0f) else {
            val e = 1f - (1f - (p * 2f).coerceAtMost(1f)).let { it * it }
            WordPose(-8f * (1f - e), 1f, 0f, (p * 3f).coerceAtMost(1f))
        }
    }
}

private fun motionOf(animation: LyricsAnimation) = when (animation) {
    LyricsAnimation.FLUID -> LineMotion(1f, 0.93f, 1.1f, 0.dp, glow = false, inactiveAlpha = 0.8f)
    LyricsAnimation.KARAOKE -> LineMotion(1f, 1f, 0f, 0.dp, glow = true, inactiveAlpha = 0.55f)
    LyricsAnimation.SLIDE -> LineMotion(1f, 0.96f, 0.6f, 18.dp, glow = false, inactiveAlpha = 0.7f)
    LyricsAnimation.ZOOM -> LineMotion(1.08f, 0.86f, 1.9f, 0.dp, glow = false, inactiveAlpha = 0.6f)
    LyricsAnimation.MINIMAL -> LineMotion(1f, 1f, 0f, 0.dp, glow = false, inactiveAlpha = 0.6f)
    LyricsAnimation.BOUNCE -> LineMotion(1f, 0.94f, 1f, 0.dp, glow = false, inactiveAlpha = 0.75f, word = WordMotion.BOUNCE)
    LyricsAnimation.POP -> LineMotion(1f, 0.96f, 0.8f, 0.dp, glow = false, inactiveAlpha = 0.6f, word = WordMotion.POP)
    LyricsAnimation.REVEAL -> LineMotion(1f, 0.95f, 1.2f, 0.dp, glow = false, inactiveAlpha = 0.5f, word = WordMotion.REVEAL)
}

@Composable
private fun LyricLineView(
    line: LyricLine,
    distance: Int,
    isActive: Boolean,
    animation: LyricsAnimation,
    blurEnabled: Boolean,
    position: () -> Long,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val motion = motionOf(animation)
    val bright = MaterialTheme.colorScheme.onSurface
    val dim = bright.copy(alpha = 0.32f)
    val scale by animateFloatAsState(
        if (isActive) motion.activeScale else motion.inactiveScale,
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "lineScale",
    )
    val alpha by animateFloatAsState(
        if (isActive) 1f else if (distance < 0) motion.inactiveAlpha - 0.1f else motion.inactiveAlpha,
        tween(if (animation == LyricsAnimation.MINIMAL) 200 else 400),
        label = "lineAlpha",
    )
    val blur by animateDpAsState(
        if (!blurEnabled || isActive) 0.dp else (minOf(abs(distance), 4) * motion.blurPerLine).dp,
        tween(500),
        label = "lineBlur",
    )
    // Slide: lines wait a little to the right and the current one glides home.
    val shift by animateDpAsState(
        if (isActive || motion.slide == 0.dp) 0.dp else motion.slide,
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "lineShift",
    )
    val ui by AppSettings.ui.collectAsState()
    val centred = ui.lyricsAlign == LyricsAlign.CENTER
    val base = lyricsStyle()
    val style = if ((motion.glow || ui.lyricsGlow) && isActive) base.copy(shadow = Shadow(bright.copy(alpha = 0.55f), blurRadius = 24f)) else base
    val text = remember(line) { if (line.words.isEmpty()) line.text else line.words.joinToString("") { it.text } }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Where each word sits, worked out once per layout rather than every frame.
    val wordBoxes = remember(layout, line) {
        val l = layout ?: return@remember emptyList()
        var start = 0
        line.words.map { w ->
            val end = (start + w.text.length).coerceAtMost(text.length)
            val box = if (end > start) l.getPathForRange(start, end).getBounds() else Rect.Zero
            start = end
            box
        }
    }
    val sweep = isActive && line.words.isNotEmpty()
    // Words that move are drawn one by one, each measured on its own and set where the line put it.
    val perWord = sweep && motion.word != WordMotion.NONE
    val measurer = rememberTextMeasurer()
    val words = remember(layout, line, style, perWord) {
        val l = layout
        if (!perWord || l == null) return@remember emptyList()
        var start = 0
        line.words.map { w ->
            val at = start.coerceAtMost(text.length)
            start = (start + w.text.length).coerceAtMost(text.length)
            val row = l.getLineForOffset(at)
            measurer.measure(AnnotatedString(w.text), style) to Offset(l.getHorizontalPosition(at, usePrimaryDirection = true), l.getLineTop(row))
        }
    }

    Text(
        text = text,
        style = style,
        color = if (isActive) bright else dim,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(remember { MutableInteractionSource() }, indication = null, onLongClick = onLongClick, onClick = onClick)
            .padding(vertical = 10.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                translationX = shift.toPx()
                transformOrigin = TransformOrigin(if (centred) 0.5f else 0f, 0.5f)
                // The sweep below cuts into the text's own pixels, so it needs a layer of its own.
                if (sweep) compositingStrategy = CompositingStrategy.Offscreen
            }
            .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > 0.dp) Modifier.blur(blur) else Modifier)
            .then(
                when {
                    perWord && words.isNotEmpty() -> Modifier.drawWithContent { drawMovingWords(line, words, position(), motion.word, bright, dim) }
                    sweep -> Modifier.drawWithContent { drawContent(); sweepWords(line, wordBoxes, position()) }
                    else -> Modifier
                },
            ),
    )
}

/**
 * Lights the active line word by word, the highlight sweeping along each
 * word as it's sung. The text is drawn bright; what isn't reached yet is
 * faded back to the dim lyric colour. This happens at draw time, so the
 * line isn't rebuilt and measured again every frame.
 */
private fun DrawScope.sweepWords(line: LyricLine, boxes: List<Rect>, positionMs: Long) {
    val feather = 14.dp.toPx()
    line.words.forEachIndexed { i, word ->
        val box = boxes.getOrNull(i) ?: return@forEachIndexed
        if (box.isEmpty) return@forEachIndexed
        val span = (word.endMs - word.startMs).coerceAtLeast(1)
        val t = easeOut(((positionMs - word.startMs).toFloat() / span).coerceIn(0f, 1f))
        if (t >= 1f) return@forEachIndexed
        val litX = box.left + (box.width + feather) * t
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                1f to UNLIT,
                startX = litX - feather,
                endX = litX,
            ),
            topLeft = Offset(box.left, box.top),
            size = Size(box.width, box.height),
            blendMode = BlendMode.DstOut,
        )
    }
}

/**
 * The current line for the word-motion styles: each word drawn in its place,
 * moved and lit by how far through it the singer is. Sung words are bright,
 * the word being sung fills from the left, and the rest are dim.
 */
private fun DrawScope.drawMovingWords(
    line: LyricLine,
    words: List<Pair<TextLayoutResult, Offset>>,
    positionMs: Long,
    motion: WordMotion,
    bright: Color,
    dim: Color,
) {
    line.words.forEachIndexed { i, word ->
        val (laid, at) = words.getOrNull(i) ?: return@forEachIndexed
        val span = (word.endMs - word.startMs).coerceAtLeast(1)
        val raw = (positionMs - word.startMs).toFloat() / span
        val t = raw.coerceIn(0f, 1f)
        val pose = wordPose(motion, raw)
        if (pose.alpha <= 0f) return@forEachIndexed
        val w = laid.size.width.toFloat()
        val fill = easeOut(t)
        val brush = when {
            t >= 1f || motion == WordMotion.REVEAL -> SolidColor(bright)
            t <= 0f -> SolidColor(dim)
            else -> Brush.horizontalGradient(0f to bright, fill to bright, (fill + 0.12f).coerceAtMost(1f) to dim, 1f to dim, startX = 0f, endX = w)
        }
        val liftPx = pose.lift.dp.toPx()
        withTransform({
            translate(at.x, at.y - liftPx)
            scale(pose.scale, pose.scale, pivot = Offset(w / 2f, laid.size.height / 2f))
        }) {
            if (pose.glow > 0.01f) drawText(laid, color = bright.copy(alpha = 0.5f * pose.glow), shadow = Shadow(bright.copy(alpha = 0.8f * pose.glow), blurRadius = 26f))
            drawText(laid, brush = brush, alpha = pose.alpha)
        }
    }
}

/** Cuts bright text down to the dim lyric colour's 0.32 alpha. */
private val UNLIT = Color.Black.copy(alpha = 0.68f)

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
private fun Credit(source: String) {
    Spacer(Modifier.height(24.dp))
    Text(
        "Lyrics from $source",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Out of time? Long-press the line being sung to line them up.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 4.dp),
    )
}

private const val RESUME_FOLLOW_MS = 3_000L
/** How far ahead of its timestamp a line lights up. */
private const val LINE_LEAD_MS = 150L

/** The lyrics style at the size and alignment chosen in Settings. */
@Composable
private fun lyricsStyle(): androidx.compose.ui.text.TextStyle {
    val ui = AppSettings.ui.collectAsState().value
    val scale = ui.lyricsTextScale
    return LyricsTextStyle.copy(
        fontSize = LyricsTextStyle.fontSize * scale,
        lineHeight = LyricsTextStyle.lineHeight * scale,
        textAlign = if (ui.lyricsAlign == LyricsAlign.CENTER) TextAlign.Center else TextAlign.Start,
    )
}
