package com.opentune.ui.player

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import android.content.ClipData
import android.content.res.Configuration
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.ui.draw.blur
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.opentune.data.LogExport
import com.opentune.data.covers.AlbumCovers
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.PlayerStyle
import com.opentune.data.settings.RemixPreset
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.data.together.Together
import com.opentune.playback.AudioFormatInfo
import com.opentune.playback.PlaybackRequests
import com.opentune.ui.LyricsState
import com.opentune.ui.PlayerViewModel
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.LocalBackdrop
import com.opentune.ui.components.LocalHazeState
import com.opentune.ui.components.LocalSongMenu
import com.opentune.ui.components.MarqueeText
import com.opentune.ui.components.MenuRow
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SongMenuSheet
import com.opentune.ui.components.glass
import com.opentune.ui.theme.PlayerTheme
import com.opentune.ui.theme.rememberArtworkSeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically

/**
 * The playback position, refreshed every frame while playing and the caller
 * is on screen (MediaController extrapolates between updates, so this is
 * smooth), and a few times a second while paused, to catch seeks.
 */
@Composable
fun rememberPlaybackPosition(vm: PlayerViewModel): () -> Long {
    val position = remember { mutableLongStateOf(vm.positionMs()) }
    LaunchedEffect(vm) {
        while (true) {
            if (vm.isPlaying.value) withFrameMillis { } else delay(250)
            // Through a quality switch, where the song is heard rather than the main player's stalled reading.
            position.longValue = com.opentune.playback.PlaybackRequests.soundingPosition?.invoke() ?: vm.positionMs()
        }
    }
    return { position.longValue }
}

/** Everything the full player shows, so it can be drawn without a live player. */
data class PlayerUiState(
    val song: Song,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val hasNext: Boolean,
    val shuffle: Boolean,
    val repeatMode: Int,
    val durationMs: Long,
    val lyrics: LyricsState,
    val queue: List<Song>,
    val currentIndex: Int,
    val upNext: List<Int>,
    val sound: SoundSettings,
    val theme: ThemeSettings,
    val ui: InterfaceSettings = InterfaceSettings(),
    /** "Playing from" label: an album, a playlist, Search… */
    val source: String? = null,
    val audioFormat: AudioFormatInfo? = null,
    /** Off, shuffle or smart shuffle, for the shuffle button. [shuffle] is the player's own shuffle order. */
    val shuffleMode: com.opentune.playback.ShuffleMode = if (shuffle) com.opentune.playback.ShuffleMode.ON else com.opentune.playback.ShuffleMode.OFF,
    /** The queue came from a playlist or album, so the shuffle button offers smart shuffle. */
    val smartShuffleAvailable: Boolean = false,
)

class PlayerActions(
    val togglePlay: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val toggleShuffle: () -> Unit,
    val cycleRepeat: () -> Unit,
    val seekTo: (Long) -> Unit,
    val playIndex: (Int) -> Unit,
    val removeIndex: (Int) -> Unit,
    val collapse: () -> Unit,
    val moveIndex: (Int, Int) -> Unit = { _, _ -> },
    val openTogether: () -> Unit = {},
    val openEqualizer: () -> Unit = {},
)

@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    onCollapse: () -> Unit,
    /** True while the player sits fully open, not being dragged down. */
    onCovering: (Boolean) -> Unit = {},
    onTogether: () -> Unit = {},
    onEqualizer: () -> Unit = {},
    /** Docked beside the page on a large screen; see [PlayerLayout]. */
    docked: Boolean = false,
) {
    val song by vm.currentSong.collectAsState()
    val current = song ?: return
    val theme by AppSettings.theme.collectAsState()
    val sound by AppSettings.sound.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isBuffering by vm.isBuffering.collectAsState()
    val hasNext by vm.hasNext.collectAsState()
    val shuffle by vm.shuffleEnabled.collectAsState()
    val shuffleMode by vm.shuffleMode.collectAsState()
    val smartShuffleAvailable by vm.smartShuffleAvailable.collectAsState()
    val repeat by vm.repeatMode.collectAsState()
    val duration by vm.durationMs.collectAsState()
    val lyrics by vm.lyrics.collectAsState()
    val queue by vm.queue.collectAsState()
    val currentIndex by vm.currentIndex.collectAsState()
    val upNext by vm.upNext.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val shown = current
    val source by vm.source.collectAsState()
    val audioFormat by vm.audioFormat.collectAsState()
    val actions = remember(vm, onCollapse) {
        PlayerActions(
            togglePlay = vm::togglePlayPause,
            next = vm::skipNext,
            previous = vm::skipPrevious,
            toggleShuffle = { vm.cycleShuffle() },
            cycleRepeat = vm::cycleRepeatMode,
            seekTo = vm::seekTo,
            playIndex = vm::playQueueItem,
            removeIndex = vm::removeQueueItem,
            collapse = onCollapse,
            moveIndex = vm::moveQueueItem,
            openTogether = onTogether,
            openEqualizer = onEqualizer,
        )
    }
    PlayerLayout(
        PlayerUiState(
            shown, isPlaying, isBuffering, hasNext, shuffle, repeat, duration,
            lyrics, queue, currentIndex, upNext, sound, theme, ui, source, audioFormat,
            shuffleMode, smartShuffleAvailable,
        ),
        position = rememberPlaybackPosition(vm),
        buffered = vm::bufferedPositionMs,
        actions = actions,
        seeks = rememberSeekCount(vm),
        onCovering = onCovering,
        docked = docked,
    )
}

/** A count that rises on each manual seek, for the lyrics clock to take a jump cleanly. */
@Composable
fun rememberSeekCount(vm: PlayerViewModel): () -> Long {
    val count = remember { mutableLongStateOf(0L) }
    LaunchedEffect(vm) { vm.seeks.collect { count.longValue++ } }
    return { count.longValue }
}

private enum class Pane { COVER, LYRICS, QUEUE }

