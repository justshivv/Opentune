package com.opentune.ui.components

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeProgressive
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import com.opentune.ui.theme.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.opentune.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.GlassStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

/** The content layer that floating glass blurs, provided by the app shell. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * A tinted page's wash: its colour at the top, the background its gradient
 * eases toward, and the cover's second colour glowing in from the side.
 * Provided by the page's tint wrapper, so a bar frosted over the page can
 * paint the same backdrop instead of guessing from one flat colour.
 * Compared by its colours: it's handed down as a fixed value, and a new
 * but equal one would build the whole page again.
 */
data class PageWash(val tint: Color, val base: Color, val glow: Color)

val LocalPageWash = staticCompositionLocalOf<PageWash?> { null }

/**
 * The layer Liquid Glass refracts. Only glass drawn outside that layer gets
 * one (the nav pill, mini player, player buttons, and a page header's buttons,
 * which refract the page's background and picture recorded on their own; see
 * PageButtons.kt): glass inside the layer it samples would sample itself.
 */
val LocalBackdrop = staticCompositionLocalOf<com.kyant.backdrop.Backdrop?> { null }

/** Lens refraction needs runtime shaders, which arrived in Android 13. */
val liquidGlassSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Blurring a recorded layer needs RenderEffect, which arrived in Android 12. */
val backdropBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Whether glass blurs a recorded copy of what's behind it: Android 12+, blur not reduced. */
@Composable
fun backdropBlurOn(): Boolean {
    val ui by AppSettings.ui.collectAsState()
    return backdropBlurSupported && !ui.reduceBlur
}

/** Whether glass can be Liquid Glass: both glass styles are, on Android 13+ with blur not reduced. */
@Composable
fun liquidGlassOn(): Boolean {
    val ui by AppSettings.ui.collectAsState()
    return liquidGlassSupported && !ui.reduceBlur
}

/** How much Frosted's Liquid Glass frosts what's behind it, so colours read as soft fields, not detail. */
private val FROSTED_LIQUID_BLUR = 18.dp

/** Glass with no lens (Android 12, or nothing recorded to bend): the clearest frost, for both styles. */
private val FROST_BLUR = 20.dp
private const val FROST_FILM = 0.30f
private const val FROST_SATURATION = 2.0f

