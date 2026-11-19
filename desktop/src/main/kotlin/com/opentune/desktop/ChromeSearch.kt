package com.opentune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchResult
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.ui.components.FocusRing

/** One of the few results shown while a search is being typed: a song, or something to open. */
internal sealed interface ChromeSearchHit {
    val key: String
    data class Track(val song: Song) : ChromeSearchHit { override val key get() = "s:" + song.videoId }
    data class Item(val item: ShelfItem, val kind: String?) : ChromeSearchHit { override val key get() = "i:" + (item.browseId ?: item.videoId ?: item.title) }
}

internal object ChromeSearchHits {
    /** YouTube Music's mixed search for what's typed so far, its first few rows of any kind. */
    suspend fun quick(query: String): List<ChromeSearchHit> =
        InnertubeParser.parseSearchPage(Innertube.search(query)).rows.mapNotNull { row ->
            when (row) {
                is SearchResult.TopTrack -> ChromeSearchHit.Track(row.song)
                is SearchResult.Track -> ChromeSearchHit.Track(row.song)
                is SearchResult.Browse -> row.item.let {
                    ChromeSearchHit.Item(
                        ShelfItem(it.title, it.subtitle, it.thumbnailUrl, null, it.browseId, it.explicit),
                        when (it.type) { BrowseType.ARTIST -> "Artist"; BrowseType.ALBUM -> null; BrowseType.PLAYLIST -> "Playlist"; BrowseType.OTHER -> null },
                    )
                }
            }
        }.distinctBy { it.key }.take(6)

    /** Opens a result: a video plays, anything else opens its page. */
    fun open(item: ShelfItem, onOpenAny: (Any) -> Unit) {
        if (item.videoId != null && item.browseId == null) Pages.songOf(item)?.let { Player.playAll(listOf(it), 0) }
        else onOpenAny(item)
    }
}

/** A result's name on the page's label, for screen readers. */
internal fun Modifier.chromeLabel(label: String): Modifier = semantics { contentDescription = label }

private val ROW_SHAPE = RoundedCornerShape(8.dp)

/** The faint fill a row takes under the pointer or the keyboard: at once on, easing off. */
@Composable
private fun rowWash(source: MutableInteractionSource): Color {
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val on = hovered || focused
    val wash by animateColorAsState(ChromeInk.hover().copy(alpha = if (on) ChromeInk.hover().alpha * 1.4f else 0f), tween(if (on) 0 else 150), label = "result wash")
    return wash
}

/**
 * One of YouTube Music's suggestions for what's typed: the magnifier, the suggestion in bold,
 * and at the far right an arrow pointing up and left that puts it in the field without
 * searching, so it can be typed on from. A click on the row searches for it.
 */
@Composable
internal fun ChromeSuggestionRow(text: String, onPick: () -> Unit, onFill: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(ROW_SHAPE).background(rowWash(source)).hoverable(source)
            .clickable(interactionSource = source, indication = FocusRing(ROW_SHAPE), role = Role.Button, onClickLabel = "Search for $text", onClick = onPick)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = ChromeInk.secondary(), modifier = Modifier.size(20.dp))
        Text(
            text, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
            style = resultType(16, 22, FontWeight.Bold), modifier = Modifier.weight(1f).padding(start = 16.dp),
        )
        HoverIcon(Icons.Rounded.NorthWest, "Fill in “$text”", size = 36.dp, iconSize = 18.dp, shape = RoundedCornerShape(8.dp), onClick = onFill)
    }
}

/** A hairline between the suggestions and the results under them. */
@Composable
internal fun ChromeSearchHairline() {
    Box(Modifier.padding(horizontal = GUTTER, vertical = 6.dp).fillMaxWidth().height(1.dp).background(ChromeInk.hairline()))
}