@Composable
fun PlayerLayout(
    state: PlayerUiState,
    position: () -> Long,
    buffered: () -> Long,
    actions: PlayerActions,
    /** Rises on each manual seek, for the lyrics clock to take jumps cleanly. */
    seeks: () -> Long = { 0L },
    initialLyrics: Boolean = false,
    onCovering: (Boolean) -> Unit = {},
    /**
     * Docked in a pane beside the page, on a large screen: the upright
     * layout whatever the screen's orientation, nothing to drag down, and
     * [PlayerActions.collapse] folds the pane away instead of closing a sheet.
     */
    docked: Boolean = false,
) {
    val current = state.song
    val theme = state.theme
    val sound = state.sound

    var paneName by rememberSaveable { mutableStateOf(if (initialLyrics) Pane.LYRICS.name else Pane.COVER.name) }
    val pane = Pane.valueOf(paneName)
    // The cover between the lyrics or queue header and the full cover: tap the
    // small one and it grows into the big one (the lyrics make way); swipe up on
    // the big one and it goes back into the header of the view it came from.
    var returnPane by rememberSaveable { mutableStateOf<String?>(null) }
    // The list last shown, so it stays put while the page goes back to the cover.
    var lastList by rememberSaveable { mutableStateOf(Pane.LYRICS.name) }
    LaunchedEffect(pane) { if (pane != Pane.COVER) lastList = pane.name }
    var smallCover by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // Where the title row sits under the full cover, kept from when it was last at rest,
    // so it can travel with the cover into the header and back.
    var bigTitle by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val flight = remember { PaneFlight() }
    var showRemix by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showOffset by remember { mutableStateOf(false) }
    var showLyricsSource by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showSignal by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    // Lyrics run on their own clock, shifted by this song's offset, or the
    // one found by comparing lyrics sources when none is set.
    val lyricsSettings by AppSettings.lyrics.collectAsState()
    val autoOffsetMs = ((state.lyrics as? LyricsState.Found)?.lyrics as? com.opentune.data.lyrics.Lyrics.Synced)?.autoOffsetMs ?: 0L
    val offsetMs = com.opentune.data.lyrics.lyricsOffset(lyricsSettings.offsets[current.videoId], (state.lyrics as? LyricsState.Found)?.lyrics)
    val lyricsPosition: () -> Long = { position() + offsetMs }
    // For the lyrics clock: the intent to play (true through a buffer stall, so the
    // words coast rather than snapping back), and [seeks], which rises on each seek so
    // a jump is taken cleanly instead of being refused as a backward reading.
    val lyricsPlaying: () -> Boolean = { state.isPlaying || state.isBuffering }
    val lyricsSeeks: () -> Long = seeks
    // Long-press on the line being sung: shift this song's lyrics so that line starts now.
    val syncLine: (Long) -> Unit = { lineStart ->
        val shift = lineStart - position()
        AppSettings.setLyricsOffset(current.videoId, shift)
        com.opentune.data.Pills.info("Lyrics moved %+.1f s for this song".format(shift / 1000f), context)
    }
    // Lyrics take the screen: the controls go after LYRICS_FOCUS_MS untouched while
    // synced lyrics play on the lyrics view, and come back at the next touch.
    var touchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var controlsAway by remember { mutableStateOf(false) }
    var seenTouch by remember { mutableLongStateOf(touchedAt) }
    val focusable = state.ui.lyricsFocus && pane == Pane.LYRICS && state.isPlaying &&
        ((state.lyrics as? LyricsState.Found)?.lyrics is com.opentune.data.lyrics.Lyrics.Synced)
    val sheetOpen = showMenu || showRemix || showSleep || showCast || showOffset || showLyricsSource || showDetails || showSignal
    LaunchedEffect(touchedAt, focusable, sheetOpen) {
        // A sheet opening or closing leaves the controls as they are, the tap that opened
        // it included: bringing them back resized the lyrics under the sheet as it slid
        // up, and the sheet stuttered. The countdown starts again once it's gone.
        if (sheetOpen) { seenTouch = touchedAt; return@LaunchedEffect }
        if (!focusable || touchedAt != seenTouch) controlsAway = false
        seenTouch = touchedAt
        if (!focusable) return@LaunchedEffect
        delay(LYRICS_FOCUS_MS)
        controlsAway = true
    }
    // Swiping up through the lyrics slides the controls down out of the way;
    // swiping back down brings them up again. Only the finger's own scrolling
    // counts, past a short distance, so the list's glide to the next line doesn't.
    var scrolledAway by remember { mutableStateOf(false) }
    LaunchedEffect(pane) { if (pane != Pane.LYRICS) scrolledAway = false }
    val swipePx = with(LocalDensity.current) { 24.dp.toPx() }
    val lyricsScroll = remember(swipePx) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            private var run = 0f
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                val dy = available.y
                if ((run > 0f && dy < 0f) || (run < 0f && dy > 0f)) run = 0f
                run += dy
                if (run <= -swipePx) { scrolledAway = true; run = 0f }
                else if (run >= swipePx) { scrolledAway = false; run = 0f }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val controlsHidden = controlsAway || (scrolledAway && pane == Pane.LYRICS)
    val liked by LibraryStore.liked.collectAsState()
    val isLiked = liked.any { it.videoId == current.videoId }
    val device = rememberOutputDeviceName()
    val outputRoute = rememberOutputRoute()
    val sleepLabel = sleepTimerLabel()

    // Drag down anywhere outside the lyrics and queue lists to close.
    // Plain state, written straight from the drag: the release spring then
    // can't be cancelled by a drag step still on its way, which used to leave
    // the player parked part-way down after a short pull.
    var dragOffset by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val covering by rememberUpdatedState(onCovering)
    LaunchedEffect(Unit) {
        snapshotFlow { dragOffset == 0f }.distinctUntilChanged().collect { covering(it) }
    }
    // The cover leaves the card as it's dragged down and shrinks toward the
    // mini player with the finger; let go short and it settles back with the card.
    LaunchedEffect(docked, state.ui.reduceAnimation) {
        if (docked || state.ui.reduceAnimation) return@LaunchedEffect
        // When the whole player shrinks into the mini player, the cover goes with it instead.
        snapshotFlow { dragOffset }.collect { if (!com.opentune.ui.components.PlayerMorph.sheetMorph) com.opentune.ui.components.PlayerMorph.dragClose(it) }
    }
    val scope = rememberCoroutineScope()
    val dismissPx = with(LocalDensity.current) { 140.dp.toPx() }
    val cardCornerPx = with(LocalDensity.current) { 40.dp.toPx() }
    val backdrop = rememberLayerBackdrop()
    val style = state.ui.playerStyle
    val minimal = style == PlayerStyle.MINIMAL
    // Docked in a short window (an unfolded phone on its side), the extras
    // under the controls make way so the cover keeps some size.
    val windowHeight = with(LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() }
    val roomy = !docked || windowHeight >= DOCKED_ROOMY_HEIGHT
    // On its side the player has its own layout.
    val landscape = !docked && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Full-screen cover only fits the layouts built around a plain square cover.
    val fullCover = !landscape && state.ui.fullScreenCover && pane == Pane.COVER && (style == PlayerStyle.CLASSIC || style == PlayerStyle.MINIMAL)
    // Where the cover area ends, measured from the top of the player, so a
    // full-screen cover can run down to the title on any screen height.
    var playerCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var coverBottomPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    // The plain cover flies, square or edge to edge; the vinyl, cassette and other designs just change view.
    val fullStyle = !landscape && state.ui.fullScreenCover && (style == PlayerStyle.CLASSIC || style == PlayerStyle.MINIMAL)
    val flies = !state.ui.reduceAnimation && !docked && !landscape &&
        style != PlayerStyle.VINYL && style != PlayerStyle.CASSETTE && style != PlayerStyle.HALO &&
        style != PlayerStyle.POLAROID && style != PlayerStyle.LYRICS_FIRST
    // Where no cover flies (a full-screen cover, the other designs), the cover
    // page itself follows a swipe up: 0 at rest, 1 as far as it goes. Let go
    // past the mark, the lists come in from where it was left, not after.
    var coverLift by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val liftTravel = with(LocalDensity.current) { COVER_LIFT_TRAVEL.toPx() }
    var liftSettle by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Gone to the lists: once the cover page has played out, it's at rest for next time.
    LaunchedEffect(pane == Pane.COVER) {
        if (pane != Pane.COVER && coverLift > 0f) {
            delay(COVER_LIFT_RESET_MS)
            liftSettle?.cancel()
            coverLift = 0f
        } else if (pane == Pane.COVER && coverLift > 0f) {
            // Back on the cover by any way at all: it comes back whole, not shrunk where the last swipe left it.
            liftSettle?.cancel()
            coverLift = 0f
        }
    }
    // Where the header's small cover will be, before the lists have ever been shown:
    // the header row starts at the top left of the page, 8dp down, and its cover is 64dp.
    var paneCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val headerInset = with(LocalDensity.current) { 8.dp.toPx() }
    val headerCover = with(LocalDensity.current) { 64.dp.toPx() }
    fun headerHome(): androidx.compose.ui.geometry.Rect? {
        flight.lastSmall?.let { return it }
        val at = paneCoords?.takeIf { it.isAttached }?.positionInRoot() ?: return null
        return androidx.compose.ui.geometry.Rect(at.x, at.y + headerInset, at.x + headerCover, at.y + headerInset + headerCover)
    }
    val bleedOverlap = with(LocalDensity.current) { 44.dp.toPx() }
    /**
     * The full cover, in the root. An edge-to-edge one is the player's backdrop art: a
     * square as tall as the art, across the top and cut at the sides by the screen, which
     * is how the backdrop crops it, so the flight starts and ends exactly over it.
     */
    fun bigCover(): androidx.compose.ui.geometry.Rect? {
        if (!fullStyle) return com.opentune.ui.components.PlayerMorph.playerCover?.let(com.opentune.ui.components.PlayerMorph::scaled)
        val c = playerCoords?.takeIf { it.isAttached } ?: return null
        val at = c.positionInRoot()
        val w = c.size.width.toFloat()
        val h = maxOf(w / 0.9f, coverBottomPx + bleedOverlap)
        return androidx.compose.ui.geometry.Rect(at.x + (w - h) / 2f, at.y, at.x + (w + h) / 2f, at.y + h)
    }
    fun expandCover() {
        liftSettle?.cancel()
        coverLift = 0f
        returnPane = pane.name
        val from = smallCover
        if (flies && from != null) flight.start(from, toSmall = false)
        paneName = Pane.COVER.name
    }
    // Before the lists have ever been shown, where the header's cover will be is worked out.
    fun knowHeader() { if (flight.lastSmall == null) flight.lastSmall = headerHome() }
    fun collapseCover(fly: Boolean = true) {
        val back = returnPane?.let { runCatching { Pane.valueOf(it) }.getOrNull() }?.takeIf { it != Pane.COVER } ?: Pane.QUEUE
        val from = bigCover()
        smallCover = null
        knowHeader()
        // A swipe already carrying the cover: it goes on from where the finger let go.
        if (flight.following) flight.finish()
        else if (fly && flies && from != null) flight.start(from, toSmall = true)
        paneName = back.name
        returnPane = null
    }
    // The Lyrics and Queue buttons: from the full cover the cover flies into the list's
    // header, as a swipe up takes it, and back out of it to the full cover.
    fun toggle(p: Pane) {
        val next = if (pane == p) Pane.COVER else p
        when {
            style == PlayerStyle.LYRICS_FIRST -> paneName = next.name
            pane == Pane.COVER && next != Pane.COVER -> { returnPane = next.name; collapseCover() }
            pane != Pane.COVER && next == Pane.COVER -> expandCover()
            else -> paneName = next.name
        }
    }
    // Flies the cover to wherever its new place turns out to be, once that's laid out.
    LaunchedEffect(flight.runs) {
        if (flight.runs == 0) return@LaunchedEffect
        try {
            flight.t.snapTo(flight.startAt)
            flight.primed = true
            flight.following = false
            var frames = 0
            while (flight.target(smallCover, bigCover()) == null && frames++ < 30) androidx.compose.runtime.withFrameNanos { }
            if (flight.target(smallCover, bigCover()) == null) return@LaunchedEffect
            val left = 1f - flight.t.value
            flight.t.animateTo(1f, tween((440 * left).toInt().coerceAtLeast(160), easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)))
        } finally {
            flight.end()
        }
    }
    var upPull by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // A swipe up opens the lyrics or queue only from the cover and its title, not from the
    // seek bar, buttons and volume under them.
    var upAllowed by remember { mutableStateOf(true) }
    val upPx = with(LocalDensity.current) { 64.dp.toPx() }
    var coverSettle by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // The full cover follows a swipe up toward the header it goes back into,
    // when it's known where that header's cover sits.
    val followsSwipe = flies && pane == Pane.COVER && style != PlayerStyle.LYRICS_FIRST
    // Only enough to know which way is up: at 120dp, on a dense screen the full cover sat
    // just under it from the header, so the cover never followed the finger and the page
    // was lifted and faded instead, then held there until the queue cut in.
    val minFlightTravel = with(LocalDensity.current) { 32.dp.toPx() }
    fun followUp() {
        // Not over a tap's flight already on its way.
        if (!followsSwipe || (flight.active && !flight.following)) return
        val big = bigCover()
        if (flight.following) {
            flight.pull = (-upPull / flight.travel).coerceIn(0f, 1f)
            return
        }
        val home = headerHome() ?: return
        // Measured centre to centre: the full cover's top can sit level with the
        // header's, so a top-to-top distance came out at nothing and the cover
        // never followed the finger.
        val travel = (big?.center?.y ?: 0f) - home.center.y
        if (upPull < 0f && big != null && big.height > home.height * 2f && travel > minFlightTravel) {
            // Only from the full cover as it is now: a position left from an earlier
            // flight (no bigger than the small one) made the cover leap to the
            // header at the first touch and freeze there.
            flight.follow(big, home, (-upPull / travel).coerceIn(0f, 1f), travel)
        }
    }

    PlayerTheme(seed = rememberArtworkSeed(current.thumbnailUrl), settings = theme) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Dragging down turns the player into a card over the app,
                    // the way iOS sheets do: it shrinks from the top edge and
                    // its corners round, so the screen behind shows around it.
                    // With the sheet morph, the card is moved by the sheet itself, toward the mini player.
                    if (com.opentune.ui.components.PlayerMorph.sheetMorph) return@graphicsLayer
                    val p = (dragOffset / (dismissPx * 2)).coerceIn(0f, 1f)
                    translationY = dragOffset * 0.9f
                    val scale = 1f - 0.12f * p
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    shape = RoundedCornerShape(40.dp * p)
                    clip = p > 0f
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    enabled = !docked,
                    state = rememberDraggableState { delta ->
                        var d = delta
                        // Back down after a swipe up: the cover comes back with the finger first.
                        if (upPull < 0f && d > 0f) {
                            val back = minOf(d, -upPull)
                            upPull += back
                            d -= back
                        }
                        // Upward, with the card at rest: a swipe up on the cover, counted on its own.
                        if (d < 0f && dragOffset == 0f) { if (upAllowed) upPull += d; d = 0f }
                        dragOffset = (dragOffset + d).coerceAtLeast(0f)
                        // The card shrinks toward the mini player under the finger.
                        if (com.opentune.ui.components.PlayerMorph.sheetMorph) com.opentune.ui.components.PlayerMorph.sheetDrag = dragOffset
                        followUp()
                        // When the cover itself can't fly with the finger, the whole cover page
                        // lifts with it instead, so a swipe up always moves something.
                        if (pane == Pane.COVER && style != PlayerStyle.LYRICS_FIRST && !flight.following) {
                            coverLift = (-upPull / liftTravel).coerceIn(0f, 1f)
                        }
                    },
                    // A finger landing on the card mid-spring takes hold of it where it is.
                    onDragStarted = { at ->
                        // Started below the title (on the controls): no swipe up there. Wherever the
                        // title is, measured on the cover; in other layouts it isn't, and any place does.
                        val title = bigTitle
                        val top = playerCoords?.takeIf { it.isAttached }?.positionInRoot()?.y
                        upAllowed = pane != Pane.COVER || title == null || top == null || at.y <= title.bottom - top
                        settling?.cancel()
                        coverSettle?.cancel()
                        liftSettle?.cancel()
                        upPull = when {
                            flight.following -> -flight.pull * flight.travel
                            pane == Pane.COVER && coverLift > 0f -> -coverLift * liftTravel
                            else -> 0f
                        }
                    },
                    onDragStopped = { velocity ->
                        // Up a little, then down in the same swipe: the up part came undone on the way
                        // down, so its cover is put back at once, and the card settles or closes below
                        // as any pull down does. Settling only the cover had left the card shrunk, stuck.
                        if (dragOffset > 0f && (flight.following || coverLift > 0f)) {
                            if (flight.following) flight.end()
                            liftSettle?.cancel()
                            coverLift = 0f
                            upPull = 0f
                        }
                        if (pane == Pane.COVER && style != PlayerStyle.LYRICS_FIRST && dragOffset == 0f && (upPull < -upPx || velocity < -1_500f || flight.pull > 0.3f)) {
                            // Swiped up on the cover: it goes back up into the lyrics or queue header.
                            // A lifted cover page keeps rising at the finger's speed as it leaves,
                            // so nothing stops or jumps back at the moment of letting go.
                            val lifted = coverLift > 0f
                            collapseCover(fly = !lifted)
                            if (lifted) liftSettle = scope.launch {
                                androidx.compose.animation.core.animate(coverLift, 1f, -velocity / liftTravel, spring(dampingRatio = 1f, stiffness = 420f)) { v, _ ->
                                    coverLift = v.coerceIn(0f, 1f)
                                }
                            }
                            upPull = 0f
                        } else if (coverLift > 0f && dragOffset == 0f) {
                            // Short of it: the cover page settles back down.
                            liftSettle = scope.launch {
                                androidx.compose.animation.core.animate(coverLift, 0f, -velocity / liftTravel, spring(dampingRatio = 0.85f, stiffness = 500f)) { v, _ ->
                                    coverLift = v.coerceIn(0f, 1f)
                                }
                            }
                            upPull = 0f
                        } else if (flight.following && dragOffset == 0f) {
                            // Short of it: the cover settles back into its place.
                            coverSettle = scope.launch {
                                androidx.compose.animation.core.animate(flight.pull, 0f, -velocity / flight.travel, spring(dampingRatio = 0.85f, stiffness = 500f)) { v, _ ->
                                    flight.pull = v.coerceIn(0f, 1f)
                                }
                                flight.end()
                            }
                            upPull = 0f
                        } else if (dragOffset > dismissPx || velocity > 2_000f) {
                            val size = playerCoords?.takeIf { it.isAttached }?.size
                            if (com.opentune.ui.components.PlayerMorph.sheetMorph && size != null) {
                                // The card is already part of the way to the mini player, under
                                // the finger; the shrink carries on from there at the finger's speed.
                                val travel = (com.opentune.ui.components.PlayerMorph.miniBar?.top ?: size.height * 0.85f).coerceAtLeast(1f)
                                com.opentune.ui.components.PlayerMorph.sheetFling = -velocity.coerceAtLeast(0f) / travel
                                dragOffset = 0f
                            }
                            // Otherwise leave the card as it is: the exit slide carries it away from here.
                            actions.collapse()
                        } else {
                            settling = scope.launch {
                                androidx.compose.animation.core.animate(dragOffset, 0f, velocity, spring(dampingRatio = 0.82f, stiffness = 420f)) { v, _ ->
                                    dragOffset = v.coerceAtLeast(0f)
                                    if (com.opentune.ui.components.PlayerMorph.sheetMorph) com.opentune.ui.components.PlayerMorph.sheetDrag = dragOffset
                                }
                                dragOffset = 0f
                                com.opentune.ui.components.PlayerMorph.sheetDrag = 0f
                            }
                        }
                    },
                )
                .onGloballyPositioned { playerCoords = it },
        ) {
            // The backdrop is the layer the player's Liquid Glass bends.
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                PlayerBackdrop(
                    theme.playerBackground,
                    current.thumbnailUrl,
                    Modifier.fillMaxSize(),
                    animate = !state.ui.reduceAnimation,
                    // While it flies the cover is drawn by the flight, not here as well.
                    fullCover = fullCover && !flight.active,
                    fullCoverBottom = with(LocalDensity.current) { coverBottomPx.takeIf { it > 0f }?.toDp() },
                    movingCover = state.ui.movingCover && !state.ui.reduceAnimation,
                    playing = state.isPlaying,
                    change = if (state.ui.reduceAnimation) com.opentune.data.settings.CoverChange.FADE else state.ui.coverChange,
                    index = state.currentIndex,
                )
            }

            // Behind the lyrics, the cover's colours drift slowly.
            androidx.compose.animation.AnimatedVisibility(
                visible = pane == Pane.LYRICS && !landscape,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.fillMaxSize(),
            ) {
                val scheme = MaterialTheme.colorScheme
                AmbientLights(
                    listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.inversePrimary, scheme.primaryContainer),
                    playing = state.isPlaying,
                    animate = !state.ui.reduceAnimation,
                    modifier = Modifier.fillMaxSize(),
                    field = true,
                )
            }

            // The player draws its own dark surface, so it sets its own text and
            // icon color rather than inheriting the app's (light in light theme).
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                LocalBackdrop provides backdrop,
                LocalHazeState provides null,
            ) {
            // On its side the cover goes left and the rest stacks on the right;
            // lyrics and the queue open in the cover's place.
            if (landscape) {
                LandscapePlayer(
                    state, position, lyricsPosition, actions,
                    liked = isLiked,
                    device = sleepLabel ?: device,
                    onLike = { LibraryStore.setLiked(current, !isLiked) },
                    onMore = { showMenu = true },
                    onSignal = { showSignal = true },
                    onLyrics = { toggle(Pane.LYRICS) },
                    onQueue = { toggle(Pane.QUEUE) },
                    showLines = pane != Pane.LYRICS,
                    side = when (pane) {
                        Pane.COVER -> null
                        Pane.LYRICS -> { ->
                            LyricsView(
                                state.lyrics,
                                lyricsPosition,
                                onSeek = { actions.seekTo((it - offsetMs).coerceAtLeast(0)) },
                                modifier = Modifier.fillMaxSize().nestedScroll(lyricsScroll).focusIn(!state.ui.reduceAnimation),
                                synced = state.ui.syncedLyrics,
                                blur = state.ui.blurLyrics,
                                shareSong = current,
                                onSyncLine = syncLine,
                                playing = lyricsPlaying,
                                seeks = lyricsSeeks,
                            )
                        }
                        Pane.QUEUE -> { ->
                            QueuePane(
                                queue = state.queue,
                                currentIndex = state.currentIndex,
                                upNext = state.upNext,
                                isPlaying = state.isPlaying,
                                shuffle = state.shuffle,
                                smartShuffle = state.shuffleMode == com.opentune.playback.ShuffleMode.SMART,
                                onPlayIndex = actions.playIndex,
                                onRemoveIndex = actions.removeIndex,
                                onMove = actions.moveIndex,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    },
                )
            } else Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 24.dp)
                    // Every touch anywhere on the player, seen before the parts under it
                    // handle it and never taken from them: it counts as being looked at.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val now = System.currentTimeMillis()
                                // A few times a second is plenty, and keeps a drag from recomposing the player each frame.
                                // Controls that are away come back when the finger lifts, so a tap that opens a
                                // sheet (seen in the same event) leaves them where they are.
                                if (controlsAway) {
                                    if (e.changes.any { it.previousPressed && !it.pressed }) touchedAt = now
                                } else if (e.changes.any { it.pressed } && now - touchedAt > 250) touchedAt = now
                            }
                        }
                    },
            ) {
                // Grab handle, then "Playing from" or the remix pill.
                // Docked, there's nothing to pull down: a button at the end folds the pane away.
                Column(Modifier.fillMaxWidth().padding(top = if (docked) 0.dp else 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!docked) Box(
                        Modifier.size(width = 40.dp, height = 5.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                            .clickable(onClick = actions.collapse),
                    )
                    Box(if (docked) Modifier.fillMaxWidth().height(48.dp) else Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                        if (docked) {
                            Box(
                                Modifier.align(Alignment.CenterEnd).size(40.dp).clip(CircleShape).clickable(onClick = actions.collapse),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Hide player", Modifier.size(26.dp))
                            }
                        }
                        val room by Together.room.collectAsState()
                        AnimatedContent(sound.isDefault, label = "remixPill", modifier = if (docked) Modifier.padding(horizontal = 44.dp) else Modifier) { normal ->
                            if (room != null) {
                                TogetherPill(room!!, actions.openTogether)
                            } else if (normal) {
                                if (!state.ui.hideSongStatus) SongStatus(state.source)
                            } else {
                                AssistChip(
                                    onClick = { showRemix = true },
                                    label = { Text(RemixPreset.matching(sound)?.label ?: "Custom remix") },
                                    leadingIcon = { Icon(Icons.Rounded.GraphicEq, null, Modifier.size(18.dp)) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                }

                // Cover mode: the art fills the space and the title sits below it.
                // Lyrics and queue: a compact header, then the list.
                // Lyrics and queue share one layout (the header stays put and only the
                // list below it changes), so the outer change is cover against list.
                // While a swipe up carries the cover, the change to the lists is scrubbed by
                // the finger, so the lists come in as the cover shrinks rather than after
                // letting go; let go, it plays on from there, or back if it's short.
                val outerPane = if (pane == Pane.COVER) Pane.COVER else Pane.LYRICS
                // Like BitChord: the cover page and the lists are both kept built and only
                // their layers move, on one value from 0 (the cover) to 1 (the lists) that the
                // finger or a 420ms ease drives. Nothing is composed while it moves: the lists,
                // the heaviest thing here, are built out of sight a moment after the player
                // settles, and fade up behind the cover as it reaches the header.
                val listsShown = remember { androidx.compose.animation.core.Animatable(if (outerPane == Pane.COVER) 0f else 1f) }
                var listsBuilt by remember { mutableStateOf(outerPane != Pane.COVER) }
                LaunchedEffect(Unit) { delay(LISTS_PREWARM_MS); listsBuilt = true }
                LaunchedEffect(outerPane, flight.following) {
                    val target = if (outerPane == Pane.COVER) 0f else 1f
                    if (flight.following && outerPane == Pane.COVER) {
                        listsBuilt = true
                        androidx.compose.runtime.snapshotFlow { flight.pull }.collect { listsShown.snapTo(it.coerceIn(0f, 1f)) }
                    } else if (state.ui.reduceAnimation) {
                        listsBuilt = listsBuilt || target == 1f
                        listsShown.snapTo(target)
                    } else {
                        listsBuilt = listsBuilt || target == 1f
                        val left = kotlin.math.abs(target - listsShown.value)
                        listsShown.animateTo(target, tween((PANE_SWITCH_MS * left).toInt().coerceAtLeast(120), easing = FastOutSlowInEasing))
                    }
                }
                val listsGone by remember { androidx.compose.runtime.derivedStateOf { listsShown.value <= 0.001f } }
                val listsFull by remember { androidx.compose.runtime.derivedStateOf { listsShown.value >= 0.999f } }
                val paneBody: @Composable (Pane, Boolean) -> Unit = { p, hidden ->
                    Column(
                        Modifier.fillMaxSize().graphicsLayer {
                            // The cover page, carried up by a swipe that's still under the finger.
                            if (p == Pane.COVER) {
                                val k = coverLift
                                translationY = -k * size.height * COVER_LIFT_RISE
                                val sc = 1f - COVER_LIFT_SHRINK * k
                                scaleX = sc
                                scaleY = sc
                                transformOrigin = TransformOrigin(0.5f, 0f)
                                alpha = 1f - COVER_LIFT_FADE * k
                            }
                        },
                    ) {
                        if (p == Pane.COVER && style == PlayerStyle.LYRICS_FIRST) {
                            // The lyrics are the page; the cover shrinks to the header.
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(current.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(84.dp), RoundedCornerShape(14.dp))
                                Spacer(Modifier.width(16.dp))
                                TitleRow(current, isLiked, onLike = { LibraryStore.setLiked(current, !isLiked) }, onMore = { showMenu = true }, compact = true)
                            }
                            LyricsView(
                                state.lyrics,
                                lyricsPosition,
                                onSeek = { actions.seekTo((it - offsetMs).coerceAtLeast(0)) },
                                modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(lyricsScroll),
                                synced = state.ui.syncedLyrics,
                                blur = state.ui.blurLyrics,
                                shareSong = current,
                                onSyncLine = syncLine,
                                playing = lyricsPlaying,
                                seeks = lyricsSeeks,
                            )
                        } else if (p == Pane.COVER) {
                            Box(Modifier.weight(1f).fillMaxWidth().then(if (docked) Modifier.fitSquare() else Modifier), contentAlignment = Alignment.Center) {
                                when {
                                    fullCover ->
                                        ArtworkSwipeArea(
                                            onSwipeNext = actions.next,
                                            onSwipePrevious = actions.previous,
                                            modifier = Modifier.fillMaxSize().onGloballyPositioned { c ->
                                                val root = playerCoords ?: return@onGloballyPositioned
                                                if (root.isAttached && c.isAttached) {
                                                    coverBottomPx = root.localPositionOf(c, androidx.compose.ui.geometry.Offset(0f, c.size.height.toFloat())).y
                                                }
                                            },
                                        )
                                    style == PlayerStyle.VINYL ->
                                        VinylPane(current, state.isPlaying, onSwipeNext = actions.next, onSwipePrevious = actions.previous, animate = !state.ui.reduceAnimation)
                                    style == PlayerStyle.CASSETTE ->
                                        CassettePane(
                                            current, state.isPlaying,
                                            progress = { if (state.durationMs > 0) position() / state.durationMs.toFloat() else 0f },
                                            onSwipeNext = actions.next, onSwipePrevious = actions.previous, animate = !state.ui.reduceAnimation,
                                        )
                                    style == PlayerStyle.HALO ->
                                        HaloPane(current, state.isPlaying, onSwipeNext = actions.next, onSwipePrevious = actions.previous, animate = !state.ui.reduceAnimation)
                                    style == PlayerStyle.POLAROID ->
                                        PolaroidPane(current, state.isPlaying, onSwipeNext = actions.next, onSwipePrevious = actions.previous, animate = !state.ui.reduceAnimation)
                                    else ->
                                        ArtworkPane(
                                            current, state.isPlaying, onSwipeNext = actions.next, onSwipePrevious = actions.previous,
                                            moving = state.ui.movingCover && !state.ui.reduceAnimation,
                                            change = if (state.ui.reduceAnimation) com.opentune.data.settings.CoverChange.FADE else state.ui.coverChange,
                                            index = state.currentIndex,
                                            onSeekBy = { d -> actions.seekTo((position() + d).coerceIn(0L, state.durationMs.coerceAtLeast(0L))) },
                                            glow = state.ui.coverGlow && !state.ui.reduceAnimation,
                                        )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            TitleRow(
                                current, isLiked, onLike = { LibraryStore.setLiked(current, !isLiked) }, onMore = { showMenu = true },
                                Modifier
                                    .onGloballyPositioned { if (!flight.active && coverLift == 0f && dragOffset == 0f) bigTitle = it.boundsInRoot() }
                                    .graphicsLayer { alpha = if (flight.active && bigTitle != null) 0f else 1f },
                            )
                        } else {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(
                                    current.thumbnailUrl.artworkAt(ROW_ART_PX),
                                    Modifier.size(64.dp)
                                        // Not while parked out of sight: that place isn't where it shows.
                                        .onGloballyPositioned { if (!hidden) { smallCover = it.boundsInRoot(); flight.lastSmall = smallCover } }
                                        .graphicsLayer { alpha = if (flight.active) 0f else 1f }
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(onClickLabel = "Show the cover") { expandCover() },
                                    RoundedCornerShape(10.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Row(Modifier.weight(1f).graphicsLayer { alpha = if (flight.active && bigTitle != null) 0f else 1f }, verticalAlignment = Alignment.CenterVertically) {
                                    TitleRow(current, isLiked, onLike = { LibraryStore.setLiked(current, !isLiked) }, onMore = { showMenu = true }, compact = true)
                                }
                            }
                            // Between lyrics and queue the new list slides up from below on a soft
                            // spring while the old one lifts a little and fades, under a still header.
                            // Both stay composed once shown, and only their layers move: switching
                            // never builds a list (or scrolls the lyrics to their line) mid-slide.
                            // On the cover, mid-swipe: the list the swipe goes to. Leaving: the one that was up.
                            val list = pane.takeIf { it != Pane.COVER }
                                ?: if (flight.following) returnPane?.let { runCatching { Pane.valueOf(it) }.getOrNull() }?.takeIf { it != Pane.COVER } ?: Pane.QUEUE
                                else runCatching { Pane.valueOf(lastList) }.getOrDefault(Pane.LYRICS)
                            val toQueue = remember { androidx.compose.animation.core.Animatable(if (list == Pane.QUEUE) 1f else 0f) }
                            LaunchedEffect(list, state.ui.reduceAnimation) {
                                val target = if (list == Pane.QUEUE) 1f else 0f
                                if (state.ui.reduceAnimation) toQueue.snapTo(target)
                                else toQueue.animateTo(target, spring(dampingRatio = 1f, stiffness = 320f, visibilityThreshold = 0.001f))
                            }
                            var lyricsBuilt by remember { mutableStateOf(list == Pane.LYRICS) }
                            var queueBuilt by remember { mutableStateOf(list == Pane.QUEUE) }
                            if (list == Pane.LYRICS) lyricsBuilt = true else queueBuilt = true
                            // The other one is built a moment after this view opens, out of sight,
                            // so the first switch to it is as smooth as the rest.
                            LaunchedEffect(Unit) { delay(700); lyricsBuilt = true; queueBuilt = true }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                for (shown in listOf(Pane.LYRICS, Pane.QUEUE)) {
                                    if (!(if (shown == Pane.LYRICS) lyricsBuilt else queueBuilt)) continue
                                    val queueSide = shown == Pane.QUEUE
                                    Column(
                                        Modifier.fillMaxSize()
                                            // The one in view is on top, and takes the touches.
                                            .zIndex(if (shown == list) 1f else 0f)
                                            .graphicsLayer {
                                                val q = toQueue.value
                                                val inView = if (queueSide) q else 1f - q
                                                alpha = inView
                                                // Faded per draw, not through a buffer the size of the pane,
                                                // which would cut the blurred lines off at the page margin.
                                                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                                                // Coming in from below; going out, lifting a little.
                                                val arriving = shown == list
                                                translationY = if (arriving) size.height * 0.22f * (1f - inView) else -size.height * 0.06f * (1f - inView)
                                            },
                                    ) {
                                    if (shown == Pane.LYRICS) {
                                        LyricsView(
                                            state.lyrics,
                                            // Parked behind the cover, the lyrics follow once a second rather than
                                            // drawing every frame; they stay on their line, so coming in needs no scroll.
                                            if (hidden) ({ lyricsPosition() / HIDDEN_LYRICS_STEP_MS * HIDDEN_LYRICS_STEP_MS }) else lyricsPosition,
                                            onSeek = { actions.seekTo((it - offsetMs).coerceAtLeast(0)) },
                                            modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(lyricsScroll).focusIn(!state.ui.reduceAnimation),
                                            synced = state.ui.syncedLyrics,
                                            blur = state.ui.blurLyrics,
                                            shareSong = current,
                                            onSyncLine = syncLine,
                                            playing = lyricsPlaying,
                                            seeks = lyricsSeeks,
                                        )
                                    } else {
                                        Text("Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                                        QueuePane(
                                            queue = state.queue,
                                            currentIndex = state.currentIndex,
                                            upNext = state.upNext,
                                            isPlaying = state.isPlaying,
                                            shuffle = state.shuffle,
                                            smartShuffle = state.shuffleMode == com.opentune.playback.ShuffleMode.SMART,
                                            onPlayIndex = actions.playIndex,
                                            onRemoveIndex = actions.removeIndex,
                                            onMove = actions.moveIndex,
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                        )
                                    }
                                    }
                                }
                            }
                        }
                    }
                }
                // Not clipped: a blurred lyric line spreads past the page's side margin.
                Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { paneCoords = it }) {
                    val switchDp = with(LocalDensity.current) { 26.dp.toPx() }
                    // The cover page: only while it's in sight, so its cover reports its own place.
                    if (outerPane == Pane.COVER || !listsFull) {
                        Box(
                            Modifier.fillMaxSize().zIndex(if (outerPane == Pane.COVER) 1f else 0f).graphicsLayer {
                                val v = listsShown.value
                                alpha = (1f - v * 1.6f).coerceIn(0f, 1f)
                                val sc = 1f - 0.06f * v
                                scaleX = sc
                                scaleY = sc
                                // Faded through a layer, the page would be cut to its own box, and the
                                // glow spilling above the cover would end in a straight line.
                                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                            },
                        ) { paneBody(Pane.COVER, false) }
                    }
                    // The lists: built once, then parked far off to the side while the cover is up,
                    // so they never take its touches or cost a frame when they come in.
                    if (listsBuilt) {
                        val parked = outerPane == Pane.COVER && !flight.following && listsGone
                        Box(
                            Modifier.fillMaxSize()
                                .zIndex(if (outerPane == Pane.COVER) 0f else 1f)
                                .offset { if (parked) androidx.compose.ui.unit.IntOffset(100_000, 0) else androidx.compose.ui.unit.IntOffset.Zero }
                                .graphicsLayer {
                                    val v = listsShown.value
                                    alpha = ((v - 0.4f) / 0.6f).coerceIn(0f, 1f)
                                    translationY = (1f - v) * switchDp
                                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                                },
                        ) { paneBody(Pane.LYRICS, parked) }
                    }
                }

                val synced = (state.lyrics as? LyricsState.Found)?.lyrics as? com.opentune.data.lyrics.Lyrics.Synced
                // Lyrics are timed against the song shifted by the offset; the bar is the song's own time.
                val choruses = remember(synced, offsetMs) { synced?.let { com.opentune.data.lyrics.SongParts.choruses(it).map { ms -> ms - offsetMs } }.orEmpty() }
                val marks = remember(synced, offsetMs) { synced?.let { com.opentune.data.lyrics.SongParts.marks(it).map { ms -> ms - offsetMs } }.orEmpty() }
                val nextChorus by androidx.compose.runtime.produceState<Long?>(null, choruses) {
                    while (true) {
                        value = com.opentune.data.lyrics.SongParts.nextChorus(choruses, position())
                        kotlinx.coroutines.delay(500)
                    }
                }
                // Lyrics with nothing in the way: a few seconds untouched while they
                // play, and the controls slide down out of sight.
                AnimatedVisibility(
                    !controlsHidden,
                    enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = 380f), expandFrom = Alignment.Top) + slideInVertically(spring(dampingRatio = 0.9f, stiffness = 380f)) { it / 2 } + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(520, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + slideOutVertically(tween(520, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(tween(360)),
                ) {
                Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(state.ui.skipToChorus && nextChorus != null, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                        SkipToChorus { nextChorus?.let { actions.seekTo((it - 250).coerceAtLeast(0)) } }
                    }
                }
                if (pane != Pane.LYRICS && state.ui.syncedLyrics && !minimal && roomy && !(pane == Pane.COVER && style == PlayerStyle.LYRICS_FIRST)) {
                    LyricPreview(state.lyrics, lyricsPosition, onOpen = { paneName = Pane.LYRICS.name }, Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                // The line at a point of the song, for the bubble shown while the bar is dragged.
                val lyricAt: ((Long) -> String?)? = if (state.ui.lyricScrubPreview && synced != null) {
                    { ms -> synced.lines.lastOrNull { it.startMs <= ms + offsetMs }?.text?.takeIf { it.isNotBlank() } }
                } else null
                SeekBar(position, buffered, state.durationMs, onSeek = actions.seekTo, wavy = state.ui.wavySeekbar && !state.ui.reduceAnimation, playing = state.isPlaying, lyricAt = lyricAt, marks = marks, quality = { if (state.ui.sourcePill) SourceQualityBadge(current.videoId, state.audioFormat, onClick = { showSignal = true }) })
                if (state.ui.statsForNerds) {
                    Box(Modifier.clickable { showSignal = true }) { NerdStatsLine(state.audioFormat) }
                }
                PlayerControls(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    hasNext = state.hasNext,
                    onTogglePlay = actions.togglePlay,
                    onNext = actions.next,
                    onPrevious = actions.previous,
                    style = state.ui.controlStyle,
                    animate = !state.ui.reduceAnimation,
                    progress = { if (state.durationMs > 0) position() / state.durationMs.toFloat() else 0f },
                )
                if (!state.ui.hideVolumeBar && !minimal && roomy) VolumeBar(Modifier.padding(vertical = 4.dp))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassToggle(PlayerIcons.Lyrics, "Lyrics", pane == Pane.LYRICS, { toggle(Pane.LYRICS) })
                    // One pill that morphs between sleep and output, and the queue's modes.
                    val inQueueNow = pane == Pane.QUEUE
                    MorphPill(
                        second = inQueueNow,
                        animate = !state.ui.reduceAnimation,
                        firstFill = Color.White.copy(alpha = 0.14f),
                        secondFill = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.weight(1f),
                        first = { enabled ->
                            // Its two halves split by a hairline; the fill is the morphing one behind.
                            Row(Modifier.height(52.dp).clip(RoundedCornerShape(30.dp)), verticalAlignment = Alignment.CenterVertically) {
                                // Each half is the button: a press fills the half, not a circle round the icon.
                                PillButton(Icons.Rounded.Bedtime, "Sleep timer", sleepLabel != null, { showSleep = true }, enabled = enabled)
                                Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.16f)))
                                PillButton(
                                    outputRoute.icon,
                                    "Audio output",
                                    // Headphones only change the icon; lit up is for casting, a session to notice.
                                    outputRoute == OutputRoute.CAST,
                                    { showOutput = true },
                                    enabled = enabled,
                                )
                            }
                        },
                        secondContent = { enabled ->
                            ModesPill(state.shuffleMode, state.smartShuffleAvailable, state.repeatMode, actions.toggleShuffle, actions.cycleRepeat, filled = false, enabled = enabled)
                        },
                    )
                    GlassToggle(Icons.AutoMirrored.Rounded.FormatListBulleted, "Queue", pane == Pane.QUEUE, { toggle(Pane.QUEUE) })
                }
                if (!minimal && roomy) Text(
                    sleepLabel ?: device,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                )
                }
                }
                // Where the controls went: a faint handle, and a swipe or touch brings them back.
                AnimatedVisibility(controlsHidden, enter = fadeIn(tween(400)), exit = fadeOut(tween(150))) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 44.dp, height = 5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)))
                    }
                }
            }
            }
            // The cover on its way between the header and the full cover, over the rest.
            if (flight.active) {
                val from = flight.from
                // Before the header is back (a swipe still under the finger), where it was last.
                val to = flight.target(smallCover, bigCover())
                val origin = playerCoords?.takeIf { it.isAttached }?.positionInRoot() ?: androidx.compose.ui.geometry.Offset.Zero
                if (from != null && to != null) {
                    // Progress is read in the layer only: the finger or the runner, frame by frame.
                    com.opentune.ui.components.MorphBox(
                        current.thumbnailUrl.artworkAt(com.opentune.data.model.PLAYER_ART_PX),
                        current.thumbnailUrl.artworkAt(ROW_ART_PX),
                        from, to,
                        p = { flight.progress },
                        startCornerDp = if (flight.toSmall) (if (fullStyle) 0f else 28f) else 10f,
                        endCornerDp = if (flight.toSmall) 10f else (if (fullStyle) 0f else 28f),
                        origin = origin,
                        fullBleed = fullStyle,
                    )
                    // Drawn outside the player's own colours, so given them here: the title and
                    // its buttons changed colour as they flew.
                    bigTitle?.let { title -> CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        TitleFlight(
                            current, isLiked, title,
                            big = if (flight.toSmall) from else to,
                            small = if (flight.toSmall) to else from,
                            q = { flight.progress.let { if (flight.toSmall) it else 1f - it }.coerceIn(0f, 1f) },
                            origin = origin,
                        )
                    } }
                }
            }
        }

        // Winding down for the night: the whole player dims with the music.
        val windDown by com.opentune.playback.SleepTimer.windDown.collectAsState()
        if (windDown > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = (windDown * 0.55f).coerceIn(0f, 0.55f) }.background(Color.Black))
        }
        if (showRemix) RemixSheet(onDismiss = { showRemix = false })
        if (showOutput) com.opentune.ui.cast.AudioOutputSheet(
            onDismiss = { showOutput = false },
            onCast = { showOutput = false; showCast = true },
            onPipeline = { showOutput = false; showSignal = true },
        )
        if (showCast) com.opentune.ui.cast.CastSheet(onDismiss = { showCast = false })
        if (showSleep) SleepTimerDialog(onDismiss = { showSleep = false })
        if (showMenu) {
            SongMenuSheet(
                current,
                onDismiss = { showMenu = false },
                top = { close -> MenuRow(Icons.Rounded.HighQuality, "Upgrade quality") { close(); PlaybackRequests.upgradeQuality() } },
                tools = { close ->
                    MenuRow(Icons.Rounded.HighQuality, "Track details") { close(); showDetails = true }
                    MenuRow(Icons.Rounded.GraphicEq, "Signal path") { close(); showSignal = true }
                    MenuRow(Icons.Rounded.Groups, "Listen together") { close(); actions.openTogether() }
                    MenuRow(Icons.Rounded.Bedtime, sleepLabel ?: "Sleep timer") { close(); showSleep = true }
                    MenuRow(Icons.Rounded.Tune, "Lyrics offset") { close(); showOffset = true }
                    MenuRow(Icons.Rounded.Lyrics, "Lyrics source") { close(); showLyricsSource = true }
                    if (com.opentune.data.lyrics.Romanizer.available) {
                        MenuRow(Icons.Rounded.Translate, if (lyricsSettings.romanize) "Hide romanized lyrics" else "Show romanized lyrics") {
                            close()
                            AppSettings.updateLyrics { it.copy(romanize = !it.romanize) }
                        }
                    }
                    MenuRow(Icons.Rounded.Equalizer, "Equalizer") { close(); actions.openEqualizer() }
                    MenuRow(Icons.Rounded.GraphicEq, "Remix") { close(); showRemix = true }
                },
                end = { close ->
                    MenuRow(Icons.Rounded.BugReport, "Copy log") {
                        close()
                        scope.launch {
                            val log = LogExport.recent()
                            clipboard.setPrimaryClip(ClipData.newPlainText("OpenTune log", log))
                            com.opentune.data.Pills.done("Log copied", context)
                        }
                    }
                },
            )
        }
        if (showOffset) LyricsOffsetDialog(current.videoId, autoOffsetMs, onDismiss = { showOffset = false })
        if (showLyricsSource) LyricsSourceDialog(current, state.durationMs, onDismiss = { showLyricsSource = false })
        if (showDetails) TrackDetailsDialog(current, state.durationMs, onDismiss = { showDetails = false })
        if (showSignal) SignalPathDialog(state.audioFormat, onDismiss = { showSignal = false })
    }
}

