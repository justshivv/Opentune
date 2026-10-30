package com.opentune.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.opentune.ui.components.glass
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.activeIndex
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.delay

/** How wide the icon rail down the left is, and the column on the right. */
internal val RAIL_WIDTH = 68.dp
internal val PANEL_WIDTH = 320.dp
/** Room the floating player keeps clear under every page. */
internal val PILL_ROOM = 104.dp

/** The colour that marks the page showing in the rail: the theme's own accent. */
@Composable
internal fun railAccent(): Color {
    val theme by AppSettings.theme.collectAsState()
    return Color(theme.seedColor)
}

// ---- The rail ----------------------------------------------------------------

/** One place the rail leads to. */
internal data class RailItem(val key: Any, val icon: ImageVector, val label: String)

/**
 * Down the left: the logo, then the pages as icons, grouped by dividers,
 * with Settings at the foot. The page showing has a short bar in the accent
 * at the rail's edge and its icon in the accent; hovering an icon names it.
 */
@Composable
internal fun Rail(groups: List<List<RailItem>>, selected: Any?, canGoBack: Boolean, onBack: () -> Unit, settings: RailItem, onPick: (Any) -> Unit) {
    val divider = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(RAIL_WIDTH).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(top = DesktopWindow.topInset + 14.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(rememberLogo(Logo.MARK), "OpenTune", Modifier.size(28.dp))
            Spacer(Modifier.height(10.dp))
            // Back, quiet until there's somewhere to go back to.
            RailButton(RailItem("back", Icons.AutoMirrored.Rounded.ArrowBack, "Back"), selected = false, enabled = canGoBack) { onBack() }
            groups.forEachIndexed { i, group ->
                if (i > 0) HorizontalDivider(color = divider, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                else Spacer(Modifier.height(10.dp))
                group.forEach { item -> RailButton(item, selected == item.key) { onPick(item.key) } }
            }
            Spacer(Modifier.weight(1f))
            RailButton(settings, selected == settings.key) { onPick(settings.key) }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailButton(item: RailItem, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = railAccent()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val fill by animateColorAsState(
        when { selected -> accent.copy(alpha = 0.16f); hovered && enabled -> onSurface.copy(alpha = 0.08f); else -> Color.Transparent }, tween(160), label = "rail fill",
    )
    val ink by animateColorAsState(
        when { selected -> accent; !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f); else -> MaterialTheme.colorScheme.onSurfaceVariant }, tween(160), label = "rail ink",
    )
    val bar by animateDpAsState(if (selected) 18.dp else 0.dp, Motion.soft(), label = "rail bar")
    TooltipArea(
        tooltip = {
            Text(
                item.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.shadow(6.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.CenterEnd, Alignment.CenterEnd, DpOffset(8.dp, 0.dp)),
    ) {
        Box(Modifier.width(RAIL_WIDTH).height(46.dp), contentAlignment = Alignment.Center) {
            // The bar at the rail's edge, growing in when the page is chosen.
            Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(bar).clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)).background(accent))
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(fill).hoverable(hover)
                    .clickable(enabled = enabled, interactionSource = hover, indication = null, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { Icon(item.icon, item.label, tint = ink, modifier = Modifier.size(21.dp)) }
        }
    }
}

// ---- The floating player -----------------------------------------------------

/**
 * The player as a pill floating over the bottom of the page: shuffle,
 * previous, play, next and repeat; the song, with a thin seekable line along
 * the pill's foot (click the song for the full player); then the lyrics,
 * queue and Now playing columns and the volume.
 */
@Composable
internal fun PlayerPill(song: Song, panel: SidePanel?, onPanel: (SidePanel) -> Unit, onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val playing by Player.playing.collectAsState()
    val loading by Player.loading.collectAsState()
    val shuffled by Player.shuffled.collectAsState()
    val repeat by Player.repeat.collectAsState()
    val error by Player.error.collectAsState()
    val shape = RoundedCornerShape(18.dp)
    val progress = rememberProgress(song)
    Box(
        modifier.widthIn(max = 860.dp).fillMaxWidth().height(62.dp)
            .shadow(28.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            // Frosted glass over the page sliding under it; solid with blur turned off.
            .glass(shape, MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.66f)),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            HoverIcon(Icons.Rounded.Shuffle, if (shuffled) "Shuffle on" else "Shuffle", lit = shuffled, size = 32.dp, iconSize = 17.dp, onClick = Player::toggleShuffle)
            HoverIcon(Icons.Rounded.SkipPrevious, "Previous", size = 34.dp, iconSize = 22.dp, onClick = Player::previous)
            Box(
                Modifier.padding(horizontal = 4.dp).size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface).clickable(onClick = Player::toggle),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.surface)
                else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(24.dp))
            }
            HoverIcon(Icons.Rounded.SkipNext, "Next", size = 34.dp, iconSize = 22.dp, onClick = Player::next)
            HoverIcon(
                if (repeat == Player.Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                when (repeat) { Player.Repeat.OFF -> "Repeat off"; Player.Repeat.ALL -> "Repeat all"; Player.Repeat.ONE -> "Repeat this song" },
                lit = repeat != Player.Repeat.OFF, size = 32.dp, iconSize = 17.dp, onClick = Player::cycleRepeat,
            )
            Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
            val hover = remember { MutableInteractionSource() }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).hoverWash(hover, RoundedCornerShape(10.dp))
                    .clickable(interactionSource = hover, indication = null, onClick = onOpenPlayer).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(song.thumbnailUrl, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) }, label = "pill cover") { url ->
                    Artwork(url, Modifier.size(42.dp), RoundedCornerShape(8.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        error ?: song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${Time.format(progress.positionMs)} / ${Time.format(progress.durationMs)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp),
                )
            }
            Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
            HoverIcon(Icons.Rounded.Lyrics, "Lyrics", lit = panel == SidePanel.LYRICS, size = 32.dp, iconSize = 18.dp) { onPanel(SidePanel.LYRICS) }
            HoverIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Up next", lit = panel == SidePanel.QUEUE, size = 32.dp, iconSize = 19.dp) { onPanel(SidePanel.QUEUE) }
            HoverIcon(if (panel == SidePanel.NOW) Icons.Rounded.Info else Icons.Outlined.Info, "Now playing", lit = panel == SidePanel.NOW, size = 32.dp, iconSize = 18.dp) { onPanel(SidePanel.NOW) }
            Spacer(Modifier.width(4.dp))
            VolumeControl(slider = true)
            Spacer(Modifier.width(4.dp))
        }
        SeekLine(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp))
    }
}

