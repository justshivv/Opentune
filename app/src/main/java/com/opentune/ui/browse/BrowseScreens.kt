package com.opentune.ui.browse

import com.opentune.ui.components.pinnedToTop
import com.opentune.ui.components.icon
import com.opentune.ui.components.meaning
import com.opentune.ui.components.GlassPage
import com.opentune.ui.components.LocalHazeState
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawBehind
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import com.opentune.data.model.durationMillis
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import com.opentune.ui.theme.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.model.BrowseType
import com.opentune.data.model.HEADER_ART_PX
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.UiState
import com.opentune.data.model.artworkAt
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.GlassBackButton
import com.opentune.ui.components.glass
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.Placeholder
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.SongRowPlaceholder
import com.opentune.ui.rememberLoader

/** Callbacks every track list needs. */
@androidx.compose.runtime.Immutable
class SongActions(
    val currentVideoId: String?,
    val isPlaying: Boolean,
    /** songs, start index, shuffle, "Playing from" label. */
    val playAll: (List<Song>, Int, Boolean, String?) -> Unit,
    val playNext: (Song) -> Unit,
    val addToQueue: (Song) -> Unit,
    /** Shuffle with similar songs mixed in: a long press on Shuffle. */
    val smartShuffle: (List<Song>, String?) -> Unit = { songs, source -> playAll(songs, 0, true, source) },
)

/** The same actions, with [onPlay] told each time something on the page starts playing. */
internal fun SongActions.alsoOnPlay(onPlay: () -> Unit): SongActions = SongActions(
    currentVideoId, isPlaying,
    playAll = { songs, index, shuffle, source -> onPlay(); playAll(songs, index, shuffle, source) },
    playNext = playNext,
    addToQueue = addToQueue,
    smartShuffle = { songs, source -> onPlay(); smartShuffle(songs, source) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollapsingBar(
    title: String,
    listState: LazyListState,
    onBack: () -> Unit,
    forceCollapsed: Boolean = false,
    artworkBehind: Boolean = true,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val scrolled by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val collapsed = forceCollapsed || scrolled
    // Once the header scrolls away the bar frosts in: the list under it is
    // blurred and washed with the page colour, and the frost thins out over
    // a short tail below the bar instead of stopping at a hard line.
    val frost by androidx.compose.animation.core.animateFloatAsState(if (collapsed) 1f else 0f, androidx.compose.animation.core.tween(260), label = "frost")
    val haze = LocalHazeState.current
    val page = MaterialTheme.colorScheme.background
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var tall by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    Box {
        if (frost > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .layout { measurable, constraints ->
                        // Drawn taller than the bar by the tail; the bar's own size is unchanged.
                        val h = constraints.maxHeight + FROST_TAIL.roundToPx()
                        val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                        layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, 0) }
                    }
                    .onSizeChanged { tall = it.height }
                    .then(
                        if (haze == null) {
                            Modifier.graphicsLayer { alpha = frost }.drawBehind {
                                drawRect(Brush.verticalGradient(0f to page.copy(alpha = 0.96f), 0.7f to page.copy(alpha = 0.9f), 1f to page.copy(alpha = 0f)))
                            }
                        } else {
                            Modifier.hazeEffect(haze) {
                                alpha = frost
                                backgroundColor = page
                                tints = listOf(HazeTint(page.copy(alpha = 0.6f)))
                                blurRadius = 26.dp
                                noiseFactor = 0.03f
                                progressive = HazeProgressive.verticalGradient(
                                    easing = androidx.compose.animation.core.EaseIn,
                                    startY = tall - FROST_TAIL.value * density * 1.6f,
                                    startIntensity = 1f,
                                    endY = tall.toFloat(),
                                    endIntensity = 0f,
                                )
                            }
                        },
                    ),
            )
        }
        TopAppBar(
            title = {
                androidx.compose.animation.AnimatedVisibility(
                    collapsed,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
                    exit = androidx.compose.animation.fadeOut(),
                ) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            navigationIcon = { GlassBackButton(onBack, Modifier.padding(start = 8.dp).size(48.dp)) },
            actions = {
                actions()
                Spacer(Modifier.width(8.dp))
            },
            // White over the cover, where grey sank into its colours; the page's
            // own text colour once the bar has frosted over the list.
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                actionIconContentColor = androidx.compose.animation.animateColorAsState(
                    if (collapsed || !artworkBehind) MaterialTheme.colorScheme.onSurface else Color.White, androidx.compose.animation.core.tween(260), label = "barIcons",
                ).value,
            ),
        )
    }
}

/** How far below the collapsed bar its frost reaches as it fades out. */
private val FROST_TAIL = 22.dp

@Composable
private fun PlayShuffleButtons(enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier, onSmartShuffle: (() -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onPlay, enabled = enabled, modifier = Modifier.weight(1f).height(52.dp)) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(com.opentune.ui.theme.tr("Play"))
        }
        ShuffleButton(onShuffle, onSmartShuffle, Modifier.weight(1f), enabled = enabled)
    }
}

