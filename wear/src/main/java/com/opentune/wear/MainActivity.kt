package com.opentune.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchApp(viewModel()) }
    }

    override fun onStart() {
        super.onStart()
        com.opentune.playback.AppOnScreen.shown.value = true
    }

    override fun onStop() {
        com.opentune.playback.AppOnScreen.shown.value = false
        super.onStop()
    }
}
