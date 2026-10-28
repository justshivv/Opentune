package com.opentune.ui.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.togetherWith
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.model.artworkAt
import com.opentune.ui.LyricsState
import com.opentune.ui.components.Artwork
import com.opentune.ui.formatTime

/**
 * The player on its side: the cover fills the left, and the right holds
 * where the sound is going, the song, a slim time bar, the line being sung
 * with the next under it, the controls and the volume.
 * Tap the line being sung for all the lyrics, or use the queue button;
 * either takes the cover's place on the left, and the same tap goes back.
 */
@Composable
internal fun LandscapePlayer(
    state: PlayerUiState,
    position: () -> Long,
    lyricsPosition: () -> Long,
    actions: PlayerActions,
    liked: Boolean,
    device: String,
    onLike: () -> Unit,
    onMore: () -> Unit,
    onSignal: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    /** Lyrics or the queue, shown in the cover's place; null shows the cover. */
    side: (@Composable () -> Unit)? = null,
    /** Off while the full lyrics are on the left, so the lines aren't shown twice. */
    showLines: Boolean = true,
) {
    val television = com.opentune.ui.tv.LocalIsTv.current
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (television) { withFrameNanos { }; playFocus.requestFocus() } }
    val song = state.song
    val scheme = MaterialTheme.colorScheme
    val animate = !state.ui.reduceAnimation
    var previousRolls by remember { mutableIntStateOf(0) }
    var nextRolls by remember { mutableIntStateOf(0) }
    val haptics = com.opentune.ui.components.rememberHaptics()
    var showCast by remember { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }
    val outputRoute = rememberOutputRoute()
    Row(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (side != null) {
            Box(Modifier.weight(1.1f).fillMaxHeight()) { side() }
        } else {
            ArtworkSwipeArea(
                onSwipeNext = actions.next,
                onSwipePrevious = actions.previous,
                modifier = if (television) Modifier.weight(0.9f).aspectRatio(1f) else Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true),
            ) {
                Artwork(
                    song.thumbnailUrl.artworkAt(1080),
                    Modifier.fillMaxSize().shadow(18.dp, RoundedCornerShape(16.dp)),
                    RoundedCornerShape(16.dp),
                )
            }
        }
        Spacer(Modifier.width(32.dp))
        BoxWithConstraints(Modifier.weight(if (television) 1.3f else 1f).fillMaxHeight().focusGroup()) {
            // Short screens drop the coming lines first, then the volume.
            val roomy = maxHeight >= 300.dp
            val previewFits = !television || maxHeight >= 440.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.Top) {
                    // Lyrics or the queue in the cover's place: the cover stays in sight, small, before the title.
                    if (side != null) {
                        Artwork(
                            song.thumbnailUrl.artworkAt(com.opentune.data.model.ROW_ART_PX),
                            Modifier.padding(top = 4.dp, end = 14.dp).size(64.dp),
                            RoundedCornerShape(10.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(device, style = MaterialTheme.typography.labelLarge, color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(4.dp))
                            Icon(outputRoute.icon, null, Modifier.size(14.dp), tint = scheme.primary)
                        }
                        Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.albumName?.takeIf { it.isNotBlank() }).joinToString(" — "),
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    RoundIcon(Icons.Rounded.MoreHoriz, "More", onMore)
                    RoundIcon(Icons.Rounded.KeyboardArrowDown, "Close player", actions.collapse)
                }
                if (television) SeekBar(position, { 0L }, state.durationMs, actions.seekTo)
                else TimeBar(position, state.durationMs, actions.seekTo)
                if (state.ui.sourcePill) SourceQualityBadge(song.videoId, state.audioFormat, onClick = onSignal, modifier = Modifier.align(Alignment.CenterHorizontally))
                // The line being sung and the next, together above the controls, in the lyrics' own type.
                val lines = if (showLines) rememberLyricLines(state.lyrics, lyricsPosition) else null
                if (lines != null && previewFits) LyricsPeek(lines, next = roomy, animate = animate, onClick = onLyrics)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (television) Arrangement.Center else Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (!television) RoundIcon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Unlike" else "Like", { haptics.pattern(if (liked) com.opentune.ui.components.Haptics.Pattern.OFF else com.opentune.ui.components.Haptics.Pattern.LIKE); onLike() }, tint = if (liked) scheme.primary else scheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tap("Previous", { haptics.pattern(com.opentune.ui.components.Haptics.Pattern.PREVIOUS); previousRolls++; actions.previous() }) { SkipGlyph(false, previousRolls, scheme.onSurface, animate, Modifier.size(38.dp)) }
                        Tap(if (state.isPlaying) "Pause" else "Play", { haptics.pattern(if (state.isPlaying) com.opentune.ui.components.Haptics.Pattern.PAUSE else com.opentune.ui.components.Haptics.Pattern.PLAY); actions.togglePlay() }, modifier = if (television) Modifier.focusRequester(playFocus) else Modifier) { PlayPauseGlyph(state.isPlaying, scheme.onSurface, animate, Modifier.size(54.dp)) }
                        Tap("Next", { haptics.pattern(com.opentune.ui.components.Haptics.Pattern.NEXT); nextRolls++; actions.next() }, enabled = state.hasNext) {
                            SkipGlyph(true, nextRolls, scheme.onSurface.copy(alpha = if (state.hasNext) 1f else 0.35f), animate, Modifier.size(38.dp))
                        }
                    }
                    val context = LocalContext.current
                    if (!television) RoundIcon(Icons.Rounded.SpeakerGroup, "Choose where it plays", { openOutputSwitcher(context) })
                    if (!television) RoundIcon(
                        outputRoute.icon,
                        "Audio output",
                        { showOutput = true },
                        tint = if (outputRoute == OutputRoute.CAST) scheme.primary else scheme.onSurface,
                    )
                    if (showOutput) com.opentune.ui.cast.AudioOutputSheet(
                        onDismiss = { showOutput = false },
                        onCast = { showOutput = false; showCast = true },
                        onPipeline = { showOutput = false; onSignal() },
                    )
                    if (showCast) com.opentune.ui.cast.CastSheet(onDismiss = { showCast = false })
                }
                if (television) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundIcon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Unlike" else "Like", onLike)
                    RoundIcon(outputRoute.icon, "Audio output", { showOutput = true })
                    RoundIcon(Icons.Rounded.Lyrics, "Lyrics", onLyrics)
                    RoundIcon(Icons.Rounded.Shuffle, "Shuffle", actions.toggleShuffle, tint = if (state.shuffle) scheme.primary else scheme.onSurface)
                    RoundIcon(Icons.Rounded.Repeat, "Repeat", actions.cycleRepeat)
                    RoundIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", onQueue)
                }
                if (roomy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { VolumeBar() }
                        if (!television) RoundIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", onQueue)
                    }
                } else if (!television) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { RoundIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", onQueue) }
                }
            }
        }
    }
}

