package com.opentune.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.opentune.data.subsonic.Subsonic
import kotlinx.coroutines.delay

/** How the app moves: one spring and one fade for everything that comes and goes, as on the phone. */
internal object Motion {
    fun <T> soft() = spring<T>(dampingRatio = 0.9f, stiffness = 380f)
    fun <T> bouncy() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 500f)
    fun <T> fade(ms: Int = 220) = tween<T>(ms, easing = FastOutSlowInEasing)
}

/** Grows a little under the pointer and settles back when it leaves; pressed, it gives a little. */
internal fun Modifier.hoverLift(source: MutableInteractionSource, grow: Float = 1.03f): Modifier = composed {
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    // With smooth motion off, things stay their size.
    val still = com.opentune.data.settings.AppSettings.ui.collectAsState().value.reduceAnimation
    val scale by animateFloatAsState(if (still) 1f else if (pressed) 0.97f else if (hovered) grow else 1f, Motion.soft(), label = "lift")
    hoverable(source).graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A faint wash under the pointer, for rows and sidebar items. */
internal fun Modifier.hoverWash(source: MutableInteractionSource, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp)): Modifier = composed {
    val hovered by source.collectIsHoveredAsState()
    val wash by animateColorAsState(
        if (hovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent, Motion.fade(160), label = "wash",
    )
    hoverable(source).clip(shape).background(wash)
}

/**
 * Rises and fades in after [index] steps, for the first rows of a list;
 * later ones are there at once. [since] is when the list first showed: a row
 * composed again after that (scrolled back into view) doesn't animate again.
 */
internal fun Modifier.appear(index: Int, since: Long = 0L): Modifier = composed {
    val fresh = since == 0L || System.nanoTime() - since < 600_000_000L
    val shown = remember { Animatable(if (index < 14 && fresh) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (shown.value < 1f) {
            delay(index * 24L)
            shown.animateTo(1f, Motion.fade(280))
        }
    }
    graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * 14.dp.toPx()
    }
}

/** A shimmering placeholder, in the shape what's loading will take; the sweep moves in drawing alone. */
@Composable
internal fun Placeholder(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp)) {
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val light = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f)
    val sweep = rememberInfiniteTransition(label = "shimmer").animateFloat(
        -1f, 2f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "sweep",
    )
    Box(
        modifier.clip(shape).drawBehind {
            val x = sweep.value * 600f
            drawRect(
                Brush.linearGradient(
                    listOf(base, light, base),
                    start = androidx.compose.ui.geometry.Offset(x, 0f),
                    end = androidx.compose.ui.geometry.Offset(x + 300f, 120f),
                ),
            )
        },
    )
}

/**
 * The cover's own colour: the average of its livelier pixels, from a small
 * copy, so the player and album pages can wear it as the phone's do.
 */
@Composable
internal fun rememberCoverColor(url: String?, fallback: Color): Color {
    val context = LocalPlatformContext.current
    var found by remember(url) { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val result = SingletonImageLoader.get(context).execute(
            ImageRequest.Builder(context).data(Subsonic.resolveCover(url)).size(48).build(),
        )
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        var r = 0.0; var g = 0.0; var b = 0.0; var weight = 0.0
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            val c = bitmap.getColor(x, y)
            val rr = (c shr 16 and 0xff) / 255.0; val gg = (c shr 8 and 0xff) / 255.0; val bb = (c and 0xff) / 255.0
            val max = maxOf(rr, gg, bb); val min = minOf(rr, gg, bb)
            // Lively, mid-bright pixels count most; greys and near-black hardly at all.
            val w = 0.05 + (max - min) * (1.0 - kotlin.math.abs(max - 0.55))
            r += rr * w; g += gg * w; b += bb * w; weight += w
        }
        if (weight > 0) found = Color((r / weight).toFloat(), (g / weight).toFloat(), (b / weight).toFloat())
    }
    val target = found ?: fallback
    return animateColorAsState(target, tween(900), label = "cover colour").value
}

/** A colour from the cover, mixed into [into] so text stays readable over it. */
internal fun tint(cover: Color, into: Color, amount: Float = 0.32f): Color = lerp(into, cover, amount)

/** The heart, which jumps a little and fills when a song is liked. */
@Composable
internal fun LikeButton(liked: Boolean, onToggle: () -> Unit, idle: Color = MaterialTheme.colorScheme.onSurfaceVariant, size: Dp = 22.dp) {
    val pop = remember { Animatable(1f) }
    var last by remember { mutableStateOf(liked) }
    LaunchedEffect(liked) {
        if (liked && !last) {
            pop.animateTo(1.35f, tween(110))
            pop.animateTo(1f, Motion.bouncy())
        }
        last = liked
    }
    val ink by animateColorAsState(if (liked) MaterialTheme.colorScheme.primary else idle, Motion.fade(), label = "heart")
    IconButton(onClick = onToggle) {
        Icon(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Unlike" else "Like", tint = ink,
            modifier = Modifier.size(size).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
        )
    }
}

/**
 * Where the song is playing from and how, in grey; for a lossless file a
 * band of light passes along it now and then, as the phone's quality label does.
 */
@Composable
internal fun QualityLabel(quality: Player.Quality?, modifier: Modifier = Modifier) {
    AnimatedContent(quality, transitionSpec = { fadeIn(Motion.fade(300)) togetherWith fadeOut(Motion.fade(200)) }, label = "quality", modifier = modifier) { q ->
        if (q == null) return@AnimatedContent Box(Modifier.size(1.dp))
        val grey = MaterialTheme.colorScheme.onSurfaceVariant
        val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
        val text = "${q.detail} · ${q.source}"
        if (!q.lossless) {
            Text(text, style = style, color = grey, maxLines = 1)
        } else {
            val sweep by rememberInfiniteTransition(label = "beam").animateFloat(
                -0.6f, 1.6f, infiniteRepeatable(tween(2_600, delayMillis = 1_400, easing = LinearEasing), RepeatMode.Restart), label = "beam at",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 6.dp).width(3.dp).size(3.dp, 12.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
                Text(
                    text, maxLines = 1,
                    style = style.merge(
                        TextStyle(
                            brush = Brush.horizontalGradient(
                                0f to grey, (sweep - 0.15f).coerceIn(0f, 1f) to grey, sweep.coerceIn(0f, 1f) to Color.White,
                                (sweep + 0.15f).coerceIn(0f, 1f) to grey, 1f to grey,
                            ),
                        ),
                    ),
                )
            }
        }
    }
}

/** Three bars that rise and fall for the song playing now, in a list. */
@Composable
internal fun PlayingBars(playing: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val t by rememberInfiniteTransition(label = "bars").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "bars t")
    Row(modifier.size(16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 3) {
            val phase = (t + i * 0.33f) % 1f
            val h = if (playing) 0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(phase * Math.PI)).toFloat() else 0.3f
            Box(Modifier.width(3.dp).fillMaxHeight(h).clip(RoundedCornerShape(1.dp)).background(color))
        }
    }
}
