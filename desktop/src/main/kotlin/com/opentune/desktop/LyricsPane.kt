package com.opentune.desktop

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.ui.draw.blur

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.draw.drawWithContent
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.lyrics.SongParts
import com.opentune.data.lyrics.VOICE_SECOND
import com.opentune.data.lyrics.activeIndex
import com.opentune.data.lyrics.lyricsOffset
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.LyricsSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What the lyrics pane shows for the song playing. */
internal sealed interface LyricsState {
    data object Loading : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
    data object NotFound : LyricsState
}

/**
 * The song's lyrics from the same sources the phone asks, in the order set
 * in Settings, found again when that order or this song's pick changes.
 */
@Composable
internal fun rememberLyrics(song: Song): LyricsState {
    val settings by AppSettings.lyrics.collectAsState()
    var state by remember(song.videoId) { mutableStateOf<LyricsState>(LyricsState.Loading) }
    val asked = Triple(settings.ordered, settings.preferWordSynced, settings.chosen[song.videoId])
    LaunchedEffect(song.videoId, asked) {
        state = LyricsState.Loading
        // Sources match on the length, and the player's is exact where the row's "3:45" is rounded.
        val duration = withTimeoutOrNull(20_000) {
            Player.durationOf.first { (id, ms) -> id == song.videoId && ms > 0 }.second
        } ?: 0L
        LyricsRepository.lyricsUpdates(song.videoId, song.title, song.artist, duration).collect { found ->
            state = found?.let(LyricsState::Found) ?: LyricsState.NotFound
        }
    }
    return state
}

/**
 * Lyrics beside the cover: the line being sung large and lit word by word,
 * the rest dimmed. Clicking a line plays from it. Above them, where they came
 * from (pick another source for this song), the timing offset, and a jump to
 * the next chorus.
 */
