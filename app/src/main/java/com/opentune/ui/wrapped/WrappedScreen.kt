package com.opentune.ui.wrapped

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.history.History
import com.opentune.data.history.Wrapped
import com.opentune.data.settings.AppSettings
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.rememberHaptics
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Which stretch of the history the story covers. */
enum class WrappedPeriod(val label: String) { YEAR("This year"), MONTH("Last 30 days"), ALL("All time") }

/**
 * One slide's colours: a flat field, the art drawn on it, a second colour
 * for small touches, and the ink for words.
 */
internal data class Look(val field: Color, val shape: Color, val accent: Color, val ink: Color)

internal val LOOKS = listOf(
    Look(Color(0xFFF3EDE2), Color(0xFFE2583E), Color(0xFF2F5D50), Color(0xFF1C1A17)), // cream, terracotta
    Look(Color(0xFF16302B), Color(0xFFA7C4A0), Color(0xFFF2C14E), Color(0xFFF3EDE2)), // forest, sage
    Look(Color(0xFFCDBDF2), Color(0xFF2D2A6E), Color(0xFFF6F1E7), Color(0xFF16153A)), // lilac, indigo
    Look(Color(0xFFF4D35E), Color(0xFFEE6C3B), Color(0xFF3A2A1A), Color(0xFF2A1A0A)), // butter, tangerine
    Look(Color(0xFF151A2E), Color(0xFFFF7E6B), Color(0xFFB9D7F0), Color(0xFFF5F1EA)), // midnight, coral
    Look(Color(0xFFF5CAC3), Color(0xFF5A2346), Color(0xFFE2583E), Color(0xFF2B0F20)), // blush, plum
    Look(Color(0xFFAED6EE), Color(0xFF1F4FBF), Color(0xFFF3EDE2), Color(0xFF0D1A33)), // sky, cobalt
    Look(Color(0xFF141414), Color(0xFFF3EDE2), Color(0xFFE2583E), Color(0xFFF3EDE2)), // ink, cream
)

/** The shape each slide is drawn around. */
internal enum class Art { SUN, WAVE, ARCS, DOTS, PAIR, STRIPES }

private const val SLIDE_MS = 7_000
private val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private enum class Slide(val art: Art) {
    INTRO(Art.SUN), MINUTES(Art.WAVE), TOP_ARTIST(Art.ARCS), ARTISTS(Art.DOTS), TOP_SONG(Art.PAIR),
    SONGS(Art.DOTS), CLOCK(Art.STRIPES), MONTHS(Art.WAVE), REPEAT(Art.ARCS), SUMMARY(Art.SUN),
}

/**
 * The listening history told as a story: one quiet slide at a time, each a
 * flat field of colour with a single shape drifting slowly on it, and the
 * numbers set large. Each slide moves on by itself; a tap on the right goes
 * on, on the left goes back, and holding pauses.
 */