/**
 * Shifts this song's lyrics earlier or later when they run out of step with
 * the audio. Kept per song. Starts from [autoMs], the shift found by
 * comparing lyrics sources, until one is set here; Reset goes back to it.
 */
@Composable
private fun LyricsOffsetDialog(videoId: String, autoMs: Long, onDismiss: () -> Unit) {
    val lyrics by AppSettings.lyrics.collectAsState()
    val manual = lyrics.offsets[videoId]
    val ms = manual ?: autoMs
    // A chosen 0 is kept when there's an automatic shift it overrides.
    fun set(v: Long) = AppSettings.setLyricsOffset(videoId, v.coerceIn(-10_000, 10_000), keepZero = autoMs != 0L)
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lyrics offset") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        ms == 0L -> "In step"
                        ms > 0 -> "%.1f s earlier".format(ms / 1000f)
                        else -> "%.1f s later".format(-ms / 1000f)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (manual == null && autoMs != 0L) {
                    Text(
                        "Set to match the other lyrics sources",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    "Earlier if the words come after the singing, later if they come before.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-500L to "−0.5", -100L to "−0.1", 100L to "+0.1", 500L to "+0.5").forEach { (step, label) ->
                        OutlinedButton(onClick = { set(ms + step) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label) }
                    }
                }
            }
        },
        confirmButton = { SheetButton("Done", onClick = onDismiss, closes = true) },
        dismissButton = { SheetButton("Reset", onClick = { AppSettings.setLyricsOffset(videoId, 0) }) },
    )
}

