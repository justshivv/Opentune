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

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.opentune.ui.theme.AppLanguages.wrap(newBase))
    }
    private var tv = false

    override fun onStop() {
        super.onStop()
        // Off the screen, the app can be closed at any moment: a like or a playlist change made
        // just before was still waiting for its save, and was lost.
        Thread { com.opentune.data.Core.flush() }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.opentune.data.innertube.Innertube.regionOverride = runCatching {
            getSystemService(android.telephony.TelephonyManager::class.java)?.networkCountryIso?.takeIf { it.length == 2 }
        }.getOrNull() ?: android.content.res.Resources.getSystem().configuration.locales[0].country.takeIf { it.length == 2 }
        java.util.Locale.setDefault(resources.configuration.locales[0])
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        tv = com.opentune.ui.tv.Tv.isTv(this)
        if (tv) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContent {
            val vm: PlayerViewModel = viewModel()
            val theme by AppSettings.theme.collectAsState()
            val ui by AppSettings.ui.collectAsState()
            LaunchedEffect(ui.highRefreshRate, ui.refreshRateHz) { preferRefreshRate(ui.highRefreshRate, ui.refreshRateHz) }
            // A TV dims and starts its screensaver after a few minutes without a key press;
            // while music plays from here, the cover and lyrics stay on screen instead.
            val playing by vm.isPlaying.collectAsState()
            LaunchedEffect(playing) {
                if (!tv) return@LaunchedEffect
                if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val song by vm.currentSong.collectAsState()
            OpenTuneTheme(theme, artworkSeed = rememberArtworkSeed(song?.thumbnailUrl)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Some TVs crop the picture's edges: keep pages and buttons inside the safe area.
                    androidx.compose.foundation.layout.Box(if (tv) Modifier.padding(horizontal = 28.dp, vertical = 16.dp) else Modifier) {
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
                        if (tv) com.opentune.ui.tv.TvRemoteSurface(page) else page()
                    }
                    com.opentune.ui.account.ConfirmPairing()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The process may have started in the background, offline (after an update, say).
        AccountStore.ensureProfile()
        lifecycleScope.launchWhenStarted {
            com.opentune.data.addons.AddonRefresh.refresh()
        }
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
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay) ?: return
        val now = display.mode
        val modes = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
        // Only 60, 90 and 120 Hz are offered; "fastest" (0, from before) is the fastest of those.
        val offered = modes.filter { it.refreshRate.roundToInt() in com.opentune.ui.settings.OFFERED_RATES }.ifEmpty { modes }
        val best = when {
            !on -> null
            hz <= 0 -> offered.maxByOrNull { it.refreshRate }
            else -> offered.minByOrNull { kotlin.math.abs(it.refreshRate - hz) }
        }
        val id = best?.modeId ?: 0
        if (window.attributes.preferredDisplayModeId != id) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = id }
        }
    }
}
