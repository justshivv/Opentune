package com.opentune

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.opentune.data.spotify.Spotify
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
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
            val song by vm.currentSong.collectAsState()
            OpenTuneTheme(theme, artworkSeed = rememberArtworkSeed(song?.thumbnailUrl)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    /** Spotify's sign-in redirect is finished here; anything else is a link to open. */
    private fun receive(intent: Intent?) {
        val data = intent?.data
        if (Spotify.isRedirect(data)) {
            lifecycleScope.launch {
                val result = Spotify.finishSignIn(data!!)
                Toast.makeText(
                    this@MainActivity,
                    result.fold({ "Signed in to Spotify as ${it.name}. Import from Settings › Spotify." }, { it.message ?: "Spotify sign-in failed" }),
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        Links.receive(intent)
    }
}
