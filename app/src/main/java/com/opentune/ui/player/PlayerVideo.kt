package com.opentune.ui.player

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.opentune.i18n.Tr
import com.opentune.playback.PlayerConnection
import com.opentune.data.model.artworkAt
import com.opentune.ui.theme.Text
import kotlin.math.roundToInt

/**
 * The player screen's one picture output: a single texture view, moved
 * between the player and full screen, whose SurfaceTexture is kept across
 * each move. The player draws into the same Surface the whole time, so the
 * decoder is never handed a new one: no black frame, no rebuffer, the
 * stream untouched. A view that leaves and isn't placed again within
 * [KEEP_MS] gives its surface up.
 */
internal class VideoOutput(
    context: android.content.Context,
    private val attach: (Surface) -> Unit,
    private val detach: (Surface) -> Unit,
) {
    // Not opaque, and no background of its own below: the scheme's
    // background is pure black in this theme, so anything the surface
    // paints around the picture reads as black bars. Left clear, whatever
    // the player itself shows behind the surface shows around the picture.
    val view: TextureView = TextureView(context).apply { isOpaque = false }

    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private val handler = Handler(Looper.getMainLooper())
    private val drop = Runnable { release() }

    /** The surface the player is drawing into, if any; the same one through every move. */
    val current: Surface? get() = surface

    init {
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = adopt(st)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

            // Taken off the window: kept for a moment, in case it's only moving.
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                if (st !== texture) return true
                handler.removeCallbacks(drop)
                handler.postDelayed(drop, KEEP_MS)
                return false
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            // Put back before it draws, so it shows the kept texture instead of making a new one.
            override fun onViewAttachedToWindow(v: View) {
                handler.removeCallbacks(drop)
                val kept = texture ?: return
                if (view.surfaceTexture !== kept && !kept.isReleased) runCatching { view.setSurfaceTexture(kept) }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    /** A texture the view made for itself: the first one, or after a kept one was given up. */
    internal fun adopt(st: SurfaceTexture) {
        handler.removeCallbacks(drop)
        if (st === texture) return
        release()
        texture = st
        surface = Surface(st).also(attach)
    }

    /** Takes the surface back from the player and frees it. */
    fun release() {
        handler.removeCallbacks(drop)
        surface?.let { detach(it); it.release() }
        surface = null
        if (texture !== view.surfaceTexture) texture?.release()
        texture = null
    }

    companion object {
        /** How long a view taken off screen keeps its surface for a move to finish. */
        const val KEEP_MS = 1_000L
    }
}

/** The player's [VideoOutput], drawing into [connection], given up when the player leaves. */
@Composable
internal fun rememberVideoOutput(connection: PlayerConnection): VideoOutput {
    val context = LocalContext.current
    val output = remember(connection) { VideoOutput(context, connection::setVideoSurface, connection::clearVideoSurface) }
    DisposableEffect(output) { onDispose { output.release() } }
    return output
}

/**
 * The playing song's music-video picture, drawn by the playback player
 * into [output]'s view: the whole frame fitted inside the box, or with
 * [crop] filling it, the overflow cut off. Only one place shows it at a time.
 */
@OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun VideoSurface(output: VideoOutput, size: VideoSize?, modifier: Modifier = Modifier, crop: Boolean = false) {
    val view = output.view
    val latest = androidx.compose.runtime.rememberUpdatedState(size)
    DisposableEffect(view) {
        val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            latest.value?.let { fitVideo(view, it) }
        }
        view.addOnLayoutChangeListener(layout)
        onDispose { view.removeOnLayoutChangeListener(layout) }
    }
    BoxWithConstraints(
        modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        // The view is sized to the picture's own shape. A surface stretched
        // past the frame paints black around it inside the view itself, so
        // no colour behind the view ever shows; a view the frame's exact
        // size leaves only what's behind the surface around the picture.
        val ratio = size?.let(::videoAspect) ?: 0f
        val fit =
            if (ratio > 0f) {
                // Fitted, a picture wider than the box takes its width; cropped, its height.
                if ((ratio > maxWidth / maxHeight) != crop) {
                    maxWidth to (maxWidth / ratio)
                } else {
                    (maxHeight * ratio) to maxHeight
                }
            } else {
                maxWidth to maxHeight
            }
        AndroidView(
            // The one view, taken from wherever it was last shown.
            factory = { (view.parent as? ViewGroup)?.removeView(view); view },
            modifier = Modifier
                .requiredSize(fit.first, fit.second)
                // Where the picture is seen (cut to the box), for the small window to grow out of.
                .onGloballyPositioned { c ->
                    if (PictureInPicture.active.value || !c.isAttached) return@onGloballyPositioned
                    val b = c.boundsInWindow()
                    PictureInPicture.report(android.graphics.Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()))
                },
            update = { v -> size?.let { fitVideo(v, it) } },
        )
    }
}

/**
 * The size of the box the music video shows in, in the cover's place, at [shape] (width
 * over height: the picture's own, or 1 for the cover's square it eases from). The box
 * keeps inside the cover's square of side [side]: a wide picture as wide as the cover, a
 * tall one as tall, centred on the cover's middle, so it never runs past where the cover
 * sat and nothing around it moves.
 */
internal fun videoBoxSize(side: Float, shape: Float): Pair<Float, Float> {
    if (!shape.isFinite() || shape <= 0f) return side to side
    return if (shape >= 1f) side to side / shape else side * shape to side
}

/** How long the box takes to change between the cover's square and the picture's shape. */
internal const val VIDEO_BOX_MS = 380

