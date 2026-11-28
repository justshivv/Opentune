package com.opentune.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class IslandMode {
    COMPACT,
    TRACK_PEEK,
    EXPANDED,
}

/**
 * Dynamic Island-style floating pill & expandable mini-player rendered around the front camera cutout.
 * Matches OpenTune's dark glass Material 3 aesthetic and extracts dynamic accent colors from artwork.
 */
@Composable
fun NotchPlayerOverlay(
    state: NotchOverlayState,
    cutout: CutoutGeometry,
    visible: Boolean = true,
    actions: NotchOverlayActions,
    onWidenedChanged: (Boolean) -> Unit = {},
    onExitAnimationFinished: () -> Unit = {},
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
        val collapsedPillHeight = if (cutout.hasCutout) {
            (cutout.cutoutHeightDp + 6f).coerceIn(34f, 42f).dp
        } else {
            36.dp
        }

        // Briefly expand into a compact track-change banner when new playback info arrives while collapsed.
        var trackPeekActive by remember { mutableStateOf(false) }
        var lastAnnouncedMediaId by remember { mutableStateOf(state.mediaId) }

        LaunchedEffect(state.mediaId, state.expanded, visible) {
            if (state.expanded || !visible) {
                trackPeekActive = false
                if (state.mediaId != null) {
                    lastAnnouncedMediaId = state.mediaId
                }
            } else if (state.mediaId != null && state.mediaId != lastAnnouncedMediaId) {
                lastAnnouncedMediaId = state.mediaId
                trackPeekActive = true
                delay(2_800L)
                trackPeekActive = false
            }
        }

        val islandMode = when {
            state.expanded -> IslandMode.EXPANDED
            trackPeekActive -> IslandMode.TRACK_PEEK
            else -> IslandMode.COMPACT
        }

        LaunchedEffect(islandMode) {
            onWidenedChanged(islandMode != IslandMode.COMPACT)
        }

        // Whole-island cutout entrance & exit animation anchored to the top-center camera cutout.
        val visibilityProgress = remember { Animatable(if (visible) 1f else 0f) }
        LaunchedEffect(visible, reducedMotion) {
            if (visible) {
                visibilityProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = if (reducedMotion) {
                        tween(durationMillis = 120, easing = LinearOutSlowInEasing)
                    } else {
                        spring(dampingRatio = 0.72f, stiffness = 420f)
                    },
                )
            } else {
                visibilityProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 90 else 210,
                        easing = FastOutLinearInEasing,
                    ),
                )
                onExitAnimationFinished()
            }
        }

        // Subtle elastic micro-pulse when track metadata or play state updates.
        val pulseScaleX = remember { Animatable(1f) }
        val pulseScaleY = remember { Animatable(1f) }
        var hasObservedInitialPulseKey by remember { mutableStateOf(false) }
        LaunchedEffect(state.mediaId, state.isPlaying) {
            if (!hasObservedInitialPulseKey) {
                hasObservedInitialPulseKey = true
                return@LaunchedEffect
            }
            if (!visible || reducedMotion) return@LaunchedEffect
            launch {
                pulseScaleX.animateTo(1.032f, tween(95, easing = LinearOutSlowInEasing))
                pulseScaleX.animateTo(1f, spring(dampingRatio = 0.56f, stiffness = 500f))
            }
            launch {
                pulseScaleY.animateTo(0.978f, tween(95, easing = LinearOutSlowInEasing))
                pulseScaleY.animateTo(1f, spring(dampingRatio = 0.56f, stiffness = 500f))
            }
        }

        val cornerRadius by animateDpAsState(
            targetValue = when (islandMode) {
                IslandMode.COMPACT -> collapsedPillHeight / 2f
                IslandMode.TRACK_PEEK -> 26.dp
                IslandMode.EXPANDED -> 32.dp
            },
            animationSpec = if (reducedMotion) {
                tween(120)
            } else {
                spring(dampingRatio = 0.78f, stiffness = 380f)
            },
            label = "notchCornerRadius",
        )

        val (waveformStart, waveformEnd) = remember(accent, secondaryAccent) {
            deriveWaveformGradient(accent, secondaryAccent)
        }

        val shape = RoundedCornerShape(cornerRadius)
        val islandBlack = Color(0xFF000000)
        val backgroundBrush = if (islandMode == IslandMode.EXPANDED) {
            Brush.verticalGradient(
                colors = listOf(
                    islandBlack,
                    lerp(islandBlack, waveformStart, 0.055f),
                    islandBlack,
                ),
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(islandBlack, islandBlack),
            )
        }
        val rimBorderBrush = if (islandMode == IslandMode.EXPANDED) {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.24f),
                    Color.White.copy(alpha = 0.14f),
                    Color.White.copy(alpha = 0.20f),
                ),
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.26f),
                    Color.White.copy(alpha = 0.19f),
                    Color.White.copy(alpha = 0.24f),
                ),
            )
        }

        val progress = visibilityProgress.value.coerceIn(0f, 1.08f)

        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleX = lerpFloat(0.46f, 1f, progress) * pulseScaleX.value
                    scaleY = lerpFloat(0.68f, 1f, progress) * pulseScaleY.value
                    translationY = lerpFloat(-8.dp.toPx(), 0f, progress.coerceIn(0f, 1f))
                    alpha = progress.coerceIn(0f, 1f)
                }
                .shadow(
                    elevation = when (islandMode) {
                        IslandMode.EXPANDED -> 20.dp
                        IslandMode.TRACK_PEEK -> 10.dp
                        IslandMode.COMPACT -> 4.dp
                    },
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = 0.75f),
                    spotColor = Color.Black.copy(alpha = 0.85f),
                )
                .clip(shape)
                .background(backgroundBrush)
                .border(width = 0.9.dp, brush = rimBorderBrush, shape = shape)
                .pointerInput(islandMode) {
                    var cumulativeDragY = 0f
                    detectVerticalDragGestures(
                        onDragStart = { cumulativeDragY = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            cumulativeDragY += dragAmount
                        },
                        onDragEnd = {
                            if (cumulativeDragY < -44f) {
                                haptics.tick()
                                when (islandMode) {
                                    IslandMode.EXPANDED -> actions.onCollapse()
                                    IslandMode.TRACK_PEEK -> trackPeekActive = false
                                    IslandMode.COMPACT -> actions.onDismiss()
                                }
                            }
                            cumulativeDragY = 0f
                        },
                        onDragCancel = { cumulativeDragY = 0f },
                    )
                },
        ) {
            AnimatedContent(
                targetState = islandMode,
                contentAlignment = Alignment.TopCenter,
                transitionSpec = {
                    val expanding = targetState.ordinal > initialState.ordinal
                    val contentTransform = if (reducedMotion) {
                        fadeIn(tween(130)) togetherWith fadeOut(tween(90))
                    } else if (expanding) {
                        (
                            fadeIn(tween(220, delayMillis = 55, easing = LinearOutSlowInEasing)) +
                                scaleIn(
                                    animationSpec = spring(dampingRatio = 0.84f, stiffness = 420f),
                                    initialScale = 0.90f,
                                    transformOrigin = TransformOrigin(0.5f, 0f),
                                ) +
                                slideInVertically(tween(230, easing = FastOutSlowInEasing)) { -it / 10 }
                            ) togetherWith (
                            fadeOut(tween(90, easing = FastOutLinearInEasing)) +
                                scaleOut(
                                    animationSpec = tween(110),
                                    targetScale = 0.88f,
                                    transformOrigin = TransformOrigin(0.5f, 0f),
                                )
                            )
                    } else {
                        (
                            fadeIn(tween(180, delayMillis = 65, easing = LinearOutSlowInEasing)) +
                                scaleIn(
                                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 440f),
                                    initialScale = 0.86f,
                                    transformOrigin = TransformOrigin(0.5f, 0.35f),
                                )
                            ) togetherWith (
                            fadeOut(tween(105, easing = FastOutLinearInEasing)) +
                                scaleOut(
                                    animationSpec = tween(135),
                                    targetScale = 0.88f,
                                    transformOrigin = TransformOrigin(0.5f, 0f),
                                ) +
                                slideOutVertically(tween(135)) { -it / 12 }
                            )
                    }
                    contentTransform.using(
                        SizeTransform(clip = true) { _, _ ->
                            if (reducedMotion) {
                                tween(140)
                            } else {
                                spring(dampingRatio = 0.78f, stiffness = 380f)
                            }
                        },
                    )
                },
                label = "notchIslandModeMorph",
            ) { mode ->
                when (mode) {
                    IslandMode.COMPACT -> CollapsedNotchPill(
                        state = state,
                        cutout = cutout,
                        pillHeight = collapsedPillHeight,
                        waveformStart = waveformStart,
                        waveformEnd = waveformEnd,
                        onTap = {
                            haptics.tick()
                            actions.onToggleExpanded()
                        },
                    )
                    IslandMode.TRACK_PEEK -> TrackPeekBanner(
                        state = state,
                        cutout = cutout,
                        waveformStart = waveformStart,
                        waveformEnd = waveformEnd,
                        reducedMotion = reducedMotion,
                        onTap = {
                            haptics.tick()
                            trackPeekActive = false
                            actions.onToggleExpanded()
                        },
                    )
                    IslandMode.EXPANDED -> ExpandedNotchCard(
                        state = state,
                        cutout = cutout,
                        accent = waveformStart,
                        secondaryAccent = waveformEnd,
                        reducedMotion = reducedMotion,
                        haptics = haptics,
                        actions = actions,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsedNotchPill(
    state: NotchOverlayState,
    cutout: CutoutGeometry,
    pillHeight: Dp,
    waveformStart: Color,
    waveformEnd: Color,
    onTap: () -> Unit,
) {
    val thumbSize: Dp = (pillHeight - 10.dp).coerceIn(22.dp, 28.dp)
    val thumbShape = RoundedCornerShape(7.dp)
    // Reserve the camera lens plus wide pure-black flanking room to keep the signature stadium pill proportion.
    val centerCutoutSpacer: Dp = if (cutout.hasCutout) {
        (cutout.cutoutWidthDp + 44f).coerceIn(66f, 136f).dp
    } else {
        64.dp
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
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = state.artworkUrl,
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.84f)) togetherWith
                    (fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.90f))
            },
            label = "pillArtworkSwap",
        ) { artUrl ->
            Artwork(
                url = artUrl.artworkAt(ROW_ART_PX),
                shape = thumbShape,
                modifier = Modifier
                    .size(thumbSize)
                    .clip(thumbShape),
                containerColor = Color(0xFF141418),
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
                        accent = waveformStart,
                        secondaryAccent = waveformEnd,
                        modifier = Modifier.size(width = 24.dp, height = 16.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Pause,
                        contentDescription = "Paused",
                        tint = waveformEnd.copy(alpha = 0.82f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Intermediate Dynamic Island banner that smoothly expands beneath the camera cutout whenever a
 * new track begins in the background, then shrinks back to the compact stadium pill.
 */
@Composable
private fun TrackPeekBanner(
    state: NotchOverlayState,
    cutout: CutoutGeometry,
    waveformStart: Color,
    waveformEnd: Color,
    reducedMotion: Boolean,
    onTap: () -> Unit,
) {
    val bannerWidth: Dp = (cutout.screenWidthDp - 44f).coerceIn(252f, 336f).dp
    val topClearance: Dp = if (cutout.hasCutout) {
        (cutout.cutoutHeightDp + 2f).coerceIn(22f, 40f).dp
    } else {
        10.dp
    }
    val artShape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .testTag("notch_track_peek_banner")
            .width(bannerWidth)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Expand mini player",
                onClick = onTap,
            )
            .padding(
                start = 12.dp,
                end = 14.dp,
                top = topClearance,
                bottom = 11.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = state.artworkUrl,
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.86f)) togetherWith
                    (fadeOut(tween(130)) + scaleOut(tween(130), targetScale = 0.90f))
            },
            label = "peekArtworkSwap",
        ) { artUrl ->
            Artwork(
                url = artUrl.artworkAt(ROW_ART_PX),
                shape = artShape,
                modifier = Modifier
                    .size(38.dp)
                    .clip(artShape),
                containerColor = Color(0xFF141418),
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        AnimatedContent(
            targetState = Triple(state.mediaId, state.title, state.artist),
            transitionSpec = {
                if (reducedMotion) {
                    fadeIn(tween(140)) togetherWith fadeOut(tween(100))
                } else {
                    (slideInVertically(tween(230, easing = LinearOutSlowInEasing)) { it / 2 } + fadeIn(tween(210))) togetherWith
                        (slideOutVertically(tween(130, easing = FastOutLinearInEasing)) { -it / 2 } + fadeOut(tween(120)))
                }
            },
            modifier = Modifier.weight(1f),
            label = "peekTrackInfoSwap",
        ) { (_, title, artist) ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title.ifBlank { "OpenTune" },
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                    ),
                    color = Color(0xFFF5F5F7),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(iterations = Int.MAX_VALUE),
                )
                if (artist.isNotBlank()) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = Color(0xFFA8A8B3),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        if (state.isPlaying) {
            LiveEqualizerBars(
                accent = waveformStart,
                secondaryAccent = waveformEnd,
                modifier = Modifier.size(width = 24.dp, height = 16.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Pause,
                contentDescription = "Paused",
                tint = waveformEnd.copy(alpha = 0.82f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ExpandedNotchCard(
    state: NotchOverlayState,
    cutout: CutoutGeometry,
    accent: Color,
    secondaryAccent: Color,
    reducedMotion: Boolean,
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

    // Staggered glide-in for the seek bar and transport controls when expanding.
    val controlsReveal = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            controlsReveal.snapTo(1f)
        } else {
            delay(50L)
            controlsReveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 230, easing = LinearOutSlowInEasing),
            )
        }
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
                AnimatedContent(
                    targetState = state.artworkUrl,
                    transitionSpec = {
                        (fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 0.88f)) togetherWith
                            (fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.92f))
                    },
                    label = "expandedArtworkSwap",
                ) { artUrl ->
                    Artwork(
                        url = artUrl.artworkAt(CARD_ART_PX),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(56.dp),
                        containerColor = Color(0xFF1A1A20),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            AnimatedContent(
                targetState = Triple(state.mediaId, state.title, state.artist),
                transitionSpec = {
                    if (reducedMotion) {
                        fadeIn(tween(140)) togetherWith fadeOut(tween(100))
                    } else {
                        (slideInVertically(tween(230, easing = LinearOutSlowInEasing)) { it / 2 } + fadeIn(tween(210))) togetherWith
                            (slideOutVertically(tween(130, easing = FastOutLinearInEasing)) { -it / 2 } + fadeOut(tween(120)))
                    }
                },
                modifier = Modifier.weight(1f),
                label = "expandedTrackMetaSwap",
            ) { (_, title, artist) ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = title.ifBlank { "OpenTune" },
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
                    if (artist.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = artist,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                            color = Color(0xFFB4B4BE),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (state.isPlaying) {
                LiveEqualizerBars(
                    accent = accent,
                    secondaryAccent = secondaryAccent,
                    modifier = Modifier
                        .padding(end = 2.dp)
                        .size(width = 24.dp, height = 16.dp),
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val reveal = controlsReveal.value.coerceIn(0f, 1f)
                    alpha = reveal
                    translationY = lerpFloat(-8.dp.toPx(), 0f, reveal)
                },
        ) {
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
 * 5-bar live audio equalizer that follows OpenTune's real-time [AudioLevels] when audio passes
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
        val barCount = 5
        val barWidth = (size.width / (barCount * 1.72f)).coerceIn(2.2.dp.toPx(), 3.dp.toPx())
        val totalBarsWidth = barCount * barWidth
        val gap = if (barCount > 1) (size.width - totalBarsWidth) / (barCount - 1) else 0f
        val maxSegmentLength = (size.height - barWidth).coerceAtLeast(0f)
        val centerY = size.height / 2f
        val liveDriven = audioEnergy > 0.04f
        val envelope = floatArrayOf(0.46f, 0.78f, 1.00f, 0.76f, 0.42f)
        val phaseOffsets = floatArrayOf(0.0f, 1.15f, 2.35f, 3.55f, 4.85f)
        val speedMultipliers = floatArrayOf(1.0f, 1.24f, 0.92f, 1.18f, 1.36f)

        for (i in 0 until barCount) {
            val wave = ((sin(phase * speedMultipliers[i] + phaseOffsets[i]) + 1f) * 0.5f)
            val shaped = if (liveDriven) {
                val bandEnergy = when (i) {
                    1, 2 -> (bassEnergy * 0.65f + audioEnergy * 0.35f)
                    else -> audioEnergy
                }
                val amplitude = (bandEnergy * 0.72f + wave * 0.28f).coerceIn(0f, 1f)
                (envelope[i] * (0.14f + 0.86f * amplitude)).coerceIn(0f, 1f)
            } else {
                val base = if (i == 2) 0.34f else if (i == 1 || i == 3) 0.20f else 0.0f
                (envelope[i] * (base + (1f - base) * wave)).coerceIn(0f, 1f)
            }

            val segmentLength = maxSegmentLength * shaped
            val x = i * (barWidth + gap) + barWidth / 2f
            val halfSegment = segmentLength / 2f
            val barColor = lerp(accent, secondaryAccent, i / (barCount - 1).toFloat())

            drawLine(
                color = barColor,
                start = Offset(x, centerY - halfSegment),
                end = Offset(x, centerY + halfSegment),
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
        luminance < 0.30f -> lerp(color, Color.White, 0.40f)
        luminance > 0.88f -> lerp(color, Color(0xFFDCDCE6), 0.2f)
        else -> color
    }
}

/**
 * Produces a vivid left-to-right gradient pair for the 5-bar waveform from the current artwork's
 * palette, ensuring rich contrast on a pure #000000 island even for muted or monochrome covers.
 */
private fun deriveWaveformGradient(primary: Color, secondary: Color): Pair<Color, Color> {
    val start = boostChroma(primary, saturationBoost = 1.25f)
    val rawEnd = boostChroma(secondary, saturationBoost = 1.2f)
    val channelDelta = kotlin.math.abs(start.red - rawEnd.red) +
        kotlin.math.abs(start.green - rawEnd.green) +
        kotlin.math.abs(start.blue - rawEnd.blue)
    val end = if (channelDelta < 0.28f) {
        // Shift toward a brighter, airier companion tint when both artwork swatches are nearly identical.
        Color(
            red = (start.red * 0.78f + 0.22f).coerceIn(0f, 1f),
            green = (start.green * 0.85f + 0.28f).coerceIn(0f, 1f),
            blue = (start.blue * 0.90f + 0.30f).coerceIn(0f, 1f),
            alpha = 1f,
        )
    } else {
        lerp(rawEnd, Color.White, 0.18f)
    }
    return start to end
}

private fun boostChroma(color: Color, saturationBoost: Float): Color {
    val avg = (color.red + color.green + color.blue) / 3f
    val r = (avg + (color.red - avg) * saturationBoost).coerceIn(0f, 1f)
    val g = (avg + (color.green - avg) * saturationBoost).coerceIn(0f, 1f)
    val b = (avg + (color.blue - avg) * saturationBoost).coerceIn(0f, 1f)
    return Color(r, g, b, 1f)
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