/**
 * Glass for floating controls, as Settings › Glass style asks, in three grades:
 *
 * - Liquid Glass (Android 13+): the content behind bent at the edges by a lens, with a lit
 *   rim. The Liquid Glass style is the web player's ([webLiquidGlass]): clear, lightly blurred
 *   and brightened. Frosted (once called Liquid) is Apple's material, softly frosted and
 *   lifted by vibrancy.
 * - Frosted (Android 12, or Android 13+ where nothing is recorded to bend): a recorded copy
 *   of what's behind, blurred and tinted; through Haze where only that is provided (older
 *   phones too).
 * - Solid: with "Reduce dynamic blur" on, or inside the very layer glass
 *   would sample (a page's own buttons), where there's nothing to blur.
 *
 * Every grade keeps a hairline edge so the shape reads on any background. A see-through
 * [tint] is the glass's film; an opaque one is the colour a frost is washed with.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    touchResponse: Boolean = true,
    swell: Boolean = true,
): Modifier {
    val ui by AppSettings.ui.collectAsState()
    val liquidOn = liquidGlassOn()
    val haze = LocalHazeState.current
    val backdrop = LocalBackdrop.current
    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    // A caller's own see-through tint is a frost's film; an opaque one is laid on thin.
    val frostFilm = if (tint.alpha < 1f) tint else tint.copy(alpha = FROST_FILM)
    // On a phone whose frames ran late under Liquid Glass, its two costliest touches are left out.
    val lite = GlassPerformance.lite
    // Liquid Glass answers a finger: it swells a little toward it, settles back with a small
    // wobble, and a pool of light follows the touch. Left out in Lite mode and with motion reduced.
    val touch = remember { LiquidTouch() }
    val liquid = backdrop != null && liquidOn
    val live = liquid && !lite && !ui.reduceAnimation && touchResponse
    val press by animateFloatAsState(
        if (live && touch.down) 1f else 0f,
        spring(dampingRatio = 0.5f, stiffness = 320f),
        label = "liquidPress",
    )
    val body = if (live) this.liquidTouch(touch, swell) { press } else this
    if (backdrop != null && liquidOn) {
        androidx.compose.runtime.DisposableEffect(Unit) {
            GlassPerformance.glassShowing++
            onDispose { GlassPerformance.glassShowing-- }
        }
    }
    if (backdrop != null && liquidOn && ui.glassStyle == GlassStyle.WEB) {
        // The web player's Liquid Glass, as the Poster layout's buttons have it, under the
        // caller's own film or the clear one; and the light under a finger, as Frosted has.
        val film = if (tint.alpha < 1f) tint else webFilm(dark)
        return body.webLens(backdrop, shape, film, dark, lite) { touchLight(touch, press, dark) }
    }
    if (backdrop != null && liquidOn) {
        // Frosted, Apple's material: what's behind is softly frosted, so colours read
        // as glowing fields rather than detail; a deep lens at the edges that
        // bends it and splits the light into a colour fringe; vibrancy so the
        // colours glow through; a bright specular rim and a soft inner shade
        // that give the glass depth.
        val film = if (tint.alpha < 1f) tint else (if (dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.2f))
        return body.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(FROSTED_LIQUID_BLUR.toPx())
                // A wide, deep lens: content bends visibly for the last stretch
                // before the edge, with the light split into a fringe there. Deep
                // enough that the glass reads as thick, like BitChord's, but never
                // past the middle of a short pill, where the top and bottom bands
                // would meet in a hard line across it.
                val depth = lensDepth(30.dp.toPx(), size.minDimension)
                lens(depth, 62.dp.toPx() * depth / 30.dp.toPx(), depthEffect = true, chromaticAberration = !lite)
            },
            highlight = { Highlight.Default },
            shadow = { Shadow.Default },
            innerShadow = if (lite) null else ({ InnerShadow.Default }),
            onDrawSurface = {
                drawRect(film)
                // Light falling across the top of the glass, fading out by its middle: the
                // sheen that makes Apple's glass read as a solid, curved piece.
                drawRect(Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.10f else 0.18f),
                    0.5f to Color.Transparent,
                ))
                // And light gathering at the lower edge, where a thick piece of glass catches it.
                drawRect(Brush.verticalGradient(
                    0.72f to Color.Transparent,
                    1f to Color.White.copy(alpha = if (dark) 0.07f else 0.12f),
                ))
                touchLight(touch, press, dark)
            },
        ).border(
            1.1.dp,
            // A lit rim, brightest where light would catch it (top left and bottom
            // right). Strong, like the bright bevel on BitChord's Liquid Glass.
            Brush.linearGradient(
                0f to Color.White.copy(alpha = if (dark) 0.6f else 0.85f),
                0.45f to Color.White.copy(alpha = if (dark) 0.14f else 0.3f),
                1f to Color.White.copy(alpha = if (dark) 0.44f else 0.65f),
            ),
            shape,
        )
    }
    if (backdrop != null && backdropBlurOn()) {
        // Frosted: the content behind, blurred and a little saturated, under
        // the film. No lens, so nothing bends, but it is the real
        // content, moving as it scrolls.
        return this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                colorControls(saturation = FROST_SATURATION)
                blur(FROST_BLUR.toPx())
            },
            shadow = { Shadow.Default },
            onDrawSurface = { drawRect(frostFilm) },
        ).border(0.75.dp, edge, shape)
    }
    val base = this.clip(shape)
    val filled = if (haze == null || ui.reduceBlur) {
        // Nothing blurred behind (Lite mode, blur reduced): a see-through tint, meant to sit on
        // frosted glass, is laid over the solid surface instead, so the mini player and the dock
        // have a body rather than letting the page show through them.
        val face = if (tint.alpha < 1f) lerp(MaterialTheme.colorScheme.surfaceContainerHigh, tint.copy(alpha = 1f), tint.alpha) else tint
        base.background(face)
    } else {
        base.hazeEffect(
            state = haze,
            style = HazeStyle(
                backgroundColor = MaterialTheme.colorScheme.background,
                tint = HazeTint(frostFilm),
                blurRadius = FROST_BLUR,
                noiseFactor = 0f,
            ),
        )
    }
    return filled.border(0.75.dp, edge, shape)
}

/**
 * A page whose [overlay] (a top bar, floating buttons) is glass over the
 * page's own [content]. Glass drawn inside the layer it samples gets nothing
 * to blur, so the content is recorded here on its own and the overlay,
 * outside that recording, blurs and bends it.
 */
