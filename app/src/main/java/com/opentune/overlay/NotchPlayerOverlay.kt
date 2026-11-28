package com.opentune.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings
import com.opentune.playback.AudioLevels
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.Haptics
import com.opentune.ui.components.rememberHaptics
import com.opentune.ui.formatTime
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.PlayerTheme
import com.opentune.ui.theme.Text
import com.opentune.ui.theme.rememberArtworkPalette
import com.opentune.ui.theme.rememberArtworkSeed
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Dynamic Island-style floating pill & expandable mini-player rendered around the front camera cutout.
 * Matches OpenTune's dark glass Material 3 aesthetic and extracts dynamic accent colors from artwork.
 */
@Composable
fun NotchPlayerOverlay(
    state: NotchOverlayState,
    cutout: CutoutGeometry,
    actions: NotchOverlayActions,
) {
    if (!state.hasActiveMedia) return

    val themeSettings by AppSettings.theme.collectAsState()
    val uiSettings by AppSettings.ui.collectAsState()
    val artworkSeed = rememberArtworkSeed(state.artworkUrl)
    val artworkPalette = rememberArtworkPalette(state.artworkUrl)
    val haptics = rememberHaptics()

    PlayerTheme(seed = artworkSeed, settings = themeSettings) {
        val rawAccent = artworkPalette.firstOrNull()
            ?: artworkSeed
            ?: MaterialTheme.colorScheme.primary
        val rawSecondary = artworkPalette.getOrNull(1)
            ?: MaterialTheme.colorScheme.tertiary

        val accent by animateColorAsState(
            targetValue = ensureReadableAccent(rawAccent),
            animationSpec = tween(durationMillis = 500),
            label = "notchAccent",
        )
        val secondaryAccent by animateColorAsState(
            targetValue = ensureReadableAccent(rawSecondary),
            animationSpec = tween(durationMillis = 500),
            label = "notchSecondaryAccent",
        )

        val reducedMotion = uiSettings.reduceAnimation
        val cornerRadius by animateDpAsState(
            targetValue = if (state.expanded) 30.dp else 24.dp,
            animationSpec = if (reducedMotion) {
                tween(120)
            } else {
                spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
            },
            label = "notchCornerRadius",
        )

        val shape = RoundedCornerShape(cornerRadius)
        val obsidianBase = Color(0xFF0A0A0E)
        val tintedSurface = lerp(obsidianBase, accent, if (state.expanded) 0.12f else 0.08f)
        val backgroundBrush = Brush.verticalGradient(
            colors = listOf(
                obsidianBase.copy(alpha = 0.96f),
                tintedSurface.copy(alpha = 0.94f),
                obsidianBase.copy(alpha = 0.97f),
            ),
        )
        val glassBorderBrush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.22f),
                accent.copy(alpha = 0.28f),
                Color.White.copy(alpha = 0.08f),
            ),
        )

        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .shadow(
                    elevation = if (state.expanded) 22.dp else 10.dp,
                    shape = shape,
                    ambientColor = accent.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.85f),
                )
                .clip(shape)
                .background(backgroundBrush)
                .border(width = 1.dp, brush = glassBorderBrush, shape = shape)
                .animateContentSize(
                    animationSpec = if (reducedMotion) {
                        tween(140)
                    } else {
                        spring(
                            dampingRatio = 0.76f,
                            stiffness = Spring.StiffnessMediumLow,
                        )
                    },
                )
                .pointerInput(state.expanded) {
                    var cumulativeDragY = 0f
                    detectVerticalDragGestures(
                        onDragStart = { cumulativeDragY = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            cumulativeDragY += dragAmount
                        },
                        onDragEnd = {
                            if (cumulativeDragY < -44f) {
                                haptics.tick()
                                if (state.expanded) {
                                    actions.onCollapse()
                                } else {
                                    actions.onDismiss()
                                }
                            }
                            cumulativeDragY = 0f
                        },
                        onDragCancel = { cumulativeDragY = 0f },
                    )
                },
        ) {
            if (!state.expanded) {
                CollapsedNotchPill(
                    state = state,
                    cutout = cutout,
                    accent = accent,
                    secondaryAccent = secondaryAccent,
                    onTap = {
                        haptics.tick()
                        actions.onToggleExpanded()
                    },
                )
            } else {
                ExpandedNotchCard(
                    state = state,
                    cutout = cutout,
                    accent = accent,
                    secondaryAccent = secondaryAccent,
                    haptics = haptics,
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun CollapsedNotchPill(
    state: NotchOverlayState,
    cutout: CutoutGeometry,
    accent: Color,
    secondaryAccent: Color,
    onTap: () -> Unit,
) {
    val pillHeight: Dp = if (cutout.hasCutout) {
        (cutout.cutoutHeightDp + 6f).coerceIn(36f, 48f).dp
    } else {
        36.dp
    }
    val thumbSize: Dp = (pillHeight - 10.dp).coerceIn(22.dp, 28.dp)
    // Reserve the camera lens width plus breathing room so content flanks the cutout cleanly.
    val centerCutoutSpacer: Dp = if (cutout.hasCutout) {
        (cutout.cutoutWidthDp + 16f).coerceIn(34f, 140f).dp
    } else {
        20.dp
    }

    Row(
        modifier = Modifier
            .testTag("notch_collapsed_pill")
            .height(pillHeight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Expand mini player",
                onClick = onTap,
            )
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(thumbSize)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = if (state.isPlaying) accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.2f),
                    shape = CircleShape,
                ),
        ) {
            Artwork(
                url = state.artworkUrl.artworkAt(ROW_ART_PX),
                shape = CircleShape,
                modifier = Modifier.size(thumbSize),
                containerColor = Color(0xFF1A1A20),
            )
        }

        Spacer(modifier = Modifier.width(centerCutoutSpacer))

        Box(
            modifier = Modifier
                .size(width = 26.dp, height = thumbSize),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = state.isPlaying,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.85f)) togetherWith
                        (fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.85f))
                },
                label = "pillStatusIndicator",
            ) { playing ->
                if (playing) {
                    LiveEqualizerBars(
                        accent = accent,
                        secondaryAccent = secondaryAccent,
                        modifier = Modifier.size(width = 20.dp, height = 14.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Pause,
                        contentDescription = "Paused",
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedNotchCard(
    state: NotchOverlayState,
    cutout: CutoutGeometry,
    accent: Color,
    secondaryAccent: Color,
    haptics: Haptics,
    actions: NotchOverlayActions,
) {
    val cardWidth: Dp = (cutout.screenWidthDp - 24f).coerceIn(280f, 384f).dp
    // Keep the top cutout zone clear so the camera lens sits neatly in the dark header margin.
    val topCutoutClearance: Dp = if (cutout.hasCutout) {
        (cutout.cutoutHeightDp + 2f).coerceIn(24f, 44f).dp
    } else {
        14.dp
    }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val durationMs = state.durationMs.coerceAtLeast(0L)
    val liveFraction = if (durationMs > 0L) {
        (state.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val displayFraction = if (isScrubbing) scrubFraction else liveFraction
    val displayPositionMs = if (isScrubbing && durationMs > 0L) {
        (scrubFraction * durationMs).roundToLong()
    } else {
        state.positionMs.coerceAtLeast(0L)
    }
    val remainingMs = (durationMs - displayPositionMs).coerceAtLeast(0L)

    Column(
        modifier = Modifier
            .testTag("notch_expanded_card")
            .width(cardWidth)
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = topCutoutClearance,
                bottom = 12.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .testTag("notch_artwork_button")
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        width = 1.dp,
                        color = accent.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Open full player",
                    ) {
                        haptics.press()
                        actions.onOpenPlayer()
                    },
            ) {
                Artwork(
                    url = state.artworkUrl.artworkAt(CARD_ART_PX),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(56.dp),
                    containerColor = Color(0xFF1A1A20),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.ifBlank { "OpenTune" },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    ),
                    color = Color(0xFFF5F5F7),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(iterations = Int.MAX_VALUE),
                )
                if (state.artist.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = state.artist,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = Color(0xFFB4B4BE),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (state.isPlaying) {
                LiveEqualizerBars(
                    accent = accent,
                    secondaryAccent = secondaryAccent,
                    modifier = Modifier
                        .padding(end = 2.dp)
                        .size(width = 22.dp, height = 16.dp),
                )
            } else {
                IconButton(
                    onClick = {
                        haptics.tick()
                        actions.onCollapse()
                    },
                    modifier = Modifier
                        .testTag("notch_collapse_button")
                        .minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "Collapse mini player",
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        NotchSeekBar(
            fraction = displayFraction,
            enabled = durationMs > 0L,
            accent = accent,
            secondaryAccent = secondaryAccent,
            isScrubbing = isScrubbing,
            onScrubStart = { fraction ->
                isScrubbing = true
                scrubFraction = fraction
            },
            onScrubMove = { fraction ->
                scrubFraction = fraction
            },
            onScrubEnd = { fraction ->
                isScrubbing = false
                if (durationMs > 0L) {
                    actions.onSeekTo((fraction * durationMs).roundToLong())
                }
            },
            onTapSeek = { fraction ->
                if (durationMs > 0L) {
                    haptics.tick()
                    actions.onSeekTo((fraction * durationMs).roundToLong())
                }
            },
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(displayPositionMs),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = Color(0xFFB4B4BE),
            )
            Text(
                text = if (durationMs > 0L) "-${formatTime(remainingMs)}" else "--:--",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = Color(0xFFB4B4BE),
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    haptics.pattern(Haptics.Pattern.PREVIOUS)
                    actions.onPrevious()
                },
                enabled = state.hasPrevious,
                modifier = Modifier
                    .testTag("notch_previous_button")
                    .minimumInteractiveComponentSize(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "Previous track",
                    tint = Color.White.copy(alpha = if (state.hasPrevious) 0.92f else 0.35f),
                    modifier = Modifier.size(30.dp),
                )
            }

            Box(
                modifier = Modifier
                    .testTag("notch_play_pause_button")
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(accent, secondaryAccent),
                        ),
                    )
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (state.isPlaying) "Pause" else "Play",
                    ) {
                        haptics.pattern(if (state.isPlaying) Haptics.Pattern.PAUSE else Haptics.Pattern.PLAY)
                        actions.onTogglePlayPause()
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color(0xFF0A0A0E),
                        strokeWidth = 2.4.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = Color(0xFF0A0A0E),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            IconButton(
                onClick = {
                    haptics.pattern(Haptics.Pattern.NEXT)
                    actions.onNext()
                },
                enabled = state.hasNext,
                modifier = Modifier
                    .testTag("notch_next_button")
                    .minimumInteractiveComponentSize(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Next track",
                    tint = Color.White.copy(alpha = if (state.hasNext) 0.92f else 0.35f),
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Collapse mini player",
                ) {
                    haptics.tick()
                    actions.onCollapse()
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(3.5.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.24f)),
            )
        }
    }
}

@Composable
private fun NotchSeekBar(
    fraction: Float,
    enabled: Boolean,
    accent: Color,
    secondaryAccent: Color,
    isScrubbing: Boolean,
    onScrubStart: (Float) -> Unit,
    onScrubMove: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onTapSeek: (Float) -> Unit,
) {
    var latestDragFraction by remember { mutableFloatStateOf(fraction) }

    Box(
        modifier = Modifier
            .testTag("notch_seek_bar")
            .fillMaxWidth()
            .height(26.dp)
            .semantics { contentDescription = "Playback progress" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val target = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onTapSeek(target)
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val start = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        latestDragFraction = start
                        onScrubStart(start)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val moved = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        latestDragFraction = moved
                        onScrubMove(moved)
                    },
                    onDragEnd = { onScrubEnd(latestDragFraction) },
                    onDragCancel = { onScrubEnd(latestDragFraction) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp),
        ) {
            val trackHeight = if (isScrubbing) 6.dp.toPx() else 4.dp.toPx()
            val centerY = size.height / 2f
            val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)

            drawRoundRect(
                color = Color.White.copy(alpha = 0.18f),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = corner,
            )

            val filledWidth = (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(0f)
            if (filledWidth > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(colors = listOf(accent, secondaryAccent)),
                    topLeft = Offset(0f, centerY - trackHeight / 2f),
                    size = Size(filledWidth, trackHeight),
                    cornerRadius = corner,
                )
            }

            val thumbRadius = if (isScrubbing) 6.5.dp.toPx() else 4.5.dp.toPx()
            if (enabled) {
                drawCircle(
                    color = Color.White,
                    radius = thumbRadius,
                    center = Offset(filledWidth.coerceIn(thumbRadius, (size.width - thumbRadius).coerceAtLeast(thumbRadius)), centerY),
                )
            }
        }
    }
}

/**
 * 4-bar live audio equalizer that follows OpenTune's real-time [AudioLevels] when audio passes
 * through the DSP pipeline and smoothly falls back to harmonic motion on offload/bit-perfect paths.
 */
@Composable
private fun LiveEqualizerBars(
    accent: Color,
    secondaryAccent: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "notchEqualizer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "eqPhase",
    )

    var audioEnergy by remember { mutableFloatStateOf(0f) }
    var bassEnergy by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nowNs ->
                val sample = AudioLevels.at(nowNs)
                if (sample != null) {
                    val targetLevel = (sample.level * 2.4f).coerceIn(0f, 1f)
                    val targetBass = (sample.bass * 2.8f).coerceIn(0f, 1f)
                    audioEnergy += (targetLevel - audioEnergy) * 0.35f
                    bassEnergy += (targetBass - bassEnergy) * 0.4f
                } else {
                    audioEnergy *= 0.85f
                    bassEnergy *= 0.85f
                }
            }
        }
    }

    Canvas(modifier = modifier) {
        val barCount = 4
        val barWidth = (size.width / (barCount * 1.9f)).coerceAtLeast(2.dp.toPx())
        val totalBarsWidth = barCount * barWidth
        val gap = if (barCount > 1) (size.width - totalBarsWidth) / (barCount - 1) else 0f
        val liveDriven = audioEnergy > 0.04f

        for (i in 0 until barCount) {
            val wave = ((sin(phase * (1f + i * 0.18f) + i * 1.35f) + 1f) * 0.5f)
            val fraction = if (liveDriven) {
                val bandBoost = if (i == 0 || i == 1) bassEnergy else audioEnergy
                (0.24f + 0.76f * (bandBoost * 0.72f + wave * 0.28f)).coerceIn(0.2f, 1f)
            } else {
                (0.24f + 0.72f * wave).coerceIn(0.22f, 0.96f)
            }

            val barHeight = size.height * fraction
            val x = i * (barWidth + gap) + barWidth / 2f
            val topY = (size.height - barHeight) / 2f
            val barColor = lerp(accent, secondaryAccent, i / (barCount - 1).toFloat())

            drawLine(
                color = barColor,
                start = Offset(x, topY),
                end = Offset(x, topY + barHeight),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Keeps dark or desaturated artwork colors luminous and legible on an obsidian pill surface. */
private fun ensureReadableAccent(color: Color): Color {
    val luminance = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
    return when {
        luminance < 0.32f -> lerp(color, Color.White, 0.45f)
        luminance > 0.88f -> lerp(color, Color(0xFFDCDCE6), 0.2f)
        else -> color
    }
}
