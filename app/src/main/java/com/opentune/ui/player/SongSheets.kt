package com.opentune.ui.player

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.opentune.R
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.SheetStyle
import com.opentune.i18n.Tr
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.PLAIN_DARK
import com.opentune.ui.components.PLAIN_LIGHT
import com.opentune.ui.components.PosterCaps
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.TransparentSheetNavBar
import com.opentune.ui.components.artistNamesIn
import com.opentune.ui.components.posterLayout
import com.opentune.ui.components.rememberHaptics
import com.opentune.ui.theme.AppIcons
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How the song sheet was asked for: from the title or artists (the song), or the menu's About song. */
internal enum class SongSheetOpen { CLOSED, SONG, ABOUT }

/** Opens the song sheet; provided by the full player, so its title and artists open it rather than a page. */
internal val LocalSongSheet = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** What the song sheet's rows lead to, wired up by the player. Null leaves a row out. */
internal class SongSheetActions(
    /** Opens one of the song's artists by name (as the song credits them). */
    val openArtist: ((String) -> Unit)? = null,
    val openAlbum: (() -> Unit)? = null,
    /** The player's audio format and pipeline details. */
    val streamInfo: (() -> Unit)? = null,
    /** Every credit the label sent, in the credits sheet. */
    val allCredits: (() -> Unit)? = null,
    val openUrl: (String) -> Unit = {},
)

/**
 * The song sheet and, stacked on it, About song: what the full player shows for a tap on
 * the song's title or artists.
 *
 * The song sheet rises over the player with the song, its YouTube description, a row to
 * open each artist and the album, About song and Stream info. About song slides up over it
 * as a second card; the first stays in sight behind, its top just showing, a little
 * narrower and dimmed, the way a stack of cards looks. Dragging a sheet down, its handle,
 * back, or a tap on the dim closes the top one first. All the motion is the layers' own
 * (translation, scale, alpha): no sheet is laid out again while it moves.
 */