// ---- Now playing -------------------------------------------------------------

/**
 * The column on the right, as BitChord's desktop has it: Now playing (the
 * cover, a few lines of the lyrics, the artist), the full lyrics, or the
 * queue, with a switch between them at its head.
 */
@Composable
internal fun RightPanel(panel: SidePanel, song: Song, queue: List<Song>, index: Int, onPanel: (SidePanel?) -> Unit, onOpenArtist: (ShelfItem) -> Unit) {
    Row(Modifier.width(PANEL_WIDTH).fillMaxHeight()) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = DesktopWindow.topInset)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SidePanel.entries.forEach { p -> Segment(p.label, p == panel) { onPanel(p) } }
                }
                HoverIcon(Icons.Rounded.Close, "Close", size = 30.dp, iconSize = 17.dp) { onPanel(null) }
            }
            AnimatedContent(panel, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "right panel", modifier = Modifier.fillMaxSize()) { p ->
                when (p) {
                    SidePanel.NOW -> NowPlayingCards(song, queue, index, onLyrics = { onPanel(SidePanel.LYRICS) }, onOpenArtist = onOpenArtist)
                    SidePanel.LYRICS -> LyricsPane(song, Modifier.fillMaxSize().padding(horizontal = 14.dp))
                    SidePanel.QUEUE -> androidx.compose.runtime.CompositionLocalProvider(LocalPageBottom provides PILL_ROOM) {
                        if (queue.isEmpty()) Message("The queue is empty.", null)
                        else SongList(queue, current = index) { _, i -> Player.playAt(i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCards(song: Song, queue: List<Song>, index: Int, onLyrics: () -> Unit, onOpenArtist: (ShelfItem) -> Unit) {
    val base = MaterialTheme.colorScheme.surfaceContainer
    val colour = rememberCoverColor(song.thumbnailUrl, base)
    val liked by Library.liked.collectAsState()
    val isLiked = liked.any { it.videoId == song.videoId }
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = PILL_ROOM),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "cover") {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                AnimatedContent(song.thumbnailUrl, transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) }, label = "panel cover") { url ->
                    Artwork(url, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(16.dp))
                }
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    LikeButton(isLiked, { Library.setLiked(song, !isLiked) }, idle = Color.White.copy(alpha = 0.8f), size = 22.dp)
                }
            }
        }
        item(key = "lyrics") { LyricsPreview(song, tint(colour, base, 0.42f), onLyrics) }
        item(key = "artist") { AboutArtist(song, base, onOpenArtist) }
        val next = queue.drop(index + 1).take(4)
        if (next.isNotEmpty()) item(key = "next") {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(base).padding(vertical = 10.dp)) {
                Text("Next up", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                next.forEachIndexed { i, s ->
                    val hover = remember { MutableInteractionSource() }
                    Row(
                        Modifier.fillMaxWidth().hoverWash(hover, RoundedCornerShape(0.dp)).clickable(interactionSource = hover, indication = null) { Player.playAt(index + 1 + i) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(s.thumbnailUrl, Modifier.size(40.dp), RoundedCornerShape(6.dp))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** The line being sung and the two after it, on a card in the cover's colour; Show more opens the full lyrics. */
@Composable
private fun LyricsPreview(song: Song, colour: Color, onMore: () -> Unit) {
    val state = rememberLyrics(song)
    val lyrics = (state as? LyricsState.Found)?.lyrics
    var position by remember(song.videoId) { mutableLongStateOf(0L) }
    LaunchedEffect(song.videoId) {
        while (true) {
            position = Player.positionMs()
            delay(250)
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colour).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics preview", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
            if (lyrics != null) {
                Text(
                    "Show more", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onMore).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            state is LyricsState.Loading -> repeat(3) { Placeholder(Modifier.padding(vertical = 4.dp).fillMaxWidth(0.8f - it * 0.15f).height(16.dp), RoundedCornerShape(6.dp)) }
            lyrics is Lyrics.Synced -> {
                val at = lyrics.lines.activeIndex(position).coerceAtLeast(0)
                val shown = lyrics.lines.drop(at).take(3)
                AnimatedContent(at, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) }, label = "preview line") { _ ->
                    Column {
                        shown.forEachIndexed { i, line ->
                            Text(
                                lyricDisplayText(line).ifBlank { "♪" },
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = if (i == 0) 1f else 0.55f - i * 0.12f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            lyrics is Lyrics.Plain -> Text(lyrics.text.lines().filter { it.isNotBlank() }.take(3).joinToString("\n"), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 3)
            else -> Text("No lyrics for this one", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** The artist: their photo, monthly listeners and the start of their story, and a way to their page. */
@Composable
private fun AboutArtist(song: Song, base: Color, onOpenArtist: (ShelfItem) -> Unit) {
    val artistId = song.artistId
    val page by produceState<ArtistPage?>(null, artistId) {
        value = artistId?.let { id -> runCatching { InnertubeParser.parseArtistPage(Innertube.browse(id)) }.getOrNull() }
    }
    val name = page?.name ?: song.artist.substringBefore(",").substringBefore(" & ")
    val apple by produceState<String?>(null, name) {
        value = runCatching { com.opentune.data.covers.AppleArtistArtRepository.artFor(name)?.heroUrl }.getOrNull()
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(base)) {
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            Artwork(apple ?: page?.thumbnailUrl ?: song.thumbnailUrl, Modifier.fillMaxSize(), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
            Text("About the artist", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(14.dp))
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (artistId != null) {
                    Text(
                        "View artist", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onOpenArtist(ShelfItem(name, "Artist", page?.thumbnailUrl, null, artistId)) }.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            (page?.monthlyListenerCount ?: page?.subscriberCountText)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            page?.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