/** Where the song is in its synced lyrics: the line being sung and the one after it. */
private data class PeekLines(val index: Int, val now: String, val next: String)

/** The line being sung and the one after it; null without synced lyrics. */
@Composable
private fun rememberLyricLines(lyrics: LyricsState, position: () -> Long): PeekLines? {
    val pos by rememberUpdatedState(position)
    val synced = (lyrics as? LyricsState.Found)?.lyrics as? Lyrics.Synced
    val lines by remember(synced) {
        derivedStateOf {
            val list = synced?.lines?.filter { it.text.isNotBlank() }.orEmpty()
            if (list.isEmpty()) return@derivedStateOf null
            val at = list.indexOfLast { it.startMs <= pos() }.coerceAtLeast(0)
            PeekLines(at, list[at].text, list.getOrNull(at + 1)?.text.orEmpty())
        }
    }
    return lines
}

/**
 * The lyrics in brief beside the cover: the line being sung, large and
 * bright, and the next one dimmer under it. As a line starts, the pair
 * slides up a step, so the next line rises into the lit place. Tapping
 * opens all the lyrics.
 */
@Composable
private fun LyricsPeek(lines: PeekLines, next: Boolean, animate: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val now = com.opentune.ui.theme.LyricsTextStyle.copy(fontFamily = MaterialTheme.typography.bodyLarge.fontFamily, fontSize = 22.sp, lineHeight = 27.sp)
    val after = now.copy(fontSize = 17.sp, lineHeight = 22.sp)
    androidx.compose.animation.AnimatedContent(
        targetState = lines,
        contentKey = { it.index },
        transitionSpec = {
            (if (!animate) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            else {
                val forward = targetState.index > initialState.index
                (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 380f)) { if (forward) it / 3 else -it / 3 } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260, delayMillis = 60))) togetherWith
                    (androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(240)) { if (forward) -it / 3 else it / 3 } +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)))
            }) using androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() }
        },
        label = "peek",
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) { shown ->
        Column(Modifier.fillMaxWidth()) {
            Text(shown.now, style = now, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (next && shown.next.isNotEmpty()) {
                Text(
                    shown.next,
                    style = after,
                    color = ink.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Elapsed time, a slim bar to drag or tap, and the time left. */
@Composable
private fun TimeBar(position: () -> Long, durationMs: Long, onSeek: (Long) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var drag by remember { mutableStateOf<Float?>(null) }
    val p = position()
    val fraction = drag ?: if (durationMs > 0) (p.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = (fraction * durationMs).toLong()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(shownMs), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        Box(
            Modifier
                .weight(1f)
                .height(28.dp)
                .padding(horizontal = 12.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { o -> if (durationMs > 0) onSeek(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()) }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { if (durationMs > 0) onSeek((it * durationMs).toLong()) }; drag = null },
                        onDragCancel = { drag = null },
                    ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
                }
                .drawBehind {
                    val h = (if (drag != null) 8 else 6).dp.toPx()
                    val top = (size.height - h) / 2f
                    drawRoundRect(scheme.onSurface.copy(alpha = 0.22f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2f))
                    drawRoundRect(scheme.onSurface.copy(alpha = 0.85f), Offset(0f, top), Size(size.width * fraction, h), CornerRadius(h / 2f))
                },
        )
        Text("-" + formatTime((durationMs - shownMs).coerceAtLeast(0)), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Box(Modifier.size(if (com.opentune.ui.tv.LocalIsTv.current) 48.dp else 44.dp).clip(CircleShape).clickable(interactionSource = remember { MutableInteractionSource() }, indication = com.opentune.ui.components.FocusRing(CircleShape), onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(24.dp), tint = tint)
    }
}

@Composable
private fun Tap(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(64.dp)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = com.opentune.ui.components.FocusRing(CircleShape), enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The system's picker for where audio plays, or Bluetooth settings where there's none. */
internal fun openOutputSwitcher(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (runCatching { android.media.MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)) return
    }
    val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
        .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(panel) }.isSuccess) return
    runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