@Composable
fun GlassPage(modifier: Modifier = Modifier, overlay: @Composable BoxScope.() -> Unit, content: @Composable BoxScope.() -> Unit) {
    val ui by AppSettings.ui.collectAsState()
    val haze = rememberHazeState()
    val backdrop = rememberLayerBackdrop()
    val sampled = backdropBlurOn()
    Box(modifier) {
        Box(
            Modifier.fillMaxSize()
                .then(if (sampled) Modifier.layerBackdrop(backdrop) else Modifier)
                // Inside the recording, so the glass never sees through to the sharp page.
                .background(MaterialTheme.colorScheme.background)
                .hazeSource(haze),
            content = content,
        )
        CompositionLocalProvider(
            LocalHazeState provides haze.takeIf { !ui.reduceBlur },
            LocalBackdrop provides backdrop.takeIf { sampled },
        ) {
            // The page's own frost, under its overlay: drawn at the root it
            // would sit over the whole NavHost and blur the top bar itself,
            // so AppRoot skips its strip on these pages.
            TopEdgeFrost(Modifier.align(Alignment.TopCenter))
            // The status bar's solid backdrop too, when Settings ask for it:
            // the navigation bar's is drawn at the root, behind the chrome.
            com.opentune.ui.theme.SystemBarBackdrops(top = true, bottom = false)
            overlay()
        }
    }
}

/** Where a finger is on a piece of Liquid Glass, and whether it's down. */
@Stable
internal class LiquidTouch {
    var down by mutableStateOf(false)
    var at by mutableStateOf(Offset.Unspecified)
}

/**
 * Watches touches on the glass without taking them (the buttons on it still get every tap), and
 * swells it toward the finger by [press], 0 at rest to 1 while held. [press] runs on a spring,
 * so letting go sends it a little past rest and back: the glass settles like a drop.
 */
private fun Modifier.liquidTouch(touch: LiquidTouch, swell: Boolean, press: () -> Float): Modifier {
    val tracked = this.pointerInput(touch) {
        awaitEachGesture {
            // The first pass sees the touch before anything on the glass does, and leaves it alone.
            val first = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
            touch.at = first.position
            touch.down = true
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val still = event.changes.firstOrNull { it.pressed }
                if (still == null) break
                touch.at = still.position
            }
            touch.down = false
        }
    }
    // A card that is itself dragged (the mini player) keeps the light under
    // the finger but not the swell: swelling a layer while it is being
    // pulled around draws it past its own face and shaves its corners.
    if (!swell) return tracked
    return tracked.graphicsLayer {
        val p = press()
        if (p == 0f || size.width <= 0f || size.height <= 0f) return@graphicsLayer
        // About 8 dp wider and 5 dp taller at most, whatever the size: a big bar swells no more than a small button.
        scaleX = 1f + minOf(LIQUID_SWELL, 8.dp.toPx() / size.width) * p
        scaleY = 1f + minOf(LIQUID_SWELL, 5.dp.toPx() / size.height) * p
        val at = touch.at
        // Anchored on the side away from the finger, so the glass grows
        // toward the finger and its middle follows it. Anchored at the
        // finger, the growth ran to the far side and the middle drifted
        // against the drag.
        if (at.isSpecified) transformOrigin = TransformOrigin(1f - (at.x / size.width).coerceIn(0f, 1f), 1f - (at.y / size.height).coerceIn(0f, 1f))
    }
}

private const val LIQUID_SWELL = 0.05f

