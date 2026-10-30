package com.opentune.ui.welcome

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.ExploreFeed
import com.opentune.data.MusicRepository
import com.opentune.data.Welcome
import com.opentune.data.account.AccountStore
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.releases.NewReleases
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.GlassStyle
import com.opentune.data.settings.ThemeMode
import com.opentune.ui.BottomChrome
import com.opentune.ui.ChromeTab
import com.opentune.ui.PageMotion
import com.opentune.ui.ScreenCache
import com.opentune.ui.account.LoginScreen
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.GlassBackButton
import com.opentune.ui.components.GlassPage
import com.opentune.ui.components.PillSegmented
import com.opentune.ui.components.glass
import com.opentune.ui.components.pressable
import com.opentune.ui.components.rememberHaptics
import com.opentune.ui.player.MiniPlayer
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The welcome's steps, in order. */
internal enum class WelcomeStep { ARTISTS, LOOK, SIGN_IN }

/** Done needs this many picks; with none, the step can only be skipped. */
internal const val MIN_PICKS = 1

/**
 * The first-run welcome, shown once before Home on a fresh install (see
 * [Welcome]): artists to follow, where each pick brings in more like it (see
 * [ArtistChain]), which seed Home's first picks and mixes;
 * the theme and glass, with a live preview of the dock and mini player; and
 * an optional YouTube Music sign-in. Back goes a step back. Every choice
 * goes through the app's own settings, follows and sign-in, so the same
 * things can be changed later in Settings.
 */
