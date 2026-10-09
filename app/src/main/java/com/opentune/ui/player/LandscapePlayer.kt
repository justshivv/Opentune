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
import androidx.compose.ui.text.font.FontFamily
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
 * in a serif, the controls, the lines that come next and the volume.
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
    val song = state.song
    val scheme = MaterialTheme.colorScheme
    val animate = !state.ui.reduceAnimation
    var previousRolls by remember { mutableIntStateOf(0) }
    var nextRolls by remember { mutableIntStateOf(0) }
    val haptics = com.opentune.ui.components.rememberHaptics()
    var showCast by remember { mutableStateOf(false) }
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
                modifier = Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true),
            ) {
                Artwork(
                    song.thumbnailUrl.artworkAt(1080),
                    Modifier.fillMaxSize().shadow(18.dp, RoundedCornerShape(16.dp)),
                    RoundedCornerShape(16.dp),
                )
            }
        }
        Spacer(Modifier.width(32.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            // Short screens drop the coming lines first, then the volume.
            val roomy = maxHeight >= 300.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(device, style = MaterialTheme.typography.labelLarge, color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.Headphones, null, Modifier.size(14.dp), tint = scheme.primary)
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
                SourceQualityBadge(song.videoId, state.audioFormat, onClick = onSignal)
                TimeBar(position, state.durationMs, actions.seekTo)
                val lines = if (showLines) rememberLyricLines(state.lyrics, lyricsPosition) else "" to ""
                LyricLine(lines.first, alpha = 0.92f, onLyrics)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Unlike" else "Like", { haptics.pattern(if (liked) com.opentune.ui.components.Haptics.Pattern.OFF else com.opentune.ui.components.Haptics.Pattern.LIKE); onLike() }, tint = if (liked) scheme.primary else scheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tap("Previous", { haptics.pattern(com.opentune.ui.components.Haptics.Pattern.PREVIOUS); previousRolls++; actions.previous() }) { SkipGlyph(false, previousRolls, scheme.onSurface, animate, Modifier.size(38.dp)) }
                        Tap(if (state.isPlaying) "Pause" else "Play", { haptics.pattern(if (state.isPlaying) com.opentune.ui.components.Haptics.Pattern.PAUSE else com.opentune.ui.components.Haptics.Pattern.PLAY); actions.togglePlay() }) { PlayPauseGlyph(state.isPlaying, scheme.onSurface, animate, Modifier.size(54.dp)) }
                        Tap("Next", { haptics.pattern(com.opentune.ui.components.Haptics.Pattern.NEXT); nextRolls++; actions.next() }, enabled = state.hasNext) {
                            SkipGlyph(true, nextRolls, scheme.onSurface.copy(alpha = if (state.hasNext) 1f else 0.35f), animate, Modifier.size(38.dp))
                        }
                    }
                    val context = LocalContext.current
                    RoundIcon(Icons.Rounded.SpeakerGroup, "Choose where it plays", { openOutputSwitcher(context) })
                    RoundIcon(Icons.Rounded.Cast, "Play on another device", { showCast = true })
                    if (showCast) com.opentune.ui.cast.CastSheet(onDismiss = { showCast = false })
                }
                if (roomy) {
                    LyricLine(lines.second, alpha = 0.55f, onLyrics, maxLines = 2)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { VolumeBar() }
                        RoundIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", onQueue)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { RoundIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", onQueue) }
                }
            }
        }
    }
}

/** The line being sung and the two after it; empty without synced lyrics. */
@Composable
private fun rememberLyricLines(lyrics: LyricsState, position: () -> Long): Pair<String, String> {
    val pos by rememberUpdatedState(position)
    val synced = (lyrics as? LyricsState.Found)?.lyrics as? Lyrics.Synced
    val lines by remember(synced) {
        derivedStateOf {
            val list = synced?.lines?.filter { it.text.isNotBlank() }.orEmpty()
            if (list.isEmpty()) return@derivedStateOf "" to ""
            val p = pos()
            val at = list.indexOfLast { it.startMs <= p }.coerceAtLeast(0)
            list[at].text to list.drop(at + 1).take(2).joinToString("\n") { it.text }
        }
    }
    return lines
}

@Composable
private fun LyricLine(text: String, alpha: Float, onClick: () -> Unit, maxLines: Int = 1) {
    Text(
        text.lowercase(),
        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 22.sp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = text.isNotEmpty(), onClick = onClick),
    )
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
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(24.dp), tint = tint)
    }
}

@Composable
private fun Tap(label: String, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
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