/** A soft pool of light under the finger, as large as the glass, gone when the finger is. */
private fun DrawScope.touchLight(touch: LiquidTouch, press: Float, dark: Boolean) {
    val at = touch.at
    val strength = press.coerceIn(0f, 1f)
    if (strength < 0.01f || at.isUnspecified) return
    drawRect(
        Brush.radialGradient(
            0f to Color.White.copy(alpha = (if (dark) 0.30f else 0.42f) * strength),
            1f to Color.Transparent,
            center = at,
            radius = maxOf(size.width, size.height) * 0.62f,
        ),
    )
}

/** How deep a lens may bend at the edge: [wanted], kept to under half the shape's short side. */
private fun lensDepth(wanted: Float, shortSide: Float): Float = minOf(wanted, shortSide * 0.42f).coerceAtLeast(0f)

/**
 * The web player's Liquid Glass (web/app/src/lib/liquidGlass.ts and the
 * `.glass-pill[data-liquid]` rules): what's behind is bent strongly at the rim by a lens about
 * 22 dp deep, blurred only lightly (5 dp, so the bent colours stay sharp enough to read as
 * glass rather than frost), its colours lifted (saturation 1.8, a touch brighter), under a thin
 * [film] with a soft light across the top, and lit along the rim brightest at the top left.
 * It's the Liquid Glass glass style: [glass] draws it while that style is chosen, and so do the
 * Poster layout's [posterGlass]. Where the phone can't bend the backdrop it
 * falls back to the app's own [glass], frosted, under the same [film].
 */
@Composable
fun Modifier.webLiquidGlass(shape: Shape, film: Color): Modifier {
    val ui by AppSettings.ui.collectAsState()
    val backdrop = LocalBackdrop.current
    if (backdrop == null || !liquidGlassSupported || ui.reduceBlur) return this.glass(shape, tint = film, swell = false)
    val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val lite = GlassPerformance.lite
    androidx.compose.runtime.DisposableEffect(Unit) {
        GlassPerformance.glassShowing++
        onDispose { GlassPerformance.glassShowing-- }
    }
    return this.webLens(backdrop, shape, film, dark, lite)
}

/** The web player's clear film: barely there on a dark theme, a light one on a light theme. */
internal fun webFilm(dark: Boolean): Color = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.3f)

/** What [webLiquidGlass] draws, over [backdrop]; [overlay] is drawn on its face, over the film. */
private fun Modifier.webLens(
    backdrop: com.kyant.backdrop.Backdrop,
    shape: Shape,
    film: Color,
    dark: Boolean,
    lite: Boolean,
    overlay: DrawScope.() -> Unit = {},
): Modifier =
    this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(brightness = 0.04f, saturation = 1.8f)
            blur(5.dp.toPx())
            val depth = lensDepth(22.dp.toPx(), size.minDimension)
            lens(depth, depth * 2f, depthEffect = true, chromaticAberration = !lite)
        },
        highlight = { Highlight.Default },
        shadow = { Shadow.Default },
        onDrawSurface = {
            drawRect(film)
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.10f else 0.16f), 0.45f to Color.Transparent))
            overlay()
        },
    ).border(
        1.dp,
        Brush.linearGradient(
            0f to Color.White.copy(alpha = if (dark) 0.6f else 0.95f),
            0.5f to Color.White.copy(alpha = if (dark) 0.08f else 0.2f),
            1f to Color.White.copy(alpha = if (dark) 0.3f else 0.6f),
        ),
        shape,
    )

/**
 * A strip under the status bar where pages blur and fade out as they scroll
 * beneath it, strongest at the top edge and gone 28dp below the bar. With
 * blur reduced it's only the fade. Draws nothing when the setting is off.
 */