@Composable
internal fun SongInfoSheets(
    song: Song,
    info: SongInfo,
    actions: SongSheetActions,
    onDismiss: () -> Unit,
    durationMs: Long = 0L,
    /** Opens with About song already on top (the player menu's "About song"). */
    startOnAbout: Boolean = false,
) {
    val ui by AppSettings.ui.collectAsState()
    val still = ui.reduceAnimation
    val lite = ui.liteMode
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    val haptics = rememberHaptics()
    val songShown = remember { Animatable(0f) }
    val aboutShown = remember { Animatable(0f) }
    var aboutOpen by remember { mutableStateOf(startOnAbout) }
    var leaving by remember { mutableStateOf(false) }
    // The song sheet's own height, for its entrance and its lift as About rises. Read only in
    // the layer blocks, so a change redraws and never recomposes.
    var songHeight by remember { mutableFloatStateOf(0f) }
    val enter: AnimationSpec<Float> = if (still) snap() else spring(dampingRatio = 1f, stiffness = if (lite) 700f else 420f)
    val exit: AnimationSpec<Float> = if (still) snap() else tween(SHEET_EXIT_MS, easing = FastOutLinearInEasing)

    fun closeAll(after: (() -> Unit)? = null) {
        if (leaving) return
        leaving = true
        scope.launch {
            launch { aboutShown.animateTo(0f, exit) }
            songShown.animateTo(0f, exit)
            dismiss()
            after?.invoke()
        }
    }
    val aboutPull = remember { SheetPull(scope) }
    val songPull = remember { SheetPull(scope) }
    fun closeAbout() {
        if (!aboutOpen || leaving) return
        aboutOpen = false
        scope.launch {
            aboutShown.animateTo(0f, exit)
            aboutPull.drag = 0f
        }
    }
    fun openAbout() {
        if (aboutOpen || leaving) return
        haptics.tick()
        aboutOpen = true
        aboutPull.drag = 0f
        scope.launch { aboutShown.animateTo(1f, enter) }
    }
    fun closeTop() = if (aboutOpen) closeAbout() else closeAll()
    songPull.onClose = { closeAll() }
    aboutPull.onClose = { closeAbout() }

    Dialog(
        onDismissRequest = { closeTop() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        val view = LocalView.current
        androidx.compose.runtime.DisposableEffect(view) {
            // The sheets bring their own dim and motion; the window adds neither.
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setWindowAnimations(0)
                window.setDimAmount(0f)
            }
            onDispose { }
        }
        TransparentSheetNavBar()
        val colors = MaterialTheme.colorScheme
        val dark = colors.surface.luminance() <= 0.5f
        val fill = if (ui.sheetStyle == SheetStyle.SOLID) (if (dark) PLAIN_DARK else PLAIN_LIGHT) else colors.surfaceContainerHigh
        val density = LocalDensity.current
        LaunchedEffect(Unit) {
            // Up once the song sheet knows its height, so its first frame is already below the screen.
            androidx.compose.runtime.withFrameNanos { }
            withTimeoutOrNull(300) { snapshotFlow { songHeight > 0f }.first { it } }
            launch { songShown.animateTo(1f, enter) }
            if (startOnAbout) aboutShown.animateTo(1f, enter)
        }
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                val fullPx = with(density) { maxHeight.toPx() }
                val topRoom = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp
                val stackTopPx = with(density) { topRoom.toPx() }
                val aboutHeightPx = fullPx - with(density) { (topRoom + STACK_PEEK).toPx() }
                songPull.closeAt = with(density) { 110.dp.toPx() }
                aboutPull.closeAt = with(density) { 130.dp.toPx() }
                // How far About stands over the song sheet: its rise, less any pull down.
                fun stacked(): Float = (aboutShown.value * (1f - aboutPull.drag / aboutHeightPx.coerceAtLeast(1f))).coerceIn(0f, 1f)

                // The dim over the player: a tap on it closes the top sheet.
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer { alpha = songShown.value.coerceIn(0f, 1f) * (1f - (songPull.drag / fullPx).coerceIn(0f, 1f)) }
                        .background(colors.scrim.copy(alpha = if (dark) 0.5f else 0.36f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close") { closeTop() },
                )
                // The song sheet.
                SheetCard(
                    fill = fill,
                    dark = dark,
                    shadow = !lite,
                    modifier = Modifier
                        .heightIn(max = maxHeight - topRoom)
                        .onSizeChanged { songHeight = it.height.toFloat() }
                        .graphicsLayer {
                            val k = stacked()
                            val top = fullPx - songHeight
                            alpha = if (songHeight > 0f) 1f else 0f
                            translationY = (1f - songShown.value) * songHeight + songPull.drag + (stackTopPx - top).coerceAtMost(0f) * k
                            val s = if (lite) 1f else 1f - BACK_SCALE * k
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                        .drawWithContent {
                            drawContent()
                            val k = stacked()
                            if (k > 0f) drawRect(Color.Black, alpha = (if (dark) 0.34f else 0.14f) * k)
                        },
                ) {
                    SongSheetContent(song, info, songPull, actions, onAbout = ::openAbout, onClose = { closeAll() }, onLeave = { go -> closeAll(go) })
                    // Behind About, the sheet is one thing to tap: it brings it back to the front.
                    val behind by remember { derivedStateOf { aboutOpen } }
                    if (behind) Box(
                        Modifier.matchParentSize().clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClickLabel = "Back to song", role = Role.Button,
                        ) { closeAbout() },
                    )
                }
                // About song, over it.
                val aboutComposed by remember { derivedStateOf { aboutOpen || aboutShown.value > 0f } }
                if (aboutComposed) SheetCard(
                    fill = fill,
                    dark = dark,
                    shadow = !lite,
                    modifier = Modifier
                        .padding(top = topRoom + STACK_PEEK)
                        .fillMaxHeight()
                        .graphicsLayer {
                            translationY = (1f - aboutShown.value) * aboutHeightPx + aboutPull.drag
                        },
                ) {
                    AboutSongContent(song, info, durationMs, aboutPull, actions, onBack = ::closeAbout, onLeave = { go -> closeAll(go) })
                }
            }
        }
    }
}

/**
 * A sheet's finger: how far it's pulled down, from its handle or from its content at the
 * top of its scroll. Let go past [closeAt] or fast enough, it closes; else it springs back.
 */
@Stable
internal class SheetPull(private val scope: CoroutineScope) {
    var drag by mutableFloatStateOf(0f)
    var closeAt = Float.MAX_VALUE
    var onClose: () -> Unit = {}
    private var settle: Job? = null

    fun stop() { settle?.cancel() }

    fun by(dy: Float) {
        settle?.cancel()
        drag = (drag + dy).coerceAtLeast(0f)
    }

