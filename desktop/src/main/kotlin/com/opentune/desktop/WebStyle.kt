package com.opentune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.delay

/**
 * The web player's measurements (web/app/src/styles: tokens.css, with opentune.css's overrides,
 * which come later in the same layer and so win), so the desktop's pages read as the web's do.
 */
internal object Web {
    // Artwork and rows: opentune.css's radii, not tokens.css's smaller Apple ones.
    val ArtSmall = 12.dp
    val ArtMedium = 20.dp
    val ArtLarge = 28.dp
    val TrackRow = 14.dp

    /** The space between a shelf's cards. */
    val ShelfGap = 17.dp

    /** The sidebar's width, which the web's breakpoints count as part of the viewport. */
    val Sidebar = 260.dp

    // motion.css's curves.
    /** Material's emphasized decelerate, for things arriving. */
    val Emph = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** A quick settle with no overshoot: hovers, presses. */
    val Settle = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    /** tokens.css's --ease-out-panel. */
    val Panel = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

    /** The content pages' side margin: 20dp at any width, as the reference lays them out. */
    @Suppress("UNUSED_PARAMETER")
    fun gutter(pageWidth: Dp): Dp = PageGutter

    /** A page title's size, clamp(30px, 4vw, 44px) (opentune.css's --header-emphasized). */
    fun headerSize(pageWidth: Dp): Float = ((pageWidth + Sidebar).value * 0.04f).coerceIn(30f, 44f)
}

/** The page's side margin, set by the page around it; pages that don't set it keep the app's own. */
internal val LocalGutter = compositionLocalOf { GUTTER }

/** True while smooth motion is off in Settings: things then appear and change at once. */
@Composable
internal fun stillMotion(): Boolean = AppSettings.ui.collectAsState().value.reduceAnimation

/**
 * The web's type ramp as `font: weight size/line-height`, in the theme's own family and never
 * lighter than the weight Settings asks text to have at least.
 */
@Composable
internal fun webType(size: Float, line: Float, weight: Int = 400, tracking: Float = 0f): TextStyle {
    val base = MaterialTheme.typography.bodyMedium
    val floor = base.fontWeight?.weight ?: 400
    return base.copy(
        fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(maxOf(weight, floor)),
        letterSpacing = if (tracking == 0f) 0.sp else tracking.em,
    )
}

/** tokens.css's named type, with opentune.css's larger title-2. */
internal object WebType {
    val title2 @Composable get() = webType(22f, 28f, 700, -0.01f)
    val title3 @Composable get() = webType(15f, 20f)
    val title3Emph @Composable get() = webType(15f, 20f, 600)
    val largeTitle @Composable get() = webType(26f, 30f)
    val largeTitleEmph @Composable get() = webType(26f, 30f, 700)
    val body @Composable get() = webType(13f, 16f)
    val bodyEmph @Composable get() = webType(13f, 16f, 600)
    val bodyTall @Composable get() = webType(13f, 18f)
    val callout @Composable get() = webType(12f, 15f)
    val calloutEmph @Composable get() = webType(12f, 15f, 600)
    val subheadEmph @Composable get() = webType(11f, 14f, 600)

    /** A page's big title, 800 weight at [size], 1.1 line height. */
    @Composable fun header(size: Float, tracking: Float = 0f) = webType(size, size * 1.1f, 800, tracking)
}

/** The web's label colours and fills (opentune.css), from the theme's own ink. */
internal object WebInk {
    private val dark @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val primary @Composable get() = MaterialTheme.colorScheme.onSurface
    val secondary @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val tertiary @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    /** --systemQuinary: the faintest fill, a button at rest. */
    val quinary @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.05f else 0.045f)
    /** --systemQuaternary: a button under the pointer, a hairline ring. */
    val quaternary @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    /** --tracklistHoverColor. */
    val rowHover @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.06f else 0.05f)
    /** --labelDivider: the hairline between rows. */
    val divider @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.11f else 0.12f)
    /** --vibrantDivider: a menu's ring and separators. */
    val vibrantDivider @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.19f else 0.19f)
    /** --radiosityShadowColor: the shadow under lifted artwork. */
    val radiosity @Composable get() = Color.Black.copy(alpha = if (dark) 0.3f else 0.1f)
    /** --containerInnerStroke at its alpha: artwork's hairline edge. */
    val artworkStroke @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.2f else 0.12f)

    /** --keyColor: the accent as text, pale on dark pages and deep on light ones. */
    val key: Color @Composable get() {
        val seed = railAccent()
        return if (dark) lerp(Color(0xFFFFE0D5), seed, 0.32f) else lerp(Color(0xFF44211B), seed, 0.65f)
    }

    /** --keyColorBG: the accent as a fill under white text. */
    val keyFill: Color @Composable get() {
        val seed = railAccent()
        return if (dark) lerp(seed, Color.Black, 0.18f) else lerp(Color(0xFF44211B), seed, 0.65f)
    }
}

