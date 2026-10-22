package com.opentune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The now-playing cover growing out of the mini player into the full player as
 * it opens, and shrinking back into it on close. The mini player reports where
 * its cover sits ([miniLanding]); the player reports where its own cover lands
 * ([playerLanding]); [startOpen] and [startClose] run the flight over the top
 * while the player fades in or out behind it. A swipe drives the same flight by
 * hand: up on the mini player ([pullOpen]) or down on the player ([dragClose]),
 * the cover following the finger, and the flight finishes from wherever the
 * finger let go. Always on unless motion is reduced.
 */
object PlayerMorph {
    internal var miniCover by mutableStateOf<Rect?>(null)
    internal var playerCover by mutableStateOf<Rect?>(null)
    /** Where the player's cover sat the last time it was open, for a swipe up before it's there. */
    internal var lastPlayerCover: Rect? = null
    /** How much the player's cover is scaled about its centre (smaller while paused). */
    internal var playerCoverScale = 1f

    var expanding by mutableStateOf(false)
        private set
    var collapsing by mutableStateOf(false)
        private set
    /** 0..1 while a finger pulls the mini player up; 0 otherwise. */
    var pulling by mutableFloatStateOf(0f)
        private set
    /** 0..1 while a finger drags the open player down; 0 otherwise. */
    var dragging by mutableFloatStateOf(0f)
        private set
    /** True while the cover flies between the big cover and the small one in the player's lyrics and queue header. */
    var flying by mutableStateOf(false)
    /** Whether the player's own cover should hide, the flying one standing in for it. */
    val coverAway: Boolean get() = expanding || collapsing || dragging > 0f || flying

    private var openFrom: Rect? = null
    private var closeFrom: Rect? = null
    private var openAt = 0f
    private var closeAt = 0f
    private var round = false
    /** Where the cover was when the drag down began, before the player moved with it. */
    private var dragRest: Rect? = null

    /** On the mini player's cover, so a flight knows where it starts and lands. */
    fun Modifier.miniLanding(): Modifier = onGloballyPositioned { miniCover = it.boundsInRoot() }

    // ---- The whole player out of the mini player and back (see [PlayerSheet]) ----

    /** Where the whole mini player sits, for the player to grow out of and shrink back into. */
    internal var miniBar by mutableStateOf<Rect?>(null)
    /** Whether that mini player is the round, folded one. */
    internal var miniBarRound = false

    /**
     * The player card's progress (0 on the mini player, 1 filling the screen)
     * while it's on screen, read frame by frame; null when it isn't.
     */
    internal var sheetProgress by mutableStateOf<(() -> Float)?>(null)

    /**
     * On the real mini player: hidden while the card is over it, so the card
     * is what's seen turning into it, and coming in only over the last
     * [MINI_HANDOVER] of the card's way, as the card lands on it with the
     * same face (see PlayerSheet).
     */
    fun Modifier.miniUnderCard(): Modifier = graphicsLayer {
        val p = sheetProgress?.invoke()
        alpha = if (p == null) 1f else ((MINI_HANDOVER - p) / MINI_HANDOVER).coerceIn(0f, 1f)
    }

    /** On the mini player itself, outside any swipe offset. */
    fun Modifier.miniBarLanding(round: Boolean): Modifier = onGloballyPositioned {
        miniBar = it.boundsInRoot()
        miniBarRound = round
    }

    /**
     * True while the whole player is the thing that moves: it grows out of the
     * mini player as one card, cover and all, and shrinks back into it. The
     * cover's own flight above is then not used.
     */
    var sheetMorph by mutableStateOf(false)
    /** How far a pull up had taken the card when the player was let open. */
    internal var sheetAt by mutableFloatStateOf(0f)

    /**
     * Pixels a finger has dragged the open player down, while [sheetMorph]:
     * the card shrinks toward the mini player under the finger, and when let
     * go the shrink carries on from there at [sheetFling] (card heights per
     * second, negative toward the mini player).
     */
    var sheetDrag by mutableFloatStateOf(0f)
    var sheetFling = 0f
    /** Where the player's card was when a drag down let go, in the root, and its corner in px. */
    internal var cardFrom by mutableStateOf<Rect?>(null)
    internal var cardCornerPx = 0f

    /** The player opens from the mini player, carrying on from any pull. */
    fun openSheet() {
        sheetAt = pulling
        pulling = 0f
    }

    /** A drag down let the player go at [card]: the shrink starts from there. */
    fun handOff(card: Rect, cornerPx: Float) {
        cardFrom = card
        cardCornerPx = cornerPx
    }