@Composable
internal fun LyricsPane(song: Song, modifier: Modifier = Modifier) {
    val state = rememberLyrics(song)
    val settings by AppSettings.lyrics.collectAsState()
    val lyrics = (state as? LyricsState.Found)?.lyrics
    val offset = lyricsOffset(settings.offsets[song.videoId], lyrics)
    // Translated a line under each, as on the phone, when a language is chosen.
    val target = settings.translateTo
    val lines = remember(lyrics) {
        when (lyrics) {
            is Lyrics.Synced -> lyrics.lines.map(::lyricDisplayText)
            is Lyrics.Plain -> lyrics.text.lines()
            else -> emptyList()
        }
    }
    val translated by androidx.compose.runtime.produceState<List<String?>>(emptyList(), lines, target) {
        value = emptyList()
        if (target.isBlank() || lines.isEmpty()) return@produceState
        value = com.opentune.data.lyrics.LyricsTranslator.translate(lines, target).getOrDefault(emptyList())
    }
    val ui by AppSettings.ui.collectAsState()
    Column(modifier) {
        LyricsHeader(song, lyrics, offset)
        // The lyrics fade in once found, rather than popping in over the spinner, as the web's do.
        androidx.compose.animation.AnimatedContent(
            state is LyricsState.Loading,
            transitionSpec = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if (ui.reduceAnimation) 120 else 400)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
            },
            label = "lyrics shown",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { loading ->
            Box(Modifier.fillMaxSize()) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    lyrics is Lyrics.Synced -> SyncedLyrics(song, lyrics, offset, translated)
                    lyrics is Lyrics.Plain -> PlainLyrics(lyrics.text, translated)
                    else -> Column(
                        Modifier.align(Alignment.Center).padding(horizontal = 24.dp).padding(bottom = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No lyrics available", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                        Text(
                            "None of the lyric sources has this song. Try another in the list above.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A small control above the lyrics, as the web's are: 12sp, grey, a soft fill
 * under the pointer; [lit] in the key colour.
 */
@Composable
private fun HeaderChip(text: String, onClick: () -> Unit, lit: Boolean = false, menu: Boolean = false, modifier: Modifier = Modifier) {
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    val fill by animateColorAsState(if (hovered) quinaryFill() else MaterialTheme.colorScheme.onSurface.copy(alpha = 0f), androidx.compose.animation.core.tween(150), label = "chip")
    val ink = if (lit) keyColour() else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.pressGive(hover, to = 0.96f).clip(shape).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = com.opentune.ui.components.FocusRing(shape), role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = ink, maxLines = 1, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold))
        if (menu) Icon(Icons.Rounded.ExpandMore, null, tint = ink, modifier = Modifier.padding(start = 2.dp).size(15.dp))
    }
}

@Composable
private fun LyricsHeader(song: Song, lyrics: Lyrics?, offset: Long) {
    val scheme = MaterialTheme.colorScheme
    val settings by AppSettings.lyrics.collectAsState()
    var picking by remember { mutableStateOf(false) }
    // On one line where there's room (the full player), over two in the narrow column.
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            HeaderChip(lyrics?.source?.let { "From $it" } ?: "Lyrics source", { picking = true }, menu = true)
            DropdownMenu(picking, onDismissRequest = { picking = false }) {
                val chosen = settings.chosen[song.videoId]
                DropdownMenuItem(
                    text = { Text("Best match") },
                    leadingIcon = { if (chosen == null) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { AppSettings.setLyricsSource(song.videoId, null); picking = false },
                )
                LyricsSource.entries.forEach { source ->
                    DropdownMenuItem(
                        text = { Text(source.label) },
                        leadingIcon = { if (chosen == source) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                        onClick = { AppSettings.setLyricsSource(song.videoId, source); picking = false },
                    )
                }
            }
        }
        TranslateButton(settings.translateTo, settings.translateTarget)
        if (lyrics is Lyrics.Synced) Row(verticalAlignment = Alignment.CenterVertically) {
            val choruses = remember(lyrics) { SongParts.choruses(lyrics) }
            if (choruses.isNotEmpty()) {
                HeaderChip("Skip to chorus", {
                    SongParts.nextChorus(choruses, Player.positionMs() + offset)?.let { Player.seekTo((it - offset).coerceAtLeast(0)) }
                })
            }
            // Earlier or later by a tenth of a second, kept for this song, as the phone's "Lyrics offset":
            // a positive shift shows the words earlier. A 0 set here is kept when it overrides the automatic shift.
            fun set(ms: Long) = AppSettings.setLyricsOffset(song.videoId, ms.coerceIn(-10_000, 10_000), keepZero = lyrics.autoOffsetMs != 0L)
            HoverIcon(Icons.Rounded.Remove, "Lyrics later", size = 28.dp, iconSize = 16.dp, shape = RoundedCornerShape(8.dp)) { set(offset - 100) }
            Text(
                when {
                    offset == 0L -> "In step"
                    offset > 0 -> "%.1f s earlier".format(offset / 1000f)
                    else -> "%.1f s later".format(-offset / 1000f)
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
                maxLines = 1,
                // Clicking it goes back to the automatic shift.
                modifier = Modifier.width(80.dp).clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Back to the automatic timing") { AppSettings.setLyricsOffset(song.videoId, 0) },
            )
            HoverIcon(Icons.Rounded.Add, "Lyrics earlier", size = 28.dp, iconSize = 16.dp, shape = RoundedCornerShape(8.dp)) { set(offset + 100) }
        }
    }
}

@Composable
private fun SyncedLyrics(song: Song, lyrics: Lyrics.Synced, offset: Long, translated: List<String?>) {
    var position by remember(song.videoId) { mutableLongStateOf(0L) }
    LaunchedEffect(song.videoId) {
        while (true) {
            position = Player.positionMs()
            delay(40)
        }
    }
    val at = position + offset
    val active = lyrics.lines.activeIndex(at)
    val list = rememberLazyListState()
    LaunchedEffect(active) {
        if (active < 0) return@LaunchedEffect
        // The line sung keeps a third of the way down.
        val third = list.layoutInfo.viewportSize.height / 3
        list.animateScrollToItem(active, -third)
    }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(top = 24.dp, bottom = 240.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        // Soft at the top and the foot, so lines fade into the edges rather than being cut off there.
        modifier = Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(LYRIC_FADE, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            },
    ) {
        itemsIndexed(lyrics.lines, key = { i, l -> "$i:${l.startMs}" }, contentType = { i, _ -> i }) { i, line ->
            // Only the line being sung follows the clock; the rest stay as they are, so the
            // 25-a-second position updates redraw one row rather than the whole list.
            LyricRow(line, i == active, if (i == active) at else 0L, translated.getOrNull(i)) { Player.seekTo((line.startMs - offset).coerceAtLeast(0)) }
        }
    }
}

@Composable
private fun LyricRow(line: LyricLine, active: Boolean, at: Long, translation: String?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val lit = scheme.onSurface
    val dim = scheme.onSurface.copy(alpha = 0.32f)
    val ink by animateColorAsState(if (active) lit else dim)
    val scale by animateFloatAsState(if (active) 1f else 0.94f, androidx.compose.animation.core.spring(dampingRatio = 1f))
    val second = line.voice == VOICE_SECOND
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = if (second) Alignment.End else Alignment.Start,
    ) {
        val style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        val text = if (active && line.wordSynced && line.words.isNotEmpty()) {
            // Each word fills in as it's sung.
            buildAnnotatedString {
                line.words.forEach { w ->
                    val f = when {
                        at >= w.endMs -> 1f
                        at <= w.startMs -> 0f
                        else -> (at - w.startMs).toFloat() / (w.endMs - w.startMs).coerceAtLeast(1)
                    }
                    withStyle(SpanStyle(color = lerp(dim, lit, f))) { append(w.text) }
                }
            }
        } else {
            buildAnnotatedString { withStyle(SpanStyle(color = ink)) { append(lyricDisplayText(line)) } }
        }
        Text(
            text, style = style,
            modifier = Modifier.fillMaxWidth().graphicsLayer {
                scaleX = scale; scaleY = scale
                transformOrigin = TransformOrigin(if (second) 1f else 0f, 0.5f)
            },
            textAlign = if (second) TextAlign.End else TextAlign.Start,
        )
        line.background?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = if (active) lit.copy(alpha = 0.7f) else dim, modifier = Modifier.padding(top = 2.dp))
        }
        translation?.takeIf { it.isNotBlank() && it != lyricDisplayText(line) }?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = if (active) lit.copy(alpha = 0.75f) else dim, modifier = Modifier.padding(top = 2.dp),
                textAlign = if (second) TextAlign.End else TextAlign.Start)
        }
    }
}

