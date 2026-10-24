package com.opentune.ui.player

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.opentune.data.lyrics.LyricGap
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.LyricWord
import com.opentune.data.lyrics.Romanizer
import com.opentune.data.lyrics.instrumentalGaps
import com.opentune.data.lyrics.VOICE_SECOND
import com.opentune.data.lyrics.VOICE_TOGETHER
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.opentune.data.lyrics.Lyrics
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
    /** The intent to play (true through a buffer stall), so the sync clock coasts rather than snapping back. */
    playing: () -> Boolean = { true },
    /** Rises on each seek or skip, so the sync clock takes those jumps cleanly. */
    seeks: () -> Long = { 0L },
) {
    // The song and its lines as they were when sharing began, so a song
    // change while the sheet is open can't mix one song's words with another's.
    var sharing by remember { mutableStateOf<Pair<com.opentune.data.model.Song, List<String>>?>(null) }
    // Synced lines picked for a lyric card, by index; picking starts with a
    // long press on a line (or the share button) and ends with Cancel or Share.
    val syncedLines = ((state as? LyricsState.Found)?.lyrics as? Lyrics.Synced)?.lines
    val selection = remember(syncedLines) { androidx.compose.runtime.mutableStateListOf<Int>() }
    // The picked lines handed to the card sheet, frozen when Share was pressed.
    var sharingPicked by remember { mutableStateOf<Pair<com.opentune.data.model.Song, List<String>>?>(null) }
    Box(modifier) {
        when (state) {
            is LyricsState.Loading -> Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SettingUpBadge()
                listOf(0.8f, 0.6f, 0.9f, 0.5f, 0.7f).forEach {
                    Placeholder(Modifier.fillMaxWidth(it).height(28.dp), MaterialTheme.shapes.small)
                }
            }
            is LyricsState.NotFound -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                MessageState(Icons.Rounded.Lyrics, "No lyrics for this one", message = "None of your lyrics sources has this track yet. You can change them in Settings › Lyrics sources.")
            }
            is LyricsState.Found -> when (val lyrics = state.lyrics) {
                is Lyrics.Synced ->
                    if (synced) SyncedLyrics(
                        lyrics.lines, position, onSeek, blur, lyrics.source, onSyncLine, playing, seeks,
                        selection = selection.takeIf { shareSong != null },
                        onShareSelection = shareSong?.let { song ->
                            {
                                val picked = selection.sorted().mapNotNull { lyrics.lines.getOrNull(it)?.text?.trim()?.takeIf(String::isNotEmpty) }
                                if (picked.isNotEmpty()) sharingPicked = song to picked
                                selection.clear()
                            }
                        },
                    )
                    else PlainLyrics(lyrics.lines.joinToString("\n") { it.text }, lyrics.source, note = null)
                is Lyrics.Plain -> PlainLyrics(lyrics.text, lyrics.source)
            }
        }
        val found = (state as? LyricsState.Found)?.lyrics
        if (found != null) {
            val haptics = com.opentune.ui.components.rememberHaptics()
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Turn romanized lines (Latin letters under another script) on or off from the page.
                if (found is Lyrics.Synced && Romanizer.available) {
                    val romanizeOn = AppSettings.lyrics.collectAsState().value.romanize
                    LyricActionButton(Icons.Rounded.Translate, if (romanizeOn) "Turn off romanization" else "Show romanized lyrics", active = romanizeOn) {
                        haptics.tick()
                        AppSettings.updateLyrics { it.copy(romanize = !it.romanize) }
                    }
                }
                if (shareSong != null) {
                    // Lines with words in them (synced lyrics carry empty ones for the gaps), with
                    // their start times so the sheet can open on the line being sung.
                    val timed = remember(found) {
                        when (found) {
                            is Lyrics.Synced -> found.lines.filter { it.text.isNotBlank() }.map { it.text.trim() to it.startMs }
                            is Lyrics.Plain -> found.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { it to -1L }
                        }
                    }
                    val lines = remember(timed) { timed.map { it.first } }
                    var startAt by remember { mutableStateOf(-1) }
                    LyricActionButton(Icons.Rounded.FormatQuote, "Share lyrics") {
                        haptics.tick()
                        val now = position()
                        if (found is Lyrics.Synced && synced) {
                            // Start picking on the page itself, from the line being sung.
                            selection.clear()
                            selection.add(found.lines.indexOfLast { it.startMs <= now }.coerceAtLeast(0))
                        } else {
                            startAt = if (found is Lyrics.Synced) timed.indexOfLast { it.second <= now } else -1
                            sharing = shareSong to lines
                        }
                    }
                    sharing?.let { (song, shared) -> com.opentune.ui.share.LyricCardSheet(song, shared, startAt = startAt, onDismiss = { sharing = null }) }
                }
            }
        }
        sharingPicked?.let { (song, picked) ->
            com.opentune.ui.share.LyricCardSheet(song, picked, startAt = 0, onDismiss = { sharingPicked = null }, pickable = false)
        }
    }
}