    fun release(velocity: Float) {
        if (drag <= 0f) return
        if (drag > closeAt || velocity > FLING_CLOSE) {
            onClose()
        } else {
            settle?.cancel()
            settle = scope.launch {
                animate(drag, 0f, velocity, spring(dampingRatio = 1f, stiffness = 520f)) { v, _ -> drag = v.coerceAtLeast(0f) }
            }
        }
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Pushed back up mid-pull: the sheet goes back up before the content scrolls.
            if (drag <= 0f || available.y >= 0f) return Offset.Zero
            val take = maxOf(available.y, -drag)
            by(take)
            return Offset(0f, take)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // The content at its top and still pulled down: the sheet comes with it.
            if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            by(available.y)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (drag <= 0f) return Velocity.Zero
            release(available.y)
            return available
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (drag > 0f) release(0f)
            return Velocity.Zero
        }
    }
}

/** One card of the stack: full width up to the sheets' cap, flush to the bottom, round at the top. */
@Composable
private fun SheetCard(
    fill: Color,
    dark: Boolean,
    shadow: Boolean,
    modifier: Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER)
    val rim = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.08f else 0.06f)
    val scrim = MaterialTheme.colorScheme.scrim
    Box(
        modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .graphicsLayer {
                this.shape = shape
                clip = true
                if (shadow) {
                    shadowElevation = 24.dp.toPx()
                    ambientShadowColor = scrim
                    spotShadowColor = scrim
                }
            }
            .background(fill)
            .border(1.dp, rim, shape)
            // Taps on the card stay on the card.
            .pointerInput(Unit) { detectTapGestures { } },
        content = content,
    )
}

/** The grab bar along a sheet's top: drags it, and a tap does [onClick]. Turns to a chevron once [chevron] says so. */
@Composable
private fun SheetHandle(pull: SheetPull, label: String, onClick: () -> Unit, chevron: () -> Float = { 0f }) {
    val hairline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Box(
        Modifier.fillMaxWidth().height(40.dp)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { pull.by(it) },
                onDragStarted = { pull.stop() },
                onDragStopped = { v -> pull.release(v) },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = label, role = Role.Button, onClick = onClick,
            )
            .semantics { contentDescription = Tr.t(label) }
            .drawWithContent {
                drawContent()
                // A hairline under the bar once the content runs beneath it.
                val a = chevron()
                if (a > 0f) drawRect(hairline, Offset(0f, size.height - 1.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()), alpha = a)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 36.dp, height = 4.dp)
                .graphicsLayer { alpha = 1f - chevron() }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)),
        )
        Icon(
            ImageVector.vectorResource(AppIcons.KeyboardArrowDown), null,
            Modifier.size(28.dp).graphicsLayer { alpha = chevron() },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SongSheetContent(
    song: Song,
    info: SongInfo,
    pull: SheetPull,
    actions: SongSheetActions,
    onAbout: () -> Unit,
    onClose: () -> Unit,
    onLeave: (() -> Unit) -> Unit,
) {
    val poster = posterLayout()
    val names = remember(song.artist) { artistNamesIn(song.artist) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxWidth().semantics { paneTitle = Tr.t("Song") }) {
        SheetHandle(pull, "Close", onClick = onClose)
        Column(
            Modifier.weight(1f, fill = false).nestedScroll(pull.connection).verticalScroll(rememberScrollState())
                .padding(bottom = bottom + 16.dp),
        ) {
            SongHeader(song, poster, large = poster)
            Spacer(Modifier.height(if (poster) 18.dp else 12.dp))
            val description = info.description
            when {
                description != null -> DescriptionCard(description, note = "From YouTube")
                SongInfoPart.DESCRIPTION in info.pending -> PlaceholderCard(lines = 3)
            }
            Spacer(Modifier.height(12.dp))
            RowGroup {
                actions.openArtist?.let { open ->
                    names.forEach { name ->
                        SheetRow(Tr.f("View {0}", name), onClick = { onLeave { open(name) } }) {
                            Artwork(info.photos[name], Modifier.size(40.dp), CircleShape, placeholder = ImageVector.vectorResource(AppIcons.Person))
                        }
                    }
                }
                actions.openAlbum?.let { open ->
                    val album = song.albumName?.takeIf { it.isNotBlank() } ?: info.album
                    SheetRow(if (album != null) Tr.f("View {0}", album) else Tr.t("View album"), onClick = { onLeave(open) }) {
                        Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(40.dp), RoundedCornerShape(10.dp), placeholder = ImageVector.vectorResource(AppIcons.Album))
                    }
                }
                SheetRow("About song", onClick = onAbout) { IconTile(ImageVector.vectorResource(AppIcons.Info)) }
                actions.streamInfo?.let { open ->
                    SheetRow("Stream info", onClick = { onLeave(open) }) { IconTile(ImageVector.vectorResource(AppIcons.GraphicEq)) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(Modifier.padding(horizontal = 16.dp)) {
                SheetButton("Done", onClick = onClose, tone = SheetTone.Primary)
            }
        }
    }
}

@Composable
private fun AboutSongContent(
    song: Song,
    info: SongInfo,
    durationMs: Long,
    pull: SheetPull,
    actions: SongSheetActions,
    onBack: () -> Unit,
    onLeave: (() -> Unit) -> Unit,
) {
    val poster = posterLayout()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val names = remember(song.artist) { artistNamesIn(song.artist) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val turnPx = with(density) { 24.dp.toPx() }
    Column(Modifier.fillMaxSize().semantics { paneTitle = Tr.t("About song") }) {
        // A chevron once the page has scrolled: the way back to the song sheet, plainly.
        SheetHandle(pull, "Back to song", onClick = onBack, chevron = { (scroll.value / turnPx).coerceIn(0f, 1f) })
        Column(
            Modifier.weight(1f).nestedScroll(pull.connection).verticalScroll(scroll).padding(bottom = bottom + 28.dp),
        ) {
            Text(
                "About song",
                style = if (poster) MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.4).sp)
                else MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp),
            )
            SongHeader(song, poster, large = false)
            GeneralInformation(song, info, durationMs, names, actions)
            CreditsSection(info, names, actions)
            AboutSection(song, info, actions)
            BySection(names, info, actions, onLeave)
            ElsewhereSection(song, info, actions)
        }
    }
}

/** The song as each sheet shows it at its top: cover, title, artists. The Poster layout centres it, larger. */
@Composable
private fun SongHeader(song: Song, poster: Boolean, large: Boolean) {
    val colors = MaterialTheme.colorScheme
    if (large) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX * 3), Modifier.size(120.dp), RoundedCornerShape(18.dp))
            Text(
                song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp),
            )
            PosterCaps(song.artist, Modifier.padding(top = 6.dp))
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(60.dp), RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (poster) PosterCaps(song.artist, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Start)
            else Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A heading for one of About song's parts; the Poster layout's in spaced capitals. */