/**
 * Rises a few pixels and fades in once, when first shown: the web's ot-rise and ot-item-in
 * (motion.css). Still with smooth motion off. [since] is when the list holding it first showed:
 * something composed again long after that (scrolled back into view) is simply there.
 */
internal fun Modifier.riseIn(
    delayMs: Int = 0,
    durationMs: Int = 500,
    distance: Dp = 10.dp,
    fromScale: Float = 1f,
    since: Long = 0L,
): Modifier = composed {
    val still = stillMotion()
    val fresh = since == 0L || System.nanoTime() - since < 600_000_000L
    val shown = remember { Animatable(if (still || !fresh) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (shown.value < 1f) {
            delay(delayMs.toLong())
            shown.animateTo(1f, tween(durationMs, easing = Web.Emph))
        }
    }
    graphicsLayer {
        val p = shown.value
        alpha = p
        translationY = (1f - p) * distance.toPx()
        val s = fromScale + (1f - fromScale) * p
        scaleX = s
        scaleY = s
    }
}

/** Gives a little when pressed, as the web's .ot-press does: 0.92 in 80ms, back in 160ms. */
internal fun Modifier.pressScale(source: MutableInteractionSource, hoverScale: Float = 1f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val still = stillMotion()
    val target = when {
        still -> 1f
        pressed -> 0.92f
        hovered -> hoverScale
        else -> 1f
    }
    val scale by animateFloatAsState(target, tween(if (pressed) 80 else 160, easing = Web.Settle), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

private val explicitPath by lazy {
    PathParser().parsePathString(
        "M2.5 0h7A2.5 2.5 0 0 1 12 2.5v7A2.5 2.5 0 0 1 9.5 12h-7A2.5 2.5 0 0 1 0 9.5v-7A2.5 2.5 0 0 1 2.5 0Z" +
            "M3.9 3v6h4.2V7.75H5.35v-1.15H7.8V5.4H5.35V4.25H8.1V3Z",
    ).toPath().apply { fillType = PathFillType.EvenOdd }
}

/**
 * The boxed "E" after an explicit song's title, 4px after it: one shape with the letter cut out,
 * as the web draws it (ExplicitBadge.tsx), so whatever is behind shows through the letter.
 */
@Composable
internal fun ExplicitBadge(size: Dp = 12.dp, modifier: Modifier = Modifier) {
    val ink = WebInk.secondary
    Canvas(modifier.padding(start = 4.dp).size(size).semantics { contentDescription = "Explicit" }) {
        scale(this.size.width / 12f, pivot = Offset.Zero) { drawPath(explicitPath, ink) }
    }
}

/**
 * The web's round 40px button beside Play (DetailHeader's detailButton.circle): a faint fill with
 * a hairline ring, a stronger fill under the pointer, giving a little when pressed.
 */
@Composable
internal fun CircleButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 18.dp,
    ring: Boolean = true,
    iconModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val fill by animateColorAsState(if ((hovered || focused) && enabled) WebInk.quaternary else WebInk.quinary, tween(150), label = "circle fill")
    Box(
        Modifier.pressScale(source).size(size).clip(CircleShape).background(fill)
            .then(if (ring) Modifier.border(0.75.dp, WebInk.quaternary, CircleShape) else Modifier)
            .hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = WebInk.primary, modifier = iconModifier.size(iconSize)) }
}

/**
 * The web's Play pill (detailButton.pill): 40px tall, at least 150 wide, the page's ink as its
 * fill with the page's colour on it, a soft shadow under it; a little brighter under the pointer.
 */
@Composable
internal fun PillButton40(label: String, icon: ImageVector, enabled: Boolean = true, minWidth: Dp = 150.dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val ink = MaterialTheme.colorScheme.onSurface
    val page = MaterialTheme.colorScheme.background
    val fill by animateColorAsState(if (hovered && enabled) lerp(ink, page, 0.1f) else ink, tween(150), label = "pill fill")
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.pressScale(source).height(40.dp).defaultMinSize(minWidth = minWidth)
            .shadow(if (enabled) 10.dp else 0.dp, shape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(shape).background(fill.copy(alpha = if (enabled) 1f else 0.4f))
            .hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, null, tint = page, modifier = Modifier.size(18.dp))
        Text(label, style = WebType.title3Emph, color = page, maxLines = 1, softWrap = false)
    }
}