@Composable
fun TopEdgeFrost(modifier: Modifier = Modifier) {
    val ui by AppSettings.ui.collectAsState()
    if (!ui.frostedTopEdge) return
    val haze = LocalHazeState.current
    val page = MaterialTheme.colorScheme.background
    // On a washed page the strip takes the wash's top colour, not the flat
    // background, so it agrees with what the page paints under it.
    val edge = LocalPageWash.current?.tint ?: page
    val height = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 28.dp
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (haze == null || ui.reduceBlur) Modifier else Modifier.hazeEffect(haze) {
                    backgroundColor = edge
                    tints = emptyList()
                    blurRadius = 20.dp
                    noiseFactor = 0f
                    inputScale = HazeInputScale.Fixed(0.5f)
                    progressive = HazeProgressive.verticalGradient(easing = EaseOutCubic, startIntensity = 1f, endIntensity = 0f)
                },
            )
            .background(Brush.verticalGradient(0f to edge.copy(alpha = 0.6f), 0.55f to edge.copy(alpha = 0.25f), 1f to edge.copy(alpha = 0f))),
    )
}

/** A round glass button holding one icon, as used for back, menu and search. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    content: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // The Poster layout's clear glass, as on its pages' buttons.
        modifier = modifier.size(size).then(if (posterLayout()) Modifier.posterGlass(CircleShape) else Modifier.glass(CircleShape)),
    ) {
        // Drawn content of its own (the account's photo) says nothing to a screen reader: the
        // button is named for it, once, in its place.
        val named = if (content != null && contentDescription != null) {
            val said = com.opentune.i18n.Tr.t(contentDescription)
            Modifier.clearAndSetSemantics { this.contentDescription = said }
        } else Modifier
        Box(named, contentAlignment = Alignment.Center) {
            if (content != null) content() else Icon(icon, contentDescription, Modifier.size(size * 0.46f))
        }
    }
}

@Composable
fun GlassBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassIconButton(ImageVector.vectorResource(AppIcons.ArrowBack), "Back", onClick, modifier)
}

/** A page's top: optional glass back button and a large title. Draws under the status bar inset. */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)? = null, actions: (@Composable () -> Unit)? = null) {
    Column(
        Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) GlassBackButton(onBack) else Box(Modifier.size(56.dp))
            Box(Modifier.weight(1f))
            actions?.invoke()
        }
        Text(
            title,
            // The Poster layout's titles are heavy and set a little tight, as on its pages.
            style = if (posterLayout()) MaterialTheme.typography.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Black, letterSpacing = (-0.5).sp)
            else MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        )
    }
}

/**
 * What a raised lens over a control bends: the control itself, recorded, over the page's own
 * recording. Made by [rememberLensSource], recorded by [lensSource] and drawn by [raisedLens].
 */
class LensSource internal constructor(internal val layer: com.kyant.backdrop.backdrops.LayerBackdrop, internal val bent: com.kyant.backdrop.Backdrop)

/** A [LensSource], or null where a lens can't bend what's behind (before Android 13, blur off, Lite). */
@Composable
fun rememberLensSource(): LensSource? {
    val page = LocalBackdrop.current
    val layer = rememberLayerBackdrop()
    if (page == null || !liquidGlassOn() || GlassPerformance.lite) return null
    val bent = com.kyant.backdrop.backdrops.rememberCombinedBackdrop(page, layer)
    return remember(layer, bent) { LensSource(layer, bent) }
}

/** Records this control for a raised lens over it; nothing without a [source]. */
fun Modifier.lensSource(source: LensSource?): Modifier = if (source == null) this else this.layerBackdrop(source.layer)

/**
 * A raised piece of clear glass in [shape]: with a [source], what's under it swells and bends at
 * its rim with a colour fringe; without one, a clear face with a light sheen.
 */
fun Modifier.raisedLens(source: LensSource?, shape: Shape, dark: Boolean): Modifier =
    if (source != null) this.drawBackdrop(
        backdrop = source.bent,
        shape = { shape },
        effects = {
            val d = size.minDimension
            lens(d * 0.24f, d * 0.55f, depthEffect = true, chromaticAberration = true)
        },
        highlight = { Highlight.Default },
        onDrawSurface = {
            drawRect(Color.White.copy(alpha = if (dark) 0.03f else 0.10f))
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.10f else 0.18f), 0.45f to Color.Transparent))
        },
    ) else this.clip(shape).background(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.06f)))
    )