/** Title and artist with the heart and "…" glass buttons on the right. */
@Composable
private fun RowScope.TitleRow(song: Song, liked: Boolean, onLike: () -> Unit, onMore: () -> Unit, compact: Boolean = false) {
    Column(Modifier.weight(1f)) {
        // Tapping the title opens its album, and the artist opens their page.
        val menu = LocalSongMenu.current
        val album = song.albumId?.takeIf { menu != null && !song.videoId.startsWith("local:") }
        MarqueeText(
            song.title,
            Modifier.fillMaxWidth().then(if (album != null) Modifier.clickable(onClickLabel = "Open album") { menu?.openAlbum?.invoke(album) } else Modifier),
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
        )
        // Each artist's name opens their own page.
        val named = menu?.viewArtistNamed?.takeIf { !song.videoId.startsWith("local:") }
        com.opentune.ui.components.ArtistLinks(
            song.artist,
            onArtist = named?.let { open -> { name: String -> open(song, name) } },
            explicit = song.isExplicit == true,
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Spacer(Modifier.width(10.dp))
    HeartButton(liked, onLike)
    Spacer(Modifier.width(10.dp))
    Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onMore), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MoreHoriz, "More", Modifier.size(26.dp))
    }
}

@Composable
private fun TitleRow(song: Song, liked: Boolean, onLike: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TitleRow(song, liked, onLike, onMore, compact = false)
    }
}