/**
 * A list's Shuffle button; a long press starts smart shuffle, with similar
 * songs mixed in. The player's shuffle button cycles through a third state
 * instead, but this one starts a queue rather than holding a state, so a
 * second tap would have nothing to move on to. [compact] is the small round
 * glass button.
 */
@Composable
internal fun ShuffleButton(onShuffle: () -> Unit, onSmartShuffle: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false) {
    val smartAllowed = com.opentune.data.settings.AppSettings.playback.collectAsState().value.smartShuffle
    val context = androidx.compose.ui.platform.LocalContext.current
    val onLong: (() -> Unit)? = onSmartShuffle?.takeIf { smartAllowed && enabled }?.let { smart ->
        {
            com.opentune.data.Pills.info("Smart shuffle: similar songs mixed in", context)
            smart()
        }
    }
    val shape = if (compact) androidx.compose.foundation.shape.CircleShape else androidx.compose.material3.ButtonDefaults.filledTonalShape
    val press = Modifier.clip(shape).combinedClickable(
        enabled = enabled,
        role = androidx.compose.ui.semantics.Role.Button,
        onLongClickLabel = if (onLong != null) "Smart shuffle" else null,
        onLongClick = onLong,
        onClick = onShuffle,
    )
    if (compact) {
        Box(modifier.size(44.dp).glass(androidx.compose.foundation.shape.CircleShape).then(press), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Shuffle, "Shuffle", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
    } else {
        // Drawn like a FilledTonalButton, which has no long press of its own.
        val colors = MaterialTheme.colorScheme
        androidx.compose.material3.Surface(
            shape = shape,
            color = if (enabled) colors.secondaryContainer else colors.onSurface.copy(alpha = 0.12f),
            contentColor = if (enabled) colors.onSecondaryContainer else colors.onSurface.copy(alpha = 0.38f),
            modifier = modifier.height(52.dp).then(press),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shuffle, null)
                Spacer(Modifier.width(8.dp))
                Text(com.opentune.ui.theme.tr("Shuffle"), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The cover blurred and stretched behind a page's header, fading into the
 * page below it. Before Android 12, which can't blur, a tiny copy of the
 * cover stretched to fill makes the same soft wash.
 */
@Composable
internal fun HeaderBackdrop(
    url: String?,
    modifier: Modifier = Modifier,
    /** What it fades into at its foot: the page under it, plain or tinted (see [rememberCoverTint]). */
    into: Color = MaterialTheme.colorScheme.background,
) {
    if (url.isNullOrBlank()) return
    val background = into
    Box(modifier.clipToBounds().fadeOutAtFoot()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Artwork(
                url,
                Modifier.matchParentSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f; alpha = 0.85f }.blur(70.dp),
                shape = androidx.compose.ui.graphics.RectangleShape,
            )
        } else {
            Artwork(
                url.artworkAt(24),
                Modifier.matchParentSize().graphicsLayer { scaleX = 1.4f; scaleY = 1.4f; alpha = 0.7f },
                shape = androidx.compose.ui.graphics.RectangleShape,
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0f to background.copy(alpha = 0.15f), 0.55f to background.copy(alpha = 0.45f), 1f to background),
            ),
        )
    }
}

/**
 * Fades what it's on out to nothing over its last stretch, so a header melts
 * into the page's wash behind it. Ending on a solid colour drew a hard line
 * across the page wherever the header stopped, because the wash behind it is
 * pinned to the screen and is a different shade at every height.
 */
internal fun Modifier.fadeOutAtFoot(): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

/**
 * A list page's cover, faded out over its lower half so it melts into whatever lies behind
 * the page (the cover's own blurred colour, or the plain background) with no edge where it
 * ends. Eased, so the fade has no visible start either.
 */
internal fun Modifier.meltIntoPage(): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0.40f to Color.Black,
                0.55f to Color.Black.copy(alpha = 0.86f),
                0.70f to Color.Black.copy(alpha = 0.55f),
                0.85f to Color.Black.copy(alpha = 0.2f),
                1f to Color.Transparent,
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

/**
 * The page's colour taken from a cover: its own colour laid lightly over the
 * background. A dark cover's colour is lifted to a mid tone first, and a
 * black-and-white one (which has no colour to read) gets a soft grey, so
 * neither leaves the page plain black.
 */
@Composable
internal fun rememberCoverTint(url: String?): Color {
    val background = MaterialTheme.colorScheme.background
    val seed = com.opentune.ui.theme.rememberArtworkSeed(url)
    val loaded = !url.isNullOrBlank()
    val tint by androidx.compose.animation.animateColorAsState(
        if (loaded) androidx.compose.ui.graphics.lerp(background, coverTone(seed, background.luminance() < 0.5f), 0.32f) else background,
        animationSpec = if (com.opentune.data.settings.AppSettings.ui.collectAsState().value.reduceAnimation)
            androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(550),
        label = "coverTint",
    )
    return tint
}

/** [seed] at a tone that reads on the page: a mid tone on a dark page, a light one on a light page; grey without a seed. */
internal fun coverTone(seed: Color?, dark: Boolean): Color {
    val hct = com.materialkolor.hct.Hct.fromInt((seed ?: Color(0xFF808080)).toArgb())
    val tone = if (dark) hct.tone.coerceIn(42.0, 62.0) else hct.tone.coerceIn(70.0, 88.0)
    return Color(com.materialkolor.hct.Hct.from(hct.hue, if (seed == null) 0.0 else hct.chroma, tone).toInt())
}

/**
 * The cover's colour behind the whole page, not just its header: even down the
 * top half of the screen, where the header fades into it, then easing toward
 * the plain background at the foot.
 */
@Composable
internal fun Modifier.coverWash(tint: Color, artwork: String? = null): Modifier {
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < 0.5f
    val palette = com.opentune.ui.theme.rememberArtworkPalette(artwork)
    val still = com.opentune.data.settings.AppSettings.ui.collectAsState().value.reduceAnimation
    val secondary by androidx.compose.animation.animateColorAsState(
        palette.getOrNull(1)?.let { androidx.compose.ui.graphics.lerp(background, coverTone(it, dark), if (dark) 0.36f else 0.18f) } ?: tint,
        animationSpec = if (still) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(550),
        label = "ambientSecondary",
    )
    return this.drawBehind {
        drawRect(Brush.verticalGradient(0f to tint, 0.45f to androidx.compose.ui.graphics.lerp(tint, background, 0.18f),
            // Not further toward the background than this: a dark cover's page would end in black.
            1f to androidx.compose.ui.graphics.lerp(tint, background, 0.3f)))
        drawRect(Brush.radialGradient(listOf(secondary.copy(alpha = 0.65f), Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.3f),
            radius = maxOf(size.width, size.height * 0.7f).coerceAtLeast(1f)))
    }
}

/** The theme's own background outside a page tinted by [ListPageTint]; null on a plain page. */
private val LocalListBase = androidx.compose.runtime.compositionLocalOf<Color?> { null }
/** The cover a tinted page is coloured from. */
private val LocalListCover = androidx.compose.runtime.compositionLocalOf<String?> { null }
/** Which look a tinted page has behind its songs. */
private val LocalListLook = androidx.compose.runtime.compositionLocalOf { com.opentune.data.settings.ListBackground.PLAIN }

/**
 * A playlist, album or Liked page with the background Settings ask for (Playlist page
 * background). Plain leaves the theme alone. Cover colour makes the page's background the
 * cover's own colour, so the hero fades into it and the bars and cards sit on it;
 * [ListPageWash], drawn behind the songs, adds the blur of the cover and the fade down the
 * page. [seed] colours a page whose cover is drawn, not loaded (Liked Music).
 */
@Composable
internal fun ListPageTint(coverUrl: String?, seed: Color? = null, content: @Composable () -> Unit) {
    val ui by com.opentune.data.settings.AppSettings.ui.collectAsState()
    val base = MaterialTheme.colorScheme
    if (ui.listBackground == com.opentune.data.settings.ListBackground.PLAIN) {
        content()
        return
    }
    val dark = base.background.luminance() < 0.5f
    val fromCover = rememberCoverTint(coverUrl)
    val fromSeed by androidx.compose.animation.animateColorAsState(
        seed?.let { androidx.compose.ui.graphics.lerp(base.background, coverTone(it, dark), 0.32f) } ?: base.background,
        animationSpec = if (ui.reduceAnimation) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(550),
        label = "listSeedTint",
    )
    val tint = if (coverUrl.isNullOrBlank()) fromSeed else fromCover
    fun lift(f: Float) = androidx.compose.ui.graphics.lerp(tint, base.onSurface, f)
    val scheme = base.copy(
        background = tint,
        surface = tint,
        surfaceVariant = lift(0.14f),
        surfaceContainerLowest = lift(0.02f),
        surfaceContainerLow = lift(0.04f),
        surfaceContainer = lift(0.07f),
        surfaceContainerHigh = lift(0.10f),
        surfaceContainerHighest = lift(0.14f),
    )
    MaterialTheme(colorScheme = scheme) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalListBase provides base.background, LocalListCover provides coverUrl, LocalListLook provides ui.listBackground,
            content = content,
        )
    }
}

