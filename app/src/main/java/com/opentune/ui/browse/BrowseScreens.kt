package com.opentune.ui.browse

import com.opentune.ui.components.tipAnchor
import com.opentune.ui.components.pinnedToTop
import com.opentune.ui.components.iconRes
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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import com.opentune.data.model.durationMillis
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
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.opentune.ui.components.posterLayout
import com.opentune.ui.components.PosterCaps
import com.opentune.ui.components.HeaderButtonsScope
import com.opentune.ui.components.pageButtonFace
import com.opentune.ui.components.pageButtonInk
import com.opentune.ui.components.recordHeaderPicture
import com.opentune.ui.components.recordPageBackdrop
import com.opentune.ui.components.rememberHeaderBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.opentune.ui.components.PosterPill
import com.opentune.ui.components.PosterRound
import com.opentune.ui.components.POSTER_ROUND
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.Placeholder
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.SongRowPlaceholder
import com.opentune.ui.rememberLoader
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

/** Callbacks every track list needs. */
@androidx.compose.runtime.Immutable
class SongActions(
    val currentVideoId: String?,
    val isPlaying: Boolean,
    /** songs, start index, shuffle, "Playing from" label. */
    val playAll: (List<Song>, Int, Boolean, String?) -> Unit,
    val playNext: (Song) -> Unit,
    val addToQueue: (Song) -> Unit,
    /** The "Playing from" label of the queue playing now, or null when it has none. */
    val queueSource: String? = null,
    /** Pause or resume what is playing, from where it is. */
    val togglePlayPause: () -> Unit = {},
    /** Actually making sound now, not still loading: a list's Pause waits for this. */
    val isSounding: Boolean = false,
    /** Shuffle with similar songs mixed in: a long press on Shuffle. */
    val smartShuffle: (List<Song>, String?) -> Unit = { songs, source -> playAll(songs, 0, true, source) },
)