/**
 * The title row travelling with the cover between [big] and [small] (root bounds), [q]
 * being how far toward the small one. It hangs under the cover as it shrinks, then
 * comes round to its right as the cover reaches the header, shrinking to the header's size.
 */
@Composable
private fun TitleFlight(
    song: Song, liked: Boolean, bigTitle: androidx.compose.ui.geometry.Rect,
    big: androidx.compose.ui.geometry.Rect, small: androidx.compose.ui.geometry.Rect,
    q: () -> Float, origin: androidx.compose.ui.geometry.Offset,
) {
    val sideGap = with(LocalDensity.current) { 14.dp.toPx() }
    val under = bigTitle.top - big.bottom
    // How far round to the cover's side: none for the first half, eased in over the rest.
    fun side(q: Float) = ((q - 0.5f) / 0.5f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    Box(
        Modifier.layout { measurable, _ ->
            val k = q()
            val s = side(k)
            val left = lerp(big.left, small.left, k)
            val top = lerp(big.top, small.top, k)
            val right = lerp(big.right, small.right, k)
            val bottom = lerp(big.bottom, small.bottom, k)
            val width = lerp(bigTitle.width, bigTitle.right - small.right - sideGap, s).coerceAtLeast(0f)
            val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixedWidth(width.toInt()))
            val x = lerp(lerp(bigTitle.left, small.left, k), right + sideGap, s)
            // An edge-to-edge cover runs on behind the title; shrinking, the title soon
            // clears it rather than staying tucked under its foot.
            val gap = lerp(under, maxOf(under, sideGap), (k * 3f).coerceAtMost(1f))
            val y = lerp(bottom + gap, (top + bottom) / 2f - placeable.height / 2f, s)
            layout(placeable.width, placeable.height) {
                placeable.place((x - origin.x).toInt(), (y - origin.y).toInt())
            }
        },
    ) {
        Row(Modifier.fillMaxWidth().align(Alignment.CenterStart).graphicsLayer { alpha = 1f - side(q()) }, verticalAlignment = Alignment.CenterVertically) {
            TitleRow(song, liked, onLike = {}, onMore = {}, compact = false)
        }
        Row(Modifier.fillMaxWidth().align(Alignment.CenterStart).graphicsLayer { alpha = side(q()) }, verticalAlignment = Alignment.CenterVertically) {
            TitleRow(song, liked, onLike = {}, onMore = {}, compact = true)
        }
    }
}

