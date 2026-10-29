package com.opentune.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentune.data.history.ReplaySummary
import com.opentune.data.history.TopEntry
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.artworkAt
import com.opentune.ui.components.Artwork
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/** Which stretch of listening Replay covers: this calendar week, month or year, or all of it. */
internal enum class ReplayRange(val label: String, val word: String, val opening: String) {
    WEEK("Weekly", "week", "This week"),
    MONTH("Monthly", "month", "This month"),
    YEAR("Yearly", "year", "This year"),
    ALL("Lifetime", "time", "Over time");

    fun since(now: Long = System.currentTimeMillis()): Long {
        if (this == ALL) return 0L
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        when (this) {
            WEEK -> { c.firstDayOfWeek = Calendar.MONDAY; c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); if (c.timeInMillis > now) c.add(Calendar.DAY_OF_YEAR, -7) }
            MONTH -> c.set(Calendar.DAY_OF_MONTH, 1)
            else -> c.set(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    /** Under the page's title, and on the cards: "This week", "October 2026", "2026", "All time". */
    fun heading(now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        return when (this) {
            WEEK -> "This week"
            MONTH -> "${c.getDisplayName(Calendar.MONTH, Calendar.LONG, java.util.Locale.getDefault())} ${c.get(Calendar.YEAR)}"
            YEAR -> "${c.get(Calendar.YEAR)}"
            ALL -> "All time"
        }
    }
}

/** Each card's own colours, top to bottom: listening, top song, top artist, top album. */
private val CardColors = listOf(
    Color(0xFF5E1C5C) to Color(0xFF2E1030),
    Color(0xFF6B2466) to Color(0xFF321234),
    Color(0xFF2F4B49) to Color(0xFF17221F),
    Color(0xFF15558A) to Color(0xFF0B2A47),
)

/** The colour a page behind card [index] takes. */
internal fun replayTint(index: Int): Color = CardColors[index.coerceIn(0, CardColors.lastIndex)].first

private fun minutes(ms: Long) = TimeUnit.MILLISECONDS.toMinutes(ms)
private fun plays(n: Int) = if (n == 1) "1 Play" else "$n Plays"

/** "09/26": the month of the first listen kept on this device. */
internal fun memberSince(firstListen: Long?): String {
    val c = Calendar.getInstance().apply { timeInMillis = firstListen ?: System.currentTimeMillis() }
    return "%02d/%02d".format(c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR) % 100)
}

private class CardFace(val value: String, val label: String, val detail: String)

/**
 * "Your listening experience": minutes listened, then the top song, artist and album,
 * a card each, swiped through one at a time. [onCard] hears which card is in front.
 */
@Composable
internal fun ReplayCards(
    summary: ReplaySummary,
    range: ReplayRange,
    name: String,
    since: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onCard: (Int) -> Unit = {},
) {
    val faces = buildList {
        add(CardFace("${minutes(summary.listenedMs)}", "MINUTES LISTENED", "${plays(summary.totalPlays)} · ${range.heading()}"))
        summary.topSongs.firstOrNull()?.let { add(CardFace(it.title, "TOP SONG", "${it.subtitle} · ${plays(it.plays)}")) }
        summary.topArtists.firstOrNull()?.let { add(CardFace(it.title, "TOP ARTIST", "${minutes(it.listenedMs)} min · ${plays(it.plays)}")) }
        summary.topAlbums.firstOrNull()?.let { add(CardFace(it.title, "TOP ALBUM", "${it.subtitle} · ${minutes(it.listenedMs)} min")) }
    }
    val list = rememberLazyListState()
    LaunchedEffect(list) {
        snapshotFlow {
            val info = list.layoutInfo.visibleItemsInfo.firstOrNull() ?: return@snapshotFlow 0
            // The card most in view: the first, until it's more than half gone.
            if (info.offset < -info.size / 2) info.index + 1 else info.index
        }.collect(onCard)
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = (maxWidth - 56.dp).coerceAtMost(420.dp)
        LazyRow(
            state = list,
            flingBehavior = rememberSnapFlingBehavior(list),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(faces) { i, face -> ExperienceCard(face, i, name, since, onClick, Modifier.width(width)) }
        }
    }
}

@Composable
private fun ExperienceCard(face: CardFace, index: Int, name: String, since: String, onClick: () -> Unit, modifier: Modifier) {
    val (top, bottom) = CardColors[index.coerceIn(0, CardColors.lastIndex)]
    val shape = RoundedCornerShape(28.dp)
    val lift = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 18f)
    Column(
        modifier.height(200.dp).clip(shape)
            .background(Brush.linearGradient(listOf(top, bottom)))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("YOUR LISTENING EXPERIENCE", Modifier.weight(1f), color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, maxLines = 1)
            Icon(painterResource(com.opentune.R.drawable.ic_launcher_monochrome), null, Modifier.size(40.dp).offset(x = 8.dp), tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        Text(face.value, color = Color.White, style = TextStyle(fontSize = if (face.value.length > 14) 26.sp else 34.sp,
            fontWeight = FontWeight.ExtraBold, lineHeight = 32.sp, shadow = lift), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(face.label, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(name.uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(face.detail, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("MEMBER SINCE", color = Color.White.copy(alpha = 0.7f), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(since, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
        }
    }
}

/** One page of the story: its colours and what's on it. */
private class StoryPage(val top: Color, val bottom: Color, val content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)

private const val PAGE_MS = 6_000

/**
 * "Play your Replay": the period told a page at a time, as a story. It moves on by
 * itself; a tap on the right goes on, on the left goes back (the remote's arrows too),
 * and each page can be shared as a picture.
 */
@Composable
internal fun ReplayStory(summary: ReplaySummary, range: ReplayRange, onDismiss: () -> Unit) {
    val pages = remember(summary, range) { storyPages(summary, range) }
    var page by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picture = rememberGraphicsLayer()
    val focus = remember { FocusRequester() }
    // Timed by frames rather than an animation, so turning animations off in the
    // system settings doesn't run every page out at once. Holding a finger down pauses.
    LaunchedEffect(page) {
        progress = 0f
        var last = withFrameMillis { it }
        while (progress < 1f) {
            val now = withFrameMillis { it }
            if (!held) progress = (progress + (now - last) / PAGE_MS.toFloat()).coerceAtMost(1f)
            last = now
        }
        if (page < pages.lastIndex) page++
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val go: (Int) -> Unit = { by -> page = (page + by).coerceIn(0, pages.lastIndex) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val p = pages[page]
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .focusRequester(focus).focusable()
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionRight -> { go(1); true }
                        Key.DirectionLeft -> { go(-1); true }
                        else -> false
                    }
                }
                .pointerInput(pages.size) {
                    detectTapGestures(
                        onPress = { held = true; tryAwaitRelease(); held = false },
                        onLongPress = { },
                        onTap = { at -> go(if (at.x < size.width / 3f) -1 else 1) },
                    )
                },
        ) {
            // What's shared: the page itself, without the bars and buttons over it.
            Box(
                Modifier.fillMaxSize()
                    .drawWithContent {
                        picture.record { this@drawWithContent.drawContent() }
                        drawLayer(picture)
                    }
                    .background(Brush.verticalGradient(listOf(p.top, p.bottom)))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 84.dp),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Replay · ${range.heading()}", Modifier.weight(1f), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Icon(painterResource(com.opentune.R.drawable.ic_launcher_monochrome), null, Modifier.size(30.dp), tint = Color.White)
                        Text("OpenTune", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(14.dp))
                    p.content(this)
                }
            }
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(end = 48.dp, top = 14.dp)) {
                    pages.indices.forEach { i ->
                        val fill = when { i < page -> 1f; i == page -> progress; else -> 0f }
                        Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                            Box(Modifier.fillMaxWidth(fill).height(3.dp).background(Color.White))
                        }
                    }
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(4.dp)) {
                Icon(Icons.Rounded.Close, "Close", tint = Color.White)
            }
            IconButton(
                onClick = {
                    scope.launch {
                        val bitmap = runCatching { picture.toImageBitmap().asAndroidBitmap() }.getOrNull()
                        com.opentune.ui.share.ShareCard.sendImage(context, bitmap, "replay.png", null, "My Replay")
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
                    .size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)),
            ) { Icon(Icons.Rounded.IosShare, "Share this page", tint = Color.White) }
        }
    }
}

