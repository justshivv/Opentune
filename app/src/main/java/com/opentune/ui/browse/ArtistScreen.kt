package com.opentune.ui.browse

import com.opentune.ui.components.pinnedToTop
import com.opentune.data.covers.ArtistPhotos
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import com.opentune.data.MusicRepository
import com.opentune.data.library.LibraryStore
import com.opentune.data.library.SavedList
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.UiState
import com.opentune.data.model.artworkAt
import com.opentune.data.releases.NewReleases
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.GlassIconButton
import com.opentune.ui.components.GlassPage
import com.opentune.ui.components.Haptics
import com.opentune.ui.components.ItemCard
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.Placeholder
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.SongRowPlaceholder
import com.opentune.ui.components.glass
import com.opentune.ui.components.pressable
import com.opentune.ui.components.rememberHaptics
import com.opentune.ui.rememberLoader
import com.opentune.ui.share.ListShare
import com.opentune.ui.theme.animated
import com.opentune.ui.theme.rememberArtworkSeed
import com.opentune.ui.type
import com.opentune.ui.cardMenu

/**
 * An artist's page: their photo across the top, fading into a page tinted
 * from it, then their newest release, top songs, the rows YouTube Music
 * keeps for them and what it says about them. Rows the page doesn't carry
 * are simply not drawn.
 */
@Composable
fun ArtistScreen(
    browseId: String,
    contentPadding: PaddingValues,
    actions: SongActions,
    onBack: () -> Unit,
    onItemClick: (ShelfItem) -> Unit,
    onShowAll: (HomeShelf) -> Unit,
) {
    val loader = rememberLoader("artist:$browseId") { MusicRepository.artist(browseId) }
    val state by loader.state.collectAsState()
    when (val s = state) {
        is UiState.Error -> {
            val listState = rememberLazyListState()
            GlassPage(Modifier.fillMaxSize(), overlay = { CollapsingBar("", listState, onBack) }) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { ErrorState(s.message, { loader.reload() }) }
            }
        }
        else -> {
            val page = (s as? UiState.Success)?.data
            // Played from here: the artist counts toward Speed dial.
            val tracked = remember(actions, page) {
                page?.name?.let { name ->
                    actions.alsoOnPlay { com.opentune.data.history.ListenedCollections.played(browseId, name, "Artist", page.thumbnailUrl) }
                } ?: actions
            }
            ArtistPageView(
            browseId = browseId,
            page = page,
            contentPadding = contentPadding,
            actions = tracked,
            onBack = onBack,
            onItemClick = onItemClick,
            onShowAll = onShowAll,
        )
        }
    }
}