/** Translate: a language to show the lyrics in, a line under each, or off. The last one picked comes first. */
@Composable
private fun TranslateButton(current: String, last: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        HeaderChip(
            com.opentune.data.lyrics.LyricsTranslator.languageName(current)?.let { "In $it" } ?: "Translate", { open = true },
            lit = current.isNotBlank(), menu = true,
        )
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            fun pick(code: String) {
                open = false
                AppSettings.updateLyrics { it.copy(translateTo = code, translateTarget = code.ifBlank { it.translateTarget }) }
            }
            if (current.isNotBlank()) DropdownMenuItem(text = { Text("Off") }, onClick = { pick("") })
            val languages = com.opentune.data.lyrics.LyricsTranslator.LANGUAGES
            (languages.filter { it.code == last } + languages.filter { it.code != last }).forEach { l ->
                DropdownMenuItem(
                    text = { Text(l.name) },
                    leadingIcon = { if (l.code == current) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { pick(l.code) },
                )
            }
        }
    }
}

@Composable
private fun PlainLyrics(text: String, translated: List<String?>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp)) {
        if (translated.isEmpty()) Text(
            text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            lineHeight = MaterialTheme.typography.titleLarge.lineHeight * 1.2f,
        ) else text.lines().forEachIndexed { i, line ->
            Text(line, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
            translated.getOrNull(i)?.takeIf { it.isNotBlank() && it != line }?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Text(
            "These lyrics aren't timed to the song.", style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            modifier = Modifier.padding(top = 24.dp, bottom = 120.dp),
        )
    }
}

private val LYRIC_FADE = androidx.compose.ui.graphics.Brush.verticalGradient(
    0f to Color.Transparent, 0.06f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent,
)

/** Timed syllables already contain their intended spacing; never insert extra spaces. */
internal fun lyricDisplayText(line: LyricLine): String =
    if (line.words.isEmpty()) line.text else line.words.joinToString("") { it.text }

// ---- The full player's lyrics ------------------------------------------------

/**
 * The full player's lyrics, as the reference sets them: big, bold and left
 * aligned, the line being sung pure white (lit word by word where the source
 * times words), the lines to come a soft white that blurs a little more the
 * further off they are, the lines sung dimmer still. The line being sung
 * keeps about a sixth of the way down, the list easing there over 450ms.
 * Clicking a line plays from it. At the foot: a globe for the source and the
 * timing, who the lyrics are by, and translate. [override] stands in for the
 * lyrics found (for pictures).
 */
@Composable
internal fun PlayerLyricsPanel(song: Song, modifier: Modifier = Modifier, override: LyricsState? = null) {
    val state = override ?: rememberLyrics(song)
    val settings by AppSettings.lyrics.collectAsState()
    val lyrics = (state as? LyricsState.Found)?.lyrics
    val offset = lyricsOffset(settings.offsets[song.videoId], lyrics)
    val target = settings.translateTo
    val lines = remember(lyrics) {
        when (lyrics) {
            is Lyrics.Synced -> lyrics.lines.map(::lyricDisplayText)
            is Lyrics.Plain -> lyrics.text.lines()
            else -> emptyList()
        }
    }
    val translated by androidx.compose.runtime.produceState<List<String?>>(emptyList(), lines, target) {
        value = emptyList()
        if (target.isBlank() || lines.isEmpty()) return@produceState
        value = com.opentune.data.lyrics.LyricsTranslator.translate(lines, target).getOrDefault(emptyList())
    }
    val ui by AppSettings.ui.collectAsState()
    Column(modifier) {
        androidx.compose.animation.AnimatedContent(
            when {
                state is LyricsState.Loading -> 0
                lyrics != null -> 1
                else -> 2
            },
            transitionSpec = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if (ui.reduceAnimation) 120 else 400)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
            },
            label = "player lyrics shown",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { shown ->
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val big = if (maxWidth < 520.dp) 30.sp else 40.sp
                when {
                    shown == 0 -> PlayerLyricsLoading(big)
                    lyrics is Lyrics.Synced -> PlayerSyncedLyrics(song, lyrics, offset, translated, big, maxHeight, blur = !ui.reduceBlur)
                    lyrics is Lyrics.Plain -> PlayerPlainLyrics(lyrics.text, translated, big)
                    else -> Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp).padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No lyrics for this song", color = Color.White.copy(alpha = 0.85f), style = playerLyricType(22, 28, FontWeight.Bold), textAlign = TextAlign.Center)
                        Text(
                            "None of the lyric sources has it yet. Try another source with the globe below.",
                            color = Color.White.copy(alpha = 0.5f), style = playerLyricType(15, 21), textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp).widthIn(max = 360.dp),
                        )
                    }
                }
            }
        }
        PlayerLyricsFooter(song, lyrics, offset)
    }
}