/** A heading with its key words bright: "You spent **167 minutes** with music." */
@Composable
private fun Heading(vararg parts: Pair<String, Boolean>) {
    Text(
        buildAnnotatedString {
            parts.forEach { (text, bright) ->
                if (bright) withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) { append(text) } else append(text)
            }
        },
        color = Color.White.copy(alpha = 0.72f), fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun Footnote(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
}

/** The first of a ranking, large: its picture, name and what it took. */
@Composable
private fun Hero(entry: TopEntry, round: Boolean, line: String) {
    Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(entry.thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.size(104.dp), if (round) CircleShape else RoundedCornerShape(10.dp))
        Column(Modifier.padding(start = 16.dp)) {
            Text(entry.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
            if (!round && entry.subtitle.isNotBlank()) Text(entry.subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

/** Second to fifth, small, at the foot of the page. */
@Composable
private fun RunnersUp(list: List<TopEntry>, round: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        list.drop(1).take(4).forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 2}", Modifier.width(24.dp), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Artwork(e.thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.size(34.dp), if (round) CircleShape else RoundedCornerShape(6.dp))
                Text(e.title, Modifier.weight(1f).padding(horizontal = 12.dp), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${minutes(e.listenedMs)} min", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            }
        }
    }
}

/** The covers of what was played most, scattered and turned a little. */
@Composable
private fun Collage(summary: ReplaySummary) {
    val covers = (summary.topSongs.map { it.thumbnailUrl } + summary.topAlbums.map { it.thumbnailUrl } + summary.topArtists.map { it.thumbnailUrl })
        .filterNotNull().distinct().take(6)
    // Where each sits, as fractions of the space, its size, turn and whether it's round.
    val spots = listOf(
        Triple(0.30f to 0.40f, 150, -4f), Triple(0.10f to 0.12f, 110, -8f), Triple(0.62f to 0.05f, 86, 7f),
        Triple(0.70f to 0.30f, 76, 0f), Triple(0.04f to 0.58f, 70, 0f), Triple(0.52f to 0.70f, 74, 0f),
    )
    BoxWithConstraints(Modifier.fillMaxWidth().height(380.dp).padding(top = 24.dp)) {
        covers.forEachIndexed { i, url ->
            val (at, px, turn) = spots[i]
            val round = i >= 3
            Artwork(url.artworkAt(CARD_ART_PX), Modifier.offset(maxWidth * at.first, maxHeight * at.second).size(px.dp * 1.2f).rotate(turn),
                if (round) CircleShape else RoundedCornerShape(6.dp))
        }
    }
}

private fun storyPages(s: ReplaySummary, range: ReplayRange): List<StoryPage> = buildList {
    val word = range.word
    add(StoryPage(Color(0xFF5E5A26), Color(0xFF2B2913)) {
        Heading("Your " to false, "Replay" to true, " is ready." to false)
        if (s.totalPlays == 0) Footnote("Nothing played yet. Listen for a while and come back.") else Collage(s)
        Spacer(Modifier.weight(1f))
        Footnote("Built privately from your listening history on this device.")
    })
    if (s.totalPlays == 0) return@buildList
    add(StoryPage(Color(0xFF6B1F45), Color(0xFF2A0D1C)) {
        Heading("You spent " to false, "${minutes(s.listenedMs)} minutes" to true, " with music." to false)
        Spacer(Modifier.weight(1f))
        val hours = TimeUnit.MILLISECONDS.toHours(s.listenedMs)
        val rest = minutes(s.listenedMs) % 60
        Footnote((if (hours > 0) "$hours hours, $rest minutes" else "$rest minutes") + ", ${plays(s.totalPlays)}.")
    })
    s.topArtists.firstOrNull()?.let { a ->
        add(StoryPage(Color(0xFF2F6E52), Color(0xFF173528)) {
            Heading("${range.opening}, " to false, "one artist" to true, " kept coming back." to false)
            Hero(a, round = true, line = "${minutes(a.listenedMs)} min · ${plays(a.plays)}")
            Spacer(Modifier.weight(1f))
            RunnersUp(s.topArtists, round = true)
        })
    }
    s.topSongs.firstOrNull()?.let { t ->
        add(StoryPage(Color(0xFF3D7146), Color(0xFF1B3520)) {
            Heading("In total, " to false, "${s.songCount} songs" to true, " filled your $word." to false)
            Hero(t, round = false, line = "${minutes(t.listenedMs)} min · ${plays(t.plays)}")
            Spacer(Modifier.weight(1f))
            RunnersUp(s.topSongs, round = false)
        })
    }
    s.topAlbums.firstOrNull()?.let { al ->
        add(StoryPage(Color(0xFF3B2F94), Color(0xFF1A1547)) {
            Heading("This time, " to false, "one album" to true, " held your attention." to false)
            Hero(al, round = false, line = "${minutes(al.listenedMs)} min · ${plays(al.plays)}")
            Spacer(Modifier.weight(1f))
            RunnersUp(s.topAlbums, round = false)
        })
    }
    add(StoryPage(Color(0xFF7A3A4E), Color(0xFF2E1520)) {
        Heading("You explored " to false, "${s.songCount} songs" to true, " from " to false, "${s.artistCount} artists" to true, "." to false)
        Spacer(Modifier.weight(1f))
        if (s.albumCount > 0) {
            Text("${s.albumCount}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Footnote("Different albums")
        }
    })
    add(StoryPage(Color(0xFF6E4630), Color(0xFF2A1A10)) {
        Heading("Your " to false, (if (range == ReplayRange.ALL) "music" else word) to true, " in music." to false)
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SummaryLine("Minutes", "${minutes(s.listenedMs)}")
            s.topSongs.firstOrNull()?.let { SummaryLine("Top song", it.title) }
            s.topArtists.firstOrNull()?.let { SummaryLine("Top artist", it.title) }
            s.topAlbums.firstOrNull()?.let { SummaryLine("Top album", it.title) }
            SummaryLine("Plays", "${s.totalPlays}")
        }
        Spacer(Modifier.weight(1f))
        Footnote("Tap Share to create a picture.")
    })
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row {
        Text(label, Modifier.width(96.dp), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