/** The bar floating over the lyrics while lines are being picked for a card: Cancel and Share. */
@Composable
private fun SelectionBar(count: Int, onCancel: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(30.dp))
            .background(Color(0xF0141416))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.height(52.dp).width(130.dp).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onCancel),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Cancel", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Row(
            Modifier.height(52.dp).width(130.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).clickable(enabled = count > 0, onClick = onShare),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.IosShare, null, tint = Color.Black, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Share", color = Color.Black, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
    }
}

/** A round translucent button on the lyrics page (romanize, share). Tinted when its state is on. */
@Composable
private fun LyricActionButton(icon: ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(20.dp), tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
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
        val romanize = AppSettings.lyrics.collectAsState().value.romanize
        val romanized by androidx.compose.runtime.produceState<List<String?>>(emptyList(), text, romanize) {
            if (!romanize) { value = emptyList(); return@produceState }
            val texts = text.lines()
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Romanizer.lines(texts) }
            // Japanese with kanji: the offline reading first, then the full one when it arrives.
            if (com.opentune.data.lyrics.JapaneseReadings.wanted(texts)) {
                com.opentune.data.lyrics.JapaneseReadings.lines(texts)?.let { read -> value = read.mapIndexed { i, r -> r ?: value.getOrNull(i) } }
            }
        }
        val dimmed = MaterialTheme.colorScheme.onSurfaceVariant
        val shown = remember(text, romanized, dimmed) {
            if (romanized.none { it != null }) AnnotatedString(text) else buildAnnotatedString {
                text.lines().forEachIndexed { i, line ->
                    if (i > 0) append("\n")
                    append(line)
                    romanized.getOrNull(i)?.let { r ->
                        append("\n")
                        withStyle(SpanStyle(color = dimmed, fontSize = style.fontSize * 0.5f)) { append(r) }
                    }
                }
            }
        }
        Text(shown, Modifier.fillMaxWidth(), style = style.copy(fontSize = style.fontSize * 0.8f, lineHeight = style.lineHeight * 0.85f))
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
    playing: () -> Boolean = { true },
    seeks: () -> Long = { 0L },
    /** Lines picked for a lyric card, by index; null where there's no song to share. */
    selection: androidx.compose.runtime.snapshots.SnapshotStateList<Int>? = null,
    onShareSelection: (() -> Unit)? = null,
) {
    val ui by AppSettings.ui.collectAsState()
    val reduced = ui.reduceAnimation || !android.animation.ValueAnimator.areAnimatorsEnabled()
    val animation = if (reduced) LyricsAnimation.MINIMAL else ui.lyricsAnimation
    val listState = rememberLazyListState()
    val selecting = selection?.isNotEmpty() == true
    val haptics = com.opentune.ui.components.rememberHaptics()
    androidx.activity.compose.BackHandler(enabled = selecting) { selection?.clear() }
    val romanize = AppSettings.lyrics.collectAsState().value.romanize
    val romanized by androidx.compose.runtime.produceState<List<String?>>(emptyList(), lines, romanize) {
        if (!romanize) { value = emptyList(); return@produceState }
        val texts = lines.map { it.text }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Romanizer.lines(texts) }
        // Japanese with kanji: the offline reading first, then the full one when it arrives.
        if (com.opentune.data.lyrics.JapaneseReadings.wanted(texts)) {
            com.opentune.data.lyrics.JapaneseReadings.lines(texts)?.let { read -> value = read.mapIndexed { i, r -> r ?: value.getOrNull(i) } }
        }
    }
    // Two singers trade lines: the second voice's sit on the other side.
    val duet = remember(lines) { lines.any { it.voice == VOICE_SECOND } }
    // A clock steered by the player's readings: it never moves the words back
    // without an announced seek, so a word just sung can't count as unsung and
    // play its motion twice, and it coasts through a buffer stall rather than
    // snapping back. Restarts for each new set of lines (a new song).
    val clock = rememberLyricsClock(position, playing, seeks, trackKey = lines)
    val pos by rememberUpdatedState(clock)
    // The lines with instrumental breaks folded in: a long stretch with no
    // singing becomes a row of breathing dots, so the last sung line doesn't
    // sit lit through a passage with no vocals.
    val rows = remember(lines) { buildLyricRows(lines) }
    // A line lights a moment before its timestamp, so it's there as the singer
    // starts it; a gap's dots take over the moment the singing stops.
    val activeRow by remember(rows) {
        derivedStateOf {
            val p = pos() + LINE_LEAD_MS
            var found = -1
            rows.forEachIndexed { i, row -> if (row.startMs <= p) found = i }
            found
        }
    }
    // The line the view is centred on, whether a line or the gap after it is lit.
    val activeLine by remember(rows) { derivedStateOf { (rows.getOrNull(activeRow) as? LyricRow.Line)?.index ?: (rows.getOrNull(activeRow) as? LyricRow.Gap)?.gap?.afterIndex ?: -1 } }
    var following by remember { mutableStateOf(true) }
    val glide = remember { GlideSpeed() }

    // A drag means the listener wants to look around. Watching drags rather
    // than any scrolling tells theirs from the glide below, even when a drag
    // cuts into a glide that's under way.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) following = false }
    }
    // While lines are being picked the list holds still under the finger.
    LaunchedEffect(selecting) { if (selecting) following = false }
    LaunchedEffect(following, listState.isScrollInProgress, selecting) {
        if (!following && !listState.isScrollInProgress && !selecting) {
            delay(RESUME_FOLLOW_MS)
            following = true
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The current line sits near the top, leaving room for just the line before it.
        val anchor = maxHeight * LINE_ANCHOR
        val waveMaxPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
        LaunchedEffect(activeRow, following, reduced) {
            if (!following) {
                glide.pxPerS = 0f
                return@LaunchedEffect
            }
            // +1 for the intro item ahead of the first line.
            glideTo(listState, (activeRow + 1).coerceAtLeast(0), reduced, glide)
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = anchor, bottom = maxHeight * 0.6f),
            modifier = Modifier.fillMaxSize().softEdges(top = EDGE_FADE_TOP, bottom = EDGE_FADE_BOTTOM),
        ) {
            item(key = "intro") {
                val firstStart = lines.firstOrNull()?.startMs ?: 0L
                val waiting by remember(firstStart) { derivedStateOf { firstStart > 2_500 && pos() < firstStart - 300 } }
                IntroDots(visible = waiting)
            }
            itemsIndexed(rows, key = { _, row -> row.key }) { r, row ->
                Box(Modifier.graphicsLayer { translationY = waveLag(r - activeRow, glide.pxPerS, waveMaxPx) }) {
                when (row) {
                    is LyricRow.Line -> {
                        val i = row.index
                        val line = row.line
                        val picked = selection?.contains(i)
                        LyricLineView(
                            line = line,
                            distance = i - activeLine,
                            isActive = r == activeRow && !selecting,
                            animation = animation,
                            blurEnabled = blur && following,
                            position = clock,
                            romanized = romanized.getOrNull(i),
                            duet = duet,
                            selected = if (selecting) picked == true else null,
                            onClick = {
                                if (selecting && selection != null) {
                                    // Tap to add or drop a line, up to the card's limit.
                                    when {
                                        i in selection -> selection.remove(i)
                                        selection.size < com.opentune.ui.share.LyricCard.MAX_LINES -> selection.add(i)
                                        else -> { haptics.pattern(com.opentune.ui.components.Haptics.Pattern.OFF); return@LyricLineView }
                                    }
                                    haptics.tick()
                                } else {
                                    onSeek(line.startMs)
                                    following = true
                                }
                            },
                            // A long press starts picking lines to share; without a song to
                            // share, it lines the lyrics up with this line as before.
                            onLongClick = when {
                                selection != null -> { { if (!selecting) { haptics.press(); selection.add(i) } } }
                                onSyncLine != null -> { { onSyncLine(line.startMs); following = true } }
                                else -> null
                            },
                        )
                    }
                    is LyricRow.Gap -> GapDots(
                        visible = r == activeRow,
                        progress = { ((clock() - row.gap.startMs).toFloat() / (row.gap.endMs - row.gap.startMs)).coerceIn(0f, 1f) },
                    )
                }
                }
            }
            item(key = "credit") { Credit(source) }
        }

        AnimatedVisibility(
            visible = !following && !selecting,
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
        AnimatedVisibility(
            visible = selecting,
            enter = fadeIn() + androidx.compose.animation.slideInVertically { it },
            exit = fadeOut() + androidx.compose.animation.slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) {
            SelectionBar(
                count = selection?.size ?: 0,
                onCancel = { haptics.tick(); selection?.clear(); following = true },
                onShare = { haptics.press(); onShareSelection?.invoke(); following = true },
            )
        }
    }
}

