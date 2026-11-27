package com.opentune.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.opentune.data.account.AccountStore
import com.opentune.data.connect.Connect
import com.opentune.data.connect.Device
import com.opentune.data.connect.WatchPairing
import com.opentune.data.connect.WatchOffer
import com.opentune.data.connect.WatchPairingKey
import com.opentune.wear.Message
import com.opentune.wear.Place
import com.opentune.wear.rememberWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** A device's icon by its form. */
internal fun formIcon(form: String): ImageVector = when (form) {
    "web" -> Icons.Rounded.Laptop
    "watch" -> Icons.Rounded.Watch
    else -> Icons.Rounded.Smartphone
}

/** The line under a device's name: "Song · Artist", "Paused · …", or nothing. */
internal fun doing(d: Device): String? {
    val s = d.state ?: return null
    val t = s.track ?: return if (s.private) "Playing something of its own" else null
    val what = listOf(t.title, t.artist).filter { it.isNotBlank() }.joinToString(" · ")
    return if (s.playing) what else "Paused · $what"
}

/** The person's devices: a tap moves the music there; "This watch" brings it here. */
@Composable
fun Devices(onPair: () -> Unit, onDone: () -> Unit) {
    val view by Connect.view.collectAsState()
    val group by Connect.group.collectAsState()
    val enabled by Connect.enabled.collectAsState()
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Devices") } }
            val v = view
            when {
                group == null -> {
                    item { Message("Pair the watch with your phone to see your other devices.") }
                    item { Place("Pair with phone", Icons.Rounded.QrCode2, onClick = onPair) }
                }
                !enabled -> item { Message("Connect is off. Turn it on in Settings.") }
                v == null || v.relays == 0 -> item { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
                else -> {
                    val here = v.activeId == v.myId || v.activeId == null
                    item {
                        Button(
                            onClick = { Connect.transferTo(v.myId); onDone() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (here) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                            icon = { androidx.wear.compose.material3.Icon(Icons.Rounded.Watch, null) },
                            label = { Text("This watch") },
                        )
                    }
                    if (v.devices.isEmpty()) item { Message("None of your other devices is open right now.") }
                    v.devices.forEach { d ->
                        item {
                            Button(
                                onClick = { Connect.transferTo(d.id); onDone() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = if (v.activeId == d.id) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                                icon = { androidx.wear.compose.material3.Icon(formIcon(d.form), null) },
                                secondaryLabel = doing(d)?.let { { Text(it, maxLines = 1) } },
                                label = { Text(d.name, maxLines = 1) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The watch's pairing code: the phone scans it and sends the devices' group
 * (and the YouTube Music session, if the person ticks that) through the relays.
 */
@Composable
fun Pair(onDone: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var link by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Getting ready…") }
    var paired by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    // An offer came: shown to the person, and used only once they tap Pair.
    var asking by remember { mutableStateOf<WatchOffer?>(null) }
    var answer by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val name by Connect.deviceName.collectAsState()

    LaunchedEffect(attempt) {
        link = null; failed = false; paired = null; asking = null; status = "Getting ready…"
        val key = WatchPairingKey.generate()
        var declined = false
        try {
            val offer = WatchPairing.receive(key, name, onListening = { link = key.link }) { offer ->
                val yes = CompletableDeferred<Boolean>()
                answer = yes
                asking = offer
                link = null
                try { yes.await().also { declined = !it } } finally { asking = null; answer = null }
            }
            if (offer == null) {
                failed = true
                status = if (declined) "Not paired. Make a new code to try again." else "The code ran out. Make a new one."
            } else {
                Connect.accept(offer)
                paired = offer.name.ifBlank { "your phone" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
            status = "Couldn't reach the relays. Check the watch's connection."
        } finally {
            key.forget()
            link = null
        }
    }

    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            val code = link
            val offer = asking
            when {
                offer != null -> {
                    item { ListHeader { Text("Pair?") } }
                    item {
                        Message(
                            "Pair with ${offer.name.ifBlank { "this phone" }}?" +
                                (if (offer.cookie != null) " The watch will be signed in to its YouTube Music account too." else "") +
                                " Tap Pair only if you just scanned the code with your own phone.",
                        )
                    }
                    item { Place("Pair", Icons.Rounded.Check) { answer?.complete(true) } }
                    item { Place("Cancel", Icons.Rounded.Close) { answer?.complete(false) } }
                }
                paired != null -> {
                    item { ListHeader { Text("Paired") } }
                    item { Message("Paired with $paired. Your devices can see this watch now.") }
                    item { Place("Done", Icons.Rounded.Watch, onClick = onDone) }
                }
                code != null -> {
                    item {
                        Box(
                            Modifier.size(132.dp).background(Color.White, RoundedCornerShape(10.dp)).padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) { QrCode(code, Modifier.fillMaxSize()) }
                    }
                    item { Message("Scan with OpenTune on your phone: Settings, Devices, Add a device, or your camera.") }
                }
                failed -> {
                    item { Message(status) }
                    item { Place("New code", Icons.Rounded.QrCode2) { attempt++ } }
                }
                else -> {
                    item { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
                    item { Message(status) }
                }
            }
        }
    }
}

/** Connect and the account on the watch. */
@Composable
fun Settings(onPair: () -> Unit) {
    val name by Connect.deviceName.collectAsState()
    val enabled by Connect.enabled.collectAsState()
    val group by Connect.group.collectAsState()
    val sync by Connect.accountSync.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val rename = rememberWords("This watch's name") { Connect.setDeviceName(it) }
    var confirmLeave by remember { mutableStateOf(false) }
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Settings") } }
            item { Place("Name", Icons.Rounded.Edit, secondary = name) { rename() } }
            item { Place("Connect", Icons.Rounded.PowerSettingsNew, secondary = if (enabled) "On" else "Off") { Connect.setEnabled(!enabled) } }
            item { Place(if (group == null) "Pair with phone" else "Pair again", Icons.Rounded.QrCode2, onClick = onPair) }
            if (group != null) item {
                Place(if (confirmLeave) "Tap again to leave" else "Leave devices", Icons.Rounded.LinkOff) {
                    if (confirmLeave) { Connect.leave(); confirmLeave = false } else confirmLeave = true
                }
            }
            if (signedIn) {
                item {
                    Place("Find devices through YouTube", Icons.Rounded.Sync, secondary = if (sync == true) "On" else "Off") { Connect.setAccountSync(sync != true) }
                }
                item {
                    Place("Sign out", Icons.AutoMirrored.Rounded.Logout, secondary = account?.email?.takeIf { it.isNotBlank() } ?: account?.name) { AccountStore.signOut() }
                }
            }
        }
    }
}

/** Asked on Home once signed in, until answered here or in Settings. */
@Composable
fun AccountAsk() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Find your devices through YouTube?", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(
            "OpenTune keeps a private playlist in your YouTube account that holds a key, so your devices find each other.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { Connect.setAccountSync(false) }, colors = ButtonDefaults.filledTonalButtonColors()) { Text("Not now") }
            Button(onClick = { Connect.setAccountSync(true) }) { Text("Turn on") }
        }
    }
}