/** The same actions, with [onPlay] told each time something on the page starts playing. */
internal fun SongActions.alsoOnPlay(onPlay: () -> Unit): SongActions = SongActions(
    currentVideoId, isPlaying,
    playAll = { songs, index, shuffle, source -> onPlay(); playAll(songs, index, shuffle, source) },
    queueSource = queueSource,
    togglePlayPause = togglePlayPause,
    isSounding = isSounding,
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
            Icon(ImageVector.vectorResource(AppIcons.PlayArrow), null)
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
        // Glass, or in a page's header the page's accent when Settings › Page buttons asks for it.
        Box(modifier.size(44.dp).pageButtonFace(androidx.compose.foundation.shape.CircleShape).then(press), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(AppIcons.Shuffle), "Shuffle", Modifier.size(20.dp), tint = pageButtonInk())
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
                Icon(ImageVector.vectorResource(AppIcons.Shuffle), null)
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

/** The stops of the gradient a page wash paints: [tint] at the top, easing toward [base]. */
internal fun pageWashStops(tint: Color, base: Color): Array<Pair<Float, Color>> = arrayOf(
    0f to tint,
    0.45f to androidx.compose.ui.graphics.lerp(tint, base, 0.18f),
    // Not further toward the background than this: a dark cover's page would end in black.
    1f to androidx.compose.ui.graphics.lerp(tint, base, 0.3f),
)

/** A cover's second colour, glowing in from the side of a washed page; [tint] when it has none. */
@Composable
internal fun rememberPageGlow(tint: Color, artwork: String?): Color {
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < 0.5f
    val palette = com.opentune.ui.theme.rememberArtworkPalette(artwork)
    val still = com.opentune.data.settings.AppSettings.ui.collectAsState().value.reduceAnimation
    val glow by androidx.compose.animation.animateColorAsState(
        palette.getOrNull(1)?.let { androidx.compose.ui.graphics.lerp(background, coverTone(it, dark), if (dark) 0.36f else 0.18f) } ?: tint,
        animationSpec = if (still) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(550),
        label = "ambientSecondary",
    )
    return glow
}

/**
 * The cover's colour behind the whole page, not just its header: even down the
 * top half of the screen, where the header fades into it, then easing toward
 * the plain background at the foot.
 */
@Composable
internal fun Modifier.coverWash(tint: Color, artwork: String? = null): Modifier {
    val background = MaterialTheme.colorScheme.background
    val secondary = rememberPageGlow(tint, artwork)
    return this.drawBehind {
        drawRect(Brush.verticalGradient(*pageWashStops(tint, background)))
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
/** The page's accent for its header buttons, while Settings › Page buttons asks for it; see [rememberListAccent]. */
private val LocalListAccent = androidx.compose.runtime.compositionLocalOf<com.opentune.ui.components.PageAccent?> { null }

/**
 * The colour a list page's header buttons are filled with when Settings › Page buttons says
 * Accent colour: the cover's own colour at a tone that reads on the page (the one its tint is
 * mixed from, see [rememberCoverTint]), or [seed]'s for a drawn cover, or the theme's accent
 * when there's neither; with black or white on it, whichever reads. Null with glass buttons.
 */
@Composable
private fun rememberListAccent(coverUrl: String?, seed: Color?): com.opentune.ui.components.PageAccent? {
    val ui by com.opentune.data.settings.AppSettings.ui.collectAsState()
    if (ui.pageButtons != com.opentune.data.settings.PageButtons.ACCENT) return null
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fromCover = com.opentune.ui.theme.rememberArtworkSeed(coverUrl?.takeIf { it.isNotBlank() })
    val target = (fromCover ?: seed)?.let { coverTone(it, dark) } ?: MaterialTheme.colorScheme.primary
    val fill by androidx.compose.animation.animateColorAsState(
        target,
        animationSpec = if (ui.reduceAnimation) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(550),
        label = "pageAccent",
    )
    return com.opentune.ui.components.PageAccent(fill, com.opentune.ui.components.inkOn(fill))
}

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
    val accent = rememberListAccent(coverUrl, seed)
    if (ui.listBackground == com.opentune.data.settings.ListBackground.PLAIN) {
        androidx.compose.runtime.CompositionLocalProvider(LocalListAccent provides accent, content = content)
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
        // The wash this page paints, shared with the bars frosted over it.
        // Read here, inside the scheme, so the glow is mixed against the
        // tint exactly as the wash behind the songs mixes it. The wash's
        // gradient eases toward the scheme's own background — the tint
        // itself — so the two colours it needs are the tint and this glow.
        val glow = rememberPageGlow(tint, coverUrl)
        androidx.compose.runtime.CompositionLocalProvider(
            LocalListBase provides base.background, LocalListCover provides coverUrl, LocalListLook provides ui.listBackground,
            LocalListAccent provides accent,
            com.opentune.ui.components.LocalPageWash provides com.opentune.ui.components.PageWash(tint, tint, glow),
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
    // Recorded, while the page asks (PageBackdropScope), for the glass buttons in its header:
    // the wash lies behind the list, so they can bend it. A plain page records its plain colour.
    val base = LocalListBase.current
    if (base == null) {
        com.opentune.ui.components.PlainPageBackdrop()
        return
    }
    val cover = LocalListCover.current
    val tint = MaterialTheme.colorScheme.background
    val ui by com.opentune.data.settings.AppSettings.ui.collectAsState()
    if (LocalListLook.current == com.opentune.data.settings.ListBackground.AMBIENT) {
        // The artist page's look: the cover's colours washed down the page, its second colour
        // glowing in from the side, all fading back to the plain background at the very bottom.
        Box(Modifier.fillMaxSize().recordPageBackdrop().coverWash(tint, cover)) {
            Box(Modifier.matchParentSize().drawBehind {
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.72f to Color.Transparent, 1f to base))
            })
        }
        return
    }
    // The page's colour is under this wash already; while it is recorded it is painted here
    // too, so the recording is the whole background rather than a blur over nothing.
    val recorded = com.opentune.ui.components.LocalPageBackdrop.current != null
    Box(Modifier.fillMaxSize().recordPageBackdrop().then(if (recorded) Modifier.background(tint) else Modifier)) {
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
    /** Newest first: playlists add songs at the end, so it's the list read from the bottom up. */
    RECENT("Recently added"),
    A_TO_Z("Alphabetical (A to Z)"),
    Z_TO_A("Alphabetical (Z to A)"),
}

/**
The sort menu for a list's songs, iOS-style: a rounded panel in the page's own
colours — it is composed inside the page's tint, so it wears them — with an
icon for each order and a check on the current one. The panel is a solid
colour from the page's scheme; nothing is blurred behind it.
 *
The panel is hosted in a dialog, as the song menu is: hosted in a dropdown,
its popup window stacked under the page's own window, and the glass bar drew
over the panel's top. A dialog opens above everything on the page.
 */
@Composable
internal fun ListSortMenu(
    expanded: Boolean,
    order: ListOrder,
    includeRecent: Boolean = true,
    onPick: (ListOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    // Where the sort button sits on the screen. The menu's dialog has no anchor
    // of its own, so the button's place is read here: this layer is a child of
    // the same box as the button and reports that box's place in screen
    // coordinates, the way the song menu's anchor reports its own.
    val view = androidx.compose.ui.platform.LocalView.current
    var anchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(
        Modifier.size(0.dp).onGloballyPositioned { c ->
            val parent = c.parentLayoutCoordinates ?: return@onGloballyPositioned
            val at = IntArray(2)
            view.rootView.getLocationOnScreen(at)
            anchor = parent.boundsInWindow().translate(at[0].toFloat(), at[1].toFloat())
        },
    )
    if (!expanded) return
    val button = anchor ?: return
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = androidx.compose.ui.platform.LocalView.current
        // Undimmed, and no window animation of the system's: the panel simply
        // appears over the untouched page, as the dropdown's did.
        androidx.compose.runtime.SideEffect {
            (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.apply {
                setDimAmount(0f)
                setWindowAnimations(0)
            }
        }
        // Where this dialog's own window sits on the screen, to place the panel
        // where the button is (the song menu's own reckoning).
        var origin by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { c ->
                    val at = IntArray(2)
                    dialogView.rootView.getLocationOnScreen(at)
                    origin = c.positionInWindow() + androidx.compose.ui.geometry.Offset(at[0].toFloat(), at[1].toFloat())
                }
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            val o = origin ?: return@Box
            val at = button.translate(-o.x, -o.y)
            // The panel grows a touch as it fades in, from the corner by the button.
            val grown = remember { androidx.compose.animation.core.Animatable(0.9f) }
            LaunchedEffect(Unit) { grown.animateTo(1f, androidx.compose.animation.core.tween(130)) }
            androidx.compose.ui.layout.Layout(
                content = {
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 0.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = grown.value
                                scaleY = grown.value
                                alpha = grown.value
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                            }
                            .pointerInput(Unit) { detectTapGestures { } },
                    ) {
                        Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).padding(vertical = 8.dp)) {
                            val options = ListOrder.entries.filter { includeRecent || it != ListOrder.RECENT }
                            options.forEachIndexed { index, option ->
                                if (index > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(option.label, fontWeight = if (option == order) androidx.compose.ui.text.font.FontWeight.SemiBold else null) },
                                    leadingIcon = {
                                        Icon(
                                            when (option) {
                                                ListOrder.DEFAULT -> ImageVector.vectorResource(AppIcons.Reorder)
                                                ListOrder.RECENT -> ImageVector.vectorResource(AppIcons.History)
                                                ListOrder.A_TO_Z, ListOrder.Z_TO_A -> ImageVector.vectorResource(AppIcons.SortByAlpha)
                                            },
                                            null,
                                            Modifier.size(20.dp),
                                        )
                                    },
                                    trailingIcon = if (option == order) ({ Icon(ImageVector.vectorResource(AppIcons.Check), null) }) else null,
                                    onClick = { onPick(option) },
                                )
                            }
                        }
                    }
                },
            ) { measurables, constraints ->
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val margin = 16.dp.roundToPx()
                val gap = 6.dp.roundToPx()
                val panel = measurables[0].measure(
                    androidx.compose.ui.unit.Constraints(maxWidth = w - 2 * margin, maxHeight = h - 2 * margin),
                )
                // Under the button, its end edge lined up with the button's, kept
                // inside the screen's margins; above the button when below has
                // no room (a bar at the screen's foot).
                val x = (at.right.roundToInt() - panel.width).coerceIn(margin, (w - margin - panel.width).coerceAtLeast(margin))
                var y = at.bottom.roundToInt() + gap
                if (y + panel.height > h - margin) y = (at.top.roundToInt() - gap - panel.height).coerceAtLeast(margin)
                layout(w, h) { panel.place(x, y) }
            }
        }
    }
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