    /** On the full player's cover, so a flight knows where it lands and leaves from. */
    fun Modifier.playerLanding(): Modifier = onGloballyPositioned { r ->
        val b = r.boundsInRoot()
        playerCover = b
        playerLaidOut = true
        if (dragging == 0f && !collapsing) lastPlayerCover = b
    }

    /** False from an open until the opening player reports its cover. */
    private var playerLaidOut = true
    internal fun awaitingPlayer() = !playerLaidOut

    /** Begins the grow from the mini cover; [round] when it's the circular one. */
    fun startOpen(round: Boolean) {
        val at = pulling
        pulling = 0f
        playerLaidOut = false
        openFrom = miniCover ?: return
        this.round = round
        openAt = at
        expanding = true
    }

    /** Begins the shrink back into the mini cover, captured before the player leaves. */
    fun startClose() {
        val from = dragRest ?: playerCover
        if (from == null || miniCover == null) {
            // Nothing to fly between: don't leave the player's cover hidden behind a drag.
            dragging = 0f; dragRest = null
            return
        }
        closeFrom = from
        closeAt = dragging
        collapsing = true
    }

    /**
     * The mini player pulled up by [px] so far: the cover grows toward where
     * the player's cover will be, as far as the finger has gone. [round] for
     * the folded, circular mini player.
     */
    fun pullOpen(px: Float, round: Boolean, screenHeight: Float) {
        // The player is already on its way; a late pull mustn't hang a second cover over it.
        if (expanding || collapsing) return
        val from = miniCover ?: return
        this.round = round
        val to = lastPlayerCover
        val travel = when {
            // The whole card follows: its top goes from the mini player's to the screen's.
            sheetMorph -> (miniBar ?: from).top.coerceAtLeast(1f)
            to != null -> (from.top - to.top).coerceAtLeast(1f)
            else -> screenHeight * 0.6f
        }
        pulling = (px / travel).coerceIn(0f, 1f)
    }

    /** The pull let go short of opening: the cover goes back to the mini player. */
    fun endPull() { pulling = 0f }