@Composable
private fun SectionHeading(text: String, trailing: (@Composable () -> Unit)? = null) {
    androidx.compose.material3.HorizontalDivider(
        Modifier.padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 18.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (posterLayout()) PosterCaps(Tr.t(text), textAlign = TextAlign.Start, color = MaterialTheme.colorScheme.onSurface)
        else Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun GeneralInformation(song: Song, info: SongInfo, durationMs: Long, names: List<String>, actions: SongSheetActions) {
    val facts = buildList {
        info.genre?.let { add("Genre" to it) }
        info.released?.let { add("Released" to releaseText(it)) }
        (song.albumName?.takeIf { it.isNotBlank() } ?: info.album)?.let { add("Album" to it) }
        if (names.isNotEmpty()) add((if (names.size == 1) "Artist" else "Artists") to names.joinToString(", "))
        info.credits?.label?.let { add("Label" to it) }
        info.trackNumber?.let { track ->
            add("Track" to listOfNotNull(info.discNumber?.takeIf { it > 1 }?.let { Tr.f("Disc {0}", it) }, Tr.f("Track {0}", track)).joinToString(" · "))
        }
        if (durationMs > 0) add("Length" to com.opentune.ui.formatTime(durationMs))
        info.isrc?.let { add("ISRC" to it) }
    }
    val loading = SongInfoPart.CATALOG in info.pending && info.genre == null && info.released == null
    if (facts.isEmpty() && !loading) return
    SectionHeading("General information")
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        facts.forEach { (label, value) -> Fact(label, value) }
        if (loading) PlaceholderLines(2, Modifier.padding(top = 4.dp))
    }
    info.musicBrainzUrl?.let { url ->
        Row(Modifier.padding(horizontal = 14.dp).padding(top = 8.dp)) {
            LinkPill("Open in MusicBrainz", onClick = { actions.openUrl(url) })
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(108.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

/** The people the label credits beyond the main artists: writers and producers first. */
@Composable
private fun CreditsSection(info: SongInfo, names: List<String>, actions: SongSheetActions) {
    val credits = info.credits ?: return
    val main = names.map { it.lowercase() }.toSet()
    val people = (credits.composition + credits.production + credits.performers.filter { it.name.lowercase() !in main })
        .distinctBy { it.name.lowercase() }
    if (people.isEmpty()) return
    SectionHeading("Credits")
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        people.take(CREDITS_SHOWN).forEach { person ->
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(person.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(person.roles.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    actions.allCredits?.let { open ->
        Row(Modifier.padding(horizontal = 14.dp).padding(top = 8.dp)) { LinkPill("All credits", onClick = open, external = false) }
    }
}

/** A text about the song, from the first source that has one; a small switch when there are more. */
@Composable
private fun AboutSection(song: Song, info: SongInfo, actions: SongSheetActions) {
    val texts = remember(info.about, info.description) {
        info.about + listOfNotNull(info.description?.let { AboutText(AboutSource.YOUTUBE, it, "https://www.youtube.com/watch?v=${song.videoId}") })
    }
    val loading = texts.isEmpty() && (SongInfoPart.ABOUT in info.pending || SongInfoPart.DESCRIPTION in info.pending)
    if (texts.isEmpty() && !loading) return
    var picked by rememberSaveable(song.videoId) { mutableIntStateOf(0) }
    val shown = texts.getOrNull(picked) ?: texts.firstOrNull()
    SectionHeading("About", trailing = if (texts.size > 1 && shown != null) ({
        val next = texts[(texts.indexOf(shown) + 1) % texts.size]
        SourceSwitch(shown.source.label, onClick = { picked = (texts.indexOf(shown) + 1) % texts.size }, nextLabel = next.source.label)
    }) else null)
    if (shown == null) {
        PlaceholderCard(lines = 4)
        return
    }
    DescriptionCard(
        shown.text,
        note = Tr.f("From {0}", shown.source.label) + if (shown.source == AboutSource.WIKIPEDIA) " · CC BY-SA" else "",
        collapsedLines = 7,
        action = when (shown.source) {
            AboutSource.YOUTUBE -> "Read it on YouTube"
            AboutSource.WIKIPEDIA -> "Read on Wikipedia"
            AboutSource.LASTFM -> "Read on Last.fm"
        } to { actions.openUrl(shown.url) },
    )
}

/** Each artist's round photo and name; a tap opens their page. */
@Composable
private fun BySection(names: List<String>, info: SongInfo, actions: SongSheetActions, onLeave: (() -> Unit) -> Unit) {
    if (names.isEmpty()) return
    SectionHeading("By")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        names.forEach { name ->
            val open = actions.openArtist
            Column(
                Modifier.width(116.dp).clip(RoundedCornerShape(20.dp))
                    .then(if (open != null) Modifier.clickable(onClickLabel = Tr.t("Open artist"), role = Role.Button) { onLeave { open(name) } } else Modifier)
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(info.photos[name], Modifier.size(100.dp), CircleShape, placeholder = ImageVector.vectorResource(AppIcons.Person))
                Text(
                    name, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

/**
 * Tiles that open the song on other services: only the ones it was found on, each with its
 * logo and name, from the left. While more are still being looked for, a small spinner
 * follows them; never a blank tile standing in for one that may not come.
 */
@Composable
private fun ElsewhereSection(song: Song, info: SongInfo, actions: SongSheetActions) {
    val links = remember(song.videoId, info.links, info.appleUrl) { serviceLinks(song, info) }
    val loading = SongInfoPart.LINKS in info.pending
    if (links.isEmpty()) return
    SectionHeading("Also available on")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
    ) {
        links.forEach { link -> ServiceTile(link.service, onClick = { actions.openUrl(link.url) }) }
        if (loading) Box(
            Modifier.padding(6.dp).size(width = 40.dp, height = 84.dp).semantics { contentDescription = Tr.t("Loading") },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                trackColor = Color.Transparent,
            )
        }
    }
}

@Composable
private fun ServiceTile(service: MusicService, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.width(96.dp).clip(RoundedCornerShape(24.dp))
            .clickable(onClickLabel = Tr.f("Open in {0}", service.label), role = Role.Button, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(tileColor()), contentAlignment = Alignment.Center) {
            val (logo, tint) = when (service) {
                MusicService.SPOTIFY -> R.drawable.ic_spotify to null
                MusicService.YOUTUBE_MUSIC -> R.drawable.ic_youtube_music to null
                MusicService.APPLE_MUSIC -> R.drawable.ic_apple_music to Color(0xFFFA243C)
                MusicService.TIDAL -> R.drawable.ic_tidal to colors.onSurface
                MusicService.AMAZON_MUSIC -> R.drawable.ic_amazon_music to Color(0xFF25A3B1)
                MusicService.SOUNDCLOUD -> R.drawable.ic_soundcloud to Color(0xFFFF5500)
                MusicService.DEEZER -> null to colors.onSurface
            }
            if (logo != null) Image(painterResource(logo), null, Modifier.size(42.dp), colorFilter = tint?.let { ColorFilter.tint(it) })
            else Icon(ImageVector.vectorResource(AppIcons.MusicNote), null, Modifier.size(38.dp), tint = Color(0xFFA238FF))
        }
        Text(
            service.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A text in a soft card, a few lines until tapped open; a quiet note of where it's from. */
@Composable
private fun DescriptionCard(text: String, note: String, collapsedLines: Int = 4, action: Pair<String, () -> Unit>? = null) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    // Closed, the blank lines between a description's parts go, so its few lines carry words.
    val compact = remember(text) { text.lines().filter { it.isNotBlank() }.joinToString("\n") }
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CARD_SHAPE).background(tileColor())
            .then(if (overflows || open) Modifier.clickable(onClickLabel = Tr.t(if (open) "Show less" else "Show more"), role = Role.Button) { open = !open } else Modifier)
            .then(if (still) Modifier else Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = 500f)))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            if (open) text else compact, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
            maxLines = if (open) Int.MAX_VALUE else collapsedLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!open) overflows = it.hasVisualOverflow },
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (action != null) LinkPill(action.first, onClick = action.second)
            else if (overflows || open) Text(if (open) "Show less" else "Show more", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Spacer(Modifier.weight(1f))
            Text(note, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1)
        }
    }
}

/** The rows of the song sheet, one rounded block with hairline gaps between them. */
@Composable
private fun RowGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CARD_SHAPE),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
private fun SheetRow(label: String, onClick: () -> Unit, leading: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).background(tileColor())
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
        Text(
            label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
        )
        Icon(ImageVector.vectorResource(AppIcons.ChevronRight), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = colors.primary)
    }
}

/** A small pill that opens something: a source's page, every credit. */
@Composable
private fun LinkPill(label: String, onClick: () -> Unit, external: Boolean = true) {
    Row(
        Modifier.minimumInteractiveComponentSize().clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Icon(ImageVector.vectorResource(if (external) AppIcons.OpenInNew else AppIcons.ChevronRight), null, Modifier.size(if (external) 15.dp else 18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The About heading's source switch: the source shown, and a tap moves on to the next. */
@Composable
private fun SourceSwitch(label: String, nextLabel: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val description = Tr.f("Source: {0}. Show {1} instead", label, nextLabel)
    Row(
        Modifier.minimumInteractiveComponentSize().clip(CircleShape).border(1.dp, colors.outline.copy(alpha = 0.5f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Icon(ImageVector.vectorResource(AppIcons.UnfoldMore), null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun PlaceholderCard(lines: Int) {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CARD_SHAPE).background(tileColor()).padding(18.dp)) {
        PlaceholderLines(lines)
    }
}

/** Grey bars where text is on its way: still, so nothing on the sheet moves while it waits. */
@Composable
private fun PlaceholderLines(lines: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().semantics { contentDescription = Tr.t("Loading") }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(lines) { i ->
            Box(Modifier.fillMaxWidth(if (i == lines - 1) 0.55f else 0.92f).height(12.dp).clip(CircleShape).background(placeholderColor()))
        }
    }
}

@Composable
private fun tileColor(): Color {
    val c = MaterialTheme.colorScheme
    return c.onSurface.copy(alpha = if (c.surface.luminance() <= 0.5f) 0.07f else 0.05f)
}

@Composable
private fun placeholderColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)

private val CARD_SHAPE = RoundedCornerShape(22.dp)
private val SHEET_CORNER: Dp = 32.dp
/** How much of the song sheet shows above About song. */
private val STACK_PEEK: Dp = 30.dp
/** How much narrower the song sheet gets behind About song. */
private const val BACK_SCALE = 0.06f
private const val SHEET_EXIT_MS = 220
private const val FLING_CLOSE = 1_400f
private const val CREDITS_SHOWN = 6
