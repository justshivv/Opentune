package com.opentune

import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.opentune.data.account.AccountStore
import androidx.compose.animation.togetherWith
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.WelcomeState
import com.opentune.ui.AppRoot
import com.opentune.ui.welcome.WelcomeScreen
import com.opentune.ui.Links
import com.opentune.ui.PlayerViewModel
import com.opentune.ui.theme.OpenTuneTheme
import com.opentune.ui.theme.rememberArtworkSeed
import com.opentune.ui.player.HeldInPictureInPicture
import com.opentune.ui.player.PictureInPicture
import com.opentune.ui.player.PictureInPictureContent
import com.opentune.ui.player.PipHost
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity(), PipHost {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.opentune.ui.theme.AppLanguages.wrap(newBase))
    }

    /** The small window's Previous, Play or Pause, and Next; only this app's own broadcasts reach it. */
    private val pipControls = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action != PictureInPicture.ACTION) return
            when (PictureInPicture.Control.of(intent.getIntExtra(PictureInPicture.EXTRA_CONTROL, -1))) {
                PictureInPicture.Control.PREVIOUS -> playerVm.skipPrevious()
                PictureInPicture.Control.PLAY -> if (!playerVm.isPlaying.value) playerVm.togglePlayPause()
                PictureInPicture.Control.PAUSE -> if (playerVm.isPlaying.value) playerVm.togglePlayPause()
                PictureInPicture.Control.NEXT -> playerVm.skipNext()
                null -> Unit
            }
        }
    }

    /** The last shape the small window was given, kept for a song without video so it doesn't jump. */
    private var pipAspect: android.util.Rational? = null

    /** What the small window would be told now: the picture's shape and place, the buttons, whether Home goes into it. */
    private fun pipSpec(): PictureInPicture.Spec {
        val vm = playerVm
        val videoMode = vm.videoMode.value
        val aspect = PictureInPicture.aspect(if (videoMode) com.opentune.ui.player.videoAspect(vm.player.videoSize.value) else 0f)
            ?: pipAspect ?: PictureInPicture.aspect(16f / 9f)
        pipAspect = aspect
        val song = vm.currentSong.value
        return PictureInPicture.Spec(
            aspect = aspect,
            sourceRect = PictureInPicture.bounds.value,
            playing = vm.isPlaying.value,
            hasNext = vm.hasNext.value,
            autoEnter = PictureInPicture.autoEnter(
                allowed = PictureInPicture.allowed.value,
                videoMode = videoMode,
                playerShowsVideo = PictureInPicture.showing.value.isNotEmpty(),
                playing = vm.isPlaying.value,
                inWindow = PictureInPicture.active.value,
            ),
            title = song?.title,
            subtitle = song?.artist,
        )
    }

    override fun enterPictureInPicture(): Boolean {
        if (!PictureInPicture.isAllowed(this)) return false
        return runCatching { enterPictureInPictureMode(PictureInPicture.params(this, pipSpec())) }
            .onFailure { com.opentune.data.DebugLog.w("MainActivity", "Picture-in-picture refused", it) }
            .getOrDefault(false)
    }

    /** Keeps Android told what the small window should be, as the song, its picture and its play state change. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun watchPictureInPicture() {
        val vm = playerVm
        val song = kotlinx.coroutines.flow.combine(vm.videoMode, vm.isPlaying, vm.hasNext, vm.player.videoSize, vm.currentSong) { a, b, c, d, e -> listOf(a, b, c, d, e?.videoId, e?.title) }
        val pip = kotlinx.coroutines.flow.combine(
            PictureInPicture.allowed, PictureInPicture.active, PictureInPicture.showing,
            // Its place moves with every frame of a drag or an easing: told once it settles.
            PictureInPicture.bounds.debounce(PIP_BOUNDS_SETTLE_MS),
        ) { a, b, c, d -> listOf(a, b, c.isNotEmpty(), d) }
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(song, pip) { a, b -> a + b }
                .distinctUntilChanged()
                .collect {
                    // Only where the window exists at all: elsewhere Android refuses the call.
                    if (!PictureInPicture.allowed.value) return@collect
                    runCatching { setPictureInPictureParams(PictureInPicture.params(this@MainActivity, pipSpec())) }
                        .onFailure { com.opentune.data.DebugLog.w("MainActivity", "Couldn't update picture-in-picture", it) }
                }
        }
    }

    /** Home pressed: before Android 12 (which goes in on its own, see PictureInPicture.params), into the small window while a video plays in the player. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pipSpec().autoEnter) enterPictureInPicture()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Out of it by expanding: the app shows again as it was. Swiped away: the activity
        // stops as when leaving the app, and the music plays on as it does then.
        PictureInPicture.setActive(isInPictureInPictureMode)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipControls) }
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        com.opentune.playback.AppOnScreen.shown.value = false
        com.opentune.ui.components.GlassPerformance.unwatch(this)
        // Off the screen, the app can be closed at any moment: a like or a playlist change made
        // just before was still waiting for its save, and was lost.
        Thread { com.opentune.data.Core.flush() }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.opentune.data.innertube.Innertube.regionOverride = com.opentune.data.UserCountry.quick(this)
            ?: android.content.res.Resources.getSystem().configuration.locales[0].country.takeIf { it.length == 2 }
        java.util.Locale.setDefault(resources.configuration.locales[0])
        enableEdgeToEdge()
        com.opentune.ui.components.GlassPerformance.init(this)
        // The small window's buttons: a receiver no other app can send to.
        androidx.core.content.ContextCompat.registerReceiver(
            this, pipControls, android.content.IntentFilter(PictureInPicture.ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        PictureInPicture.setAllowed(PictureInPicture.isAllowed(this))
        PictureInPicture.setActive(isInPictureInPictureMode)
        watchPictureInPicture()
        // Opened afresh, not turned or restored: counts toward the tips that wait for a later launch.
        if (savedInstanceState == null) com.opentune.data.tips.Tips.opened()
        if (savedInstanceState == null) receive(intent)
        // Again on every start: their labels follow the app's language, and the icon picked.
        Thread { com.opentune.ui.LauncherShortcuts.publish(this) }.start()
        // An older Android or a phone short of memory: Lite mode, once, before the first frame.
        if (!AppSettings.ui.value.liteChecked) {
            val memory = getSystemService(android.app.ActivityManager::class.java)
            val info = android.app.ActivityManager.MemoryInfo().also { memory?.getMemoryInfo(it) }
            val slow = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
                    memory?.isLowRamDevice == true || info.totalMem in 1 until LITE_MEMORY_BYTES
            AppSettings.updateUi { (if (slow) com.opentune.data.settings.liteModeApplied(it, true) else it).copy(liteChecked = true) }
        }
        setContent {
            val vm: PlayerViewModel = viewModel()
            val theme by AppSettings.theme.collectAsState()
            val ui by AppSettings.ui.collectAsState()
            // Battery saver holds the screen at 60 Hz while OpenTune is open; otherwise as chosen.
            val saver = ui.performanceProfile == com.opentune.data.settings.PerformanceProfile.BATTERY_SAVER
            LaunchedEffect(ui.highRefreshRate, ui.refreshRateHz, saver) {
                if (saver) preferRefreshRate(true, 60) else preferRefreshRate(ui.highRefreshRate, ui.refreshRateHz)
            }
            val song by vm.currentSong.collectAsState()
            val inPip by PictureInPicture.active.collectAsState()
            OpenTuneTheme(theme, artworkSeed = rememberArtworkSeed(song?.thumbnailUrl)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // In the small window only the picture shows; the app is kept just as it was underneath.
                    HeldInPictureInPicture(inPip, inWindow = { isInPictureInPictureMode }) {
                    androidx.compose.foundation.layout.Box {
                        // More than two releases behind, the app is only its update screen until it's updated.
                        val required by com.opentune.data.UpdateLock.required.collectAsState()
                        val behind by com.opentune.data.UpdateLock.behind.collectAsState()
                        LaunchedEffect(required) { if (required != null && vm.isPlaying.value) vm.togglePlayPause() }
                        if (required != null) com.opentune.ui.settings.UpdateRequiredScreen(required!!, behind) else {
                            // A fresh install's welcome comes first; Home, built after it, starts from the picks.
                            androidx.compose.animation.AnimatedContent(
                                ui.welcome == WelcomeState.DUE,
                                transitionSpec = {
                                    if (ui.reduceAnimation) {
                                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) togetherWith
                                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                                    } else {
                                        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(320, delayMillis = 80)) +
                                                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(420), initialScale = 0.96f)) togetherWith
                                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))
                                    }
                                },
                                label = "welcome",
                            ) { welcome ->
                                val page: @androidx.compose.runtime.Composable () -> Unit = {
                                    if (welcome) WelcomeScreen(onDone = {}) else AppRoot(vm)
                                }
                                page()
                            }
                        }
                        com.opentune.ui.account.ConfirmPairing()
                    }
                    }
                    if (inPip) {
                        val videoMode by vm.videoMode.collectAsState()
                        val videoSize by vm.player.videoSize.collectAsState()
                        val output = PictureInPicture.output
                        PictureInPictureContent(
                            song?.thumbnailUrl,
                            videoMode = videoMode,
                            picture = if (output != null) ({ com.opentune.ui.player.VideoSurface(output, videoSize, Modifier.matchParentSize()) }) else null,
                        )
                    }
                }
            }
        }
    }

    /**
     * With the device volume bar hidden (a player choice), the volume keys move
     * the music volume without Android's panel, but only while the big player
     * is open. The choice counts only while the player's own volume slider is
     * shown too, so one volume control is always on screen; the player's
     * slider follows the keys on its own.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (silentVolumeKey(keyCode)) {
            val direction = if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                android.media.AudioManager.ADJUST_RAISE
            } else {
                android.media.AudioManager.ADJUST_LOWER
            }
            // No FLAG_SHOW_UI: the volume moves, the panel stays away.
            getSystemService(android.media.AudioManager::class.java)
                ?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, 0)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (silentVolumeKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    /** The same PlayerViewModel the content uses; its playerOpen flag gates the keys. */
    private val playerVm by lazy { androidx.lifecycle.ViewModelProvider(this)[PlayerViewModel::class.java] }

    /** True for a volume key press the app should take silently: the setting on, the player's slider shown, the big player open. */
    private fun silentVolumeKey(keyCode: Int): Boolean {
        if (keyCode != android.view.KeyEvent.KEYCODE_VOLUME_UP && keyCode != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val ui = AppSettings.ui.value
        return ui.hideDeviceVolumeBar && !ui.hideVolumeBar && playerVm.playerOpen.value
    }

    override fun onStart() {
        super.onStart()
        // Turned on or off for the app in Settings while away.
        PictureInPicture.setAllowed(PictureInPicture.isAllowed(this))
        com.opentune.playback.AppOnScreen.shown.value = true
        com.opentune.data.AppInUse.started.value = true
        com.opentune.ui.components.GlassPerformance.watch(this)
        com.opentune.data.history.AccountHistory.syncSoon()
        // The process may have started in the background, offline (after an update, say).
        AccountStore.ensureProfile()
        lifecycleScope.launchWhenStarted {
            com.opentune.data.addons.AddonRefresh.refresh()
        }
        // Whether this version is still allowed to run (UpdateLock), at most once an hour.
        lifecycleScope.launch { com.opentune.data.UpdateLock.refresh(this@MainActivity) }
        // Playlist changes that couldn't reach YouTube Music last time try again.
        if (com.opentune.data.library.PlaylistSync.pending.value.isNotEmpty()) com.opentune.data.library.PlaylistSync.flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        if (!com.opentune.ui.account.PairingLinks.receive(intent)) Links.receive(intent)
    }

    /**
     * With [on] (high performance mode), asks for a fast refresh rate at the
     * screen's current resolution, so scrolling and animation run at 90 or
     * 120 Hz on phones that otherwise hold apps at 60: the mode nearest [hz],
     * or the fastest one when [hz] is 0. Without it the system decides.
     */
    private fun preferRefreshRate(on: Boolean, hz: Int) {
        // The language-wrapped context can come without a display of its own: then the window's.
        @Suppress("DEPRECATION")
        val display = runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay }.getOrNull()
            ?: runCatching { window.decorView.display ?: windowManager.defaultDisplay }.getOrNull() ?: return
        val now = display.mode
        val modes = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
        // Only 60, 90 and 120 Hz are offered; 0, saved by older versions, is 60 like a new install.
        val offered = modes.filter { it.refreshRate.roundToInt() in com.opentune.ui.settings.OFFERED_RATES }.ifEmpty { modes }
        val want = if (hz <= 0) 60 else hz
        val best = if (!on) null else offered.minByOrNull { kotlin.math.abs(it.refreshRate - want) }
        val id = best?.modeId ?: 0
        if (window.attributes.preferredDisplayModeId != id) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = id }
        }
    }
}

/** How long the picture's place has to hold still before Android is told it (where the small window grows from). */
private const val PIP_BOUNDS_SETTLE_MS = 150L

/** Phones with less memory than this start in Lite mode: about 3 GB, the low end today. */
private const val LITE_MEMORY_BYTES = 3_300_000_000L