@Composable
private fun playerLyricType(size: Int, line: Int, weight: FontWeight = FontWeight.Normal): androidx.compose.ui.text.TextStyle =
    MaterialTheme.typography.bodyLarge.copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp)

/** The big lyric face: DM Sans (the theme's) at its heaviest, lines set tight. */
@Composable
private fun playerBigLyric(size: androidx.compose.ui.unit.TextUnit): androidx.compose.ui.text.TextStyle =
    MaterialTheme.typography.displaySmall.copy(
        fontSize = size, lineHeight = size * 1.25f, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01f * size.value).sp,
    )

/** Three soft bars where the lines will be, while the sources are asked. */
@Composable
private fun PlayerLyricsLoading(size: androidx.compose.ui.unit.TextUnit) {
    val h = with(androidx.compose.ui.platform.LocalDensity.current) { size.toDp() }
    Column(Modifier.fillMaxSize().padding(start = 12.dp, top = 80.dp), verticalArrangement = Arrangement.spacedBy(h * 0.6f)) {
        listOf(0.78f, 0.6f, 0.7f).forEach { w -> Placeholder(Modifier.fillMaxWidth(w).height(h * 0.8f), RoundedCornerShape(10.dp)) }
    }
}

@Composable
private fun PlayerSyncedLyrics(
    song: Song,
    lyrics: Lyrics.Synced,
    offset: Long,
    translated: List<String?>,
    size: androidx.compose.ui.unit.TextUnit,
    height: androidx.compose.ui.unit.Dp,
    blur: Boolean,
) {
    var position by remember(song.videoId) { mutableLongStateOf(Player.positionMs()) }
    LaunchedEffect(song.videoId) {
        while (true) {
            position = Player.positionMs()
            delay(40)
        }
    }
    val at = position + offset
    val active = lyrics.lines.activeIndex(at)
    val list = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The line sung sits a sixth of the way down: the list starts there, and is moved to keep it there.
    val top = height / 6
    LaunchedEffect(active) {
        if (active < 0) return@LaunchedEffect
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
        if (item == null) {
            list.scrollToItem(active)
            return@LaunchedEffect
        }
        val by = item.offset - with(density) { top.toPx() }
        list.animateScrollBy(by, androidx.compose.animation.core.tween(450, easing = PLAYER_LYRIC_EASE))
    }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(top = top, bottom = height * 0.7f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(PLAYER_LYRIC_FADE, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            },
    ) {
        // Each line keeps its own row: reused for another line, a row's blur layer showed the old
        // line's text through the new one's, so lines seemed written over each other.
        itemsIndexed(lyrics.lines, key = { i, l -> "$i:${l.startMs}" }, contentType = { i, _ -> i }) { i, line ->
            PlayerLyricRow(
                line, distance = if (active < 0) i + 1 else i - active, at = if (i == active) at else 0L,
                translation = translated.getOrNull(i), size = size, blur = blur,
            ) { Player.seekTo((line.startMs - offset).coerceAtLeast(0)) }
        }
    }
}

