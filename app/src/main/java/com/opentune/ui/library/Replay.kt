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
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.opentune.ui.theme.Icon
import androidx.compose.material3.IconButton
import com.opentune.ui.theme.Text
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
import kotlinx.coroutines.async
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

/**
 * [summary] with each top artist's own photo in place of a song cover, looked up for the
 * first dozen ([com.opentune.data.history.ArtistFaces]) all at once and swapped in
 * together, so the pages change once. Covers stay where no photo is found.
 */
@Composable
private fun rememberArtistFaces(summary: ReplaySummary): ReplaySummary {
    val faces = remember(summary) {
        androidx.compose.runtime.mutableStateOf(summary.topArtists.mapNotNull { a -> com.opentune.data.history.ArtistFaces.cached(a.title)?.let { a.key to it } }.toMap())
    }
    LaunchedEffect(summary) {
        val wanted = summary.topArtists.take(FACES_LOOKED_UP).filter { it.key !in faces.value }
        if (wanted.isEmpty()) return@LaunchedEffect
        val got = kotlinx.coroutines.coroutineScope {
            wanted.map { a ->
                async {
                    // The song's artist id is theirs only when they're its first-billed artist.
                    val id = a.song?.takeIf { com.opentune.data.history.Wrapped.mainArtist(it.artist).equals(a.title, ignoreCase = true) }?.artistId
                    com.opentune.data.history.ArtistFaces.photoFor(a.title, id)?.let { a.key to it }
                }
            }.mapNotNull { it.await() }
        }
        if (got.isNotEmpty()) faces.value = faces.value + got
    }
    val found = faces.value
    return remember(summary, found) {
        if (found.isEmpty()) summary else summary.copy(topArtists = summary.topArtists.map { it.copy(thumbnailUrl = found[it.key] ?: it.thumbnailUrl) })
    }
}

private const val FACES_LOOKED_UP = 12

private fun minutes(ms: Long) = TimeUnit.MILLISECONDS.toMinutes(ms)
private fun plays(n: Int) = if (n == 1) "1 Play" else com.opentune.i18n.Tr.f("{0} Plays", n)

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
        summary.topArtists.firstOrNull()?.let { add(CardFace(it.title, "TOP ARTIST", com.opentune.i18n.Tr.f("{0} min · {1}", minutes(it.listenedMs), plays(it.plays)))) }
        summary.topAlbums.firstOrNull()?.let { add(CardFace(it.title, "TOP ALBUM", com.opentune.i18n.Tr.f("{0} · {1} min", it.subtitle, minutes(it.listenedMs)))) }
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
    // The artists with their own photos, not the cover of a song of theirs, once they're found.
    val withFaces = rememberArtistFaces(summary)
    val pages = remember(withFaces, range) { storyPages(withFaces, range) }
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
                        Text(com.opentune.i18n.Tr.f("Replay · {0}", range.heading()), Modifier.weight(1f), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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

/**
 * The first of a ranking, as the page's centre: its picture as large as the room
 * allows, its name under it, and what it took as two figures.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Hero(entry: TopEntry, round: Boolean, minutes: Long, plays: Int) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        // Room under the picture for the name and the figures.
        val side = minOf(maxWidth * 0.78f, maxHeight - 132.dp).coerceAtLeast(96.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Artwork(entry.thumbnailUrl.artworkAt(com.opentune.data.model.PLAYER_ART_PX), Modifier.size(side), if (round) CircleShape else RoundedCornerShape(14.dp))
            Text(entry.title, Modifier.padding(top = 16.dp), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 30.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (!round && entry.subtitle.isNotBlank()) Text(entry.subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(com.opentune.i18n.Tr.f("{0} min", minutes))
                Chip(plays(plays))
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(text, Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 5.dp),
        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

/** Second to fifth, under the hero. */
@Composable
private fun RunnersUp(list: List<TopEntry>, round: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.18f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        list.drop(1).take(4).forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 2}", Modifier.width(22.dp), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Artwork(e.thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.size(40.dp), if (round) CircleShape else RoundedCornerShape(8.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(e.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!round && e.subtitle.isNotBlank()) Text(e.subtitle, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(com.opentune.i18n.Tr.f("{0} min", minutes(e.listenedMs)), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
            }
        }
    }
}