@Composable
fun WrappedScreen(actions: SongActions, onBack: () -> Unit, nowMs: Long = System.currentTimeMillis()) {
    BackHandler(onBack = onBack)
    val records by History.records.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val still = ui.reduceAnimation
    val haptics = rememberHaptics()
    var period by rememberSaveable { mutableStateOf(WrappedPeriod.YEAR) }
    val year = remember(nowMs) { Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.YEAR) }
    val since = remember(period, nowMs) {
        when (period) {
            WrappedPeriod.YEAR -> Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            WrappedPeriod.MONTH -> nowMs - 30L * 24 * 60 * 60 * 1000
            WrappedPeriod.ALL -> 0L
        }
    }
    val summary = remember(records, since, nowMs) { Wrapped.summarize(records, since, nowMs) }
    val slides = remember(summary) {
        buildList {
            add(Slide.INTRO)
            if (!summary.isEmpty) {
                add(Slide.MINUTES)
                if (summary.topArtists.isNotEmpty()) add(Slide.TOP_ARTIST)
                if (summary.topArtists.size >= 3) add(Slide.ARTISTS)
                if (summary.topSongs.isNotEmpty()) add(Slide.TOP_SONG)
                if (summary.topSongs.size >= 3) add(Slide.SONGS)
                add(Slide.CLOCK)
                if (period != WrappedPeriod.MONTH) add(Slide.MONTHS)
                if (summary.onRepeat != null || summary.first != null) add(Slide.REPEAT)
                add(Slide.SUMMARY)
            }
        }
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    if (index > slides.lastIndex) index = slides.lastIndex
    var held by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val topSongs = remember(summary) { summary.topSongs.mapNotNull { it.song } }
    val title = when (period) {
        WrappedPeriod.YEAR -> "$year"
        WrappedPeriod.MONTH -> "month"
        WrappedPeriod.ALL -> "all-time"
    }

    var shown by remember { mutableIntStateOf(-1) }
    var round by remember { mutableIntStateOf(0) }
    fun go(to: Int) {
        val next = to.coerceIn(0, slides.lastIndex)
        if (next == index) return
        haptics.tick()
        index = next
    }

    // Each slide runs for SLIDE_MS, then the next comes; holding pauses it where it is.
    // The bar is reset here rather than where the slide changes, so a reset can't
    // cancel the run that's just starting.
    LaunchedEffect(index, held, slides.size, round) {
        if (shown != index) {
            progress.snapTo(0f)
            shown = index
        }
        if (held || index >= slides.lastIndex || slides.size <= 1) return@LaunchedEffect
        val left = ((1f - progress.value) * SLIDE_MS).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(left, easing = LinearEasing))
        go(index + 1)
    }

    val look = LOOKS[index % LOOKS.size]
    // The field melts from one slide's colour into the next.
    val field by animateColorAsState(look.field, tween(if (still) 0 else 700), label = "field")
    val ink by animateColorAsState(look.ink, tween(if (still) 0 else 700), label = "ink")
    Box(
        Modifier
            .fillMaxSize()
            .background(field)
            .pointerInput(slides.size) {
                detectTapGestures(
                    onPress = {
                        held = true
                        tryAwaitRelease()
                        held = false
                    },
                    onTap = { at -> if (at.x < size.width * 0.3f) go(index - 1) else go(index + 1) },
                )
            },
    ) {
        AnimatedContent(
            targetState = index,
            transitionSpec = { fadeIn(tween(if (still) 150 else 600, easing = Ease)) togetherWith fadeOut(tween(if (still) 150 else 300)) },
            label = "slide",
            modifier = Modifier.fillMaxSize(),
        ) { i ->
            val l = LOOKS[i % LOOKS.size]
            val slide = slides.getOrNull(i)
            Box(Modifier.fillMaxSize()) {
                if (slide != null) StoryArt(slide.art, l, still, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 52.dp).padding(horizontal = 28.dp, vertical = 20.dp)) {
                    when (slide) {
                        Slide.INTRO -> IntroSlide(title, period, summary.isEmpty, l, still) { p -> period = p; index = 0; shown = -1; round++ }
                        Slide.MINUTES -> MinutesSlide(summary, l, still)
                        Slide.TOP_ARTIST -> TopArtistSlide(summary.topArtists.first(), l, still)
                        Slide.ARTISTS -> RankSlide("Top artists", "The five you came back to most", summary.topArtists, round = true, l, still)
                        Slide.TOP_SONG -> TopSongSlide(summary.topSongs.first(), l, still) { actions.playAll(topSongs, 0, false, "Your Wrapped") }
                        Slide.SONGS -> RankSlide("Top songs", "On your lips all along", summary.topSongs, round = false, l, still)
                        Slide.CLOCK -> ClockSlide(summary, l, still)
                        Slide.MONTHS -> MonthsSlide(summary, l, still)
                        Slide.REPEAT -> RepeatSlide(summary, l, still)
                        Slide.SUMMARY -> SummarySlide(
                            title, summary, l, still,
                            onPlay = { actions.playAll(topSongs, 0, false, "Your Wrapped") },
                            onShuffle = { actions.playAll(topSongs, 0, true, "Your Wrapped") },
                            onAgain = { go(0) },
                        )
                        null -> Unit
                    }
                }
            }
        }
        StoryBar(slides.size, index, { progress.value }, ink, Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp))
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 24.dp, end = 12.dp)
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .semantics { role = Role.Button; contentDescription = "Close" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Close, null, tint = ink, modifier = Modifier.size(24.dp)) }
    }
}

// ---- Story chrome ------------------------------------------------------------

@Composable
private fun StoryBar(count: Int, index: Int, progress: () -> Float, ink: Color, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { i ->
            Box(
                Modifier.weight(1f).height(2.dp).clip(CircleShape).background(ink.copy(alpha = 0.2f))
                    .drawBehind {
                        val f = when {
                            i < index -> 1f
                            i == index -> if (index == count - 1) 1f else progress()
                            else -> 0f
                        }
                        if (f > 0f) drawRect(ink, size = Size(size.width * f, size.height))
                    },
            )
        }
    }
}