/**
 * What a page tinted by [ListPageTint] has behind its songs: a soft blur of the cover across
 * the top, which shows once the hero has scrolled away, and the cover's colour easing back
 * toward the theme's own background at the foot. Draws nothing on a plain page.
 */
@Composable
internal fun ListPageWash() {
    val base = LocalListBase.current ?: return
    val cover = LocalListCover.current
    val tint = MaterialTheme.colorScheme.background
    val ui by com.opentune.data.settings.AppSettings.ui.collectAsState()
    if (LocalListLook.current == com.opentune.data.settings.ListBackground.AMBIENT) {
        // The artist page's look: the cover's colours washed down the page, its second colour
        // glowing in from the side, all fading back to the plain background at the very bottom.
        Box(Modifier.fillMaxSize().coverWash(tint, cover)) {
            Box(Modifier.matchParentSize().drawBehind {
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.72f to Color.Transparent, 1f to base))
            })
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        if (!cover.isNullOrBlank() && !ui.reduceBlur && !ui.liteMode) {
            com.opentune.ui.library.CoverWash(cover.artworkAt(com.opentune.data.model.CARD_ART_PX) ?: cover, tint, Modifier.fillMaxWidth().height(380.dp))
        }
        Box(Modifier.matchParentSize().drawBehind {
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.6f to Color.Transparent, 1f to base.copy(alpha = 0.65f)))
        })
    }
}