/** How fast the list was gliding, kept from one line to the next. */
/**
 * Lines thin out toward the list's top and bottom edges instead of being cut
 * off along a hard line there: the list is drawn into its own layer and
 * faded out over [top] and [bottom] with a gradient mask.
 */
private fun Modifier.softEdges(top: androidx.compose.ui.unit.Dp, bottom: androidx.compose.ui.unit.Dp): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val h = size.height
            if (h <= 0f) return@drawWithContent
            val t = (top.toPx() / h).coerceIn(0f, 0.5f)
            val b = (bottom.toPx() / h).coerceIn(0f, 0.5f)
            drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Transparent, t to Color.Black, (1f - b) to Color.Black, 1f to Color.Transparent,
                ),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }

private val EDGE_FADE_TOP = 18.dp
private val EDGE_FADE_BOTTOM = 96.dp

/** The glide's speed, read frame by frame by each line's lag (see [waveLag]). */
private class GlideSpeed { var pxPerS by androidx.compose.runtime.mutableFloatStateOf(0f) }

/**
 * How far a row [rowsBelow] under the current one trails the list while it
 * glides at [pxPerS]: each row follows the one above a moment later, so the
 * gaps open as the lines move and close as they settle, a soft wave rather
 * than one rigid block. Rows at or above the current one don't trail.
 */
