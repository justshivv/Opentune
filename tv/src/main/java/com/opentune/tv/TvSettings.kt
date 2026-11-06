package com.opentune.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.opentune.data.account.AccountStore
import com.opentune.data.account.PairingReceiver
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: TvNav) {
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val playback by AppSettings.playback.collectAsState()
    var pairing by remember { mutableStateOf(false) }

    if (pairing) {
        SignInPanel(onClose = { pairing = false })
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { PageTitle("Settings") }
        item { Heading("Account") }
        item {
            if (signedIn) {
                SettingRow("Signed in", account?.name?.takeIf { it.isNotBlank() } ?: account?.email ?: "YouTube Music", "Sign out") { AccountStore.signOut() }
            } else {
                SettingRow("Sign in", "Use your phone to bring your YouTube Music account here", "Sign in") { pairing = true }
            }
        }
        item { Heading("Playback") }
        item {
            SettingRow("Streaming quality", playback.wifiQuality.summary, playback.wifiQuality.label) {
                val all = AudioQuality.entries
                val next = all[(all.indexOf(playback.wifiQuality) + 1) % all.size]
                AppSettings.updatePlayback { it.copy(wifiQuality = next, mobileQuality = next) }
            }
        }
        item {
            SettingRow("Lossless streaming", "Better than lossy where an add-on can supply it", onOff(playback.losslessStreaming)) {
                AppSettings.updatePlayback { it.copy(losslessStreaming = !it.losslessStreaming) }
            }
        }
        item {
            SettingRow("Even loudness", "Songs play at about the same volume", onOff(playback.loudnessNormalization)) {
                AppSettings.updatePlayback { it.copy(loudnessNormalization = !it.loudnessNormalization) }
            }
        }
        item {
            SettingRow("Autoplay", "Keep playing similar songs when the queue ends", onOff(playback.autoplay)) {
                AppSettings.updatePlayback { it.copy(autoplay = !it.autoplay) }
            }
        }
        item {
            SettingRow("Automix", "Blend each song into the next, beat to beat", onOff(playback.automix)) {
                AppSettings.updatePlayback { it.copy(automix = !it.automix) }
            }
        }
        item { Heading("About") }
        item { SettingRow("OpenTune for TV", "Version ${com.opentune.core.BuildConfig.VERSION_NAME}", null) {} }
    }
}

private fun onOff(on: Boolean) = if (on) "On" else "Off"

@Composable
private fun Heading(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, color = TvColors.Accent,
        modifier = Modifier.padding(horizontal = TvSafe.Horizontal, vertical = 10.dp),
    )
}

/** A line of settings: what it is, what it's doing, and the value OK changes. */
@Composable
private fun SettingRow(title: String, detail: String, value: String?, onClick: () -> Unit) {
    TvSurface(
        onClick = onClick, shape = RoundedCornerShape(14.dp), scale = 1.015f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = TvSafe.Horizontal - 8.dp),
    ) { _ ->
        Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim)
            }
            if (value != null) Text(value, style = MaterialTheme.typography.labelLarge, color = TvColors.Accent)
        }
    }
}

/**
 * Signing in without typing: a code on the screen that the phone's OpenTune scans, which then
 * hands this TV its YouTube Music session directly over the home Wi-Fi, encrypted.
 */
@Composable
private fun SignInPanel(onClose: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var receiver by remember { mutableStateOf<PairingReceiver?>(null) }
    var status by remember { mutableStateOf("Preparing a private connection…") }
    var done by remember { mutableStateOf(false) }
    val signedIn by AccountStore.signedIn.collectAsState()

    DisposableEffect(attempt) { onDispose { receiver?.close() } }
    LaunchedEffect(attempt) {
        receiver = null; done = false
        try {
            val current = withContext(Dispatchers.IO) { PairingReceiver() }
            receiver = current
            status = "On your phone, open OpenTune, go to Settings, then Link a device, then TV, and scan this code. Both must be on the same Wi-Fi. It lasts two minutes."
            withContext(Dispatchers.IO) { current.receive { cookie -> AccountStore.signIn(cookie); true } }
            done = true
            status = "Signed in. Your YouTube Music account is on this TV."
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            status = if (e.message?.contains("same Wi-Fi") == true) "Connect this TV and your phone to the same Wi-Fi network."
            else "That code ran out, or the phone couldn't reach this TV. Make a new one."
        } finally { receiver?.close(); receiver = null }
    }
    LaunchedEffect(signedIn, done) { if (done && signedIn) { kotlinx.coroutines.delay(1_800); onClose() } }

    Row(Modifier.fillMaxSize().padding(horizontal = TvSafe.Horizontal), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(64.dp)) {
        Box(Modifier.size(360.dp).background(Color.White, RoundedCornerShape(24.dp)).padding(28.dp), contentAlignment = Alignment.Center) {
            val link = receiver?.offer?.link
            if (link != null && !done) QrCode(link, Modifier.fillMaxSize())
            else Text(if (done) "✓" else "…", style = MaterialTheme.typography.displaySmall, color = Color.Black)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Sign in with your phone", style = MaterialTheme.typography.headlineMedium)
            Text(status, style = MaterialTheme.typography.bodyLarge, color = TvColors.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (receiver == null && !done) TvButton("New code", { attempt++ }, primary = true)
                TvButton(if (done) "Done" else "Cancel", onClose)
            }
        }
    }
}
