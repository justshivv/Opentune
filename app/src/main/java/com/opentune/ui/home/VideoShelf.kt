package com.opentune.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import com.opentune.ui.components.MenuAnchor
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.SongContextMenu
import com.opentune.ui.components.menuAnchor
import com.opentune.ui.components.menuClickable
import com.opentune.ui.components.rememberHaptics
import com.opentune.ui.theme.Text
import com.opentune.ui.toSong

/** How wide a video card is; it's 16:9, so about 169 dp tall. */
internal val VIDEO_CARD_WIDTH = 300.dp

/** A shelf of music videos or live performances, as wide cards. */
@Composable
internal fun VideoShelf(shelf: HomeShelf, onItemClick: (ShelfItem) -> Unit, action: (@Composable () -> Unit)? = null) {
    Column {
        SectionHeader(shelf.title, subtitle = shelf.subtitle, action = action)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.items) { item -> VideoCard(item, onClick = { onItemClick(item) }) }
        }
    }
}

/**
 * A music video as a wide card: its frame, with the title and artist over the
 * top left on a soft dark fade, so they read on any frame. Held down, the
 * song menu.
 */
@Composable
internal fun VideoCard(item: ShelfItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val song = remember(item) { item.toSong() }
    val anchor = remember { MenuAnchor() }
    val view = androidx.compose.ui.platform.LocalView.current
    val haptics = rememberHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    val at = anchor.rect
    if (menuOpen && at != null && song != null) {
        SongContextMenu(song, at, onDismiss = { menuOpen = false }, liftCorner = 16.dp) {
            VideoFace(item, Modifier.fillMaxWidth().fillMaxHeight())
        }
    }
    VideoFace(
        item,
        modifier
            .width(VIDEO_CARD_WIDTH)
            .aspectRatio(16f / 9f)
            .menuAnchor(anchor, view)
            .clip(MaterialTheme.shapes.medium)
            .menuClickable(onClick = onClick, onLongClick = song?.let { { haptics.press(); menuOpen = true } }),
    )
}

@Composable
private fun VideoFace(item: ShelfItem, modifier: Modifier) {
    val frames = remember(item.videoId, item.thumbnailUrl) { videoFrames(item.videoId, item.thumbnailUrl) }
    // A frame YouTube hasn't made for this video: the next one down.
    var frame by remember(frames) { mutableIntStateOf(0) }
    val artist = remember(item.subtitle) { InnertubeParser.artistFromSubtitle(item.subtitle) }
    Box(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        frames.getOrNull(frame)?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                onError = { if (frame < frames.lastIndex) frame++ },
            )
        }
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.72f).background(TopShade))
        Column(Modifier.align(Alignment.TopStart).padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth(0.86f)) {
            Text(
                item.title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist.isNotBlank()) {
                Text(
                    artist,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Dark at the top edge, gone by about two thirds down. */
private val TopShade = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.7f),
    0.45f to Color.Black.copy(alpha = 0.32f),
    1f to Color.Transparent,
)

/**
 * The frames to try for a video, best first. A YouTube frame other than the
 * full-size ones is tried after the video's 1280x720 `hq720`, and the 320x180
 * `mqdefault`, which YouTube makes for every video, comes last.
 */
internal fun videoFrames(videoId: String?, thumbnailUrl: String?): List<String> {
    val youtube = videoId != null && thumbnailUrl != null && Regex("""https://i\d?\.ytimg\.com/vi(_webp)?/${Regex.escape(videoId)}/.*""").matches(thumbnailUrl)
    val full = thumbnailUrl != null && ("/hq720." in thumbnailUrl || "/maxresdefault." in thumbnailUrl)
    return buildList {
        if (youtube && !full) add("https://i.ytimg.com/vi/$videoId/hq720.jpg")
        thumbnailUrl?.let(::add)
        if (youtube) add("https://i.ytimg.com/vi/$videoId/mqdefault.jpg")
    }.distinct()
}