private fun waveLag(rowsBelow: Int, pxPerS: Float, maxPx: Float): Float {
    if (rowsBelow <= 0) return 0f
    val lag = pxPerS * WAVE_STEP_S * rowsBelow.coerceAtMost(WAVE_ROWS)
    return lag.coerceIn(-maxPx, maxPx)
}

/** Each row trails the one above by this much of the glide's motion. */
private const val WAVE_STEP_S = 0.035f
/** Rows further down trail no more than this many rows' worth. */
private const val WAVE_ROWS = 6

/** One row of the synced list: a sung line, or the instrumental break after one. */
private sealed interface LyricRow {
    val startMs: Long
    val key: String

    class Line(val index: Int, val line: LyricLine) : LyricRow {
        override val startMs get() = line.startMs
        override val key get() = "$index:${line.startMs}"
    }

    class Gap(val gap: LyricGap) : LyricRow {
        override val startMs get() = gap.startMs
        override val key get() = "gap:${gap.afterIndex}:${gap.startMs}"
    }
}

private fun buildLyricRows(lines: List<LyricLine>): List<LyricRow> {
    val gaps = lines.instrumentalGaps().associateBy { it.afterIndex }
    return buildList {
        lines.forEachIndexed { i, line ->
            add(LyricRow.Line(i, line))
            gaps[i]?.let { add(LyricRow.Gap(it)) }
        }
    }
}

/**
 * Three dots for an instrumental break. They fill in one after another as the
 * break runs out, so you can see the next line coming, and breathe while they wait.
 *
 * The row keeps its height whether the dots show or not, and they only fade:
 * a row growing and shrinking moved the lines under the scroll's own glide,
 * and the line after a break shook and settled instead of arriving smoothly.
 */
