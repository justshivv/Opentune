package com.opentune.ui.account

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.opentune.data.account.AccountStore
import com.opentune.data.account.PairingOffer
import com.opentune.data.account.PairingReceiver
import com.opentune.data.account.PairingSender
import com.opentune.data.innertube.Innertube
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.together.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds only an unapproved QR offer. Receiving an intent never sends credentials. */
object PairingLinks {
    val pending = MutableStateFlow<PairingOffer?>(null)
    fun receive(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        val own = uri.scheme == "opentune" && uri.host == "pair"
        val site = uri.scheme == "https" && uri.host == "justshivv.github.io" && uri.path == "/Opentune/pair/"
        if (!own && !site) return false
        pending.value = runCatching { PairingOffer.parse(uri.fragment.orEmpty()) }.getOrNull()
        return true
    }
    fun dismiss() { pending.value = null }
}

@Composable
internal fun ReceiveSessionSheet(onDismiss: () -> Unit) {
    var receiver by remember { mutableStateOf<PairingReceiver?>(null) }
    var status by remember { mutableStateOf("Preparing a private connection…") }
    var complete by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    DisposableEffect(generation) { onDispose { receiver?.close() } }
    LaunchedEffect(generation) {
        receiver = null
        complete = false
        try {
            val current = withContext(Dispatchers.IO) { PairingReceiver() }
            receiver = current
            status = "Scan with your signed-in WEAV phone. Keep both devices on the same Wi-Fi. This code expires in two minutes."
            withContext(Dispatchers.IO) {
                current.receive { cookie -> AccountStore.signIn(cookie); true }
            }
            complete = AccountStore.signedIn.value
            status = if (complete) "YouTube Music is connected on this device." else "Pairing closed. Try a new code."
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { status = "Could not complete pairing. Use the same Wi-Fi, allow local network access, and generate a new code. Guest networks may block device connections." }
        finally { receiver?.close(); receiver = null }
    }
    FloatingCard(onDismiss = onDismiss, title = "Sign in with your phone") {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            receiver?.takeUnless { complete }?.let { current ->
                Box(Modifier.size(240.dp).background(Color.White).padding(16.dp)) { QrCode(current.offer.link, Modifier.fillMaxSize()) }
                Text("Only approve the code displayed on your own TV. The session transfers directly between your devices, encrypted.", style = MaterialTheme.typography.bodySmall)
            }
            if (receiver == null && !complete) SheetButton("Generate a new code", onClick = { generation++ })
            SheetButton(if (complete) "Done" else "Cancel", onClick = onDismiss, closes = true)
        }
    }
}

@Composable
fun ConfirmPairing() {
    val offer by PairingLinks.pending.collectAsState()
    val current = offer ?: return
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember(current) { mutableStateOf(false) }
    var status by remember(current) { mutableStateOf<String?>(null) }
    var login by remember(current) { mutableStateOf(false) }
    if (login) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { login = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize()) { LoginScreen { login = false } }
        }
        return
    }
    FloatingCard(onDismiss = { if (!busy) PairingLinks.dismiss() }, title = "Connect your TV") {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Share your YouTube Music session with the device at ${current.host}? Approve only if you just scanned the QR code on your own TV.")
            Text(account?.email?.takeIf { it.isNotBlank() } ?: if (signedIn) "Your signed-in YouTube Music account" else "Sign in on this phone first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            status?.let { Text(it) }
            if (!signedIn) SheetButton("Sign in to YouTube Music", onClick = { login = true }, tone = SheetTone.Primary)
            else if (!busy) SheetButton("Sign this TV in", tone = SheetTone.Primary, onClick = {
                busy = true; status = "Connecting securely…"
                scope.launch {
                    try {
                        val cookie = Innertube.cookie ?: error("No session")
                        withContext(Dispatchers.IO) { PairingSender.send(current, cookie) }
                        status = "Your TV is signed in."; PairingLinks.dismiss()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { status = "Couldn't connect. Keep both devices on the same Wi-Fi and scan a fresh code from the TV." }
                    finally { busy = false }
                }
            })
            if (!busy) SheetButton("Cancel", onClick = { PairingLinks.dismiss() }, closes = true)
        }
    }
}