/** Whether lists open with their cover across the top of the page (Settings › Full-screen playlist covers, or the Poster layout). */
@Composable
internal fun fullListCover(): Boolean =
    com.opentune.data.settings.AppSettings.ui.collectAsState().value.let { it.fullScreenListCover || it.pageLayout == com.opentune.data.settings.PageLayout.POSTER }


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
    band: com.opentune.ui.components.RubberBandOverscroll? = null,
    listState: androidx.compose.foundation.lazy.LazyListState? = null,
    info: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    // The colour the buttons take when Settings › Page buttons asks for the accent.
    val accent = LocalListAccent.current
    if (fullListCover()) {
        val heroHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * COLLECTION_HERO_FRACTION).dp
        // The cover drifts down at a fraction of the scroll, so the list rises
        // over it little by little instead of the two moving as one piece.
        val still = com.opentune.data.settings.AppSettings.ui.collectAsState().value.reduceAnimation
        val drift: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {
            if (!still && listState != null && listState.firstVisibleItemIndex == 0) {
                translationY = listState.firstVisibleItemScrollOffset * LIST_PARALLAX
            }
        }
        // The cover is recorded on its own, as it is drawn (drifting, stretched by a pull, melting
        // away), and the buttons over it are drawn beside that box rather than inside it, so
        // their glass bends the cover and the page's wash under it (see PageButtons.kt).
        val picture = rememberLayerBackdrop()
        val behind = rememberHeaderBackdrop(picture)
        Box(Modifier.fillMaxWidth().height(heroHeight)) {
            // The cover melts into the page behind it, with no edge where it ends.
            Box(Modifier.fillMaxSize().recordHeaderPicture(picture, behind).pinnedToTop(band).meltIntoPage()) {
                cover(Modifier.fillMaxSize().graphicsLayer(drift))
                // A shade at the very top, so the back button reads on any cover.
                Box(
                    Modifier.fillMaxWidth().height(96.dp)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent))),
                )
            }
            HeaderButtonsScope(behind, accent) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = footPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = info,
                )
            }
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
            // Under the framed cover the buttons sit on the page's own background, so that is all their glass bends.
            HeaderButtonsScope(rememberHeaderBackdrop(null), accent) { info() }
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
            placeholder = ImageVector.vectorResource(AppIcons.Album), contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            containerColor = Color.Transparent)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.25f), 0.16f to Color.Transparent,
            0.92f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.12f))))
    }
}