@Composable
fun WelcomeScreen(
    onDone: () -> Unit,
    /** The starting grid; by default the charts' top artists for the listener's country ([UserCountry]). */
    loadPopular: (suspend () -> List<ExploreFeed.Artist>)? = null,
    /** Artists like a picked one, to place after it. */
    loadSimilar: suspend (String) -> List<ExploreFeed.Artist> = ArtistChain::load,
) {
    val ui by AppSettings.ui.collectAsState()
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(WelcomeStep.ARTISTS) }
    val picks = rememberSaveable(saver = ArtistListSaver) { mutableStateListOf() }
    // The artists on offer, grown by the picks; kept here so going back to the step finds it as it was.
    val grid = rememberSaveable(saver = ArtistListSaver) { mutableStateListOf() }
    // Picks that have already brought in their similar artists, and those still loading them.
    val grown = rememberSaveable(saver = idSetSaver) { mutableStateListOf() }
    val growing = remember { mutableStateListOf<String>() }
    // Which pick brought each artist in, by id, for the "Like …" line under them.
    val broughtBy = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    // Artists this welcome followed, so unpicking one after going back unfollows it again.
    val followedHere = rememberSaveable(saver = idSetSaver) { mutableStateListOf() }
    var signingIn by rememberSaveable { mutableStateOf(false) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // The suggestions start from the charts where the listener is, so approximate location is
    // asked for first; whatever the answer, the artists load after it (from the network's country
    // when it's declined). A device without location (a TV) isn't asked.
    var locationAnswered by rememberSaveable {
        mutableStateOf(
            com.opentune.data.radio.ApproxLocation.granted(context) ||
                !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LOCATION),
        )
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { locationAnswered = true }
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!locationAnswered && !asked) {
            asked = true
            runCatching { askLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION) }.onFailure { locationAnswered = true }
        }
    }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    val popular: suspend () -> List<ExploreFeed.Artist> = loadPopular ?: {
        val found = com.opentune.data.UserCountry.detect(context)
        country = found
        MusicRepository.chartArtists(found)
    }

    fun finish() {
        Welcome.finish()
        // Home was perhaps loaded before the picks (the welcome shown again): fetch it afresh.
        ScreenCache.get("home")?.let { ScreenCache.put("home", it.first, at = 0L) }
        onDone()
    }

    fun follow() {
        val ids = picks.map { it.browseId }
        followedHere.filter { it !in ids }.forEach { NewReleases.unfollow(context, it) }
        followedHere.retainAll(ids)
        // The last followed is the newest, and Home seeds from the newest first:
        // followed in reverse, the first picked leads.
        picks.asReversed().forEach { a ->
            if (!NewReleases.isFollowed(a.browseId)) {
                NewReleases.follow(context, a.browseId, a.name, a.thumbnailUrl)
                followedHere += a.browseId
            }
        }
        if (picks.isNotEmpty() && AppSettings.library.value.releaseAlerts && !NewReleases.canNotify(context)) {
            askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = signingIn || step != WelcomeStep.ARTISTS) {
        if (signingIn) signingIn = false else step = WelcomeStep.entries[step.ordinal - 1]
    }

    // The opening animation dives into the welcome, as it would into Home.
    var opening by remember { mutableStateOf(!com.opentune.ui.components.Opening.played && ui.openingAnimation && !ui.reduceAnimation) }
    var dive by remember { mutableFloatStateOf(if (opening) 0f else 1f) }
    LaunchedEffect(Unit) { com.opentune.ui.components.Opening.played = true }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).graphicsLayer {
            if (dive < 1f) {
                val s = 1.14f - 0.14f * (1f - (1f - dive) * (1f - dive))
                scaleX = s
                scaleY = s
            }
        },
    ) {
        GlassPage(
            Modifier.fillMaxSize(),
            overlay = {
                WelcomeTopBar(
                    step = step,
                    onBack = { step = WelcomeStep.entries[step.ordinal - 1] },
                    onSkip = { step = WelcomeStep.LOOK },
                    reduceMotion = ui.reduceAnimation,
                )
                WelcomeBottomBar(
                    step = step,
                    picks = picks,
                    onContinue = {
                        when (step) {
                            WelcomeStep.ARTISTS -> { follow(); step = WelcomeStep.LOOK }
                            WelcomeStep.LOOK -> step = WelcomeStep.SIGN_IN
                            WelcomeStep.SIGN_IN -> if (AccountStore.signedIn.value) finish() else signingIn = true
                        }
                    },
                    onNotNow = ::finish,
                )
            },
        ) {
            AnimatedContent(
                step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    if (forward) PageMotion.enter(ui.pageTransition, ui.reduceAnimation) togetherWith PageMotion.exit(ui.pageTransition, ui.reduceAnimation)
                    else PageMotion.popEnter(ui.pageTransition, ui.reduceAnimation) togetherWith PageMotion.popExit(ui.pageTransition, ui.reduceAnimation)
                },
                label = "welcomeStep",
            ) { s ->
                when (s) {
                    WelcomeStep.ARTISTS -> ArtistsStep(
                        picks = picks,
                        grid = grid,
                        growing = growing,
                        broughtBy = broughtBy,
                        reduceMotion = ui.reduceAnimation,
                        loadPopular = popular,
                        ready = locationAnswered,
                        country = country,
                        onPicked = { artist ->
                            if (artist.browseId !in grown) {
                                grown += artist.browseId
                                growing += artist.browseId
                                scope.launch {
                                    val found = runCatching { loadSimilar(artist.browseId) }.getOrDefault(emptyList())
                                    val next = ArtistChain.grow(grid.toList(), artist.browseId, found)
                                    if (next.size != grid.size) {
                                        val had = grid.mapTo(HashSet()) { it.browseId }
                                        next.filter { it.browseId !in had }.forEach { broughtBy[it.browseId] = artist.name }
                                        grid.clear()
                                        grid.addAll(next)
                                    }
                                    growing -= artist.browseId
                                }
                            }
                        },
                    )
                    WelcomeStep.LOOK -> LookStep(picks)
                    WelcomeStep.SIGN_IN -> SignInStep()
                }
            }
        }
        if (signingIn) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                // The app's own sign-in page. Its back arrow lands here too; a session means done.
                LoginScreen(onDone = {
                    signingIn = false
                    if (AccountStore.signedIn.value) finish()
                })
            }
        }
    }
    if (opening) {
        com.opentune.ui.components.OpeningReveal(onDive = { dive = it }, onDone = { opening = false; dive = 1f })
    }
}

