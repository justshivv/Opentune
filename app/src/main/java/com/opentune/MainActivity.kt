package com.opentune

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

    private fun receive(intent: Intent?) = Links.receive(intent)
}