/** The orders a list's songs can be shown in. */
internal enum class ListOrder(val label: String) {
    DEFAULT("Default order"),
    A_TO_Z("Alphabetical (A to Z)"),
    Z_TO_A("Alphabetical (Z to A)"),
}

/** "Playlist · 146 Tracks · 9h 48m": the kind, how many songs, and how long they run where that's known. */
internal fun listMeta(kind: String, songs: List<com.opentune.data.model.Song>): String {
    val count = "${songs.size} ${if (songs.size == 1) "Track" else "Tracks"}"
    val total = songs.sumOf { it.durationMillis() } / 1000
    val length = when {
        total <= 0 -> null
        total >= 3600 -> "${total / 3600}h ${(total % 3600) / 60}m"
        total >= 60 -> com.opentune.i18n.Tr.f("{0} min", total / 60)
        else -> "${total}s"
    }
    return listOfNotNull(kind, count, length).joinToString(" · ")
}

/**
 * A playlist's counts the way the Liked page says them: how many songs
 * and how long they run, with no kind word in front.
 */
internal fun playlistMeta(songs: List<com.opentune.data.model.Song>): String {
    val total = songs.sumOf { it.durationMillis() } / 1000
    val days = total / 86_400
    val hours = total % 86_400 / 3_600
    val minutes = total % 3_600 / 60
    val length = when {
        total <= 0 -> null
        days > 0 -> "${days}d ${hours}h ${minutes}m"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
    return listOfNotNull(com.opentune.i18n.Tr.f("{0} songs", songs.size), length).joinToString(" • ")
}

/** Whether lists open with their cover across the top of the page (Settings › Full-screen playlist covers). */
@Composable
internal fun fullListCover(): Boolean =
    com.opentune.data.settings.AppSettings.ui.collectAsState().value.fullScreenListCover

/**
 * The top of a playlist, album or Liked page, as Settings › Full-screen playlist covers asks.
 * On, [cover] runs across the whole top of the page and melts into it, with [info] (the name,
 * counts and buttons) over its fade. Off, the cover is framed and centred under the bar, with
 * a soft shadow, and [info] follows below it on the page's own background.
 */
@Composable
internal fun ListHero(
    cover: @Composable (Modifier) -> Unit,
    footPadding: androidx.compose.ui.unit.Dp = 0.dp,
    info: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (fullListCover()) {
        val heroHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * COLLECTION_HERO_FRACTION).dp
        Box(Modifier.fillMaxWidth().height(heroHeight)) {
            // The cover melts into the page behind it, with no edge where it ends.
            Box(Modifier.fillMaxSize().meltIntoPage()) {
                cover(Modifier.fillMaxSize())
                // A shade at the very top, so the back button reads on any cover.
                Box(
                    Modifier.fillMaxWidth().height(96.dp)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent))),
                )
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = footPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = info,
            )
        }
    } else {
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 64.dp, start = 24.dp, end = 24.dp, bottom = footPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val frame = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
            Box(
                Modifier.fillMaxWidth(FRAMED_COVER_WIDTH).aspectRatio(1f)
                    .shadow(22.dp, frame, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                    .clip(frame),
            ) { cover(Modifier.fillMaxSize()) }
            Spacer(Modifier.height(22.dp))
            info()
        }
    }
}

/** How much of the page's width a framed cover takes, with Full-screen playlist covers off. */
private const val FRAMED_COVER_WIDTH = 0.68f

/**
 * A full-width square for the complete cover. Wide artwork is fitted within it;
 * the title follows below, and a light edge scrim keeps the top controls readable.
 * [cover] supplies generated artwork when a playlist has no image URL.
 */
@Composable
internal fun FullListCover(url: String?, modifier: Modifier = Modifier, cover: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxWidth().aspectRatio(1f).clipToBounds()) {
        if (cover != null) cover()
        else Artwork(url, Modifier.matchParentSize(), shape = androidx.compose.ui.graphics.RectangleShape,
            placeholder = Icons.Filled.Album, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            containerColor = Color.Transparent)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.25f), 0.16f to Color.Transparent,
            0.92f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.12f))))
    }
}

/** The title starts below the complete square cover, never over the artwork. */
internal const val FULL_COVER_TITLE_AT = 1f
private const val COLLECTION_HERO_FRACTION = 0.68f
private const val COLLECTION_PAGE_SIZE = 50