/** Room the steps leave for the bars over them. */
private val TOP_BAR = 72.dp
private val BOTTOM_BAR = 112.dp

@Composable
private fun BoxScope.WelcomeTopBar(step: WelcomeStep, onBack: () -> Unit, onSkip: () -> Unit, reduceMotion: Boolean) {
    Box(
        Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(56.dp),
    ) {
        AnimatedVisibility(step != WelcomeStep.ARTISTS, Modifier.align(Alignment.CenterStart), enter = fadeIn(), exit = fadeOut()) {
            GlassBackButton(onBack)
        }
        StepDots(step, reduceMotion, Modifier.align(Alignment.Center))
        AnimatedVisibility(step == WelcomeStep.ARTISTS, Modifier.align(Alignment.CenterEnd), enter = fadeIn(), exit = fadeOut()) {
            Surface(
                onClick = onSkip,
                shape = CircleShape,
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.height(44.dp).glass(CircleShape),
            ) {
                Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text("Skip", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Three dots, the current step's drawn out into a short bar. */
@Composable
private fun StepDots(step: WelcomeStep, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = "Step ${step.ordinal + 1} of ${WelcomeStep.entries.size}"
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WelcomeStep.entries.forEach { s ->
            val current = s == step
            val width by animateDpAsState(if (current) 22.dp else 7.dp, if (reduceMotion) snap() else spring(dampingRatio = 0.7f, stiffness = 500f), label = "dot")
            val colour by animateColorAsState(
                if (s.ordinal <= step.ordinal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                label = "dotColour",
            )
            Box(Modifier.size(width = width, height = 7.dp).clip(CircleShape).background(colour))
        }
    }
}

@Composable
private fun BoxScope.WelcomeBottomBar(step: WelcomeStep, picks: List<ExploreFeed.Artist>, onContinue: () -> Unit, onNotNow: () -> Unit) {
    val picked = picks.size
    val signedIn by AccountStore.signedIn.collectAsState()
    val page = MaterialTheme.colorScheme.background
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            // The step's content fades out under the buttons rather than stopping at a line.
            .background(Brush.verticalGradient(0f to page.copy(alpha = 0f), 0.35f to page.copy(alpha = 0.88f), 1f to page))
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val label = when (step) {
            WelcomeStep.ARTISTS -> when {
                picked < MIN_PICKS -> "Pick an artist to start"
                picked == 1 -> "Done · 1 artist"
                else -> "Done · $picked artists"
            }
            WelcomeStep.LOOK -> "Continue"
            WelcomeStep.SIGN_IN -> if (signedIn) "Start listening" else "Sign in to YouTube Music"
        }
        Button(
            onClick = onContinue,
            enabled = step != WelcomeStep.ARTISTS || picked >= MIN_PICKS,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (step == WelcomeStep.ARTISTS && picked > 0) PickedFaces(picks.takeLast(3).asReversed())
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
        if (step == WelcomeStep.SIGN_IN && !signedIn) {
            TextButton(onClick = onNotNow, modifier = Modifier.padding(top = 4.dp).height(48.dp)) {
                Text("Not now", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** A step's heading, under the top bar. */
@Composable
private fun StepTitle(title: String, line: String) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun topInset() = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TOP_BAR + 8.dp

@Composable
private fun bottomInset() = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BOTTOM_BAR

// ---- Step 1: artists ------------------------------------------------------

@Composable
private fun ArtistsStep(
    picks: SnapshotStateList<ExploreFeed.Artist>,
    grid: SnapshotStateList<ExploreFeed.Artist>,
    growing: List<String>,
    broughtBy: Map<String, String>,
    reduceMotion: Boolean,
    loadPopular: suspend () -> List<ExploreFeed.Artist>,
    /** False while the location question is still on screen: the grid waits for its answer. */
    ready: Boolean,
    /** The country the grid came from, named under the title once known. */
    country: String?,
    onPicked: (ExploreFeed.Artist) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    // The grid starts from the charts; once it has anything, it only grows.
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(attempt, ready) {
        if (grid.isNotEmpty() || !ready) return@LaunchedEffect
        failed = false
        val popular = runCatching { loadPopular() }.getOrDefault(emptyList())
        if (grid.isEmpty()) grid.addAll(picks.filter { p -> popular.none { it.browseId == p.browseId } } + popular)
        failed = grid.isEmpty()
    }
    var results by remember { mutableStateOf<List<ExploreFeed.Artist>?>(null) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { results = null; return@LaunchedEffect }
        delay(350)
        results = runCatching {
            MusicRepository.search(q, SearchFilter.ARTISTS)
                .mapNotNull { (it as? SearchResult.Browse)?.item?.takeIf { b -> b.type == BrowseType.ARTIST } }
                .map { ExploreFeed.Artist(it.browseId, it.title, it.thumbnailUrl) }
        }.getOrDefault(emptyList())
    }
    val haptics = rememberHaptics()
    val searching = query.isNotBlank()
    val shown: List<ExploreFeed.Artist>? = when {
        searching -> results
        grid.isEmpty() && !failed -> null
        else -> grid
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    fun toggle(artist: ExploreFeed.Artist) {
        haptics.tick()
        if (picks.any { it.browseId == artist.browseId }) {
            picks.removeAll { it.browseId == artist.browseId }
            return
        }
        picks += artist
        if (searching) {
            // A pick from search joins the grid at the top, where its similar artists follow it.
            if (grid.none { it.browseId == artist.browseId }) grid.add(0, artist)
            query = ""
        }
        onPicked(artist)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Spacer(Modifier.height(topInset()))
        val place = country?.let { runCatching { Locale.Builder().setRegion(it).build().displayCountry }.getOrNull()?.takeIf(String::isNotBlank) }
        StepTitle(
            "Pick artists you like",
            (if (place != null) "Starting with what's popular in $place. " else "") +
                "Each one you pick brings in more like them. Keep going as long as you like, then tap Done.",
        )
        TextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search artists") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
            shape = RoundedCornerShape(20.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = bottomInset() + 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                shown == null -> items(12, key = { "placeholder$it" }) { ArtistPlaceholder() }
                shown.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (searching) "No artists found for \"${query.trim()}\"." else "Couldn't load popular artists. Search for any artist above.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (!searching) TextButton(onClick = { attempt++ }) { Text("Try again") }
                    }
                }
                else -> items(shown, key = { it.browseId }) { artist ->
                    val selected = picks.any { it.browseId == artist.browseId }
                    ArtistChoice(
                        artist,
                        selected,
                        loading = artist.browseId in growing,
                        because = if (searching) null else broughtBy[artist.browseId],
                        reduceMotion = reduceMotion,
                        // New arrivals fade and grow in, and the rest slide aside to make room.
                        modifier = if (reduceMotion) Modifier else Modifier.animateItem(
                            fadeInSpec = androidx.compose.animation.core.tween(320, delayMillis = 60),
                            placementSpec = spring(dampingRatio = 0.85f, stiffness = 380f, visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1)),
                            fadeOutSpec = androidx.compose.animation.core.tween(160),
                        ),
                    ) { toggle(artist) }
                }
            }
        }
    }
}

/** Up to three picked faces, overlapping, at the start of the Done button. */
@Composable
private fun PickedFaces(faces: List<ExploreFeed.Artist>) {
    val ring = MaterialTheme.colorScheme.primary
    Box(Modifier.padding(end = 10.dp).width(24.dp + 14.dp * (faces.size - 1)).height(24.dp)) {
        faces.forEachIndexed { i, a ->
            Box(
                Modifier.padding(start = 14.dp * i).size(24.dp)
                    .border(1.5.dp, ring, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Artwork(a.thumbnailUrl.artworkAt(96), Modifier.matchParentSize(), CircleShape, placeholder = Icons.Filled.Person)
            }
        }
    }
}

/** A round photo and a name; picked, it eases back a little behind a ring and a check. */
@Composable
private fun ArtistChoice(
    artist: ExploreFeed.Artist,
    selected: Boolean,
    loading: Boolean,
    /** The pick that brought this artist in, if one did. */
    because: String?,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        if (selected) 0.9f else 1f,
        if (reduceMotion) snap() else spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "pick",
    )
    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "ring")
    Column(
        modifier
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .pressable(onClick)
            .semantics {
                this.selected = selected
                role = Role.Checkbox
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Box(
                Modifier.fillMaxSize().border(3.dp, ring, CircleShape).padding(6.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    artist.name.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (artist.thumbnailUrl != null) {
                    Artwork(artist.thumbnailUrl.artworkAt(240), Modifier.matchParentSize(), CircleShape, placeholder = Icons.Filled.Person)
                }
            }
            // While the artists like this one load, a turning arc on the ring.
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                    strokeWidth = 3.dp,
                    trackColor = Color.Transparent,
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = selected,
                modifier = Modifier.align(Alignment.BottomEnd),
                enter = if (reduceMotion) fadeIn(snap()) else scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f)) + fadeIn(),
                exit = if (reduceMotion) fadeOut(snap()) else scaleOut() + fadeOut(),
            ) {
                Box(
                    Modifier.size(28.dp).border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Text(
            artist.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = if (because != null) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (because != null) {
            Text(
                "Like $because",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ArtistPlaceholder() {
    Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        Box(Modifier.padding(top = 10.dp).size(width = 64.dp, height = 12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
    }
}

// ---- Step 2: look ---------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookStep(picks: List<ExploreFeed.Artist>) {
    val theme by AppSettings.theme.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(topInset()))
        StepTitle("Make it yours", "Change these any time in Settings › Look and feel.")
        LookPreview(picks, Modifier.padding(horizontal = 16.dp))
        Label("Theme")
        PillSegmented(
            ThemeMode.entries,
            theme.mode,
            { it.label },
            { m -> AppSettings.updateTheme { it.copy(mode = m) } },
            Modifier.padding(horizontal = 16.dp),
        )
        Label("Glass")
        FlowRow(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassStyle.entries.forEach { g ->
                ChoicePill(g.label, g == ui.glassStyle) { AppSettings.updateUi { it.copy(glassStyle = g) } }
            }
        }
        Text(
            ui.glassStyle.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
        )
        Spacer(Modifier.height(bottomInset() + 16.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 10.dp),
    )
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh, label = "pillBg")
    val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface, label = "pillFg")
    Box(
        Modifier.height(44.dp).clip(CircleShape).background(bg)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

private val previewTabs = listOf(
    ChromeTab("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    ChromeTab("Explore", Icons.Outlined.Explore, Icons.Rounded.Explore),
    ChromeTab("Podcasts", Icons.Outlined.Podcasts, Icons.Rounded.Podcasts),
    ChromeTab("Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
)

/**
 * The real dock and mini player, over a small page of the picked artists,
 * redrawn as the theme and glass change. The tabs can be tapped.
 */
@Composable
internal fun LookPreview(picks: List<ExploreFeed.Artist>, modifier: Modifier = Modifier) {
    val ui by AppSettings.ui.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val lead = picks.firstOrNull()
    val song = remember(lead) {
        Song(
            videoId = "welcome-preview",
            title = lead?.let { "${it.name} mix" } ?: "Your first song",
            artist = picks.take(3).joinToString(", ") { it.name }.ifEmpty { "OpenTune" },
            thumbnailUrl = lead?.thumbnailUrl.artworkAt(240),
        )
    }
    val scheme = MaterialTheme.colorScheme
    GlassPage(
        modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(32.dp))
            .border(1.dp, scheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(32.dp)),
        overlay = {
            BottomChrome(
                inline = false,
                tabs = previewTabs,
                selected = tab,
                onSelect = { tab = it },
                onExpand = {},
                searchSelected = false,
                onSearch = {},
                mini = { folded, m ->
                    MiniPlayer(
                        song = song,
                        isPlaying = true,
                        isBuffering = false,
                        hasNext = true,
                        progress = { 0.35f },
                        onTogglePlay = {},
                        onNext = {},
                        onPrevious = {},
                        onClick = {},
                        modifier = m,
                        inline = folded,
                    )
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                motion = ui.dockMotion,
                lens = ui.dockLens,
                reduceMotion = ui.reduceAnimation,
            )
        },
    ) {
        // Colour and faces for the glass to sit on: the accent washing in from
        // one corner, the picked artists across the top and a large photo
        // running under the dock, so frost and refraction both show.
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(scheme.primaryContainer, scheme.background, scheme.tertiaryContainer)),
            ),
        )
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text(com.opentune.ui.theme.tr("Home"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val faces = picks.take(4)
                if (faces.isEmpty()) {
                    repeat(4) { Box(Modifier.size(56.dp).clip(CircleShape).background(scheme.primary.copy(alpha = 0.25f + 0.15f * it))) }
                } else {
                    faces.forEach { Artwork(it.thumbnailUrl.artworkAt(160), Modifier.size(56.dp), CircleShape, placeholder = Icons.Filled.Person) }
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(scheme.primary, scheme.tertiary, scheme.secondary).forEachIndexed { i, colour ->
                    val photo = picks.getOrNull(i + 1)?.thumbnailUrl ?: lead?.thumbnailUrl
                    Box(Modifier.width(120.dp).height(140.dp).clip(RoundedCornerShape(20.dp)).background(colour.copy(alpha = 0.45f))) {
                        if (photo != null) Artwork(photo.artworkAt(320), Modifier.matchParentSize(), RoundedCornerShape(20.dp))
                    }
                }
            }
        }
    }
}

// ---- Step 3: sign in ------------------------------------------------------

@Composable
private fun SignInStep() {
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(topInset()))
        StepTitle(
            "Sign in",
            if (signedIn) "You're signed in${account?.name?.let { " as $it" }.orEmpty()}. Your playlists and liked songs are in Library."
            else "Sign in to YouTube Music to bring your playlists, liked songs and recommendations. It's optional, and you can do it later in Settings › Account.",
        )
        Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(132.dp).clip(CircleShape).glass(CircleShape), contentAlignment = Alignment.Center) {
                val photo = account?.thumbnailUrl
                if (signedIn && photo != null) Artwork(photo, Modifier.size(132.dp), CircleShape)
                else Icon(Icons.Rounded.AccountCircle, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                "Your YouTube Music playlists and likes in Library",
                "Home's recommendations from your account",
                "Your sign-in stays on this phone; the password goes only to Google",
            ).forEach { line ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(20.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(line, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(Modifier.height(bottomInset() + 56.dp))
    }
}

/** Picks kept across rotation and process death, as id, name and photo. */
private val ArtistListSaver = listSaver<SnapshotStateList<ExploreFeed.Artist>, String?>(
    save = { list -> list.flatMap { listOf(it.browseId, it.name, it.thumbnailUrl) } },
    restore = { flat -> mutableStateListOf(*flat.chunked(3).map { ExploreFeed.Artist(it[0]!!, it[1]!!, it[2]) }.toTypedArray()) },
)

private val idSetSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { mutableStateListOf(*it.toTypedArray()) },
)
