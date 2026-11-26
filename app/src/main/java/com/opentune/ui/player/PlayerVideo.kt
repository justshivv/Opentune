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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import kotlinx.coroutines.flow.collectLatest
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

    // Whether a frame has reached the picture since it was last armed: plain for the check
    // made on every frame, state for the one change the player's box waits on.
    private var seen = false
    private val drawnState = androidx.compose.runtime.mutableStateOf(false)

    /**
     * A frame of the picture has been drawn since [arm]: until then the player's box keeps
     * the cover, so a video still loading shows the cover, never a clear or black box.
     */
    val drawn: Boolean get() = drawnState.value

    /** Waits for a new first frame: the video was turned on, or another song's video comes. */
    fun arm() {
        seen = false
        if (drawnState.value) drawnState.value = false
    }

    /** A frame is on the surface (the view drew one, or the player says it rendered its first). */
    fun frameShown() {
        if (seen) return
        seen = true
        drawnState.value = true
    }

    init {
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = adopt(st)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = frameShown()

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
        // A new texture starts empty: the box shows the cover until a frame is drawn into it.
        arm()
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

/** How long the box takes to morph between the cover, where the layout draws it, and the video's box. */
internal const val VIDEO_BOX_MS = 420

/** The morph's easing: quick to leave, long to settle, as the cover's own flights are. */
internal val VIDEO_BOX_EASING = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** How long the picture takes to come in over the cover once its first frame is drawn, and to go. */
internal const val VIDEO_PICTURE_IN_MS = 260
internal const val VIDEO_PICTURE_OUT_MS = 200

/**
 * The video box's corners: a large card's, not the cover's own. A 16:9 box is little more
 * than half as tall as the cover's square, so the cover's 28dp read as a pill on it. A
 * cover set square in Settings keeps its video square too (see [videoBoxCorner]).
 */
internal val VIDEO_BOX_CORNER = 12.dp

/** In the edge-to-edge layout the box runs this far past the page's 24dp margin: 14dp short of the screen's edges. */
internal val VIDEO_BOX_BLEED = 10.dp

/** The picture's shape taken while its size isn't known yet: most music videos are 16:9. */
internal const val VIDEO_ASSUMED_ASPECT = 16f / 9f

/**
 * The least of a picture a filled box may show. A wide picture may lose up to 45% of its
 * width, at its sides (a 16:9 one still fills a square box); a tall one only a quarter of
 * its height, as cutting its top and bottom cuts heads off: a 9:16 one shows whole at the
 * box's full height instead.
 */
internal const val MIN_VISIBLE_WIDE = 0.55f
internal const val MIN_VISIBLE_TALL = 0.75f

/** The video's box: its size in px, and whether the picture fills it cut to fit ([crop]) or shows whole. */
internal data class VideoBoxSpec(val width: Float, val height: Float, val crop: Boolean)

/**
 * The box the music video shows in, inside a cover region [regionW] by [regionH].
 *
 * With [fill] (the edge-to-edge layout) the box takes the whole region. In the square
 * layouts it is as wide as the cover's square and as tall as the picture needs, up to the
 * square's height. A picture whose shape differs a little from the box's fills it, centred
 * and cut at the sides that overflow, never stretched; one that would lose more than
 * [MIN_VISIBLE_WIDE] (wide) or [MIN_VISIBLE_TALL] (tall) allows shows whole instead, in a
 * box at its own shape as large as the region holds. [aspect] 0 (not known yet) is taken
 * as 16:9.
 */
internal fun videoBoxSpec(regionW: Float, regionH: Float, aspect: Float, fill: Boolean): VideoBoxSpec {
    if (regionW <= 0f || regionH <= 0f) return VideoBoxSpec(0f, 0f, false)
    val a = if (aspect.isFinite() && aspect > 0f) aspect else VIDEO_ASSUMED_ASPECT
    val side = minOf(regionW, regionH)
    val w = if (fill) regionW else side
    val h = if (fill) regionH else minOf(side, side / a)
    val shape = w / h
    val visible = if (a > shape) shape / a else a / shape
    if (visible >= 0.999f) return VideoBoxSpec(w, h, crop = false)
    if (visible >= if (a >= 1f) MIN_VISIBLE_WIDE else MIN_VISIBLE_TALL) return VideoBoxSpec(w, h, crop = true)
    val fw = minOf(w, h * a)
    return VideoBoxSpec(fw, fw / a, crop = false)
}

/** The box's corner radius: [VIDEO_BOX_CORNER] edge to edge; in a square layout no rounder than the cover's own. */
internal fun videoBoxCorner(edgeToEdge: Boolean, coverCorner: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    if (edgeToEdge) VIDEO_BOX_CORNER else minOf(coverCorner, VIDEO_BOX_CORNER)

/**
 * Where a layout draws its cover when no video shows, in the cover region's own px: the
 * part that's seen ([clip]), where the whole square image sits, which may run past the
 * clip (the edge-to-edge cover is cut by the screen's sides), and the corner radius.
 */
internal data class CoverPlace(val clip: Rect, val image: Rect, val corner: Float)

/** The square layouts' cover: a square as large as the region holds, centred, rounded by [corner]. */
internal fun squareCoverPlace(width: Float, height: Float, corner: Float): CoverPlace {
    val side = minOf(width, height)
    val r = Rect(Offset((width - side) / 2f, (height - side) / 2f), Size(side, side))
    return CoverPlace(r, r, corner)
}

/** Where the edge-to-edge cover region sits: its offset in the player, the player's width, the cover's height and its solid share, in px. */
@androidx.compose.runtime.Immutable
internal data class EdgeCoverAt(val offset: Offset, val playerWidth: Float, val coverHeight: Float, val solid: Float)

/**
 * Where the edge-to-edge layout's backdrop draws the cover ([fullCoverHeight]), in the
 * region's px. The image is a square as tall as the cover, centred across the player and
 * cut by its sides; the clip is the part of it drawn solid, above its fade into the backdrop.
 */
internal fun edgeCoverPlace(at: EdgeCoverAt): CoverPlace {
    val image = Rect((at.playerWidth - at.coverHeight) / 2f, 0f, (at.playerWidth + at.coverHeight) / 2f, at.coverHeight)
    val clip = Rect(0f, 0f, at.playerWidth, at.coverHeight * at.solid)
    return CoverPlace(clip.translate(-at.offset), image.translate(-at.offset), 0f)
}

/** One frame of the morph: where the box is cut, where the cover's image sits in it, and how round it is. */
internal data class MorphFrame(val clip: Rect, val image: Rect, val corner: Float)

/**
 * The morph at [t]: 0 is the cover exactly as [cover] draws it, 1 the video's [box] rounded
 * by [boxCorner]. The cut, the image's square and the corner move together, so the box is
 * one shape on its way; at 1 the image fills the box centred, as the picture does.
 */
internal fun morphFrame(cover: CoverPlace, box: Rect, boxCorner: Float, t: Float): MorphFrame {
    val k = t.coerceIn(0f, 1f)
    val side = maxOf(box.width, box.height)
    val filled = Rect(box.center.x - side / 2f, box.center.y - side / 2f, box.center.x + side / 2f, box.center.y + side / 2f)
    return MorphFrame(
        androidx.compose.ui.geometry.lerp(cover.clip, box, k),
        androidx.compose.ui.geometry.lerp(cover.image, filled, k),
        cover.corner + (boxCorner - cover.corner) * k,
    )
}

/** This frame scaled by [scale] about [pivot]: smaller while paused, as the cover is. */
internal fun MorphFrame.scaledAbout(scale: Float, pivot: Offset): MorphFrame {
    if (scale == 1f) return this
    fun Rect.s() = Rect(
        pivot.x + (left - pivot.x) * scale, pivot.y + (top - pivot.y) * scale,
        pivot.x + (right - pivot.x) * scale, pivot.y + (bottom - pivot.y) * scale,
    )
    return MorphFrame(clip.s(), image.s(), corner * scale)
}

/**
 * The cover's slow drift and zoom (livingArt, in Motion.kt) at this moment, for an image [w]
 * by [h]: its scale about its centre, and how far it moves. The same sums as livingArt's, so
 * the box's cover starts on the very frame of the drift the layout's own cover was showing.
 */
internal fun coverDrift(motion: com.opentune.ui.components.ArtworkMotion, w: Float, h: Float): Triple<Float, Float, Float> {
    val t = motion.time()
    val amount = motion.amount()
    val zoom = 0.075f + 0.035f * kotlin.math.sin(t * 0.21f)
    val room = zoom * 0.42f * amount
    return Triple(
        1f + zoom * amount,
        w * room * (0.7f * kotlin.math.sin(t * 0.13f) + 0.3f * kotlin.math.sin(t * 0.31f + 1.3f)),
        h * room * (0.7f * kotlin.math.cos(t * 0.11f) + 0.3f * kotlin.math.sin(t * 0.27f + 0.4f)),
    )
}

/** How much of the edge-to-edge backdrop's cover shows at morph [t]: gone well before the box settles. */
internal fun edgeCoverAlpha(t: Float): Float {
    val x = (t / 0.7f).coerceIn(0f, 1f)
    return 1f - x * x * (3f - 2f * x)
}

/** A rounded rectangle anywhere in a layer, as its outline: the box's cut, moved while drawing. */
private data class BoxOutline(val rect: Rect, val radius: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline =
        androidx.compose.ui.graphics.Outline.Rounded(androidx.compose.ui.geometry.RoundRect(rect, androidx.compose.ui.geometry.CornerRadius(radius)))
}

/** Measures the content at exactly [w] by [h] px and puts it at the parent's top left, taking no room there. */
private fun Modifier.placedPx(w: Int, h: Int): Modifier = layout { measurable, _ ->
    val p = measurable.measure(Constraints.fixed(w.coerceAtLeast(0), h.coerceAtLeast(0)))
    layout(0, 0) { p.place(0, 0) }
}

/** For tests: the last frame of the morph drawn, in the cover region's px. */
internal object VideoMorphProbe {
    @Volatile var last: MorphFrame? = null
}

/**
 * The music video in the cover's place.
 *
 * The box fills the layout's cover region: edge to edge, nearly the screen's width (14dp
 * short of each side) and the region's whole height; in the square layouts, as wide as the
 * cover and as tall as the picture needs. The picture fills it, cut to fit where its shape
 * differs a little, shown whole where cutting would lose too much ([videoBoxSpec]); never
 * stretched. The corners are a card's ([videoBoxCorner]).
 *
 * Turning the video on or off is one continuous morph driven by [morph] (0 the cover, 1
 * the box): the box starts exactly where and as the cover is drawn (the backdrop's
 * edge-to-edge cover, or the square), with the cover drawn in it, and its cut, the image in
 * it and its corners move together to the video's box, all while drawing, nothing laid out
 * again. The cover stays in the box until a frame of the picture has been drawn ([ready]),
 * however long the video takes to load, and only then does the picture fade in over it.
 * Going back, the picture fades back to the cover as the box grows into it again.
 *
 * The picture is a TextureView ([VideoOutput]): drawn by the UI's own renderer, it is cut
 * to the rounded corners like anything else, where a SurfaceView would ignore the clip.
 */
@Composable
internal fun VideoCoverPane(
    artworkUrl: String?,
    /** The picture's width over height; 0 while unknown, taken as 16:9 until it's known. */
    aspect: Float,
    showVideo: Boolean,
    /** 0 at the cover, 1 at the video's box; read while drawing. */
    morph: () -> Float,
    /** The layout's own cover corners, where it's a square. */
    coverCorner: androidx.compose.ui.unit.Dp,
    /** The box's corners at rest. */
    boxCorner: androidx.compose.ui.unit.Dp,
    /** The edge-to-edge layout: the box fills the region and grows out of the backdrop's cover. */
    edgeToEdge: Boolean,
    /** Where the edge-to-edge cover sits, once measured; null before (the square is taken then). */
    edgeCover: EdgeCoverAt?,
    isPlaying: Boolean,
    animate: Boolean,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    modifier: Modifier = Modifier,
    /** A frame of the picture has been drawn; read while composing effects, not each frame. */
    ready: () -> Boolean = { true },
    /** Where the box sits in the root at rest, each time it's laid out. */
    onBox: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    /** Sends the picture into a small window over other apps, when offered. */
    onPictureInPicture: (() -> Unit)? = null,
    /** Opens the picture on the whole screen, when offered. */
    onFullScreen: (() -> Unit)? = null,
    /** Lets a playing picture grow out of its box and fill the whole pane. */
    grow: Boolean = false,
    /** Extra width the grown picture takes on each side, out of the pane. */
    growBleed: androidx.compose.ui.unit.Dp = 0.dp,
    /** The cover's slow drift and zoom, as the layout's own cover has it, so the box starts on the same frame of it. */
    moving: Boolean = false,
    artworkMotion: com.opentune.ui.components.ArtworkMotion? = null,
    /** The picture, filling its box cut to fit with crop, or whole. */
    picture: @Composable androidx.compose.foundation.layout.BoxScope.(crop: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    // Smaller while paused, as the cover is, so going between the two never jumps.
    val scale = androidx.compose.animation.core.animateFloatAsState(
        if (isPlaying || !animate) 1f else 0.86f,
        androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = androidx.compose.animation.core.Spring.StiffnessLow),
        label = "videoBoxScale",
    )
    // With grow on, a picture that keeps playing for a few seconds fills the
    // whole pane, the way it did before the box; a pause shrinks it back.
    val grownState = remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(grow, isPlaying) {
        grownState.value = if (grow && isPlaying) {
            kotlinx.coroutines.delay(3000)
            true
        } else false
    }
    val growF = androidx.compose.animation.core.animateFloatAsState(
        if (grownState.value) 1f else 0f,
        if (animate) androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing) else androidx.compose.animation.core.snap(),
        label = "videoGrow",
    )
    // The picture over the cover: in once a frame of it is drawn, out as the song comes back.
    // Already in only when the box opens settled on a picture that's drawn (the player opened
    // on the video); turning the video on, the cover always shows first.
    val pictureIn = remember { androidx.compose.animation.core.Animatable(if (showVideo && ready() && morph() >= 1f) 1f else 0f) }
    val latestReady by androidx.compose.runtime.rememberUpdatedState(ready)
    androidx.compose.runtime.LaunchedEffect(showVideo, animate) {
        if (!showVideo) {
            if (animate) pictureIn.animateTo(0f, androidx.compose.animation.core.tween(VIDEO_PICTURE_OUT_MS)) else pictureIn.snapTo(0f)
            return@LaunchedEffect
        }
        androidx.compose.runtime.snapshotFlow { latestReady() }.collectLatest { drawn ->
            val to = if (drawn) 1f else 0f
            if (!animate) pictureIn.snapTo(to)
            else pictureIn.animateTo(to, androidx.compose.animation.core.tween(if (drawn) VIDEO_PICTURE_IN_MS else VIDEO_PICTURE_OUT_MS))
        }
    }
    val pictureOn by remember(showVideo) { androidx.compose.runtime.derivedStateOf { showVideo || pictureIn.value > 0f } }
    // Under a picture that covers it, the cover's drift would only redraw what nobody sees.
    val coverHidden by remember { androidx.compose.runtime.derivedStateOf { pictureIn.value >= 1f } }
    // The picture's own buttons come with it, not over the cover of a video still loading.
    val pictureSeen by remember { androidx.compose.runtime.derivedStateOf { pictureIn.value > 0f } }
    ArtworkSwipeArea(onSwipeNext = onSwipeNext, onSwipePrevious = onSwipePrevious, modifier = modifier) {
        BoxWithConstraints(Modifier.matchParentSize()) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val bleed = if (edgeToEdge) with(density) { VIDEO_BOX_BLEED.toPx() } else 0f
            val spec = videoBoxSpec(w + 2f * bleed, h, aspect, fill = edgeToEdge)
            val box = Rect(Offset((w - spec.width) / 2f, (h - spec.height) / 2f), Size(spec.width, spec.height))
            // A size learnt late (the picture's shape known only after the morph began) eases there too.
            val boxNow = androidx.compose.animation.core.animateValueAsState(
                box, Rect.VectorConverter,
                if (animate) androidx.compose.animation.core.tween(VIDEO_BOX_MS, easing = VIDEO_BOX_EASING) else androidx.compose.animation.core.snap(),
                label = "videoBox",
            )
            val cover = if (edgeToEdge && edgeCover != null) edgeCoverPlace(edgeCover)
            else squareCoverPlace(w, h, with(density) { coverCorner.toPx() })
            // Grown: a picture cut to fit reaches the screen's edges at the box's height; a
            // whole wide one at its own shape; a tall one keeps its box.
            val growPx = with(density) { growBleed.toPx() }
            val grown = when {
                spec.crop -> Rect(-growPx, box.top, w + growPx, box.bottom)
                spec.width >= spec.height -> {
                    val gh = (w + 2f * growPx) * spec.height / spec.width
                    Rect(-growPx, h / 2f - gh / 2f, w + growPx, h / 2f + gh / 2f)
                }
                else -> box
            }
            val cornerPx = with(density) { boxCorner.toPx() }
            val pivot = Offset(w / 2f, h / 2f)
            val frame: () -> MorphFrame = {
                val g = growF.value
                val t = morph().coerceIn(0f, 1f)
                val target = androidx.compose.ui.geometry.lerp(boxNow.value, grown, g)
                // The edge-to-edge cover never shrinks on a pause; the box does, as it comes in.
                val k = if (edgeToEdge) 1f + (scale.value - 1f) * t else scale.value
                morphFrame(cover, target, cornerPx * (1f - g), t).scaledAbout(k, pivot)
            }
            // Everything the box may be cut to, so its cut always lies inside its own layer.
            val reach = maxOf(bleed, growPx)
            val stage = Rect(
                minOf(cover.clip.left, -reach, grown.left), minOf(cover.clip.top, 0f, grown.top),
                maxOf(cover.clip.right, w + reach, grown.right), maxOf(cover.clip.bottom, h, grown.bottom),
            )
            val sx = kotlin.math.floor(stage.left)
            val sy = kotlin.math.floor(stage.top)
            val stageW = kotlin.math.ceil(stage.right - sx).toInt()
            val stageH = kotlin.math.ceil(stage.bottom - sy).toInt()
            val side = kotlin.math.ceil(maxOf(cover.image.width, box.width, box.height, 1f)).toInt()
            val shadow = with(density) { 16.dp.toPx() }
            Box(
                Modifier
                    .layout { measurable, c ->
                        val p = measurable.measure(Constraints.fixed(stageW, stageH))
                        layout(c.maxWidth, c.maxHeight) { p.place(sx.toInt(), sy.toInt()) }
                    }
                    .graphicsLayer {
                        val f = frame()
                        VideoMorphProbe.last = f
                        shape = BoxOutline(f.clip.translate(-sx, -sy), f.corner)
                        clip = true
                        // The cover's own soft shadow: the square cover casts it already, the
                        // edge-to-edge one doesn't, so there it comes in with the box.
                        shadowElevation = shadow * if (edgeToEdge) morph().coerceIn(0f, 1f) else 1f
                        ambientShadowColor = Color.Black.copy(alpha = 0.22f)
                        spotShadowColor = Color.Black.copy(alpha = 0.16f)
                    },
            ) {
                // The picture, laid out once at the box's size and carried to the box's cut on
                // its way, filling it centred. Always drawn whole, under the cover: the cover
                // fades off it rather than it fading in, so the view is drawn (and hears its
                // frames) from the moment it's placed, and no buffer is made to fade a video.
                if (pictureOn) Box(
                    Modifier
                        .placedPx(spec.width.roundToInt(), spec.height.roundToInt())
                        .graphicsLayer {
                            val c = frame().clip
                            val s = maxOf(c.width / spec.width.coerceAtLeast(1f), c.height / spec.height.coerceAtLeast(1f))
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                            scaleX = s
                            scaleY = s
                            translationX = c.center.x - spec.width * s / 2f - sx
                            translationY = c.center.y - spec.height * s / 2f - sy
                        },
                    contentAlignment = Alignment.Center,
                ) { picture(spec.crop) }
                // The cover over it, where the layout draws it to begin with: what the box
                // starts as, and what shows in it until the picture's first frame, however
                // long the video takes to load. Its image's square always covers the cut.
                // Drawn where it belongs on a layer the stage's own size, never a moved layer
                // of its own, so it can't be cut to where such a layer was laid out.
                val context = LocalContext.current
                val coverUrl = artworkUrl.artworkAt(com.opentune.data.model.PLAYER_ART_PX)
                val coverPainter = coil3.compose.rememberAsyncImagePainter(
                    remember(coverUrl, side) { coil3.request.ImageRequest.Builder(context).data(coverUrl).size(side, side).build() },
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                val drift = artworkMotion.takeIf { moving && !coverHidden }
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            alpha = 1f - pictureIn.value
                            // Faded draw by draw, not through a buffer of its own on each frame.
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                        }
                        .drawBehind {
                            if (pictureIn.value >= 1f) return@drawBehind
                            val i = frame().image.translate(-sx, -sy)
                            // The cover's slow drift and zoom, as livingArt moves the layout's own
                            // cover, so the box starts on the very same frame of it.
                            val (zoom, dx, dy) = drift?.let { coverDrift(it, i.width, i.height) } ?: Triple(1f, 0f, 0f)
                            val w = i.width * zoom
                            val r = Rect(Offset(i.center.x - w / 2f + dx, i.center.y - w / 2f + dy), Size(w, w))
                            clipRect(i.left, i.top, i.right, i.bottom) {
                                translate(r.left, r.top) { with(coverPainter) { draw(r.size) } }
                            }
                            // The backdrop's shade under the status bar, as the edge-to-edge cover has it, gone as the box leaves.
                            val shade = if (edgeToEdge) 1f - morph().coerceIn(0f, 1f) else 0f
                            if (shade > 0f) drawRect(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = com.opentune.ui.components.FULL_COVER_TOP_SHADE * shade),
                                    0.2f to Color.Transparent,
                                    startY = i.top, endY = i.bottom,
                                ),
                                topLeft = Offset(i.left, i.top), size = Size(i.width, i.height * 0.2f),
                            )
                        },
                )
            }
            // The box's place at rest: what tests and the cover's flight read, and the buttons' anchor.
            Box(
                Modifier
                    .layout { measurable, c ->
                        val p = measurable.measure(Constraints.fixed(box.width.roundToInt(), box.height.roundToInt()))
                        layout(c.maxWidth, c.maxHeight) { p.place(box.left.roundToInt(), box.top.roundToInt()) }
                    }
                    .onGloballyPositioned { c -> if (c.isAttached) onBox(c.boundsInRoot()) }
                    .testTag("video-cover-box"),
            ) {
                // Over the picture, in its bottom corners: the whole screen always, and the
                // small window only while the picture plays (a paused picture in a small
                // window is a frozen window). They ride the box's corners as it moves.
                if (showVideo && pictureSeen && aspect > 0f && (onPictureInPicture != null || onFullScreen != null)) {
                    val buttonScale = (with(density) { minOf(spec.width, spec.height).toDp().value } / 190f).coerceIn(0.85f, 1.3f)
                    val shown: androidx.compose.ui.graphics.GraphicsLayerScope.(Boolean) -> Unit = { end ->
                        val c = frame().clip
                        translationX = if (end) c.right - box.right else c.left - box.left
                        translationY = c.bottom - box.bottom
                        // With the picture: not over the cover while the video still loads.
                        alpha = ((morph() - 0.7f) / 0.3f).coerceIn(0f, 1f) * pictureIn.value
                    }
                    if (isPlaying && onPictureInPicture != null) Box(
                        Modifier.align(Alignment.BottomStart).graphicsLayer { shown(this, false) }.padding(10.dp),
                    ) {
                        PaneButton(buttonScale) {
                            PictureInPictureButton(onPictureInPicture, tint = Color.White)
                        }
                    }
                    if (onFullScreen != null) Box(
                        Modifier.align(Alignment.BottomEnd).graphicsLayer { shown(this, true) }.padding(10.dp),
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