/** The page itself, for a loaded [page] or, while it loads, null. */
@Composable
internal fun ArtistPageView(
    browseId: String,
    page: ArtistPage?,
    contentPadding: PaddingValues,
    actions: SongActions,
    onBack: () -> Unit,
    onItemClick: (ShelfItem) -> Unit,
    onShowAll: (HomeShelf) -> Unit,
) {
    val listState = rememberLazyListState()
    // One portrait per artist for a day (see ArtistPhotos), so going back or coming again shows
    // the picture already in the image cache. Better artwork is downloaded for a later visit
    // rather than replacing a visible face and leaving its palette tied to the previous one.
    val photo = remember(browseId, page?.name) {
        ArtistPhotos.pinned(browseId)
            ?: page?.name?.let { com.opentune.data.covers.AppleArtistArtRepository.cached(it)?.heroUrl }
            ?: page?.thumbnailUrl.artworkAt(ARTIST_PHOTO_PX)
    }
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(photo, page?.name) {
        val name = page?.name ?: return@LaunchedEffect
        photo?.let { ArtistPhotos.pin(browseId, it) }
        val hero = com.opentune.data.covers.AppleArtistArtRepository.artFor(name)?.heroUrl
        if (hero == null || hero == photo || ArtistPhotos.pinned(browseId) == hero) return@LaunchedEffect
        val ready = coil3.SingletonImageLoader.get(context).execute(
            coil3.request.ImageRequest.Builder(context).data(hero).size(ARTWORK_PREFETCH_PX)
                .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build(),
        )
        if (ready is coil3.request.SuccessResult) ArtistPhotos.upgrade(browseId, hero)
    }
    ArtistTint(photo) {
        GlassPage(
            Modifier.fillMaxSize(),
            overlay = {
                CollapsingBar(page?.name.orEmpty(), listState, onBack) {
                    GlassIconButton(
                        Icons.Rounded.Share,
                        "Share",
                        onClick = { ListShare.shareList(context, page?.name ?: "Artist", browseId) },
                        size = 48.dp,
                    )
                }
            },
        ) {
            val band = com.opentune.ui.components.rememberRubberBand()
            LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize(), overscrollEffect = band) {
                item(key = "hero", contentType = "hero") { ArtistHero(browseId, page, photo, listState, actions, band) }
                if (page == null) {
                    item(key = "latest-placeholder") {
                        Placeholder(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(LATEST_CARD_HEIGHT), MaterialTheme.shapes.large)
                    }
                    item(key = "top-placeholder") { SectionHeader("Top songs") }
                    items(4, key = { "song-placeholder:$it" }) { SongRowPlaceholder() }
                    return@LazyColumn
                }
                latestRelease(page.sections)?.let { release ->
                    item(key = "latest", contentType = "latest") {
                        LatestReleaseCard(release, onOpen = { onItemClick(release.item) }, Modifier.padding(top = 8.dp))
                    }
                }
                if (page.songs.isNotEmpty()) {
                    item(key = "top", contentType = "top") {
                        TopSongs(
                            page,
                            actions,
                            onShowAll = page.moreSongsBrowseId?.let { id -> { onItemClick(ShelfItem("Top songs", page.name.orEmpty(), null, null, id)) } },
                        )
                    }
                }
                page.sections.forEachIndexed { i, shelf ->
                    item(key = "shelf:$i:${shelf.title}", contentType = "shelf") {
                        ArtistShelf(shelf, onItemClick, onShowAll = if (shelf.moreBrowseId != null) ({ onShowAll(shelf) }) else null)
                    }
                }
                val about = page.description?.takeIf { it.isNotBlank() }
                if (about != null || page.subscriberCountText != null || page.monthlyListenerCount != null) {
                    item(key = "about", contentType = "about") {
                        AboutArtist(about, page.subscriberCountText, page.monthlyListenerCount)
                    }
                }
                item(key = "end") { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

/**
 * The page in the colours of the artist's photo: a deep shade of its main
 * colour behind everything (see [artistPageColor]), the accent from the same
 * colour, and the theme's own until the photo has been read or when Settings
 * turn it off. Eases between the two rather than snapping.
 */
@Composable
private fun ArtistTint(photoUrl: String?, content: @Composable () -> Unit) {
    val ui by AppSettings.ui.collectAsState()
    val base = MaterialTheme.colorScheme
    val seed = rememberArtworkSeed(photoUrl?.takeIf { ui.artistPageTint })?.takeIf { ui.artistPageTint }
    val dark = base.background.luminance() < 0.5f
    // Always built, so the composition keeps its shape when the seed arrives.
    val accents = rememberDynamicColorScheme(seedColor = seed ?: base.primary, isDark = dark, isAmoled = false, style = PaletteStyle.TonalSpot)
    val scheme = if (seed == null) base else {
        val page = Color(artistPageColor(seed.toArgb(), dark))
        fun lift(f: Float) = lerp(page, base.onSurface, f)
        base.copy(
            primary = accents.primary,
            onPrimary = accents.onPrimary,
            secondaryContainer = accents.secondaryContainer,
            onSecondaryContainer = accents.onSecondaryContainer,
            background = page,
            surface = page,
            surfaceVariant = lift(0.14f),
            surfaceContainerLowest = lift(0.02f),
            surfaceContainerLow = lift(0.04f),
            surfaceContainer = lift(0.07f),
            surfaceContainerHigh = lift(0.10f),
            surfaceContainerHighest = lift(0.14f),
        )
    }
    val animated = scheme.animated()
    MaterialTheme(colorScheme = animated) {
        // Text that names no colour takes the page's, light on the deep shade and dark on the pale one.
        CompositionLocalProvider(LocalContentColor provides animated.onBackground, content = content)
    }
}

/**
 * The photo, full width, fading into the page; the name over the fade and
 * the three buttons under it. The photo drifts down at a fraction of the
 * scroll, so it slides away slower than the page, unless animation is reduced.
 */
@Composable
private fun ArtistHero(browseId: String, page: ArtistPage?, photo: String?, listState: LazyListState, actions: SongActions, band: com.opentune.ui.components.RubberBandOverscroll? = null) {
    val ui by AppSettings.ui.collectAsState()
    val still = ui.reduceAnimation
    val background = MaterialTheme.colorScheme.background
    // Apple Music's header for the artist, when it has one: a sharper photo, and
    // for some a looping video. YouTube's photo shows until (and unless) it comes.
    val name = page?.name
    val apple = remember(name) { name?.let { com.opentune.data.covers.AppleArtistArtRepository.cached(it) } }
    val shown = photo
    // The clip only where moving pictures are welcome and data is free.
    val video = apple?.videoUrl?.takeIf { apple?.heroUrl == shown && !still && !AppSettings.onMeteredNetwork() }
    val drift: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {
        if (!still && listState.firstVisibleItemIndex == 0) translationY = listState.firstVisibleItemScrollOffset * PARALLAX
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Square on a phone; on a wide window it would be a wall, so it stops growing.
        val photoHeight = min(maxWidth, MAX_PHOTO_HEIGHT)
        Box(
            Modifier
                // Pulled down at the top, the photo stays at the top of the screen and grows to
                // fill the room; the name and buttons move down over more of it.
                .pinnedToTop(band)
                .fillMaxWidth()
                .height(photoHeight)
                .clipToBounds()
                // The fade is drawn here, outside the moving photo, so it stays put while the photo drifts.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(PHOTO_FADE, blendMode = BlendMode.DstIn)
                },
        ) {
            // A photo kept from an earlier visit shows before the rest of the page is back.
            if (page == null && shown == null) {
                Placeholder(Modifier.fillMaxSize(), RectangleShape)
            } else {
                Artwork(
                    shown,
                    Modifier.fillMaxSize().graphicsLayer(drift),
                    shape = RectangleShape,
                    placeholder = Icons.Filled.Person,
                )
                if (video != null) ArtistVideo(video, Modifier.fillMaxSize().graphicsLayer(drift))
                // The lower part goes soft as it nears the page: a blurred copy of the
                // photo, faded in from about halfway down, under the fade to the page.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && video == null) {
                    Artwork(
                        shown,
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer(drift)
                            .blur(HERO_BLUR, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle)
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(BLUR_MASK, blendMode = BlendMode.DstIn)
                            },
                        shape = RectangleShape,
                    )
                }
            }
        }
        // Keeps the status bar readable over a bright photo, in whichever way the theme draws it.
        Box(
            Modifier.graphicsLayer { translationY = -(band?.pull ?: 0f) }.fillMaxWidth().height(120.dp)
                .background(Brush.verticalGradient(listOf(background.copy(alpha = 0.5f), background.copy(alpha = 0f)))),
        )
        Column(
            Modifier.fillMaxWidth().padding(top = (photoHeight - NAME_OVERLAP).coerceAtLeast(0.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (page == null) {
                Placeholder(Modifier.width(220.dp).height(44.dp))
            } else {
                ArtistNameOrSignature(page.name.orEmpty())
            }
            HeroButtons(browseId, page, actions, Modifier.padding(top = 18.dp, bottom = 12.dp))
        }
    }
}

/** Shuffle, a big Play, and Follow for new releases. */
@Composable
private fun HeroButtons(browseId: String, page: ArtistPage?, actions: SongActions, modifier: Modifier = Modifier) {
    val songs = page?.songs.orEmpty()
    val enabled = songs.isNotEmpty()
    val name = page?.name
    val haptics = rememberHaptics()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        ShuffleButton(
            onShuffle = { actions.playAll(songs, 0, true, name) },
            onSmartShuffle = { actions.smartShuffle(songs, name) },
            modifier = Modifier.size(SIDE_BUTTON),
            enabled = enabled,
            compact = true,
        )
        val colors = MaterialTheme.colorScheme
        Box(
            Modifier
                .size(PLAY_BUTTON)
                .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
                .clip(CircleShape)
                .background(colors.onSurface)
                .then(
                    if (enabled) {
                        Modifier.pressable({
                            haptics.pattern(Haptics.Pattern.PLAY)
                            actions.playAll(songs, 0, false, name)
                        })
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(40.dp), tint = colors.background)
        }
        FollowButton(browseId, page, Modifier.size(SIDE_BUTTON))
    }
}

/**
 * Follow for new-release alerts, as a star: kept on the phone, no account
 * needed. Asks for notification permission on Android 13+ the first time.
 */
@Composable
private fun FollowButton(browseId: String, page: ArtistPage?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val followed by NewReleases.followed.collectAsState()
    val following = followed.any { it.browseId == browseId }
    val haptics = rememberHaptics()
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    val label = if (following) "Following. Tap to stop new release alerts" else "Follow for new releases"
    Box(
        modifier
            .glass(CircleShape)
            .clip(CircleShape)
            .clickable(enabled = page != null, onClickLabel = label) {
                if (page == null) return@clickable
                if (following) {
                    haptics.pattern(Haptics.Pattern.OFF)
                    NewReleases.unfollow(context, browseId)
                } else {
                    haptics.pattern(Haptics.Pattern.LIKE)
                    NewReleases.follow(context, browseId, page)
                    if (!NewReleases.canNotify(context)) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (following) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            label,
            Modifier.size(24.dp),
            tint = if (following) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The newest release on a glass card, with a round button that saves it to the library. */
@Composable
private fun LatestReleaseCard(release: LatestRelease, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val item = release.item
    val id = item.browseId ?: return
    val savedLists by LibraryStore.savedLists.collectAsState()
    val saved = savedLists.any { it.browseId == id }
    val haptics = rememberHaptics()
    val shape = MaterialTheme.shapes.large
    Row(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(LATEST_CARD_HEIGHT)
            .glass(shape)
            .clip(shape)
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item.thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.size(96.dp), MaterialTheme.shapes.medium, placeholder = Icons.Filled.Album)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            if (release.line.isNotEmpty()) {
                Text(release.line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Latest release", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f), CircleShape)
                .clickable {
                    haptics.pattern(if (saved) Haptics.Pattern.OFF else Haptics.Pattern.LIKE)
                    LibraryStore.setSaved(SavedList(id, item.title, item.subtitle, item.thumbnailUrl), !saved)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (saved) Icons.Rounded.Check else Icons.Rounded.Add,
                if (saved) "Remove from library" else "Save to library",
                Modifier.size(24.dp),
                tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Top songs in columns of four that move sideways a column at a time, with
 * the next column showing at the edge so it's clear there's more.
 */
@Composable
private fun TopSongs(page: ArtistPage, actions: SongActions, onShowAll: (() -> Unit)?) {
    val songs = page.songs
    val columns = remember(songs) { songs.indices.chunked(SONGS_PER_COLUMN) }
    val rowState = rememberLazyListState()
    Column {
        SectionHeader("Top songs", action = onShowAll?.let { { ShowAll(it) } })
        LazyRow(
            state = rowState,
            flingBehavior = rememberSnapFlingBehavior(rowState, snapPosition = SnapPosition.Start),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(columns.size, key = { it }) { c ->
                Column(Modifier.fillParentMaxWidth(if (columns.size > 1) COLUMN_WIDTH else 1f)) {
                    columns[c].forEach { i ->
                        val song = songs[i]
                        SongListItem(
                            song = song,
                            onClick = { actions.playAll(songs, i, false, page.name) },
                            isCurrent = song.videoId == actions.currentVideoId,
                            isPlaying = actions.isPlaying,
                            onPlayNext = { actions.playNext(song) },
                            onAddToQueue = { actions.addToQueue(song) },
                            // A sideways drag moves the columns; the menu still has both.
                            swipeToQueue = false,
                        )
                    }
                }
            }
        }
    }
}

/** One of the page's rows: Albums, Singles & EPs, Featured on, playlists, related artists. */
@Composable
private fun ArtistShelf(shelf: HomeShelf, onItemClick: (ShelfItem) -> Unit, onShowAll: (() -> Unit)?) {
    val rowState = rememberLazyListState()
    Column {
        SectionHeader(shelf.title, action = onShowAll?.let { { ShowAll(it) } })
        LazyRow(
            state = rowState,
            flingBehavior = rememberSnapFlingBehavior(rowState, snapPosition = SnapPosition.Start),
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(shelf.items) { item ->
                ItemCard(item.title, item.subtitle, item.thumbnailUrl, item.type(), onClick = { onItemClick(item) }, width = SHELF_CARD_WIDTH, explicit = item.explicit, menu = item.cardMenu())
            }
        }
    }
}

@Composable
private fun ShowAll(onClick: () -> Unit) {
    TextButton(onClick) { Text("Show all", style = MaterialTheme.typography.labelLarge) }
}

/** The bio, three lines until More is tapped, and the audience counts as pills. */
@Composable
private fun AboutArtist(description: String?, subscribers: String?, monthly: String?) {
    val ui by AppSettings.ui.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        SectionHeader("About the artist")
        if (description != null) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            var clipped by remember { mutableStateOf(false) }
            Text(
                description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else ABOUT_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) clipped = it.hasVisualOverflow },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .then(if (ui.reduceAnimation) Modifier else Modifier.animateContentSize()),
            )
            if (clipped || expanded) {
                TextButton({ expanded = !expanded }, Modifier.padding(horizontal = 4.dp)) {
                    Text(if (expanded) "Less" else "More", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (subscribers != null || monthly != null) {
            FlowRow(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                subscribers?.let { CountPill(Icons.Rounded.Person, it) }
                monthly?.let { CountPill(Icons.Rounded.GraphicEq, it) }
            }
        }
    }
}

@Composable
private fun CountPill(icon: ImageVector, text: String) {
    Row(
        Modifier
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Everything behind one of the artist page's "Show all" links, as a grid of cards. */
@Composable
fun ArtistItemsScreen(
    title: String,
    browseId: String,
    params: String?,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onItemClick: (ShelfItem) -> Unit,
) {
    val loader = rememberLoader("artist-items:$browseId:$params") { MusicRepository.artistItems(browseId, params) }
    val state by loader.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, onBack = onBack)
        when (val s = state) {
            is UiState.Error -> Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) {
                ErrorState(s.message, onRetry = { loader.reload() })
            }
            else -> {
                val cards = (s as? UiState.Success)?.data
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = contentPadding.calculateBottomPadding()),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when {
                        cards == null -> items(6, key = { "placeholder:$it" }) {
                            Column(Modifier.padding(6.dp)) {
                                Placeholder(Modifier.fillMaxWidth().height(150.dp), MaterialTheme.shapes.medium)
                                Placeholder(Modifier.padding(top = 8.dp).width(110.dp).height(14.dp))
                            }
                        }
                        cards.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                            MessageState(Icons.Rounded.LibraryMusic, "Nothing to show here")
                        }
                        else -> items(cards) { item ->
                            ItemCard(
                                item.title, item.subtitle, item.thumbnailUrl, item.type(),
                                onClick = { onItemClick(item) },
                                modifier = Modifier.fillMaxWidth(),
                                width = Dp.Unspecified,
                                explicit = item.explicit,
                                menu = item.cardMenu(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The artist photo is drawn edge to edge, so it's asked for at about a phone's full width. */
private const val ARTIST_PHOTO_PX = 1080

/** Decoded this small while it downloads for later: the file on disk is what matters, not the picture. */
private const val ARTWORK_PREFETCH_PX = 64

/** How far the photo drifts for each pixel scrolled. */
private const val PARALLAX = 0.4f

/** Where the photo melts into the page: solid for the top half, gone at the bottom edge. */
/**
 * Apple's looping artist video, silent, over the photo: it fades in once its
 * first frame is drawn, so a slow start shows the photo rather than black,
 * and it pauses while the app isn't in front.
 */
@Composable
private fun ArtistVideo(url: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var drawn by remember(url) { mutableStateOf(false) }
    val fade by androidx.compose.animation.core.animateFloatAsState(if (drawn) 1f else 0f, androidx.compose.animation.core.tween(600), label = "artistVideo")
    val player = remember(url) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onRenderedFirstFrame() { drawn = true }
            })
            prepare()
            playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(player, lifecycle) {
        val watch = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> player.play()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(watch)
        onDispose {
            lifecycle.removeObserver(watch)
            player.release()
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c -> android.view.TextureView(c).also(player::setVideoTextureView) },
        modifier = modifier.graphicsLayer { alpha = fade },
    )
}

/** How soft the bottom of the artist's photo gets. */
private val HERO_BLUR = 26.dp

/** Where the blurred copy shows: nothing at the top, all of it near the bottom. */
private val BLUR_MASK = Brush.verticalGradient(0.45f to Color.Transparent, 0.85f to Color.Black)

private val PHOTO_FADE = Brush.verticalGradient(
    0f to Color.Black,
    0.45f to Color.Black,
    0.7f to Color.Black.copy(alpha = 0.62f),
    0.88f to Color.Black.copy(alpha = 0.18f),
    1f to Color.Transparent,
)

private val MAX_PHOTO_HEIGHT = 480.dp
/** How far up the name sits into the photo's fade. */
private val NAME_OVERLAP = 76.dp
private val SIDE_BUTTON = 56.dp
private val PLAY_BUTTON = 76.dp
private val LATEST_CARD_HEIGHT = 124.dp
private val SHELF_CARD_WIDTH = 168.dp
private const val SONGS_PER_COLUMN = 4
/** A column of top songs leaves this much of the row to it, the rest to the next one peeking in. */
private const val COLUMN_WIDTH = 0.9f
private const val ABOUT_LINES = 3