/** The heart pops when tapped on. */
@Composable
private fun HeartButton(liked: Boolean, onClick: () -> Unit) {
    val haptics = com.opentune.ui.components.rememberHaptics()
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && !still) 0.94f else 1f,
        tween(if (still) 0 else 140), label = "heartPress",
    )
    // A small burst of hearts and sparks each time the button likes a song.
    val burst = remember { androidx.compose.animation.core.Animatable(1f) }
    var bursts by remember { mutableStateOf(0) }
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(bursts) {
        if (bursts == 0) return@LaunchedEffect
        launch { pop.snapTo(0.7f); pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 500f)) }
        burst.snapTo(0f)
        burst.animateTo(1f, tween(720, easing = androidx.compose.animation.core.LinearEasing))
    }
    val burstColors = listOf(Color(0xFFFF4D6D), Color(0xFFFF8FA3), Color(0xFFFFC2D1), MaterialTheme.colorScheme.primary)
    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(46.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(interactionSource = interactions, indication = com.opentune.ui.components.FocusRing(CircleShape)) {
                    haptics.pattern(if (liked) com.opentune.ui.components.Haptics.Pattern.OFF else com.opentune.ui.components.Haptics.Pattern.LIKE)
                    if (!liked && !still) bursts++
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (liked) "Remove from Liked" else "Like", Modifier.size(24.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                tint = if (liked) Color(0xFFFF4D6D) else LocalContentColor.current)
        }
        // Drawn past the button's edges, over whatever is around it.
        if (burst.value < 1f) LikeBurst(burst.value, bursts, burstColors, Modifier.requiredSize(150.dp))
    }
}