/**
 * The music video in the cover's place, at its own shape inside a box with the cover's
 * rounded corners, inset from the screen's edges as the cover is. Never stretched or
 * cropped: the box takes the picture's shape, so there are no bars either.
 *
 * The box starts as the cover itself, the same square in the same place with the cover
 * drawn in it, and eases to the picture's shape once its size is known; until the first
 * frame the cover shows through the clear picture. With [showVideo] off (going back to
 * the song) it eases back to the square before the cover takes over.
 *
 * The picture is a TextureView ([VideoOutput]): drawn by the UI's own renderer, it is cut
 * to the rounded corners like anything else, where a SurfaceView would ignore the clip.
 */
@Composable
internal fun VideoCoverPane(
    artworkUrl: String?,
    /** The picture's width over height; 0 while unknown, which holds the cover's square. */
    aspect: Float,
    showVideo: Boolean,
    corner: androidx.compose.ui.unit.Dp,
    isPlaying: Boolean,
    animate: Boolean,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    modifier: Modifier = Modifier,
    /** Where the box sits in the root, each time it's laid out. */
    onBox: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    /** Sends the picture into a small window over other apps, when offered. */
    onPictureInPicture: (() -> Unit)? = null,
    /** Opens the picture on the whole screen, when offered. */
    onFullScreen: (() -> Unit)? = null,
    videoSurface: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val target = if (showVideo && aspect > 0f) aspect else 1f
    val shape by androidx.compose.animation.core.animateFloatAsState(
        target,
        if (animate) androidx.compose.animation.core.tween(VIDEO_BOX_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        else androidx.compose.animation.core.snap(),
        label = "videoBox",
    )
    // Smaller while paused, as the cover is, so going between the two never jumps.
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (isPlaying || !animate) 1f else 0.86f,
        androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = androidx.compose.animation.core.Spring.StiffnessLow),
        label = "videoBoxScale",
    )
    val rounded = androidx.compose.foundation.shape.RoundedCornerShape(corner)
    ArtworkSwipeArea(onSwipeNext = onSwipeNext, onSwipePrevious = onSwipePrevious, modifier = modifier) {
        BoxWithConstraints(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth.value, maxHeight.value)
            val (w, h) = videoBoxSize(side, shape)
            Box(
                Modifier
                    .requiredSize(w.dp, h.dp)
                    .onGloballyPositioned { c -> if (c.isAttached) onBox(c.boundsInRoot()) }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        // The cover's own soft shadow.
                        shadowElevation = 16.dp.toPx()
                        ambientShadowColor = Color.Black.copy(alpha = 0.22f)
                        spotShadowColor = Color.Black.copy(alpha = 0.16f)
                        this.shape = rounded
                        clip = true
                    }
                    .testTag("video-cover-box"),
                contentAlignment = Alignment.Center,
            ) {
                // The cover under the clear picture: what the box starts as, and what shows until the first frame.
                com.opentune.ui.components.Artwork(
                    artworkUrl.artworkAt(com.opentune.data.model.PLAYER_ART_PX),
                    Modifier.matchParentSize(),
                    androidx.compose.ui.graphics.RectangleShape,
                )
                if (showVideo) videoSurface()
                // Over the picture, in its bottom corners: the whole screen
                // always, and the small window only while the picture plays
                // (a paused picture in a small window is a frozen window).
                // They grow and shrink with the box itself.
                if (showVideo && aspect > 0f && (onPictureInPicture != null || onFullScreen != null)) {
                    val buttonScale = (minOf(w, h) / 190f).coerceIn(0.85f, 1.3f)
                    if (isPlaying && onPictureInPicture != null) Box(
                        Modifier.align(Alignment.BottomStart).padding(10.dp),
                    ) {
                        PaneButton(buttonScale) {
                            PictureInPictureButton(onPictureInPicture, tint = Color.White)
                        }
                    }
                    if (onFullScreen != null) Box(
                        Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    ) {
                        PaneButton(buttonScale) {
                            FullScreenButton(onFullScreen, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
/** A round dark chip under one of the pane's own buttons, scaled with the box. */
@Composable
private fun PaneButton(scale: Float, content: @Composable () -> Unit) {
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}
/** A picture's shape, width over height as it is shown, or 0 while unknown. */
@OptIn(markerClass = [UnstableApi::class])
internal fun videoAspect(size: VideoSize): Float {
    if (size.width <= 0 || size.height <= 0) return 0f
    val swapped = size.unappliedRotationDegrees % 180 != 0
    val w = if (swapped) size.height else size.width
    val h = if (swapped) size.width else size.height
    return (w.toFloat() / h) * if (swapped) 1f else size.pixelWidthHeightRatio
}

/** Fits the whole video frame inside the view, centred, with the theme's colour around it. */
private fun fitVideo(view: TextureView, video: VideoSize) {
    val vw = view.width.toFloat()
    val vh = view.height.toFloat()
    if (vw == 0f || vh == 0f || video.width == 0 || video.height == 0) return
    val frameW = video.width * video.pixelWidthHeightRatio
    val frameH = video.height.toFloat()
    val scale = minOf(vw / frameW, vh / frameH)
    val matrix = Matrix()
    matrix.setScale(frameW * scale / vw, frameH * scale / vh, vw / 2f, vh / 2f)
    view.setTransform(matrix)
}

/** The player's Song | Video switch, shown only for songs that have a video. An episode or a chapter is no song: its side of the switch reads Audio. */
@Composable
internal fun SongVideoToggle(videoMode: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier, isEpisode: Boolean = false) {
    Row(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Segment(Tr.f(if (isEpisode) "Audio" else "Song"), selected = !videoMode) { if (videoMode) onSelect(false) }
        Segment(Tr.f("Video"), selected = videoMode) { if (!videoMode) onSelect(true) }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