@Composable
private fun GapDots(visible: Boolean, progress: () -> Float) {
    val shown by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 420 else 260), label = "gapShown")
    run {
        val transition = rememberInfiniteTransition(label = "gap")
        val breath by transition.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1_200), RepeatMode.Reverse), label = "breath")
        val ink = MaterialTheme.colorScheme.onSurface
        Row(
            Modifier
                .padding(vertical = 18.dp)
                .graphicsLayer { scaleX = breath; scaleY = breath; alpha = shown; transformOrigin = TransformOrigin(0f, 0.5f) },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(3) { i ->
                Box(
                    Modifier
                        .size(14.dp)
                        .drawWithContent {
                            // Each dot takes a third of the break to light, from dim to full.
                            val lit = (progress() * 3f - i).coerceIn(0f, 1f)
                            drawCircle(ink.copy(alpha = 0.3f + 0.7f * lit))
                        },
                )
            }
        }
    }
}

/**
 * Scroll [index] to the top of the content area (the anchor), smoothly when
 * it's near. The glide is a spring that doesn't overshoot, aimed afresh each
 * frame (so the intro dots folding away mid-glide don't throw it off) and
 * starting from the speed the last one had: lines that come quickly no
 * longer stop and restart the list at each change.
 */
private suspend fun glideTo(state: LazyListState, index: Int, reduced: Boolean, speed: GlideSpeed) {
    if (reduced || state.layoutInfo.visibleItemsInfo.none { it.index == index }) {
        speed.pxPerS = 0f
        state.scrollToItem(index)
        return
    }
    state.scroll {
        var last = withFrameNanos { it }
        while (true) {
            val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: break
            val left = item.offset.toFloat()
            if (abs(left) < 0.5f && abs(speed.pxPerS) < 20f) {
                scrollBy(left)
                break
            }
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
            last = now
            speed.pxPerS += (GLIDE_STIFFNESS * left - GLIDE_DAMPING * speed.pxPerS) * dt
            val step = speed.pxPerS * dt
            // Short of the step: the list is at its end.
            if (abs(scrollBy(step)) + 0.5f < abs(step)) break
        }
    }
    speed.pxPerS = 0f
}

/** About half a second to settle, like BitChord's; critically damped (damping = 2·√stiffness), so it never overshoots. */
private const val GLIDE_STIFFNESS = 80f
private const val GLIDE_DAMPING = 17.89f

/** How one [LyricsAnimation] treats lines: sizes, blur per line away, and glow. */
/** How far, as a share of a row's height, a letter can reach past its row's box. */
private const val GLYPH_OVERHANG = 0.35f

/** Empty room round a blurred line inside its blur's buffer, so the blur fades out before the buffer's edge. */
private val BLUR_BLEED = 28.dp

private class LineMotion(
    val activeScale: Float,
    val inactiveScale: Float,
    val blurPerLine: Float,
    val glow: Boolean,
    val inactiveAlpha: Float,
)