    /** The open player dragged down by [px]: the cover shrinks toward the mini player with the finger. */
    fun dragClose(px: Float) {
        if (px <= 0f) {
            if (!collapsing) { dragging = 0f; dragRest = null }
            return
        }
        val rest = dragRest ?: playerCover?.let(::scaled)?.also { dragRest = it } ?: return
        val to = miniCover ?: return
        dragging = (px / (to.center.y - rest.center.y).coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    /** The player's cover as drawn: its laid-out box scaled about its centre. */
    internal fun scaled(laid: Rect): Rect {
        val k = playerCoverScale
        return Rect(
            laid.center.x - laid.width * k / 2f, laid.center.y - laid.height * k / 2f,
            laid.center.x + laid.width * k / 2f, laid.center.y + laid.height * k / 2f,
        )
    }

    internal fun openFrom() = openFrom
    internal fun closeFrom() = closeFrom
    internal fun openAt() = openAt
    internal fun closeAt() = closeAt
    internal fun dragRest() = dragRest
    internal fun round() = round
    internal fun endOpen() { expanding = false; openFrom = null; openAt = 0f }
    internal fun endClose() { collapsing = false; closeFrom = null; closeAt = 0f; dragging = 0f; dragRest = null }
}

/**
 * Draws the cover between [from] and [to] (p 0..1) over everything. It's laid
 * out once at the larger of the two sizes and only moved, scaled, clipped and
 * shadowed in its layer, so each frame of the flight is a redraw on the render
 * thread: no recomposition, no new layout, no image asked for at a new size.
 */
@Composable
internal fun MorphBox(
    url: String?, smallUrl: String?, from: Rect, to: Rect, p: () -> Float, startCornerDp: Float, endCornerDp: Float,
    /** Where the box's parent sits in the root, [from] and [to] being root bounds. */
    origin: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,
) {
    val big = if (from.width >= to.width) from else to
    val small = if (big === from) to else from
    if (big.width <= 0f || big.height <= 0f) return
    val density = LocalDensity.current
    val shadowPx = with(density) { 28.dp.toPx() }
    val startCorner = with(density) { startCornerDp.dp.toPx() }
    val endCorner = with(density) { endCornerDp.dp.toPx() }
    Box(
        Modifier
            .size(with(density) { big.width.toDp() }, with(density) { big.height.toDp() })
            .graphicsLayer {
                val t = p()
                fun lerp(a: Float, b: Float) = a + (b - a) * t
                val w = lerp(from.width, to.width)
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = lerp(from.left, to.left) - origin.x
                translationY = lerp(from.top, to.top) - origin.y
                scaleX = w / big.width
                scaleY = lerp(from.height, to.height) / big.height
                // The corner as it should look on screen, undone by the scale.
                shape = RoundedCornerShape(lerp(startCorner, endCorner) / scaleX.coerceAtLeast(0.01f))
                clip = true
                // Deepest at full size, none on the small cover.
                val grown = if (big.width > small.width) (w - small.width) / (big.width - small.width) else 1f
                shadowElevation = shadowPx * grown.coerceIn(0f, 1f)
            },
    ) {
        // The mini player's own copy underneath, already loaded, so it never shows empty.
        if (smallUrl != null) Artwork(smallUrl, Modifier.matchParentSize(), RectangleShape)
        Artwork(url, Modifier.matchParentSize(), RectangleShape)
    }
}

/** The flight, over everything: grows the cover on open, shrinks it on close, and follows a swipe either way. */
@Composable
fun CoverMorph(url: String?, smallUrl: String? = null) {
    val openT = remember { Animatable(0f) }
    LaunchedEffect(PlayerMorph.expanding) {
        if (!PlayerMorph.expanding) return@LaunchedEffect
        try {
            // From wherever a pull up left it.
            openT.snapTo(PlayerMorph.openAt())
            // Hold until the player has been composed and laid out: that first
            // frame is the heavy one, and a flight already running through it
            // would skip ahead. Then it starts from a clean frame.
            var frames = 0
            while (PlayerMorph.awaitingPlayer() && frames++ < 12) withFrameNanos { }
            if (PlayerMorph.playerCover == null && PlayerMorph.lastPlayerCover == null) return@LaunchedEffect
            val left = 1f - openT.value
            // On the card's own timing and curve (see PlayerSheet), so the cover and the card move as one.
            openT.animateTo(1f, tween((SHEET_OPEN_MS * left).roundToInt().coerceAtLeast(180), easing = SHEET_EASING))
        } finally {
            PlayerMorph.endOpen()
        }
    }
    val closeT = remember { Animatable(0f) }
    LaunchedEffect(PlayerMorph.collapsing) {
        if (!PlayerMorph.collapsing) return@LaunchedEffect
        try {
            // From wherever a drag down left it.
            closeT.snapTo(PlayerMorph.closeAt())
            val left = 1f - closeT.value
            closeT.animateTo(1f, tween((SHEET_CLOSE_MS * left).roundToInt().coerceAtLeast(160), easing = SHEET_EASING))
        } finally {
            PlayerMorph.endClose()
        }
    }
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    // Where the player's cover will sit before it has ever been laid out: a
    // square across the screen below the top bar, as the classic layout has it.
    val guess = with(density) {
        val w = config.screenWidthDp.dp.toPx()
        val side = w - 48.dp.toPx()
        val top = 150.dp.toPx()
        Rect(24.dp.toPx(), top, 24.dp.toPx() + side, top + side)
    }
    fun roundStart(from: Rect) = if (PlayerMorph.round()) with(density) { from.width.toDp().value / 2f } else 12f
    // Only whether a finger is on it decides what's drawn; how far it has gone
    // is read in the layer alone, so a swipe doesn't recompose this every frame.
    val pulled by remember { derivedStateOf { PlayerMorph.pulling > 0f } }
    val dragged by remember { derivedStateOf { PlayerMorph.dragging > 0f } }
    when {
        PlayerMorph.expanding -> {
            val from = PlayerMorph.openFrom() ?: return
            val to = (PlayerMorph.playerCover ?: PlayerMorph.lastPlayerCover)?.let(PlayerMorph::scaled) ?: guess
            MorphBox(url, smallUrl, from, to, { openT.value }, startCornerDp = roundStart(from), endCornerDp = 28f)
        }
        PlayerMorph.collapsing -> {
            val from = PlayerMorph.closeFrom() ?: return
            val to = PlayerMorph.miniCover ?: return
            MorphBox(url, smallUrl, from, to, { closeT.value }, startCornerDp = 28f, endCornerDp = 12f)
        }
        pulled -> {
            val from = PlayerMorph.miniCover ?: return
            val to = PlayerMorph.lastPlayerCover?.let(PlayerMorph::scaled) ?: guess
            MorphBox(url, smallUrl, from, to, { PlayerMorph.pulling }, startCornerDp = roundStart(from), endCornerDp = 28f)
        }
        dragged -> {
            val from = PlayerMorph.dragRest() ?: return
            val to = PlayerMorph.miniCover ?: return
            MorphBox(url, smallUrl, from, to, { PlayerMorph.dragging }, startCornerDp = 28f, endCornerDp = 12f)
        }
    }
}

/** The last stretch of the card's way (0..1) over which it hands over to the real mini player. */
internal const val MINI_HANDOVER = 0.06f
