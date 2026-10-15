package com.opentune

import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.opentune.data.spotify.SpotifyAccount
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentune.data.account.AccountStore
import com.opentune.data.settings.AppSettings
import com.opentune.ui.AppRoot
import com.opentune.ui.Links
import com.opentune.ui.PlayerViewModel
import com.opentune.ui.theme.OpenTuneTheme
import com.opentune.ui.theme.rememberArtworkSeed

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        setContent {
            val vm: PlayerViewModel = viewModel()
            val theme by AppSettings.theme.collectAsState()
            val ui by AppSettings.ui.collectAsState()
            LaunchedEffect(ui.highRefreshRate) { preferRefreshRate(ui.highRefreshRate) }
            val song by vm.currentSong.collectAsState()
            OpenTuneTheme(theme, artworkSeed = rememberArtworkSeed(song?.thumbnailUrl)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The process may have started in the background, offline (after an update, say).
        AccountStore.ensureProfile()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        val uri = intent?.data
        if (uri != null && com.opentune.data.lossless.ExtensionProtocol.isCallback(uri)) {
            lifecycleScope.launch {
                val message = try {
                    if (com.opentune.data.lossless.ExtensionSessions.complete(uri)) "Amazon Music connected. Checking this track again."
                    else "Verification expired or did not match. Connect again in Audio settings."
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { "Could not complete provider verification. Please try again in Audio settings." }
                android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
            }
        } else if (uri != null && SpotifyAccount.isCallback(uri)) {
            lifecycleScope.launch {
                try { SpotifyAccount.complete(uri) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) {
                    android.widget.Toast.makeText(this@MainActivity, "Spotify sign-in failed. Please try again.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } else Links.receive(intent)
    }

    /**
     * With [fastest], asks for the screen's quickest refresh rate at its
     * current resolution, so scrolling and animation run at 90 or 120 Hz on
     * phones that otherwise hold apps at 60. Without it the system decides.
     */
    private fun preferRefreshRate(fastest: Boolean) {
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay) ?: return
        val now = display.mode
        val best = if (!fastest) null else display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            .maxByOrNull { it.refreshRate }
        val id = best?.modeId ?: 0
        if (window.attributes.preferredDisplayModeId != id) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = id }
        }
    }
}