private fun motionOf(animation: LyricsAnimation) = when (animation) {
    LyricsAnimation.FLUID -> LineMotion(1f, 0.97f, 1.8f, glow = false, inactiveAlpha = 0.8f)
    LyricsAnimation.KARAOKE -> LineMotion(1f, 1f, 0f, glow = true, inactiveAlpha = 0.55f)
    // The active line stays at 1.0; the others shrink more, so it still reads as zoomed
    // without scaling the current line past its box, which chopped long lines.
    LyricsAnimation.ZOOM -> LineMotion(1f, 0.84f, 1.9f, glow = false, inactiveAlpha = 0.6f)
    LyricsAnimation.MINIMAL -> LineMotion(1f, 1f, 0f, glow = false, inactiveAlpha = 0.6f)
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
    romanized: String? = null,
    duet: Boolean = false,
    /** While lines are being picked to share: whether this one is. Null otherwise. */
    selected: Boolean? = null,
) {
    val motion = motionOf(animation)
    val bright = MaterialTheme.colorScheme.onSurface
    val dim = bright.copy(alpha = 0.45f)
    val picking = selected != null
    val scale by animateFloatAsState(
        if (isActive || picking) motion.activeScale else motion.inactiveScale,
        // Critically damped: the line settles into size without overshooting, which read as a bounce.
        spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "lineScale",
    )
    val alpha by animateFloatAsState(
        when {
            picking -> if (selected == true) 1f else 0.6f
            isActive -> 1f
            else -> (motion.inactiveAlpha - 0.06f * (minOf(abs(distance), 5) - 1) - if (distance < 0) 0.1f else 0f).coerceAtLeast(0.18f)
        },
        tween(if (animation == LyricsAnimation.MINIMAL) 200 else 400),
        label = "lineAlpha",
    )
    // Set, not animated, and read only while drawing (below): an animated blur was worked
    // out again on every frame for every visible line each time the song moved to the next
    // one, which is what made the page stutter. Now each line's blur is redrawn once per
    // line change, while its fade and size still animate, which are cheap.
    val blur = androidx.compose.runtime.rememberUpdatedState(
        if (!blurEnabled || isActive || picking) 0.dp else (minOf(abs(distance), 6) * motion.blurPerLine).dp,
    )
    // Picking lines, each sits on its own card, the picked ones a shade lighter.
    val card by androidx.compose.animation.animateColorAsState(
        when (selected) {
            null -> Color.Transparent
            true -> bright.copy(alpha = 0.16f)
            false -> bright.copy(alpha = 0.06f)
        },
        tween(220),
        label = "lineCard",
    )
    val ui by AppSettings.ui.collectAsState()
    val centred = ui.lyricsAlign == LyricsAlign.CENTER
    // In a duet the second voice's lines sit on the right and lines sung together in the middle.
    val align = when {
        !duet || centred -> if (centred) TextAlign.Center else TextAlign.Start
        line.voice == VOICE_SECOND -> TextAlign.End
        line.voice == VOICE_TOGETHER -> TextAlign.Center
        else -> TextAlign.Start
    }
    // A right-to-left line starts at the right: Start and End swap sides, and it sweeps leftwards.
    val rtl = remember(line) { com.opentune.data.lyrics.readsRightToLeft(line) }
    val origin = when (align) { TextAlign.Center -> 0.5f; TextAlign.End -> if (rtl) 0f else 1f; else -> if (rtl) 1f else 0f }
    val base = lyricsStyle().copy(textAlign = align, textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr)
    val style = if ((motion.glow || ui.lyricsGlow) && isActive) base.copy(shadow = Shadow(bright.copy(alpha = 0.55f), blurRadius = 24f)) else base
    val text = remember(line) { if (line.words.isEmpty()) line.text else line.words.joinToString("") { it.text } }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Where each word sits, worked out once per layout rather than every frame.
    // A word's trailing space is left out, and a word that still runs across a
    // wrap gets one box per row: a single box for the word that ends the first
    // row would span both rows edge to edge and dim the whole line until sung.
    val wordBoxes = remember(layout, line) { wordBoxesOf(layout, line, text) }
    // Words light one by one only where the source timed them. A line timed as a
    // whole lights all at once, on time, rather than sweeping on guessed word times.
    val sweep = isActive && line.wordSynced && line.words.isNotEmpty()
    val cardPad by animateDpAsState(if (picking) 14.dp else 0.dp, tween(220), label = "cardPad")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = cardPad / 5)
            // The card is drawn in its shape, not clipped to it: a clip would cut a
            // blurred line's soft edge into a hard box.
            .background(card, MaterialTheme.shapes.medium)
            .combinedClickable(remember { MutableInteractionSource() }, indication = com.opentune.ui.components.FocusRing(MaterialTheme.shapes.medium), onLongClick = onLongClick, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = cardPad)
            // Android blurs a layer inside a buffer its own size, so whatever the
            // blur would spread past the line was cut off in a hard box. The
            // blurred layer is made BLUR_BLEED larger on every side, with that
            // much empty space round the words, and laid out as if it weren't:
            // the line takes the same room and wraps the same, and its blur fades
            // to nothing inside the buffer. Inside, the line's fade is applied to
            // each glyph drawn (ModulateAlpha), with no buffer of its own to clip.
            .then(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurEnabled) {
                    Modifier
                        .layout { measurable, constraints ->
                            val bleed = BLUR_BLEED.roundToPx()
                            val grown = constraints.copy(
                                minWidth = constraints.minWidth + bleed * 2,
                                maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + bleed * 2 else constraints.maxWidth,
                                minHeight = constraints.minHeight + bleed * 2,
                                maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + bleed * 2 else constraints.maxHeight,
                            )
                            val p = measurable.measure(grown)
                            layout((p.width - bleed * 2).coerceAtLeast(0), (p.height - bleed * 2).coerceAtLeast(0)) { p.place(-bleed, -bleed) }
                        }
                        .graphicsLayer {
                            // Whole pixels only: a radius that changes by a fraction each frame
                            // makes the blur redraw for nothing a viewer could see.
                            val radius = kotlin.math.round(blur.value.toPx())
                            renderEffect = if (radius >= 1f) androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Decal) else null
                            clip = false
                        }
                        .padding(BLUR_BLEED)
                } else Modifier,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                compositingStrategy = CompositingStrategy.ModulateAlpha
                transformOrigin = TransformOrigin(origin, 0.5f)
            },
    ) {
        if (sweep) {
            // One crisp line: the whole active line stays readable and bright, sung words
            // lift to full while those not reached yet sit a touch below. No blurred copy,
            // so a line that wraps to two rows has no glowing halo or box around it.
            Text(
                text = text,
                style = style,
                color = bright,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    // Its own layer, so dimming the unsung words doesn't punch through what's behind.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent { drawContent(); sweepDim(line, wordBoxes, position(), 0.4f, rtl) },
            )
        } else {
            Text(
                text = text,
                style = style,
                color = if (isActive) bright else dim,
                onTextLayout = { layout = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Backing vocals, smaller and softer, under the line they're sung with.
        line.background?.let {
            Text(
                it,
                style = base.copy(fontSize = base.fontSize * 0.62f, lineHeight = base.lineHeight * 0.62f),
                color = (if (isActive) bright else dim).copy(alpha = if (isActive) 0.6f else 0.25f),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
        romanized?.let {
            Text(
                it,
                style = base.copy(fontSize = base.fontSize * 0.5f, lineHeight = base.lineHeight * 0.55f, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = (if (isActive) bright else dim).copy(alpha = if (isActive) 0.75f else 0.3f),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/** Where each of [line]'s words sits in [layout] of [text]: one box per row a word covers, trailing space left out. */
private fun wordBoxesOf(layout: TextLayoutResult?, line: LyricLine, text: String): List<List<Rect>> {
    val l = layout ?: return emptyList()
    var start = 0
    // Where the word before ended on each row: a word's box reaches back to it, so the gap
    // between them (where a j's hook or a slanted letter's foot reaches) is lit with the
    // word that follows, not left lit from the start.
    val prevRight = HashMap<Int, Float>()
    return line.words.map { w ->
        val end = (start + w.text.length).coerceAtMost(text.length)
        var visibleEnd = end
        while (visibleEnd > start && text[visibleEnd - 1].isWhitespace()) visibleEnd--
        // Past the last row shown (an ellipsized preview), there's nothing to light.
        val shown = if (l.lineCount > 0) l.getLineEnd(l.lineCount - 1, visibleEnd = true) else 0
        visibleEnd = minOf(visibleEnd, shown)
        val boxes = if (visibleEnd <= start) emptyList() else {
            val first = l.getLineForOffset(start)
            val last = l.getLineForOffset(visibleEnd - 1)
            (first..last).mapNotNull { row ->
                val from = maxOf(start, l.getLineStart(row))
                val to = minOf(visibleEnd, l.getLineEnd(row, visibleEnd = true))
                if (to <= from) return@mapNotNull null
                val box = l.getPathForRange(from, to).getBounds()
                // Glyphs reach past their row: the dots of i and j above the first
                // row (the line height is set tight), tails below the last, and a
                // j's hook left of a row's first word. The box takes them in where
                // no other word can be, so no part of a letter stays lit early.
                val pad = box.height * GLYPH_OVERHANG
                val before = prevRight[row]
                prevRight[row] = box.right
                Rect(
                    left = if (from == l.getLineStart(row)) box.left - pad else minOf(box.left, before ?: box.left),
                    top = if (row == 0) box.top - pad else box.top,
                    right = if (to == l.getLineEnd(row, visibleEnd = true)) box.right + pad else box.right,
                    bottom = if (row == l.lineCount - 1) box.bottom + pad else box.bottom,
                )
            }
        }
        start = end
        boxes
    }
}

/**
 * One line lit as it's sung, for the player's one-line preview: word by word
 * where the source timed the words, the whole line at once where it only timed
 * lines, and dimmed through an instrumental break after it.
 */
@Composable
internal fun SungLine(line: LyricLine, position: () -> Long, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    val bright = MaterialTheme.colorScheme.onSurface
    val text = remember(line) { if (line.words.isEmpty()) line.text else line.words.joinToString("") { it.text }.trim() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val boxes = remember(layout, line) { wordBoxesOf(layout, line, text) }
    Text(
        text,
        style = style,
        color = bright,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (line.wordSynced) sweepDim(line, boxes, position(), 0.55f)
            },
    )
}

/**
 * Sweeps word by word along the line as it's sung, cutting the alpha of the
 * part not reached yet by [removal] (a fraction of 0..1, fully removed at 1),
 * so the whole active line stays readable and lifts a little as each word
 * lands. A word split over two rows fills its first piece, then its second.
 * Done at draw time, so the line isn't measured again every frame.
 */
private fun DrawScope.sweepDim(line: LyricLine, boxes: List<List<Rect>>, positionMs: Long, removal: Float, rtl: Boolean = false) {
    // A wide soft edge, as BitChord's: the light eases across each word rather than wiping it.
    val feather = 26.dp.toPx()
    line.words.forEachIndexed { i, word ->
        val pieces = boxes.getOrNull(i) ?: return@forEachIndexed
        if (pieces.isEmpty()) return@forEachIndexed
        val span = (word.endMs - word.startMs).coerceAtLeast(1)
        // Even through the word: an eased fill rushed the start and lagged the end, off the singing.
        val t = ((positionMs - word.startMs).toFloat() / span).coerceIn(0f, 1f)
        if (t >= 1f) return@forEachIndexed
        val total = pieces.sumOf { it.width.toDouble() }.toFloat().coerceAtLeast(1f)
        var before = 0f
        pieces.forEach { box ->
            val pieceT = ((t * total - before) / box.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
            before += box.width
            if (pieceT >= 1f || box.isEmpty) return@forEach
            val travel = (box.width + feather) * pieceT
            val litX = if (rtl) box.right - travel else box.left + travel
            drawRect(
                brush = if (rtl) Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = removal),
                    1f to Color.Transparent,
                    startX = litX,
                    endX = litX + feather,
                ) else Brush.horizontalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = removal),
                    startX = litX - feather,
                    endX = litX,
                ),
                topLeft = Offset(box.left, box.top),
                size = Size(box.width, box.height),
                blendMode = BlendMode.DstOut,
            )
        }
    }
}

/** While the sources are asked: a small pill saying the lyrics are on their way, with a turning mark. */
@Composable
internal fun SettingUpBadge(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(CircleShape)
            .background(colors.onSurface.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            Modifier.size(12.dp), color = colors.onSurface, strokeWidth = 1.5.dp, trackColor = colors.onSurface.copy(alpha = 0.2f),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        Text("Setting up the lyrics…", style = MaterialTheme.typography.labelLarge, color = colors.onSurface.copy(alpha = 0.85f))
    }
}

/** Three dots breathing in sequence while an intro plays; like [GapDots], they fade but keep their room. */
@Composable
private fun IntroDots(visible: Boolean) {
    val shown by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 420 else 260), label = "introShown")
    run {
        val transition = rememberInfiniteTransition(label = "intro")
        Row(Modifier.padding(vertical = 18.dp).graphicsLayer { alpha = shown }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        "Long-press a line to share it on a card. Out of time? Pick the line being sung and tap sync.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 4.dp),
    )
}

private const val RESUME_FOLLOW_MS = 3_000L
/** Where the current line sits, as a share of the height from the top: room for one line above it. */
/** Where the active line sits, down from the top: high, with only a sliver of the line before it showing. */
private const val LINE_ANCHOR = 0.085f
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