/** Shared artwork, typography and spacing for YouTube, Spotify and local playlists. */
@Composable
internal fun PlaylistHeading(title: String, subtitle: String, thumbnail: String?, songs: List<com.opentune.data.model.Song>,
                             isAlbum: Boolean = false, cover: (@Composable () -> Unit)? = null,
                             /** False when the cover is shown across the page above ([FullListCover]). */
                             showCover: Boolean = true) {
    val coverSize = Modifier.fillMaxWidth(0.76f).aspectRatio(1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!showCover) Unit
        else if (cover == null) Artwork(
            thumbnail.artworkAt(HEADER_ART_PX),
            coverSize.shadow(24.dp, MaterialTheme.shapes.large),
            shape = MaterialTheme.shapes.large,
            placeholder = Icons.Filled.Album,
        )
        else Box(coverSize.shadow(24.dp, MaterialTheme.shapes.large)) { cover() }
        Text(
            title,
            style = if (showCover) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = if (showCover) 20.dp else 0.dp),
        )
        // Who it's by, then what it is: "Playlist · 146 Tracks · 9h 48m".
        if (subtitle.isNotBlank()) {
            com.opentune.ui.components.SubtitleLine(
                subtitle,
                explicit = isAlbum && songs.any { it.isExplicit == true },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            listMeta(if (isAlbum) "Album" else "Playlist", songs),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Shuffle in a round glass button, Play as a wide light pill, and a round button to save the list. */
@Composable
internal fun ListPlayRow(
    enabled: Boolean,
    saved: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSmartShuffle: () -> Unit,
    onSave: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        ShuffleButton(onShuffle, onSmartShuffle, Modifier.size(56.dp), enabled = enabled, compact = true)
        androidx.compose.material3.Button(
            onClick = onPlay,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(56.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colors.onSurface, contentColor = colors.surface),
        ) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(com.opentune.ui.theme.tr("Play"), style = MaterialTheme.typography.titleMedium)
        }
        if (onSave != null) Box(
            Modifier.size(56.dp).glass(androidx.compose.foundation.shape.CircleShape).clip(androidx.compose.foundation.shape.CircleShape).clickable(enabled = enabled, onClick = onSave),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (saved) Icons.Filled.Check else Icons.Filled.Add, actionLabel ?: if (saved) "Remove from library" else "Save to library", tint = colors.onSurface)
        }
    }
}

/** How tall the list search field is. */
internal val SEARCH_FIELD_HEIGHT = 56.dp

/**
 * "Search this list", in a rounded field under the buttons. Its X clears what's typed and shows
 * only while there's something to clear: closing the search is the bar's own X, and with both
 * there were two X buttons that did the same thing.
 */
