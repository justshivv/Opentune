package com.opentune.tv

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** What was asked of the TV's voice search ("play … on OpenTune"), until Search picks it up. */
    private val voice = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice.value = spokenQuery(intent)
        setContent { TvTheme { TvRoot(viewModel(), voice) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        spokenQuery(intent)?.let { voice.value = it }
    }

    private fun spokenQuery(intent: Intent?): String? =
        intent?.takeIf { it.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH }
            ?.getStringExtra(SearchManager.QUERY)?.trim()?.takeIf { it.isNotEmpty() }
}