// ---- Art ---------------------------------------------------------------------

/**
 * The slide's one piece of art: a large flat shape that grows in as the
 * slide arrives and then drifts on a slow loop. With less motion it is
 * drawn in place and holds still.
 */
@Composable
internal fun StoryArt(art: Art, look: Look, still: Boolean, modifier: Modifier) {
    val grow = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (!still) grow.animateTo(1f, tween(1_400, easing = Ease)) }
    val t = if (still) 0.15f else {
        val v by rememberInfiniteTransition(label = "art").animateFloat(
            0f, 1f, infiniteRepeatable(tween(36_000, easing = LinearEasing), RepeatMode.Restart), label = "drift",
        )
        v
    }
    val path = remember { Path() }
    Canvas(modifier) { drawArt(art, look, grow.value, t, path) }
}

private fun DrawScope.drawArt(art: Art, look: Look, grow: Float, t: Float, path: Path) {
    val w = size.width
    val h = size.height
    val tau = 2f * PI.toFloat()
    val a = tau * t
    when (art) {
        Art.SUN -> {
            // A low sun at the bottom right that rises in and breathes, with a small moon circling.
            val r = w * 0.62f * (0.6f + 0.4f * grow) * (1f + 0.02f * sin(a * 3f))
            val c = Offset(w * 0.86f, h * (1.04f - 0.06f * grow))
            drawCircle(look.shape, r, c)
            val orbit = r * 1.28f
            val m = Offset(c.x + orbit * cos(-PI.toFloat() * 0.62f + a), c.y + orbit * sin(-PI.toFloat() * 0.62f + a))
            drawCircle(look.accent, w * 0.045f * grow, m)
        }
        Art.WAVE -> {
            // One thick line rolling slowly across the lower half.
            val y0 = h * 0.8f
            val amp = h * 0.05f * grow
            path.reset()
            val steps = 64
            for (i in 0..steps) {
                val x = -w * 0.1f + (w * 1.2f) * i / steps
                val y = y0 + amp * sin(tau * (i.toFloat() / steps) * 1.3f + a * 2f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, look.shape, style = Stroke(w * 0.09f, cap = StrokeCap.Round))
            drawCircle(look.accent, w * 0.03f * grow, Offset(w * (0.5f + 0.35f * sin(a)), y0 - h * 0.12f + amp * cos(a * 2f)))
        }
        Art.ARCS -> {
            // Rainbow arcs nested in the bottom-left corner, each opening a little more in turn.
            val corner = Offset(-w * 0.05f, h * 1.02f)
            val stroke = w * 0.07f
            for (k in 0 until 4) {
                val r = w * (0.32f + k * 0.17f)
                val open = 90f * grow * (0.9f + 0.1f * sin(a * 2f + k))
                val color = if (k % 2 == 0) look.shape else look.shape.copy(alpha = 0.55f)
                drawArc(color, -90f, open, false, Offset(corner.x - r, corner.y - r), Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Butt))
            }
        }
        Art.DOTS -> {
            // A quiet grid of dots along the bottom, a slow swell running across it.
            val cols = 8
            val rows = 4
            val gap = w / cols
            for (row in 0 until rows) for (col in 0 until cols) {
                val swell = (sin(a * 2f - (col + row) * 0.6f) + 1f) / 2f
                val r = gap * (0.12f + 0.14f * swell) * grow
                drawCircle(if ((col + row) % 5 == 0) look.accent else look.shape.copy(alpha = 0.7f), r, Offset(gap * (col + 0.5f), h - gap * (rows - row - 0.1f)))
            }
        }
        Art.PAIR -> {
            // Two discs that drift together and apart behind the cover.
            // Kept above the words: they sit round the cover, not under the title.
            val r = w * 0.3f * grow
            val d = w * (0.2f + 0.04f * sin(a * 2f))
            val c = Offset(w * 0.5f, h * 0.3f)
            drawCircle(look.shape, r, Offset(c.x - d, c.y - d * 0.35f))
            drawCircle(look.accent.copy(alpha = 0.85f), r * 0.8f, Offset(c.x + d, c.y + d * 0.3f))
        }
        Art.STRIPES -> {
            // Rounded bands from the left edge, each reaching out and back at its own pace.
            val band = h * 0.045f
            for (k in 0 until 5) {
                val reach = w * (0.3f + 0.55f * ((sin(a * (1.5f + k * 0.3f) + k) + 1f) / 2f)) * grow
                val y = h * 0.74f + k * band * 1.45f
                drawRoundRect(
                    if (k == 2) look.accent else look.shape,
                    Offset(-band, y), Size(reach + band, band),
                    androidx.compose.ui.geometry.CornerRadius(band / 2f),
                )
            }
        }
    }
}