@Composable
internal fun ListSearchField(query: String, onQuery: (String) -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier, autoFocus: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    Row(
        modifier.fillMaxWidth().height(SEARCH_FIELD_HEIGHT).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(colors.surfaceContainerHigh.copy(alpha = 0.85f)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(com.opentune.ui.theme.tr("Search this list"), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            androidx.compose.foundation.text.BasicTextField(
                query, onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear search") }
    }
}

/** Whether a list is saved in the account's library on YouTube Music, and how to take it out. */
internal class AccountSaved(val saved: Boolean, val ask: () -> Unit)

/**
 * A playlist someone else made, saved to the account's library on YouTube Music: shown
 * saved, with a way to remove it, asked first since it goes from the library on every device.
 * Not for the account's own playlists, which are deleted, not removed.
 */
@Composable
internal fun rememberAccountSaved(c: com.opentune.data.Collection, own: Boolean): AccountSaved {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val accountLists by com.opentune.data.library.LibraryCache.playlists.collectAsState()
    val signedIn by com.opentune.data.account.AccountStore.signedIn.collectAsState()
    var removed by remember(c.browseId) { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val listed = accountLists.any { it.browseId?.removePrefix("VL") == c.browseId.removePrefix("VL") }
    // Playlists only: removing an album takes an id its page here doesn't carry.
    val saved = signedIn && !own && !removed && listed && c.browseId.startsWith("VL")
    if (asking) com.opentune.ui.components.FloatingDialog(
        onDismissRequest = { if (!working) asking = false },
        title = { Text("Remove from your library?") },
        text = { Text(com.opentune.i18n.Tr.f("{0} comes out of your YouTube Music library on every device. You can save it again later.", c.title)) },
        confirmButton = {
            com.opentune.ui.components.SheetButton(if (working) "Removing…" else "Remove", loading = working, enabled = !working, onClick = {
                working = true
                scope.launch {
                    val ok = com.opentune.data.library.PlaylistSync.removeFromAccountLibrary(c.browseId, c.browseId.removePrefix("VL"))
                    working = false
                    asking = false
                    if (ok) {
                        removed = true
                        com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Removed {0} from your library", c.title), context)
                    } else {
                        com.opentune.data.Pills.error("Couldn't remove it. Check the connection and try again.", context)
                    }
                }
            })
        },
        dismissButton = { com.opentune.ui.components.SheetButton("Cancel", onClick = { asking = false }, enabled = !working, closes = true) },
    )
    return AccountSaved(saved) { asking = true }
}

/** The page's own menu: the list's cover and name, then what can be done with the whole list. */
@Composable
private fun ListMoreSheet(c: com.opentune.data.Collection, isAlbum: Boolean, actions: SongActions, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val savedLists by com.opentune.data.library.LibraryStore.savedLists.collectAsState()
    val saved = savedLists.any { it.browseId == c.browseId }
    val own = rememberOwnPlaylist(c)
    val accountSaved = rememberAccountSaved(c, own != null)
    com.opentune.ui.components.FloatingCard(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(0.dp),
        startExpanded = true,
        header = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (com.opentune.ui.library.isYouTubeLikes(c.browseId)) {
                    com.opentune.ui.components.LikedPlaylistCover(Modifier.size(56.dp))
                } else Artwork(c.thumbnailUrl.artworkAt(com.opentune.data.model.ROW_ART_PX), Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        },
    ) {
        val card = com.opentune.ui.components.LocalFloatingCard.current
        val close: () -> Unit = { card?.close() ?: onDismiss() }
        Column {
            com.opentune.ui.components.MenuRow(Icons.Filled.Refresh, "Refresh") { close(); onRefresh() }
            com.opentune.ui.components.MenuRow(Icons.AutoMirrored.Filled.PlaylistPlay, "Play next") { close(); c.songs.asReversed().forEach(actions.playNext) }
            com.opentune.ui.components.MenuRow(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue") { close(); c.songs.forEach(actions.addToQueue) }
            com.opentune.ui.components.MenuRow(Icons.Filled.Download, "Download all") {
                close()
                c.songs.forEach { com.opentune.data.download.Downloads.enqueue(context, it) }
                com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Downloading {0} songs", c.songs.size), context)
            }
            com.opentune.ui.components.MenuRow(Icons.Filled.Share, "Share") { close(); com.opentune.ui.share.ListShare.shareList(context, c.title, c.browseId, com.opentune.ui.share.ListShare.coversFor(c.thumbnailUrl, c.songs, album = c.browseId.startsWith("MPRE")), c.subtitle) }
            val ownNow = own
            if (ownNow != null) {
                com.opentune.ui.components.MenuRow((ownNow.privacy ?: com.opentune.data.model.PlaylistPrivacy.PRIVATE).icon, (ownNow.privacy ?: com.opentune.data.model.PlaylistPrivacy.PRIVATE).meaning) { ownNow.choose() }
            }
            com.opentune.ui.components.MenuRow(if (saved) Icons.Filled.BookmarkRemove else Icons.Filled.PushPin, if (saved) "Unpin" else "Pin") {
                close()
                com.opentune.data.library.LibraryStore.setSaved(com.opentune.data.library.SavedList(c.browseId, c.title, c.subtitle, c.thumbnailUrl), !saved)
            }
            if (accountSaved.saved) {
                com.opentune.ui.components.MenuRow(Icons.Filled.BookmarkRemove, "Remove from your library") { close(); accountSaved.ask() }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * A collection's hero, in the library pages' shape: the cover across the
 * whole top, the name and counts over its fade, then the page's actions —
 * the play row and its tools — inside it.
 */
@Composable
private fun CollectionHero(
    title: String,
    subtitle: String,
    explicit: Boolean,
    meta: String,
    thumbnailUrl: String?,
    fallbackCover: (@Composable () -> Unit)?,
    buttons: @Composable () -> Unit,
) {
    ListHero(
        cover = { m ->
            when {
                !thumbnailUrl.isNullOrBlank() -> Artwork(thumbnailUrl.artworkAt(com.opentune.data.model.PLAYER_ART_PX), m, shape = androidx.compose.ui.graphics.RectangleShape)
                fallbackCover != null -> Box(m) { fallbackCover() }
                else -> Box(m.background(MaterialTheme.colorScheme.surfaceVariant))
            }
        },
    ) {
            Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                com.opentune.ui.components.SubtitleLine(
                    subtitle,
                    explicit = explicit,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(meta, style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(20.dp))
            buttons()
            Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun CollectionScreen(
    browseId: String,
    contentPadding: PaddingValues,
    pageActions: SongActions,
    onBack: () -> Unit,
) {
    val owner = com.opentune.data.library.LibraryCache.owner()
    val loader = androidx.lifecycle.viewmodel.compose.viewModel<CollectionLoader>(key = "collection:$owner:$browseId") {
        CollectionLoader(browseId, owner)
    }
    val loadingState by loader.state.collectAsState()
    val firstPage by loader.preview.collectAsState()
    val state = firstPage?.let { UiState.Success(it) } ?: loadingState
    val listState = rememberLazyListState()
    val isAlbum = MusicRepository.typeOf(browseId) == BrowseType.ALBUM
    val title = (state as? UiState.Success)?.data?.title.orEmpty()
    // Looking through the list, its order, and the page's own menu, as BitChord has them.
    val search = remember(browseId) { PlaylistSearch() }
    val searching = search.open
    val query = search.query
    LaunchedEffect(searching, query) {
        if (searching) listState.scrollToItem(0)
    }
    var order by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ListOrder.DEFAULT) }
    var sortOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val loaded = (state as? UiState.Success)?.data
    // Played from here: the album or playlist counts toward Speed dial.
    val actions = remember(pageActions, loaded) {
        loaded?.let { c ->
            pageActions.alsoOnPlay { com.opentune.data.history.ListenedCollections.played(browseId, c.title, c.subtitle, c.thumbnailUrl) }
        } ?: pageActions
    }
    val shown = remember(loaded, query, order) {
        val songs = loaded?.songs.orEmpty()
        val q = query.trim()
        val found = if (q.isEmpty()) songs else songs.filter { it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true) }
        when (order) {
            ListOrder.DEFAULT -> found
            ListOrder.A_TO_Z -> found.sortedBy { it.title.lowercase() }
            ListOrder.Z_TO_A -> found.sortedByDescending { it.title.lowercase() }
        }
    }
    if (moreOpen && loaded != null) ListMoreSheet(loaded, isAlbum, actions, onRefresh = { loader.reload() }, onDismiss = { moreOpen = false })

    // Songs picked together (long press, then Select): the bar over the top acts on them.
    val selection = com.opentune.ui.components.rememberSongSelection()
    // The page's background as Settings ask for it; YouTube Music's liked songs have a drawn cover, so its colour is given.
    val likes = com.opentune.ui.library.isYouTubeLikes(browseId)
    ListPageTint(
        coverUrl = if (likes) null else loaded?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: loaded?.songs?.firstOrNull()?.thumbnailUrl,
        seed = if (likes) com.opentune.ui.library.LIKED_SEED else null,
    ) {
    androidx.compose.runtime.CompositionLocalProvider(com.opentune.ui.components.LocalSongSelection provides selection) {
        GlassPage(Modifier.fillMaxSize().dismissPlaylistSearch(search), overlay = {
            PlaylistSearchHeader(search) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val heroPx = with(density) { (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * COLLECTION_HERO_FRACTION).dp.roundToPx() }
                val pastHero by remember(listState, heroPx) {
                    androidx.compose.runtime.derivedStateOf {
                        listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > heroPx - with(density) { 56.dp.roundToPx() }
                    }
                }
                val returning = rememberSearchReturn(search, listState) { i, o -> i > 0 || o > heroPx - with(density) { 56.dp.roundToPx() } }
                val barSolid = searching || pastHero || returning
                com.opentune.ui.library.TopNavBar(title, barSolid, onBack) {
                    // Search, order and more in one glass pill.
                    Row(
                        Modifier.height(48.dp).then(if (barSolid) Modifier else Modifier.glass(androidx.compose.foundation.shape.CircleShape)).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.IconButton(onClick = { if (searching) search.close() else search.open = true }) {
                            Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, if (searching) "Stop searching" else "Search this list")
                        }
                        Box {
                            androidx.compose.material3.IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                            androidx.compose.material3.DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
                                ListOrder.entries.forEach { o ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(o.label) },
                                        trailingIcon = { if (o == order) Icon(Icons.Filled.Check, null) },
                                        onClick = { order = o; sortOpen = false },
                                    )
                                }
                            }
                        }
                        androidx.compose.material3.IconButton(onClick = { moreOpen = true }, enabled = loaded != null) { Icon(Icons.Filled.MoreHoriz, "More") }
                    }
                }
            }
            val listed = (state as? UiState.Success)?.data?.songs.orEmpty()
            com.opentune.ui.components.SongSelectionBar(
                selection,
                rows = listed.mapIndexed { i, s -> "$i:${s.videoId}" to s },
                onAddToQueue = { picked -> picked.forEach(actions.addToQueue) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }) {
            // The cover's blur and fade behind the songs, when Settings ask for the cover-coloured page.
            ListPageWash()
            when (val s = state) {
                is UiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) { ErrorState(s.message, { loader.reload() }) }
                else -> {
                    val c = (s as? UiState.Success)?.data
                    val fallbackCover: (@Composable () -> Unit)? = when {
                        com.opentune.ui.library.isYouTubeLikes(browseId) -> ({
                            com.opentune.ui.components.LikedPlaylistCover(Modifier.fillMaxSize(), androidx.compose.ui.graphics.RectangleShape)
                        })
                        c != null && c.thumbnailUrl.isNullOrBlank() && !isAlbum -> ({
                            com.opentune.ui.components.PlaylistCover(c.title, c.songs.map { it.thumbnailUrl },
                                Modifier.fillMaxSize(), androidx.compose.ui.graphics.RectangleShape, named = false)
                        })
                        else -> null
                    }
                    var shownCount by remember { androidx.compose.runtime.mutableIntStateOf(COLLECTION_PAGE_SIZE) }
                    val paged = shown.take(shownCount)
                    LazyColumn(state = listState, contentPadding = if (searching) PaddingValues(bottom = 16.dp) else PaddingValues(bottom = contentPadding.calculateBottomPadding()), modifier = Modifier.fillMaxSize().playlistSearchResults(search)) {
                        if (!searching) item(key = "header") {
                            if (c == null) {
                                // The hero's loading shape: the cover across the
                                // whole top, then the title, the counts and the
                                // buttons as shapes over its foot.
                                // Whether the loaded page will offer its save
                                // button, from the lists already on the phone,
                                // so the shapes match the hero's (its rule is
                                // spelt out where the hero's buttons are made).
                                val savedLists by com.opentune.data.library.LibraryStore.savedLists.collectAsState()
                                val saved = savedLists.any { it.browseId == browseId }
                                val accountLists by com.opentune.data.library.LibraryCache.playlists.collectAsState()
                                val showSave = saved || accountLists.none { it.browseId?.removePrefix("VL") == browseId.removePrefix("VL") }
                                ListHero(cover = { m -> Placeholder(m, androidx.compose.ui.graphics.RectangleShape) }) {
                                    run {
                                        Placeholder(Modifier.width(200.dp).height(26.dp))
                                        Placeholder(Modifier.padding(top = 10.dp).width(140.dp).height(16.dp))
                                        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                            Placeholder(Modifier.size(56.dp), androidx.compose.foundation.shape.CircleShape)
                                            Placeholder(Modifier.weight(1f).height(56.dp), androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                                            if (showSave) Placeholder(Modifier.size(56.dp), androidx.compose.foundation.shape.CircleShape)
                                        }
                                        Spacer(Modifier.height(16.dp))
                                    }
                                }
                            } else {
                                val own = rememberOwnPlaylist(c)
                                CollectionHero(
                                    title = c.title,
                                    subtitle = if (isAlbum) c.subtitle else "",
                                    explicit = isAlbum && c.songs.any { it.isExplicit == true },
                                    meta = if (isAlbum) listMeta("Album", c.songs) else playlistMeta(c.songs),
                                    // Liked Music has its own heart cover: YouTube sends its top song's art for it.
                                    thumbnailUrl = if (com.opentune.ui.library.isYouTubeLikes(browseId)) null else c.thumbnailUrl,
                                    fallbackCover = fallbackCover,
                                    buttons = {
                                        val savedLists by com.opentune.data.library.LibraryStore.savedLists.collectAsState()
                                        val saved = savedLists.any { it.browseId == c.browseId }
                                        // Already in the account's library (made there, or saved there): nothing for a plus to add.
                                        val accountLists by com.opentune.data.library.LibraryCache.playlists.collectAsState()
                                        val inAccount = own != null || accountLists.any { it.browseId?.removePrefix("VL") == c.browseId.removePrefix("VL") }
                                        // Saved to the account's library on YouTube Music (made by someone else): the
                                        // button shows it saved, and takes it out of the library, asked first.
                                        val accountSaved = rememberAccountSaved(c, own != null)
                                        ListPlayRow(
                                            enabled = c.songs.isNotEmpty(),
                                            saved = saved || accountSaved.saved,
                                            onPlay = { actions.playAll(shown.ifEmpty { c.songs }, 0, false, c.title) },
                                            onShuffle = { actions.playAll(shown.ifEmpty { c.songs }, 0, true, c.title) },
                                            onSmartShuffle = { actions.smartShuffle(shown.ifEmpty { c.songs }, c.title) },
                                            onSave = when {
                                                accountSaved.saved && !saved -> accountSaved.ask
                                                inAccount && !saved -> null
                                                else -> ({ com.opentune.data.library.LibraryStore.setSaved(com.opentune.data.library.SavedList(c.browseId, c.title, c.subtitle, c.thumbnailUrl), !saved) })
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    },
                                )
                            }
                        }
                        if (firstPage != null && loadingState is UiState.Error) item(key = "retry-pages") {
                            androidx.compose.material3.TextButton(onClick = { loader.reload() }) { Text("Some tracks couldn't load. Retry") }
                        }
                        if (c == null) {
                            items(8) { SongRowPlaceholder() }
                        } else {
                            itemsIndexed(paged, key = { i, song -> "$i:${song.videoId}" }) { i, song ->
                                SongListItem(
                                    song = song,
                                    onClick = { actions.playAll(shown, i, false, c.title) },
                                    isCurrent = song.videoId == actions.currentVideoId,
                                    isPlaying = actions.isPlaying,
                                    leading = if (isAlbum) {
                                        {
                                            Box(Modifier.size(width = 28.dp, height = 52.dp), Alignment.Center) {
                                                Text(
                                                    "${c.songs.indexOf(song).takeIf { it >= 0 }?.plus(1) ?: (i + 1)}",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = if (song.videoId == actions.currentVideoId) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    onPlayNext = { actions.playNext(song) },
                                    onAddToQueue = { actions.addToQueue(song) },
                                    selectKey = "${c.songs.indexOf(song).takeIf { it >= 0 } ?: i}:${song.videoId}",
                                )
                            }
                        }
                        if (shownCount < shown.size) item(key = "collection-more") {
                            LaunchedEffect(shownCount) {
                                kotlinx.coroutines.delay(700)
                                shownCount += COLLECTION_PAGE_SIZE
                            }
                            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.material3.CircularProgressIndicator()
                            }
                        }

                    }
                }
            }
        }
    }
    }
}