/** An ease out for the lyrics following the song. */
private val PLAYER_LYRIC_EASE = androidx.compose.animation.core.CubicBezierEasing(0.25f, 1f, 0.5f, 1f)

/** Soft at the top and foot, so lines fade into the edges. */
private val PLAYER_LYRIC_FADE = androidx.compose.ui.graphics.Brush.verticalGradient(
    0f to Color.Transparent, 0.08f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent,
)

/**
 * One line. [distance] is how far it is from the line being sung: 0 is that
 * line, lit (word by word when timed so); after it, a soft white that blurs
 * more the further off it is; before it, dimmer.
 */
@Composable
private fun PlayerLyricRow(
    line: LyricLine,
    distance: Int,
    at: Long,
    translation: String?,
    size: androidx.compose.ui.unit.TextUnit,
    blur: Boolean,
    onClick: () -> Unit,
) {
    val lit = Color.White
    val alpha by animateFloatAsState(
        when {
            distance == 0 -> 1f
            distance > 0 -> 0.35f
            else -> 0.2f
        },
        androidx.compose.animation.core.tween(450), label = "lyric alpha",
    )
    val soft by animateFloatAsState(
        if (!blur || distance == 0) 0f else (kotlin.math.abs(distance) * 0.9f + 0.4f).coerceAtMost(4f),
        androidx.compose.animation.core.tween(450), label = "lyric blur",
    )
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val fill by animateColorAsState(Color.White.copy(alpha = if (hovered) 0.06f else 0f), androidx.compose.animation.core.tween(150), label = "lyric hover")
    val second = line.voice == VOICE_SECOND
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            // One layer that's always there, its blur set as it's drawn: adding and taking away
            // the blur modifier as a line came near and went made a new layer each time, and
            // one could keep a picture of what it showed before.
            .graphicsLayer {
                val r = soft.dp.toPx()
                renderEffect = if (soft > 0.05f) androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Decal) else null
                clip = false
            },
        horizontalAlignment = if (second) Alignment.End else Alignment.Start,
    ) {
        val style = playerBigLyric(size)
        val dimWord = lit.copy(alpha = 0.35f)
        val text = if (distance == 0 && line.wordSynced && line.words.isNotEmpty()) {
            buildAnnotatedString {
                line.words.forEach { w ->
                    val f = when {
                        at >= w.endMs -> 1f
                        at <= w.startMs -> 0f
                        else -> (at - w.startMs).toFloat() / (w.endMs - w.startMs).coerceAtLeast(1)
                    }
                    withStyle(SpanStyle(color = lerp(dimWord, lit, f))) { append(w.text) }
                }
            }
        } else {
            buildAnnotatedString { withStyle(SpanStyle(color = lit.copy(alpha = alpha))) { append(lyricDisplayText(line).ifBlank { "♪" }) } }
        }
        Text(text, style = style, textAlign = if (second) TextAlign.End else TextAlign.Start, modifier = Modifier.fillMaxWidth())
        val small = playerLyricType((size.value * 0.5f).toInt(), (size.value * 0.66f).toInt(), FontWeight.Bold)
        line.background?.let {
            Text(it, style = small, color = lit.copy(alpha = alpha * 0.7f), modifier = Modifier.padding(top = 2.dp))
        }
        translation?.takeIf { it.isNotBlank() && it != lyricDisplayText(line) }?.let {
            Text(
                it, style = small, color = lit.copy(alpha = alpha * 0.75f), modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                textAlign = if (second) TextAlign.End else TextAlign.Start,
            )
        }
    }
}