/**
 * The burst at [t] (0 to 1): small hearts and sparks flying out from the
 * button's middle on slightly different paths, growing then shrinking as
 * they fade. [seed] makes each burst a little different.
 */
@Composable
internal fun LikeBurst(t: Float, seed: Int, colors: List<Color>, modifier: Modifier) {
    val heart = remember {
        androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0.9f)
            cubicTo(-1.45f, -0.05f, -0.8f, -1.15f, 0f, -0.42f)
            cubicTo(0.8f, -1.15f, 1.45f, -0.05f, 0f, 0.9f)
            close()
        }
    }
    val pieces = remember(seed) {
        val r = kotlin.random.Random(seed * 7919 + 13)
        List(14) { i ->
            floatArrayOf(
                (i / 14f) * 2f * Math.PI.toFloat() + r.nextFloat() * 0.35f, // angle
                0.7f + r.nextFloat() * 0.6f, // how far
                0.6f + r.nextFloat() * 0.7f, // size
                if (i % 3 == 0) 0f else 1f, // spark or heart
                r.nextFloat() * 0.12f, // late start
            )
        }
    }
    androidx.compose.foundation.Canvas(modifier) {
        val c = center
        pieces.forEachIndexed { i, p ->
            val local = ((t - p[4]) / (1f - p[4])).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed
            val out = 1f - (1f - local) * (1f - local) * (1f - local)
            val dist = 58.dp.toPx() * p[1] * out
            // A little drift upward, like they float.
            val x = c.x + kotlin.math.cos(p[0]) * dist
            val y = c.y + kotlin.math.sin(p[0]) * dist - 10.dp.toPx() * out
            val grow = if (local < 0.3f) local / 0.3f else 1f - (local - 0.3f) / 0.7f
            val alpha = (1f - local * local).coerceIn(0f, 1f)
            val color = colors[i % colors.size].copy(alpha = alpha)
            if (p[3] == 0f) {
                drawCircle(color, 2.5.dp.toPx() * p[2] * (0.4f + grow), androidx.compose.ui.geometry.Offset(x, y))
            } else {
                val size = 6.dp.toPx() * p[2] * (0.3f + 0.9f * grow)
                withTransform({
                    translate(x, y)
                    rotate((p[0] * 57.3f + 90f) * 0.15f, pivot = androidx.compose.ui.geometry.Offset.Zero)
                    scale(size, size, pivot = androidx.compose.ui.geometry.Offset.Zero)
                }) { drawPath(heart, color) }
            }
        }
    }
}

