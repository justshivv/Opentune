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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.distinctUntilChanged

/** Offset of the app page inside the window while a connectivity notice has reserved space. */
internal val LocalPageTopOffset = staticCompositionLocalOf { 0f }

/** Network changes are observed locally; no polling or analytics request is needed. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun ConnectionBanner(modifier: Modifier = Modifier, reduceMotion: Boolean = false,
    onOnline: () -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    var notice by remember { mutableStateOf<Boolean?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val available by rememberUpdatedState(onOnline)
    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            visible = false
            val notices = ConnectionNoticeState()
            try {
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
                }.steadyConnectivity().collect { online ->
                    if (online) available()
                    notices.accept(online)?.let { state -> notice = state; generation++; visible = true }
                }
            } finally { visible = false }
        }
    }
    LaunchedEffect(generation) {
        if (generation > 0) { kotlinx.coroutines.delay(5_000); visible = false }
    }
    ConnectionNoticeLayout(notice == true, visible, reduceMotion, modifier, content = content)
}

/** Measure the notice first so the page moves with it in the same frame, without overlay or padding lag. */
@Composable
internal fun ConnectionNoticeLayout(
    online: Boolean,
    visible: Boolean,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
    statusInsets: WindowInsets = WindowInsets.statusBars,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val face = if (online) { if (dark) Color(0xff183b2e) else Color(0xffc6efdf) }
        else { if (dark) Color(0xff492b30) else Color(0xffffd8dc) }
    val ink = if (dark) Color.White else Color(0xff171b19)
    SubcomposeLayout(modifier.background(MaterialTheme.colorScheme.background)) { constraints ->
        val banner = subcompose("notice") {
            AnimatedVisibility(visible, Modifier.windowInsetsPadding(statusInsets.only(WindowInsetsSides.Top)),
                enter = if (reduceMotion) EnterTransition.None else
                    expandVertically(tween(350), expandFrom = Alignment.Top) + slideInVertically(tween(350)) { -it } + fadeIn(tween(250)),
                exit = if (reduceMotion) ExitTransition.None else
                    shrinkVertically(tween(280), shrinkTowards = Alignment.Top) + slideOutVertically(tween(280)) { -it } + fadeOut(tween(200)),
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
        }.map { it.measure(constraints.copy(minHeight = 0)) }
        // Pages already reserve the status bar. Move them only by the notice's animated body height,
        // keeping their existing insets intact and the bottom navigation inside the remaining space.
        val occupied = ((banner.maxOfOrNull { it.height } ?: 0) - statusInsets.getTop(this))
            .coerceIn(0, constraints.maxHeight)
        val pageHeight = constraints.maxHeight - occupied
        val page = subcompose("page") {
            CompositionLocalProvider(LocalPageTopOffset provides occupied.toFloat()) {
                Box(Modifier.fillMaxSize(), content = content)
            }
        }.map { it.measure(constraints.copy(minHeight = pageHeight, maxHeight = pageHeight)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            page.forEach { it.placeRelative(0, occupied) }
            banner.forEach { it.placeRelative(0, 0) }
        }
    }
}

/** A resume starts a new baseline. "Back online" is only shown after an observed foreground outage. */
internal class ConnectionNoticeState {
    private var offlineAnnounced = false
    fun accept(online: Boolean): Boolean? = when {
        !online && !offlineAnnounced -> { offlineAnnounced = true; false }
        online && offlineAnnounced -> { offlineAnnounced = false; true }
        else -> null
    }
}

/** Validation briefly disappears while Android wakes or changes networks; do not call that an outage. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun Flow<Boolean>.steadyConnectivity(
    initialOfflineMs: Long = 3_000, offlineMs: Long = 1_800, onlineMs: Long = 400,
): Flow<Boolean> {
    var first = true
    return distinctUntilChanged().transformLatest { online ->
        val wait = if (online) onlineMs else if (first) initialOfflineMs else offlineMs
        first = false
        kotlinx.coroutines.delay(wait)
        emit(online)
    }.distinctUntilChanged()
}
