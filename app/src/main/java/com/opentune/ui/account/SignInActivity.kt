package com.opentune.ui.account

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.opentune.data.settings.AppSettings
import com.opentune.ui.theme.OpenTuneTheme
import com.opentune.ui.theme.SystemBars

/** Keeps account pages outside the app's player, navigation, and recorded glass layers. */
class SignInActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by AppSettings.theme.collectAsState()
            OpenTuneTheme(theme) {
                SystemBars(MaterialTheme.colorScheme.background)
                if (intent.getBooleanExtra(DISCORD, false)) DiscordLoginScreen(onDone = { finish() })
                else LoginScreen(onDone = { finish() })
            }
        }
    }

    companion object {
        private const val DISCORD = "discord"
        fun intent(context: Context, discord: Boolean = false): Intent =
            Intent(context, SignInActivity::class.java).putExtra(DISCORD, discord)
    }
}