@Composable
private fun PlayerPlainLyrics(text: String, translated: List<String?>, size: androidx.compose.ui.unit.TextUnit) {
    val style = playerBigLyric(size * 0.75f)
    Column(
        Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent { drawContent(); drawRect(PLAYER_LYRIC_FADE, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn) }
            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 48.dp),
    ) {
        text.lines().forEachIndexed { i, line ->
            Text(line, style = style, color = Color.White.copy(alpha = 0.85f))
            translated.getOrNull(i)?.takeIf { it.isNotBlank() && it != line }?.let {
                Text(it, style = playerLyricType(17, 22, FontWeight.Bold), color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Text(
            "These lyrics aren't timed to the song.", style = playerLyricType(13, 18), color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 24.dp, bottom = 120.dp),
        )
    }
}

/**
 * Under the lyrics: a globe (where they come from, the timing, a jump to the
 * chorus), "Lyrics by" the source in the middle, and translate at the right.
 */
@Composable
private fun PlayerLyricsFooter(song: Song, lyrics: Lyrics?, offset: Long) {
    val settings by AppSettings.lyrics.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var translating by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            PlayerLyricCircle(Icons.Rounded.Language, "Lyrics source and timing", lit = settings.chosen[song.videoId] != null) { picking = true }
            PlayerLyricsSourceMenu(song, lyrics, offset, picking) { picking = false }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            Text(
                lyrics?.source?.let { "Lyrics by $it" } ?: "Choose a lyrics source",
                color = Color.White.copy(alpha = if (hovered) 0.85f else 0.55f),
                style = playerLyricType(14, 18, FontWeight.Medium).copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).hoverable(hover)
                    .clickable(interactionSource = hover, indication = null, role = androidx.compose.ui.semantics.Role.Button) { picking = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Box {
            PlayerLyricCircle(Icons.Rounded.Translate, "Translate lyrics", lit = settings.translateTo.isNotBlank()) { translating = true }
            PlayerTranslateMenu(settings.translateTo, settings.translateTarget, translating) { translating = false }
        }
    }
}

/** A 40dp circle with an icon, a soft fill under the pointer, a firmer one when [lit]. */
@Composable
private fun PlayerLyricCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, lit: Boolean, onClick: () -> Unit) {
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val fill by animateColorAsState(
        Color.White.copy(alpha = when { lit -> 0.2f; hovered -> 0.14f; else -> 0.08f }), androidx.compose.animation.core.tween(150), label = "lyric circle",
    )
    Box(
        Modifier.size(40.dp).pressGive(hover).clip(androidx.compose.foundation.shape.CircleShape).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = com.opentune.ui.components.FocusRing(androidx.compose.foundation.shape.CircleShape), role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White.copy(alpha = if (lit || hovered) 1f else 0.8f), modifier = Modifier.size(20.dp)) }
}