// ---- Type and pieces ---------------------------------------------------------

private fun display(size: TextUnit, color: Color) = TextStyle(
    color = color,
    fontFamily = FontFamily.Serif,
    fontWeight = FontWeight.Normal,
    fontSize = size,
    lineHeight = size * 1.02f,
    letterSpacing = (-0.02f * size.value).sp,
)

private fun eyebrow(color: Color) = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.4.sp)
private fun body(color: Color) = TextStyle(color = color, fontSize = 17.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp)
private fun strong(color: Color) = TextStyle(color = color, fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp)

@Composable
private fun Eyebrow(text: String, look: Look, modifier: Modifier = Modifier) {
    Text(text.uppercase(Locale.getDefault()), style = eyebrow(look.ink.copy(alpha = 0.7f)), modifier = modifier)
}

/** 0 to 1 after [delayMs], easing in; 1 at once with less motion. */
@Composable
private fun entrance(delayMs: Int, still: Boolean): Float {
    val a = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        delay(delayMs.toLong())
        a.animateTo(1f, tween(900, easing = Ease))
    }
    return a.value
}

/** Fades up from a little below as [v] goes 0 to 1. */
private fun Modifier.rise(v: Float) = graphicsLayer {
    alpha = v.coerceIn(0f, 1f)
    translationY = (1f - v) * 28.dp.toPx()
}

/** A number that counts up to [target] as it arrives. */
@Composable
private fun countUp(target: Long, still: Boolean, delayMs: Int = 300): Long {
    val a = remember { Animatable(if (still) target.toFloat() else 0f) }
    LaunchedEffect(target) {
        if (still) { a.snapTo(target.toFloat()); return@LaunchedEffect }
        delay(delayMs.toLong())
        a.animateTo(target.toFloat(), tween(2_000, easing = Ease))
    }
    return a.value.toLong()
}

@Composable
private fun Pill(text: String, icon: ImageVector?, filled: Boolean, look: Look, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = if (filled) look.field else look.ink
    Row(
        modifier
            .clip(CircleShape)
            .then(if (filled) Modifier.background(look.ink) else Modifier.border(1.dp, look.ink.copy(alpha = 0.4f), CircleShape))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun Rule(look: Look) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(look.ink.copy(alpha = 0.14f)))
}

/** A cover asked for at a size that stays sharp when shown big. */
internal fun hiRes(url: String?): String? = url?.replace(Regex("""=w\d+-h\d+"""), "=w544-h544")

// ---- Slides ------------------------------------------------------------------