/**
 * Narrows what it's on to its height, centred, when that's the smaller
 * side, so a cover sized by its width still fits a short space whole.
 */
private fun Modifier.fitSquare(): Modifier = layout { measurable, constraints ->
    val side = minOf(constraints.maxWidth, constraints.maxHeight)
    val placeable = measurable.measure(constraints.copy(minWidth = minOf(constraints.minWidth, side), maxWidth = side))
    val width = constraints.maxWidth
    val height = placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) { placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2) }
}

/** Below this window height the docked player drops the lyric line, volume and output name. */
private val DOCKED_ROOMY_HEIGHT = 720.dp

/** How long the lyrics view goes untouched before the controls slide away. */
private const val LYRICS_FOCUS_MS = 5_000L

/** Mini player wired to the view model; the bottom bar places and sizes it. */
@Composable
fun MiniPlayerBar(vm: PlayerViewModel, onExpand: () -> Unit, modifier: Modifier = Modifier, inline: Boolean = false, replica: Boolean = false) {
    val song by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isBuffering by vm.isBuffering.collectAsState()
    val hasNext by vm.hasNext.collectAsState()
    val duration by vm.durationMs.collectAsState()
    val position = rememberPlaybackPosition(vm)
    val haptics = com.opentune.ui.components.rememberHaptics()
    val s = song ?: return
    MiniPlayer(
        song = s,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        hasNext = hasNext,
        progress = { if (duration > 0) position().toFloat() / duration else 0f },
        onTogglePlay = {
            haptics.pattern(if (isPlaying) com.opentune.ui.components.Haptics.Pattern.PAUSE else com.opentune.ui.components.Haptics.Pattern.PLAY)
            vm.togglePlayPause()
        },
        onNext = { haptics.pattern(com.opentune.ui.components.Haptics.Pattern.NEXT); vm.skipNext() },
        onPrevious = { haptics.pattern(com.opentune.ui.components.Haptics.Pattern.PREVIOUS); vm.skipPrevious() },
        onClick = onExpand,
        modifier = modifier,
        inline = inline,
        replica = replica,
    )
}

/** Shown where "Playing from" goes while in a room: whose room, and how many are in. */
@Composable
private fun TogetherPill(room: Together.Room, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                when {
                    room.hosting -> "Your room · ${room.members.size} listening"
                    room.holding -> "Paused for you · ${room.hostName ?: "room"}"
                    room.phase == Together.Phase.Live -> "With ${room.hostName ?: "the host"} · ${room.members.size} listening"
                    else -> "Listening together"
                },
            )
        },
        leadingIcon = { Icon(Icons.Rounded.Groups, null, Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/**
 * Brings what it's on into focus as it appears: from a soft blur and a
 * touch smaller to sharp, over half a second. Blur needs Android 12; before
 * that it only grows into place.
 */
@Composable
private fun Modifier.focusIn(animate: Boolean): Modifier {
    val v = remember { androidx.compose.animation.core.Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) v.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
    val blur = (1f - v.value) * 2f
    return this
        .graphicsLayer { val s = 0.96f + 0.04f * v.value; scaleX = s; scaleY = s }
        .then(if (blur > 0.2f) Modifier.blur(blur.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded) else Modifier)
}


/**
 * The cover flying between the small one in the lyrics or queue header and the
 * full cover. [from] is where it left; it lands on the small cover when
 * [toSmall], on the full one otherwise, wherever that has been laid out.
 */
/** How far a swipe goes to carry the cover page all the way. */
private val COVER_LIFT_TRAVEL = 300.dp
/** How much the cover page rises, shrinks and fades at the end of that swipe. */
/** How long the cover page and the lists take to trade places, BitChord's 420ms. */
private const val PANE_SWITCH_MS = 420
/** How long after the player settles the lists are built out of sight, ready for the first swipe. */
private const val LISTS_PREWARM_MS = 900L
/** How often the lyrics, kept built behind the cover, catch up with the song. */
private const val HIDDEN_LYRICS_STEP_MS = 1_000L
private const val COVER_LIFT_RISE = 0.18f
private const val COVER_LIFT_SHRINK = 0.1f
private const val COVER_LIFT_FADE = 0.55f
/** Long enough for the cover page's exit to finish before its lift is put back. */
private const val COVER_LIFT_RESET_MS = 600L

private class PaneFlight {
    var from by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
        private set
    var toSmall by mutableStateOf(false)
        private set
    var active by mutableStateOf(false)
        private set
    /** Bumped for each flight, so the runner starts again. */
    var runs by mutableStateOf(0)
        private set
    val t = androidx.compose.animation.core.Animatable(0f)
    /** Where the runner picks up: 0 for a tap, wherever the finger let go for a swipe. */
    var startAt by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set
    /** Whether the runner has taken over this flight, [t] set to [startAt]. */
    var primed by mutableStateOf(false)

    /**
     * How far the cover has flown: the finger's pull, the runner's once it has picked up,
     * and until then where it starts. [t] still held the last flight's end, so a tap back
     * to the cover drew it full size for the frames before the runner reset it.
     */
    val progress: Float get() = when {
        following -> pull
        primed -> t.value
        else -> startAt
    }

    /** Where the header's small cover sat when last shown, for a swipe up before it's back. */
    var lastSmall: androidx.compose.ui.geometry.Rect? = null
    /** True while a finger moves the cover itself, [pull] (0..1) being how far toward the header. */
    var following by mutableStateOf(false)
    var pull by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** Pixels of swipe from the full cover to the header. */
    var travel = 1f
        private set

    fun start(from: androidx.compose.ui.geometry.Rect, toSmall: Boolean) {
        this.from = from
        this.toSmall = toSmall
        startAt = 0f
        primed = false
        following = false
        active = true
        com.opentune.ui.components.PlayerMorph.flying = true
        runs++
    }

    /** Where a swipe is carrying the cover: the header's cover, measured or worked out. */
    var home: androidx.compose.ui.geometry.Rect? = null
        private set

    /** The full cover swiped up to [p] of the way, from its place at [from] toward [home]. */
    fun follow(from: androidx.compose.ui.geometry.Rect, home: androidx.compose.ui.geometry.Rect, p: Float, travel: Float) {
        if (!following) {
            this.from = from
            this.home = home
            this.toSmall = true
            this.travel = travel
            following = true
            active = true
            com.opentune.ui.components.PlayerMorph.flying = true
        }
        pull = p
    }

    /** The swipe let go far enough: the flight finishes from where the finger left it. */
    fun finish() {
        startAt = pull
        primed = false
        runs++
    }

    /**
     * Where the cover lands. Going into the header: where it's laid out now, else where
     * a swipe was taking it, else where it last sat. Waiting for a fresh layout alone
     * held the cover still for half a second and then dropped it in place: a header
     * that doesn't move reports no new position.
     */
    fun target(small: androidx.compose.ui.geometry.Rect?, big: androidx.compose.ui.geometry.Rect?): androidx.compose.ui.geometry.Rect? =
        if (toSmall) small ?: home ?: lastSmall else big

    fun end() {
        active = false
        primed = false
        following = false
        pull = 0f
        from = null
        home = null
        com.opentune.ui.components.PlayerMorph.flying = false
    }
}