/** A number as the page's centre, with what it counts under it. */
@Composable
private fun BigNumber(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(value, color = Color.White, fontSize = if (value.length > 5) 80.sp else 104.sp, fontWeight = FontWeight.Black,
            lineHeight = 108.sp, maxLines = 1)
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    }
}

/** Three figures side by side, each in its own tile. */
@Composable
private fun Figures(vararg items: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (value, label) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f)).padding(vertical = 14.dp, horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(value, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** Bars for when the listening happened, the busiest one bright. */
@Composable
private fun Bars(title: String, values: List<Long>, labels: List<String>, labelEvery: Int = 1) {
    val top = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    val peak = values.indexOf(values.maxOrNull())
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.18f)).padding(14.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().height(72.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, v ->
                Box(
                    Modifier.weight(1f).fillMaxHeight((v.toFloat() / top).coerceAtLeast(0.04f))
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(Color.White.copy(alpha = if (i == peak) 0.95f else 0.38f)),
                )
            }
        }
        // One label for each group of bars, as wide as the group.
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            labels.chunked(labelEvery).forEach { group ->
                Text(group.first(), Modifier.weight(group.size.toFloat()), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1,
                    softWrap = false)
            }
        }
    }
}

/** The covers of what was played most, scattered and turned a little, filling the room there is. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Collage(summary: ReplaySummary) {
    val covers = (summary.topSongs.map { it.thumbnailUrl } + summary.topAlbums.map { it.thumbnailUrl } + summary.topArtists.map { it.thumbnailUrl })
        .filterNotNull().distinct().take(7)
    // Where each sits, as fractions of the space, its size as a share of the width, turn and whether it's round.
    val spots = listOf(
        Triple(0.22f to 0.30f, 0.52f, -4f), Triple(0.02f to 0.04f, 0.36f, -8f), Triple(0.60f to 0.02f, 0.30f, 7f),
        Triple(0.66f to 0.40f, 0.30f, 0f), Triple(0.00f to 0.62f, 0.26f, 0f), Triple(0.44f to 0.74f, 0.28f, 0f),
        Triple(0.74f to 0.78f, 0.20f, 5f),
    )
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 20.dp)) {
        val unit = minOf(maxWidth, maxHeight * 0.75f)
        covers.forEachIndexed { i, url ->
            val (at, share, turn) = spots[i]
            val round = i >= 3
            Artwork(url.artworkAt(CARD_ART_PX), Modifier.offset(maxWidth * at.first, maxHeight * at.second).size(unit * share).rotate(turn),
                if (round) CircleShape else RoundedCornerShape(8.dp))
        }
    }
}

private val HOURS = List(24) { h -> when (h) { 0 -> "12a"; 12 -> "12p"; else -> if (h < 12) "${h}a" else "${h - 12}p" } }
private val WEEKDAYS = listOf("M", "T", "W", "T", "F", "S", "S")

private fun storyPages(s: ReplaySummary, range: ReplayRange): List<StoryPage> = buildList {
    val word = range.word
    add(StoryPage(Color(0xFF5E5A26), Color(0xFF2B2913)) {
        Heading("Your " to false, "Replay" to true, " is ready." to false)
        if (s.totalPlays == 0) {
            Footnote("Nothing played yet. Listen for a while and come back.")
            Spacer(Modifier.weight(1f))
        } else Collage(s)
        Footnote("Built privately from your listening history on this device.")
    })
    if (s.totalPlays == 0) return@buildList
    add(StoryPage(Color(0xFF6B1F45), Color(0xFF2A0D1C)) {
        Heading("You spent " to false, com.opentune.i18n.Tr.f("{0} minutes", minutes(s.listenedMs)) to true, " with music." to false)
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            BigNumber("${minutes(s.listenedMs)}", "MINUTES")
        }
        val hours = TimeUnit.MILLISECONDS.toHours(s.listenedMs)
        Figures(
            "$hours" to if (hours == 1L) "hour" else "hours",
            "${s.totalPlays}" to "plays",
            "${s.activeDays}" to if (s.activeDays == 1) "day" else "days",
        )
        Spacer(Modifier.height(12.dp))
        Bars("When you listen", s.byHour, HOURS, labelEvery = 6)
        s.biggestDay?.let { (day, ms) ->
            Spacer(Modifier.height(12.dp))
            val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(day))
            Footnote(com.opentune.i18n.Tr.f("Your biggest day was {0}, with {1} minutes.", date, minutes(ms)))
        }
    })
    s.topArtists.firstOrNull()?.let { a ->
        add(StoryPage(Color(0xFF2F6E52), Color(0xFF173528)) {
            Heading("${range.opening}, " to false, "one artist" to true, " kept coming back." to false)
            Hero(a, round = true, minutes = minutes(a.listenedMs), plays = a.plays)
            RunnersUp(s.topArtists, round = true)
        })
    }
    s.topSongs.firstOrNull()?.let { t ->
        add(StoryPage(Color(0xFF3D7146), Color(0xFF1B3520)) {
            Heading("In total, " to false, com.opentune.i18n.Tr.f("{0} songs", s.songCount) to true, com.opentune.i18n.Tr.f(" filled your {0}.", word) to false)
            Hero(t, round = false, minutes = minutes(t.listenedMs), plays = t.plays)
            RunnersUp(s.topSongs, round = false)
        })
    }
    s.topAlbums.firstOrNull()?.let { al ->
        add(StoryPage(Color(0xFF3B2F94), Color(0xFF1A1547)) {
            Heading("This time, " to false, "one album" to true, " held your attention." to false)
            Hero(al, round = false, minutes = minutes(al.listenedMs), plays = al.plays)
            RunnersUp(s.topAlbums, round = false)
        })
    }
    add(StoryPage(Color(0xFF7A3A4E), Color(0xFF2E1520)) {
        Heading("You explored " to false, com.opentune.i18n.Tr.f("{0} songs", s.songCount) to true, " from " to false, com.opentune.i18n.Tr.f("{0} artists", s.artistCount) to true, "." to false)
        // The artists, as a wall of faces.
        val faces = s.topArtists.distinctBy { it.thumbnailUrl ?: it.key }.map { it.thumbnailUrl }.take(12)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            val columns = 4
            val rows = (faces.size + columns - 1) / columns
            val side = minOf((maxWidth - 12.dp * (columns - 1)) / columns, if (rows > 0) (maxHeight - 12.dp * (rows - 1)) / rows else maxHeight)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                faces.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { Artwork(it.artworkAt(CARD_ART_PX), Modifier.size(side), CircleShape) }
                    }
                }
            }
        }
        Figures("${s.songCount}" to "songs", "${s.artistCount}" to "artists", "${s.albumCount}" to "albums")
        Spacer(Modifier.height(12.dp))
        Bars("Your week", s.byWeekday, WEEKDAYS)
    })
    add(StoryPage(Color(0xFF6E4630), Color(0xFF2A1A10)) {
        Heading("Your " to false, (if (range == ReplayRange.ALL) "music" else word) to true, " in music." to false)
        // The top four songs' covers, as a square.
        val covers = s.topSongs.distinctBy { it.thumbnailUrl ?: it.key }.map { it.thumbnailUrl }.take(4)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth * 0.8f, maxHeight)
            if (covers.size >= 4) {
                Column(Modifier.size(side).clip(RoundedCornerShape(16.dp))) {
                    covers.chunked(2).forEach { row ->
                        Row(Modifier.weight(1f)) { row.forEach { Artwork(it.artworkAt(CARD_ART_PX), Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp)) } }
                    }
                }
            } else covers.firstOrNull()?.let { Artwork(it.artworkAt(CARD_ART_PX), Modifier.size(side), RoundedCornerShape(16.dp)) }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.18f)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryLine("Minutes", "${minutes(s.listenedMs)}")
            s.topSongs.firstOrNull()?.let { SummaryLine("Top song", it.title) }
            s.topArtists.firstOrNull()?.let { SummaryLine("Top artist", it.title) }
            s.topAlbums.firstOrNull()?.let { SummaryLine("Top album", it.title) }
            SummaryLine("Plays", "${s.totalPlays}")
        }
    })
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row {
        Text(label, Modifier.width(96.dp), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
