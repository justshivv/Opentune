package com.opentune.ui.components

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/** Network changes are observed locally; no polling or analytics request is needed. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun ConnectionBanner(modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val context = LocalContext.current
    var notice by remember { mutableStateOf<Boolean?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        var previous: Boolean? = null
        callbackFlow {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            fun report() { trySend(AppSettings.isOnline()) }
            val listener = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = report()
                override fun onLost(network: Network) = report()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = report()
            }
            cm?.registerDefaultNetworkCallback(listener)
            report()
            awaitClose { if (cm != null) runCatching { cm.unregisterNetworkCallback(listener) } }
        }.debounce(450).distinctUntilChanged().collect { online ->
            if (previous != null || !online) { notice = online; generation++; visible = true }
            previous = online
        }
    }
    LaunchedEffect(generation) {
        if (generation > 0) { kotlinx.coroutines.delay(5_000); visible = false }
    }
    val online = notice == true
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val face = if (online) { if (dark) Color(0xff183b2e) else Color(0xffc6efdf) }
        else { if (dark) Color(0xff492b30) else Color(0xffffd8dc) }
    val ink = if (dark) Color.White else Color(0xff171b19)
    AnimatedVisibility(visible, modifier.statusBarsPadding(),
        enter = if (reduceMotion) fadeIn() else slideInVertically(tween(350)) { -it } + fadeIn(tween(250)),
        exit = if (reduceMotion) fadeOut() else slideOutVertically(tween(280)) { -it } + fadeOut(tween(200)),
    ) {
        Row(Modifier.fillMaxWidth().background(face).semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(if (online) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff, null, Modifier.size(28.dp), tint = ink)
            Spacer(Modifier.width(14.dp))
            Text(if (online) "You're back online." else "You're offline. Check your internet connection.",
                style = MaterialTheme.typography.titleSmall, color = ink)
        }
    }
}