@Composable
private fun BoxScope.IntroSlide(title: String, period: WrappedPeriod, empty: Boolean, look: Look, still: Boolean, onPeriod: (WrappedPeriod) -> Unit) {
    val a = entrance(100, still)
    val b = entrance(300, still)
    val c = entrance(500, still)
    Column(Modifier.align(Alignment.TopStart).padding(top = 28.dp)) {
        Eyebrow("OpenTune", look, Modifier.rise(a))
        Spacer(Modifier.height(18.dp))
        Text("Your", style = display(64.sp, look.ink), modifier = Modifier.rise(a))
        Text(title, style = display(if (title.length > 5) 64.sp else 92.sp, look.shape), modifier = Modifier.rise(b))
        Text("Wrapped", style = display(64.sp, look.ink), modifier = Modifier.rise(c))
        Spacer(Modifier.height(20.dp))
        Text(
            if (empty) "Nothing played here yet. Play some music and come back for your story."
            else "Your listening, told in a few pages. Tap to turn, hold to pause.",
            style = body(look.ink.copy(alpha = 0.75f)),
            modifier = Modifier.rise(c).widthIn(max = 300.dp),
        )
    }
    Row(
        Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp).pointerInput(Unit) { detectTapGestures { } },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WrappedPeriod.entries.forEach { p ->
            val on = p == period
            Text(
                p.label,
                style = TextStyle(color = if (on) look.field else look.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .clip(CircleShape)
                    .then(if (on) Modifier.background(look.ink) else Modifier.border(1.dp, look.ink.copy(alpha = 0.35f), CircleShape))
                    .clickable { onPeriod(p) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.MinutesSlide(s: Wrapped.Summary, look: Look, still: Boolean) {
    val a = entrance(100, still)
    val b = entrance(350, still)
    val c = entrance(1_500, still)
    val minutes = countUp(s.minutes, still)
    Column(Modifier.align(Alignment.TopStart).padding(top = 40.dp)) {
        Eyebrow("Time listening", look, Modifier.rise(a))
        Spacer(Modifier.height(18.dp))
        Text("You spent", style = body(look.ink), modifier = Modifier.rise(a))
        Text("%,d".format(minutes), style = display(96.sp, look.shape), maxLines = 1, modifier = Modifier.rise(b))
        Text("minutes with music", style = display(34.sp, look.ink), modifier = Modifier.rise(b))
        Spacer(Modifier.height(22.dp))
        val hours = s.minutes / 60
        Text(
            when {
                hours >= 48 -> "That's $hours hours, about ${hours / 24} days."
                hours >= 2 -> "That's about $hours hours."
                else -> "A good start."
            },
            style = body(look.ink.copy(alpha = 0.75f)),
            modifier = Modifier.rise(c),
        )
        Spacer(Modifier.height(28.dp))
        Row(Modifier.rise(c), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Fact("%,d".format(s.plays), "plays", look)
            Fact("%,d".format(s.songs), "songs", look)
            Fact("%,d".format(s.artists), "artists", look)
        }
    }
}

@Composable
private fun Fact(value: String, what: String, look: Look) {
    Column {
        Text(value, style = display(32.sp, look.ink))
        Text(what, style = body(look.ink.copy(alpha = 0.6f)).copy(fontSize = 14.sp))
    }
}

@Composable
private fun BoxScope.TopArtistSlide(e: Wrapped.Entry, look: Look, still: Boolean) {
    val a = entrance(100, still)
    val ring = entrance(300, still)
    val b = entrance(700, still)
    Column(Modifier.align(Alignment.TopCenter).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Eyebrow("Your top artist", look, Modifier.rise(a))
        Spacer(Modifier.height(30.dp))
        Box(Modifier.fillMaxWidth(0.7f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            // A thin ring draws itself round the photo as it arrives.
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 1.5.dp.toPx()
                drawArc(look.ink, -90f, 360f * ring, false, Offset(stroke, stroke), Size(size.width - stroke * 2, size.height - stroke * 2), style = Stroke(stroke))
            }
            Artwork(hiRes(e.thumbnailUrl), Modifier.fillMaxSize(0.88f).graphicsLayer { alpha = ring; val s = 0.94f + 0.06f * ring; scaleX = s; scaleY = s }, CircleShape)
        }
        Spacer(Modifier.height(30.dp))
        Text(e.title, style = display(46.sp, look.ink), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(b))
        Spacer(Modifier.height(10.dp))
        Text("${e.plays} plays · ${e.minutes} minutes", style = body(look.ink.copy(alpha = 0.7f)), modifier = Modifier.rise(b))
    }
}

@Composable
private fun BoxScope.RankSlide(heading: String, line: String, list: List<Wrapped.Entry>, round: Boolean, look: Look, still: Boolean) {
    val h = entrance(80, still)
    Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = 24.dp)) {
        Eyebrow(line, look, Modifier.rise(h))
        Spacer(Modifier.height(12.dp))
        Text(heading, style = display(46.sp, look.ink), modifier = Modifier.rise(h))
        Spacer(Modifier.height(24.dp))
        list.take(5).forEachIndexed { i, e ->
            val v = entrance(260 + i * 150, still)
            Column(Modifier.rise(v)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = display(26.sp, if (i == 0) look.shape else look.ink.copy(alpha = 0.5f)), modifier = Modifier.width(36.dp))
                    Artwork(hiRes(e.thumbnailUrl), Modifier.size(52.dp), if (round) CircleShape else RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = strong(look.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (round) "${e.plays} plays" else "${e.subtitle} · ${e.plays} plays", style = body(look.ink.copy(alpha = 0.6f)).copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (i < list.size.coerceAtMost(5) - 1) Rule(look)
            }
        }
    }
}

@Composable
private fun BoxScope.TopSongSlide(e: Wrapped.Entry, look: Look, still: Boolean, onPlay: () -> Unit) {
    val a = entrance(100, still)
    val b = entrance(350, still)
    val c = entrance(800, still)
    val float = if (still) 0f else {
        val v by rememberInfiniteTransition(label = "float").animateFloat(-1f, 1f, infiniteRepeatable(tween(4_000, easing = Ease), RepeatMode.Reverse), label = "y")
        v
    }
    Column(Modifier.align(Alignment.TopCenter).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Eyebrow("Your top song", look, Modifier.rise(a))
        Spacer(Modifier.height(30.dp))
        Artwork(
            hiRes(e.thumbnailUrl),
            Modifier
                .fillMaxWidth(0.68f)
                .aspectRatio(1f)
                .rise(b)
                .graphicsLayer { translationY = float * 6.dp.toPx() }
                .shadow(24.dp, RoundedCornerShape(14.dp)),
            RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(34.dp))
        Text(e.title, style = display(38.sp, look.ink), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(c))
        Spacer(Modifier.height(6.dp))
        Text(e.subtitle, style = strong(look.ink.copy(alpha = 0.75f)), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(c))
        Spacer(Modifier.height(4.dp))
        Text("Played ${e.plays} times", style = body(look.ink.copy(alpha = 0.6f)), modifier = Modifier.rise(c))
        Spacer(Modifier.height(22.dp))
        Pill("Play your top songs", Icons.Rounded.PlayArrow, filled = true, look, onPlay, Modifier.rise(c))
    }
}

@Composable
private fun BoxScope.ClockSlide(s: Wrapped.Summary, look: Look, still: Boolean) {
    val a = entrance(100, still)
    val grow = entrance(400, still)
    val max = (s.hours.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column(Modifier.align(Alignment.TopCenter).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Eyebrow("When you listen", look, Modifier.rise(a))
        Spacer(Modifier.height(18.dp))
        Text(s.clock.title, style = display(54.sp, look.ink), textAlign = TextAlign.Center, modifier = Modifier.rise(a))
        Spacer(Modifier.height(8.dp))
        Text(s.clock.line, style = body(look.ink.copy(alpha = 0.7f)), textAlign = TextAlign.Center, modifier = Modifier.rise(a))
        Spacer(Modifier.height(26.dp))
        Box(Modifier.fillMaxWidth(0.82f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val inner = r * 0.42f
                for (hour in 0 until 24) {
                    val f = s.hours[hour].toFloat() / max
                    val angle = (hour / 24f) * 2f * PI.toFloat() - PI.toFloat() / 2f
                    val len = (r - inner) * (0.06f + 0.94f * f) * grow
                    val start = Offset(center.x + inner * cos(angle), center.y + inner * sin(angle))
                    val end = Offset(center.x + (inner + len) * cos(angle), center.y + (inner + len) * sin(angle))
                    val peak = hour == s.peakHour
                    drawLine(if (peak) look.shape else look.ink.copy(alpha = 0.55f), start, end, strokeWidth = (if (peak) 7 else 4).dp.toPx(), cap = StrokeCap.Round)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.rise(grow)) {
                Text(hourName(s.peakHour), style = display(30.sp, look.ink))
                Text("busiest hour", style = body(look.ink.copy(alpha = 0.6f)).copy(fontSize = 13.sp))
            }
        }
    }
}

private fun hourName(h: Int): String = when {
    h == 0 -> "12 am"
    h < 12 -> "$h am"
    h == 12 -> "12 pm"
    else -> "${h - 12} pm"
}

@Composable
private fun BoxScope.MonthsSlide(s: Wrapped.Summary, look: Look, still: Boolean) {
    val a = entrance(100, still)
    val grow = entrance(350, still)
    val c = entrance(1_000, still)
    val max = (s.months.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val best = s.months.maxByOrNull { it.second }
    Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = 24.dp)) {
        Eyebrow("Your year, month by month", look, Modifier.rise(a))
        Spacer(Modifier.height(14.dp))
        if (best != null && best.second > 0) Text("${best.first} was\nyour month", style = display(48.sp, look.ink), modifier = Modifier.rise(a))
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            s.months.forEach { (name, minutes) ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    val f = minutes.toFloat() / max
                    val top = minutes == best?.second
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight((0.02f + 0.84f * f) * grow)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (top) look.shape else look.ink.copy(alpha = 0.25f)),
                    )
                    Text(name.take(1), style = body(look.ink.copy(alpha = 0.55f)).copy(fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(Modifier.rise(c), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val day = s.busiestDayMs
            if (day != null) Line("Biggest day", "${SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(day))} · ${s.busiestDayMinutes} minutes", look)
            if (s.streakDays >= 2) Line("Longest streak", "${s.streakDays} days in a row", look)
        }
    }
}

@Composable
private fun Line(label: String, value: String, look: Look) {
    Column {
        Eyebrow(label, look)
        Spacer(Modifier.height(4.dp))
        Text(value, style = strong(look.ink).copy(fontSize = 20.sp))
    }
}

@Composable
private fun BoxScope.RepeatSlide(s: Wrapped.Summary, look: Look, still: Boolean) {
    val a = entrance(100, still)
    val b = entrance(700, still)
    Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = 24.dp)) {
        s.onRepeat?.let { e ->
            Column(Modifier.rise(a)) {
                Eyebrow("On repeat", look)
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Artwork(hiRes(e.thumbnailUrl), Modifier.size(120.dp).shadow(16.dp, RoundedCornerShape(10.dp)), RoundedCornerShape(10.dp))
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text("${e.plays} times", style = display(44.sp, look.shape))
                        Text("in a single day", style = body(look.ink.copy(alpha = 0.7f)))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(e.title, style = display(32.sp, look.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(e.subtitle, style = body(look.ink.copy(alpha = 0.65f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(28.dp))
        }
        s.first?.let { e ->
            Column(Modifier.rise(b)) {
                Rule(look)
                Spacer(Modifier.height(22.dp))
                Eyebrow("Where it started", look)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(hiRes(e.thumbnailUrl), Modifier.size(56.dp), CircleShape)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(e.title, style = strong(look.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.subtitle, style = body(look.ink.copy(alpha = 0.6f)).copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.SummarySlide(title: String, s: Wrapped.Summary, look: Look, still: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit, onAgain: () -> Unit) {
    val a = entrance(100, still)
    val b = entrance(500, still)
    Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = 16.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .rise(a)
                .clip(RoundedCornerShape(24.dp))
                .background(look.field.copy(alpha = 0.92f))
                .border(1.dp, look.ink.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
                .padding(22.dp),
        ) {
            Eyebrow("OpenTune", look)
            Spacer(Modifier.height(8.dp))
            Text("Your $title Wrapped", style = display(34.sp, look.ink))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Eyebrow("Top artists", look)
                    s.topArtists.take(5).forEachIndexed { i, e -> Text("${i + 1}  ${e.title}", style = strong(look.ink).copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Eyebrow("Top songs", look)
                    s.topSongs.take(5).forEachIndexed { i, e -> Text("${i + 1}  ${e.title}", style = strong(look.ink).copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Rule(look)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Minutes", look)
                    Text("%,d".format(s.minutes), style = display(30.sp, look.shape))
                }
                Column(Modifier.weight(1f)) {
                    Eyebrow("Listener", look)
                    Text(s.clock.title, style = display(26.sp, look.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        // Buttons take their own taps (and the gaps between them), so the story doesn't turn.
        Row(Modifier.rise(b).pointerInput(Unit) { detectTapGestures { } }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Play", Icons.Rounded.PlayArrow, filled = true, look, onPlay)
            Pill("Shuffle", Icons.Rounded.Shuffle, filled = false, look, onShuffle)
            Pill("Watch again", null, filled = false, look, onAgain)
        }
    }
}

/** A quiet card that opens the Wrapped story: a flat colour, one shape and the title. */
@Composable
fun WrappedBanner(heading: String, line: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LOOKS[0]
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(look.field)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
    ) {
        // Drawn still: a card in a list shouldn't move under the reader.
        Canvas(Modifier.matchParentSize()) {
            val r = size.height * 0.85f
            drawCircle(look.shape, r, Offset(size.width - r * 0.35f, size.height + r * 0.15f))
            drawCircle(look.accent, size.height * 0.07f, Offset(size.width - r * 1.05f, size.height * 0.28f))
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(heading, style = display(28.sp, look.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(line, style = body(look.ink.copy(alpha = 0.7f)).copy(fontSize = 14.sp, lineHeight = 19.sp), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(44.dp).clip(CircleShape).background(look.ink), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = look.field, modifier = Modifier.size(22.dp))
            }
        }
    }
}