/** The globe's menu: the source for this song, then the timing and a jump to the chorus. */
@Composable
private fun PlayerLyricsSourceMenu(song: Song, lyrics: Lyrics?, offset: Long, open: Boolean, onDismiss: () -> Unit) {
    val settings by AppSettings.lyrics.collectAsState()
    DropdownMenu(open, onDismissRequest = onDismiss) {
        val chosen = settings.chosen[song.videoId]
        Text(
            "Lyrics source", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        DropdownMenuItem(
            text = { Text("Best match") },
            leadingIcon = { if (chosen == null) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
            onClick = { AppSettings.setLyricsSource(song.videoId, null); onDismiss() },
        )
        LyricsSource.entries.forEach { source ->
            DropdownMenuItem(
                text = { Text(source.label) },
                leadingIcon = { if (chosen == source) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                onClick = { AppSettings.setLyricsSource(song.videoId, source); onDismiss() },
            )
        }
        if (lyrics is Lyrics.Synced) {
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val choruses = remember(lyrics) { SongParts.choruses(lyrics) }
            if (choruses.isNotEmpty()) DropdownMenuItem(
                text = { Text("Skip to chorus") },
                leadingIcon = { Spacer(Modifier.size(24.dp)) },
                onClick = {
                    SongParts.nextChorus(choruses, Player.positionMs() + offset)?.let { Player.seekTo((it - offset).coerceAtLeast(0)) }
                    onDismiss()
                },
            )
            // Earlier or later by a tenth of a second, kept for this song, as in the column's lyrics.
            fun set(ms: Long) = AppSettings.setLyricsOffset(song.videoId, ms.coerceIn(-10_000, 10_000), keepZero = lyrics.autoOffsetMs != 0L)
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Timing", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 12.dp))
                HoverIcon(Icons.Rounded.Remove, "Lyrics later", size = 30.dp, iconSize = 16.dp, shape = RoundedCornerShape(8.dp)) { set(offset - 100) }
                Text(
                    when {
                        offset == 0L -> "In step"
                        offset > 0 -> "%.1f s earlier".format(offset / 1000f)
                        else -> "%.1f s later".format(-offset / 1000f)
                    },
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.width(92.dp).clip(RoundedCornerShape(6.dp))
                        .clickable(onClickLabel = "Back to the automatic timing") { AppSettings.setLyricsOffset(song.videoId, 0) },
                )
                HoverIcon(Icons.Rounded.Add, "Lyrics earlier", size = 30.dp, iconSize = 16.dp, shape = RoundedCornerShape(8.dp)) { set(offset + 100) }
            }
        }
    }
}

/** Translate's menu: off, or a language, the one picked last first. */
@Composable
private fun PlayerTranslateMenu(current: String, last: String, open: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(open, onDismissRequest = onDismiss, modifier = Modifier.heightIn(max = 420.dp)) {
        fun pick(code: String) {
            onDismiss()
            AppSettings.updateLyrics { it.copy(translateTo = code, translateTarget = code.ifBlank { it.translateTarget }) }
        }
        if (current.isNotBlank()) DropdownMenuItem(text = { Text("Off") }, onClick = { pick("") })
        val languages = com.opentune.data.lyrics.LyricsTranslator.LANGUAGES
        (languages.filter { it.code == last } + languages.filter { it.code != last }).forEach { l ->
            DropdownMenuItem(
                text = { Text(l.name) },
                leadingIcon = { if (l.code == current) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                onClick = { pick(l.code) },
            )
        }
    }
}