/** The title starts below the complete square cover, never over the artwork. */
internal const val FULL_COVER_TITLE_AT = 1f
private const val COLLECTION_HERO_FRACTION = 0.68f
private const val LIST_PARALLAX = 0.4f
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
            placeholder = ImageVector.vectorResource(AppIcons.Album),
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
    /** The queue playing now is this list's and it is playing: offer Pause instead of Play. */
    showPause: Boolean = false,
    /** Set while the queue playing now is this list's: pressing Play or Pause pauses or resumes it in place, never a restart. */
    onToggle: (() -> Unit)? = null,
    /** This list's queue is still loading: the Play button shows but cannot be pressed again. */
    busy: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    if (posterLayout()) {
        // Play as a glass pill, then round glass buttons beside it, centred under the title.
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            PosterPill(com.opentune.ui.theme.tr(if (showPause) "Pause" else "Play"), ImageVector.vectorResource(if (showPause) AppIcons.Pause else AppIcons.PlayArrow), enabled && !busy, onToggle ?: onPlay)
            ShuffleButton(onShuffle, onSmartShuffle, Modifier.size(POSTER_ROUND), enabled = enabled, compact = true)
            if (onSave != null) PosterRound(
                if (saved) ImageVector.vectorResource(AppIcons.Check) else ImageVector.vectorResource(AppIcons.Add),
                actionLabel ?: if (saved) "Remove from library" else "Save to library",
                enabled = enabled, onClick = onSave,
            )
        }
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        ShuffleButton(onShuffle, onSmartShuffle, Modifier.size(56.dp), enabled = enabled, compact = true)
        androidx.compose.material3.Button(
            onClick = onToggle ?: onPlay,
            enabled = enabled && !busy,
            modifier = Modifier.weight(1f).height(56.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colors.onSurface, contentColor = colors.surface),
        ) {
            Icon(ImageVector.vectorResource(if (showPause) AppIcons.Pause else AppIcons.PlayArrow), null)
            Spacer(Modifier.width(8.dp))
            Text(com.opentune.ui.theme.tr(if (showPause) "Pause" else "Play"), style = MaterialTheme.typography.titleMedium)
        }
        if (onSave != null) Box(
            Modifier.size(56.dp).pageButtonFace(androidx.compose.foundation.shape.CircleShape, poster = false).clickable(enabled = enabled, onClick = onSave),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (saved) ImageVector.vectorResource(AppIcons.Check) else ImageVector.vectorResource(AppIcons.Add), actionLabel ?: if (saved) "Remove from library" else "Save to library", tint = pageButtonInk())
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
        Icon(ImageVector.vectorResource(AppIcons.Search), null, tint = colors.onSurfaceVariant)
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
        if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = onClear) { Icon(ImageVector.vectorResource(AppIcons.Close), "Clear search") }
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
    val fromList = com.opentune.ui.library.LocalDownloadList.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val savedLists by com.opentune.data.library.LibraryStore.savedLists.collectAsState()
    val saved = savedLists.any { it.browseId == c.browseId }
    val own = rememberOwnPlaylist(c)
    val accountSaved = rememberAccountSaved(c, own != null)
    // Every song of the album or playlist into a playlist at once, from the same picker as one song.
    var adding by remember { mutableStateOf(false) }
    if (adding) {
        com.opentune.ui.components.AddAllToPlaylistDialog(c.songs, onDismiss = { adding = false; onDismiss() }, onDone = { adding = false; onDismiss() })
        return
    }
    com.opentune.ui.components.FloatingCard(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(0.dp),
        expandable = true,
        startExpanded = true,
        fullFraction = 1f,
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
            com.opentune.ui.components.MenuGroupBreak()
        },
    ) {
        val card = com.opentune.ui.components.LocalFloatingCard.current
        val close: () -> Unit = { card?.close() ?: onDismiss() }
        Column {
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Refresh), "Refresh") { close(); onRefresh() }
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.PlaylistPlay), "Play next") { close(); c.songs.asReversed().forEach(actions.playNext) }
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.QueueMusic), "Add to queue") { close(); c.songs.forEach(actions.addToQueue) }
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Download), "Download all") {
                close()
                com.opentune.data.tips.Tips.done(com.opentune.data.tips.Tip.DOWNLOAD)
                c.songs.forEach { com.opentune.data.download.Downloads.enqueue(context, it) }
                fromList?.keepWhole(c.songs)
                com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Downloading {0} songs", c.songs.size), context)
            }
            if (c.songs.isNotEmpty()) com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.PlaylistAdd), if (isAlbum) "Add album to playlist" else "Add all to playlist") { adding = true }
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Share), "Share") { close(); com.opentune.ui.share.ListShare.shareList(context, c.title, c.browseId, com.opentune.ui.share.ListShare.coversFor(c.thumbnailUrl, c.songs, album = c.browseId.startsWith("MPRE")), c.subtitle) }
            val ownNow = own
            if (ownNow != null) {
                com.opentune.ui.components.MenuRow(ImageVector.vectorResource((ownNow.privacy ?: com.opentune.data.model.PlaylistPrivacy.PRIVATE).iconRes), (ownNow.privacy ?: com.opentune.data.model.PlaylistPrivacy.PRIVATE).meaning) { ownNow.choose() }
            }
            // Already in the account's library (made there or saved there): nothing to add.
            val accountLists by com.opentune.data.library.LibraryCache.playlists.collectAsState()
            val inAccount = own != null || accountSaved.saved || accountLists.any { it.browseId?.removePrefix("VL") == c.browseId.removePrefix("VL") }
            if (saved || !inAccount) {
                com.opentune.ui.components.MenuRow(
                    ImageVector.vectorResource(if (saved) AppIcons.BookmarkRemove else AppIcons.BookmarkAdd),
                    if (saved) "Remove from Library" else "Add to Library",
                ) {
                    close()
                    com.opentune.data.library.LibraryStore.setSaved(com.opentune.data.library.SavedList(c.browseId, c.title, c.subtitle, c.thumbnailUrl), !saved)
                    com.opentune.data.Pills.done(if (saved) "Removed from Library" else "Added to Library", context)
                }
            }
            if (accountSaved.saved) {
                com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.BookmarkRemove), "Remove from your library") { close(); accountSaved.ask() }
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
internal fun CollectionHero(
    title: String,
    subtitle: String,
    explicit: Boolean,
    meta: String,
    thumbnailUrl: String?,
    fallbackCover: (@Composable () -> Unit)?,
    buttons: @Composable () -> Unit,
    band: com.opentune.ui.components.RubberBandOverscroll? = null,
    listState: androidx.compose.foundation.lazy.LazyListState? = null,
    /** "Album" or "Playlist", leading the Poster layout's counts line. */
    kind: String? = null,
) {
    val poster = posterLayout()
    ListHero(
        cover = { m ->
            when {
                !thumbnailUrl.isNullOrBlank() -> Artwork(thumbnailUrl.artworkAt(com.opentune.data.model.PLAYER_ART_PX), m, shape = androidx.compose.ui.graphics.RectangleShape)
                fallbackCover != null -> Box(m) { fallbackCover() }
                else -> Box(m.background(MaterialTheme.colorScheme.surfaceVariant))
            }
        },
        band = band,
        listState = listState,
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (poster) {
            // The title, who it's by in spaced capitals, the buttons, then what it is and how long:
            // "ALBUM · 9 SONGS · 42 MIN", over a hairline where the songs begin.
            if (subtitle.isNotBlank()) PosterCaps(subtitle, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f))
            Spacer(Modifier.height(24.dp))
            buttons()
            // "42m" reads "42 MIN" in capitals, as "1h 5m" reads "1 HR 5 MIN".
            val counts = meta.replace(" • ", " · ").replace(Regex("""(\d+)m\b"""), "$1 min").replace(Regex("""(\d+)h\b"""), "$1 hr")
            PosterCaps(listOfNotNull(kind?.let { com.opentune.ui.theme.tr(it) }, counts.ifBlank { null }).joinToString(" · "), Modifier.padding(top = 22.dp))
            androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            return@ListHero
        }
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
    val band = com.opentune.ui.components.rememberRubberBand()
    val isAlbum = MusicRepository.typeOf(browseId) == BrowseType.ALBUM
    val title = (state as? UiState.Success)?.data?.title.orEmpty()
    // Looking through the list, its order, and the page's own menu, as BitChord has them.
    val search = remember(browseId) { PlaylistSearch() }
    val searching = search.open
    val query = search.query
    LaunchedEffect(searching, query) {
        if (searching) listState.scrollToItem(0)
    }
    // Albums have no "Recently added": their order stays the default. Playlists open newest first.
    var order by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (browseId.startsWith("MPRE")) ListOrder.DEFAULT else ListOrder.RECENT) }
    var sortOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val loaded = (state as? UiState.Success)?.data
    val context = androidx.compose.ui.platform.LocalContext.current
    // Played from often and not yet saved: the download, in this page's menu, pointed out once.
    val collectionPlays by com.opentune.data.history.ListenedCollections.plays.collectAsState()
    val downloadEntries by com.opentune.data.download.Downloads.entries.collectAsState()
    val playedOften = collectionPlays.firstOrNull { it.browseId == browseId }?.playedAt.orEmpty().size >= com.opentune.data.tips.TipRules.DOWNLOAD_AFTER_PLAYS
    val savedAll = loaded?.songs?.let { songs ->
        val pick = com.opentune.data.download.downloadSelection(songs, downloadEntries)
        pick.wanted.isEmpty() || pick.complete.size == pick.wanted.size
    } ?: true
    val downloadTip = com.opentune.ui.components.rememberTipTarget(
        com.opentune.data.tips.Tip.DOWNLOAD,
        "Listen to this a lot? Download it from here to play it without a connection.",
        enabled = playedOften && !savedAll,
    )
    // Taken out of this playlist on YouTube Music: hidden here at once, and
    // put back if YouTube Music says no.
    var removed by remember(browseId) { mutableStateOf<Set<String>>(emptySet()) }
    val removeSongs: (List<Song>) -> Unit = { songs ->
        if (songs.isNotEmpty()) {
            val ids = songs.mapTo(HashSet()) { it.videoId }
            removed = removed + ids
            com.opentune.data.library.PlaylistSync.removeFromAccountPlaylist(browseId, songs) { ok ->
                if (ok) {
                    com.opentune.data.Pills.done(
                        if (songs.size == 1) com.opentune.i18n.Tr.f("Removed \"{0}\"", songs[0].title)
                        else com.opentune.i18n.Tr.f("Removed {0} songs", songs.size),
                        context,
                    )
                } else {
                    removed = removed - ids
                    com.opentune.data.Pills.error(com.opentune.i18n.Tr.f("Couldn't remove from this playlist"), context)
                }
            }
        }
    }
    // Played from here: the album or playlist counts toward Speed dial.
    val actions = remember(pageActions, loaded) {
        loaded?.let { c ->
            pageActions.alsoOnPlay { com.opentune.data.history.ListenedCollections.played(browseId, c.title, c.subtitle, c.thumbnailUrl) }
        } ?: pageActions
    }
    val shown = remember(loaded, query, order, removed) {
        val songs = loaded?.songs.orEmpty().filter { it.videoId !in removed }
        val q = query.trim()
        val found = if (q.isEmpty()) songs else songs.filter { it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true) }
        when (order) {
            ListOrder.DEFAULT -> found
            ListOrder.RECENT -> found.asReversed()
            ListOrder.A_TO_Z -> found.sortedBy { it.title.lowercase() }
            ListOrder.Z_TO_A -> found.sortedByDescending { it.title.lowercase() }
        }
    }
    com.opentune.ui.components.PreloadCandidates("collection:$browseId", shown.take(3))


    // Songs picked together (long press, then Select): the bar over the top acts on them.
    val selection = com.opentune.ui.components.rememberSongSelection()
    // The page's background as Settings ask for it; YouTube Music's liked songs have a drawn cover, so its colour is given.
    val likes = com.opentune.ui.library.isYouTubeLikes(browseId)
    // Songs downloaded from this page are kept under it in Downloads (whole, or past five one by one).
    val downloadList = remember(loaded) {
        loaded?.let { c -> com.opentune.ui.library.DownloadedListInfo(c.browseId, c.title, c.subtitle, c.thumbnailUrl?.takeIf { it.isNotBlank() } ?: c.songs.firstOrNull()?.thumbnailUrl, c.songs.map { it.videoId }) }
    }
    androidx.compose.runtime.CompositionLocalProvider(com.opentune.ui.library.LocalDownloadList provides downloadList) {
    ListPageTint(
        coverUrl = if (likes) null else loaded?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: loaded?.songs?.firstOrNull()?.thumbnailUrl,
        seed = if (likes) com.opentune.ui.library.LIKED_SEED else null,
    ) {
        // The page's menu is composed inside the tint, so its sheet wears the page's colours.
        if (moreOpen && loaded != null) ListMoreSheet(loaded, isAlbum, actions, onRefresh = { loader.reload() }, onDismiss = { moreOpen = false })
        // The page's wash is recorded for the glass buttons in its header.
        androidx.compose.runtime.CompositionLocalProvider(com.opentune.ui.components.LocalSongSelection provides selection) { com.opentune.ui.components.PageBackdropScope {
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
                                Icon(if (searching) ImageVector.vectorResource(AppIcons.Close) else ImageVector.vectorResource(AppIcons.Search), if (searching) "Stop searching" else "Search this list")
                            }
                            Box {
                                androidx.compose.material3.IconButton(onClick = { sortOpen = true }) { Icon(ImageVector.vectorResource(AppIcons.Sort), "Sort") }
                                // An album's songs aren't added to it: no "Recently added" there.
                                ListSortMenu(sortOpen, order, includeRecent = !browseId.startsWith("MPRE"), onPick = { order = it; sortOpen = false }, onDismiss = { sortOpen = false })
                            }
                            androidx.compose.material3.IconButton(onClick = { moreOpen = true }, enabled = loaded != null, modifier = Modifier.tipAnchor(downloadTip)) { Icon(ImageVector.vectorResource(AppIcons.MoreHoriz), "More") }
                        }
                    }
                }
                val listed = (state as? UiState.Success)?.data?.songs.orEmpty()
                com.opentune.ui.components.SongSelectionBar(
                    selection,
                    rows = listed.mapIndexed { i, s -> "$i:${s.videoId}" to s },
                    onAddToQueue = { picked -> picked.forEach(actions.addToQueue) },
                    onRemove = if ((state as? UiState.Success)?.data?.owned == true) ({ keys -> removeSongs(listed.filterIndexed { i, s -> "$i:${s.videoId}" in keys && s.videoId !in removed }) }) else null,
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
                        LazyColumn(state = listState, overscrollEffect = band, contentPadding = if (searching) PaddingValues(bottom = 16.dp) else PaddingValues(bottom = contentPadding.calculateBottomPadding()), modifier = Modifier.fillMaxSize().playlistSearchResults(search)) {
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
                                    ListHero(cover = { m -> Placeholder(m, androidx.compose.ui.graphics.RectangleShape) }, band = band, listState = listState) {
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
                                        meta = playlistMeta(c.songs),
                                        // Liked Music has its own heart cover: YouTube sends its top song's art for it.
                                        thumbnailUrl = if (com.opentune.ui.library.isYouTubeLikes(browseId)) null else c.thumbnailUrl,
                                        fallbackCover = fallbackCover,
                                        band = band,
                                        listState = listState,
                                        kind = if (isAlbum) "Album" else "Playlist",
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
                                                showPause = actions.queueSource == c.title && actions.currentVideoId != null && actions.isSounding,
                                                onToggle = if (actions.queueSource == c.title && actions.currentVideoId != null) actions.togglePlayPause else null,
                                                busy = actions.queueSource == c.title && actions.currentVideoId != null && actions.isPlaying && !actions.isSounding,
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
                                        onRemoveFromPlaylist = if (c.owned == true) ({ removeSongs(listOf(song)) }) else null,
                                        onSwipeRemove = if (c.owned == true) ({ removeSongs(listOf(song)) }) else null,
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
        } }
    }
    }
}