/** A kind's heading over its rows ("Songs", "Artists"), with Show all when there are more than the first few. */
@Composable
internal fun ChromeResultsHead(title: String, more: Boolean, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = GUTTER, end = GUTTER - 8.dp, top = 22.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = resultType(22, 28, FontWeight.Bold), modifier = Modifier.weight(1f).semantics { heading() })
        if (more) {
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Text(
                if (open) "Show less" else "Show all", style = resultType(14, 18, FontWeight.Medium),
                color = if (hovered) MaterialTheme.colorScheme.onSurface else ChromeInk.secondary(),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).hoverable(source)
                    .clickable(interactionSource = source, indication = FocusRing(RoundedCornerShape(6.dp)), role = Role.Button, onClick = onToggle)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * A result as a flat row, no card: the cover 64dp (round for an artist, 6dp corners otherwise),
 * the title in bold 17sp over a grey line; a faint fill under the pointer. [cover] can lay a
 * play button over the picture under the pointer; [trailing] sits at the far end.
 */
@Composable
private fun ChromeResultRow(
    url: String?,
    round: Boolean,
    title: String,
    subtitle: String,
    label: String,
    source: MutableInteractionSource = remember { MutableInteractionSource() },
    playable: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    pick: RowPick? = null,
    onClick: () -> Unit,
) {
    val hovered by source.collectIsHoveredAsState()
    val shade by animateFloatAsState(if (hovered && playable) 1f else 0f, tween(150), label = "result play")
    val shape = if (round) CircleShape else RoundedCornerShape(6.dp)
    val picked = pick?.picked == true
    val picking = pick?.selection?.active == true
    Row(
        Modifier.fillMaxWidth().height(80.dp).clip(ROW_SHAPE)
            .background(if (picked) keyColour().copy(alpha = 0.16f) else Color.Transparent)
            .background(rowWash(source)).hoverable(source)
            .then(pick?.capture ?: Modifier)
            .clickable(interactionSource = source, indication = FocusRing(ROW_SHAPE), role = Role.Button, onClickLabel = label) { pick?.click(onClick) ?: onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A song's box, at the row's start under the pointer and on every row while picking.
        if (pick != null) Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            if (hovered || picking) PickBox(picked, title, onToggle = pick::toggle)
        }
        Box(Modifier.size(64.dp)) {
            Cover(url, 64.dp, shape)
            if (shade > 0f && !picking) Box(
                Modifier.size(64.dp).clip(shape).graphicsLayer { alpha = shade }.background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, style = resultType(17, 22, FontWeight.Bold))
            if (subtitle.isNotBlank()) Text(
                subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ChromeInk.secondary(),
                style = resultType(14, 19), modifier = Modifier.padding(top = 2.dp),
            )
        }
        trailing?.invoke()
    }
}

/** A song found: plays from it on a click (the list it came in as the queue), its menu on a right click or under ⋯. */
@Composable
internal fun ChromeSongResultRow(song: Song, songs: List<Song>, index: Int, kind: String? = null) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val queue by Player.queue.collectAsState()
    val at by Player.index.collectAsState()
    val current = queue.getOrNull(at)?.videoId == song.videoId
    val line = listOfNotNull(kind, song.artist.takeIf { it.isNotBlank() }, song.albumName?.takeIf { it.isNotBlank() && kind == null }).joinToString(" • ")
    val pick = if (index >= 0) rememberRowPick(songs, index) else null
    SongContextMenu(song) {
        ChromeResultRow(
            song.thumbnailUrl, round = false,
            title = song.title, subtitle = line, label = "Play ${song.title}",
            source = source, playable = true, pick = pick,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current) Box(Modifier.padding(end = 12.dp)) { PlayingBars(Player.playing.collectAsState().value) }
                    Text(song.durationText.orEmpty(), color = ChromeInk.secondary(), style = resultType(14, 18), modifier = Modifier.padding(end = 4.dp))
                    Box(Modifier.width(36.dp).graphicsLayer { alpha = if (hovered) 1f else 0f }) { SongMoreButton(song, size = 32.dp) }
                }
            },
        ) { if (current) Player.toggle() else Player.playAll(songs, index.coerceAtLeast(0)) }
    }
}

/** An artist, album, playlist or video found; [kind] leads its grey line where the line doesn't say it already. */
@Composable
internal fun ChromeItemResultRow(item: ShelfItem, kind: String? = null, onClick: () -> Unit) {
    val round = item.browseId?.startsWith("UC") == true
    val line = item.subtitle.let { s -> if (kind != null && !s.startsWith(kind)) listOf(kind, s).filter { it.isNotBlank() }.joinToString(" • ") else s }
    ChromeResultRow(item.thumbnailUrl, round, item.title, line, label = "Open ${item.title}", playable = item.videoId != null && item.browseId == null, onClick = onClick)
}

@Composable
private fun resultType(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    MaterialTheme.typography.bodyMedium.copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp)
