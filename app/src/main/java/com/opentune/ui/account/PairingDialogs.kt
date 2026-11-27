package com.opentune.ui.account

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.opentune.data.Pills
import com.opentune.data.account.AccountStore
import com.opentune.data.account.Lan
import com.opentune.data.account.PairingExtras
import com.opentune.data.account.PairingOffer
import com.opentune.data.account.PairingReceiver
import com.opentune.data.account.PairingSender
import com.opentune.data.account.WebShare
import com.opentune.data.account.WebShareOffer
import com.opentune.data.discord.DiscordRpc
import com.opentune.data.innertube.Innertube
import com.opentune.data.lastfm.LastFm
import com.opentune.data.remote.LanNetworks
import com.opentune.data.remote.TvDiscovery
import com.opentune.i18n.Tr
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
    /** A watch's pairing code, waiting for the person to add it. */
    val watch = MutableStateFlow<com.opentune.data.connect.WatchPairingKey?>(null)
    /** The web player's "Sign in with your phone" code, waiting for approval. */
    val pendingWeb = MutableStateFlow<WebShareOffer?>(null)
    /** Why the last sign-in code scanned couldn't be used, until it's read. */
    val problem = MutableStateFlow<String?>(null)

    fun receive(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme == "opentune" && uri.host == "watch") {
            watch.value = com.opentune.data.connect.WatchPairingKey.parse(uri.toString())
            return true
        }
        val own = uri.scheme == "opentune" && uri.host == "pair"
        val site = uri.scheme == "https" && uri.host == "justshivv.github.io" && uri.path == "/Opentune/pair/"
        if (own && uri.path == "/web") {
            val offer = runCatching { WebShareOffer.parse(uri.fragment.orEmpty()) }.getOrNull()
            pendingWeb.value = offer
            if (offer == null) problem.value = "That code from the web player has run out or didn't scan whole. Make a new one there and scan it again."
            return true
        }
        if (!own && !site) return false
        val read = runCatching {
            PairingOffer.parse(uri.fragment.orEmpty(), take = uri.getQueryParameter("take"), also = uri.getQueryParameter("also"))
        }
        pending.value = read.getOrNull()
        read.exceptionOrNull()?.let { e ->
            problem.value = if (e is Lan.OffHomeNetwork) Tr.f("That code points to {0}, which isn't on your home network. Make a new code on the TV.", e.host)
            else "That code didn't scan whole. Make a new one on the TV and scan it again, holding the phone steady."
        }
        return true
    }
    fun dismiss() { pending.value = null }
    fun dismissWatch() { watch.value = null }
    fun dismissWeb() { pendingWeb.value = null }
}

/**
 * A watch's code was scanned: adds the watch to this phone's devices, and
 * signs it in to YouTube Music too if the person ticks that.
 */
@Composable
fun ConfirmWatch() {
    val key by PairingLinks.watch.collectAsState()
    val current = key ?: return
    val signedIn by AccountStore.signedIn.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember(current) { mutableStateOf(false) }
    var status by remember(current) { mutableStateOf<String?>(null) }
    var withAccount by remember(current) { mutableStateOf(false) }
    FloatingCard(onDismiss = { if (!busy) PairingLinks.dismissWatch() }, title = "Add your watch") {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Add this watch to your devices? It will see and control what your other devices play. Approve only if you just scanned the code on your own watch.")
            if (signedIn) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = withAccount, onCheckedChange = { if (!busy) withAccount = it })
                Text("Also sign the watch in to YouTube Music")
            }
            status?.let { Text(it) }
            if (!busy) SheetButton("Add watch", tone = SheetTone.Primary, onClick = {
                busy = true; status = "Sending to the watch…"
                scope.launch {
                    try {
                        val name = com.opentune.data.connect.Connect.addWatch(current, withAccount)
                        if (name != null) {
                            status = null
                            current.forget()
                            PairingLinks.dismissWatch()
                            com.opentune.data.Pills.info(com.opentune.i18n.Tr.f("{0} is one of your devices now", name.ifBlank { "Your watch" }))
                        } else status = "The watch didn't answer. Scan a fresh code from it."
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { status = "Couldn't reach the watch. Check this phone's connection and try again." }
                    finally { busy = false }
                }
            })
            if (!busy) SheetButton("Cancel", onClick = { PairingLinks.dismissWatch() }, closes = true)
        }
    }
}

/** What the phone says when it couldn't sign a device in, in plain words for each reason. */
internal fun sendProblem(e: Throwable, status: LanNetworks.Status?, host: String): String {
    val reason = (e as? PairingSender.Failed)?.reason
    val isolation = "If NepTune still shows the code there, your router may be keeping devices on Wi-Fi apart (AP or client isolation, common on guest networks). Turn that off, or turn on this phone's hotspot, connect the device to it and make a new code."
    return when (reason) {
        PairingSender.Failed.Reason.EXPIRED -> "This code has run out. Make a new one on the TV and scan it again."
        PairingSender.Failed.Reason.EXPIRED_THERE -> "The TV says this code has run out. Make a new one on the TV and scan it again."
        PairingSender.Failed.Reason.OFF_NETWORK -> Tr.f("That code points to {0}, which isn't on your home network. Make a new code on the TV.", host)
        PairingSender.Failed.Reason.NOT_LISTENING -> "The TV is on this network but isn't waiting for this code any more. Make a new code on the TV and scan it again."
        PairingSender.Failed.Reason.REJECTED -> "The TV didn't take this code. It may have been used already, or a newer code is showing. Scan the code that's on the TV now."
        PairingSender.Failed.Reason.BAD_SESSION -> "The TV says this phone's YouTube Music sign-in isn't complete. Sign out and in again on this phone, then try again."
        PairingSender.Failed.Reason.NO_REPLY -> "The TV got the sign-in but didn't confirm it. If the TV doesn't show your account, make a new code there and try again."
        PairingSender.Failed.Reason.NO_ROUTE, PairingSender.Failed.Reason.NO_ANSWER, null -> when {
            status != null && !status.onLan -> "This phone isn't on Wi-Fi. Connect it to the same Wi-Fi as the TV and try again."
            status?.sameSubnet == false -> Tr.f("This phone and the TV are on different networks (the TV is at {0}). Connect both to the same Wi-Fi, not a guest network, and try again.", host)
            status?.vpn == true -> Tr.f("The TV at {0} didn't answer. Turn off the VPN on this phone, or let it allow local network access, and try again.", host)
            else -> Tr.f("The TV at {0} didn't answer.", host) + " " + Tr.t(isolation)
        }
    }
}

/** "Discord and Last.fm", for the kinds of sign-in named in [kinds]. */
private fun names(kinds: Set<String>): String =
    listOfNotNull("Discord".takeIf { PairingExtras.DISCORD in kinds }, "Last.fm".takeIf { PairingExtras.LASTFM in kinds })
        .joinToString(" and ")

/**
 * The phone's other sign-ins a code asked for, each a tick that starts on: nothing more than
 * the ticked ones leaves the phone, and only for this one scan.
 */
private class ShareChoices(take: Set<String>, discord: DiscordRpc.Account?, lastFm: LastFm.Account?) {
    val discordName = discord?.name?.takeIf { PairingExtras.DISCORD in take }
    val lastFmName = lastFm?.user?.takeIf { PairingExtras.LASTFM in take && LastFm.shared() != null }
    var discord by mutableStateOf(discordName != null)
    var lastFm by mutableStateOf(lastFmName != null)
    val offered: Boolean get() = discordName != null || lastFmName != null

    fun extras() = PairingExtras(
        DiscordRpc.account.value?.token?.takeIf { discord && discordName != null }?.let(PairingExtras::Discord),
        if (lastFm && lastFmName != null) LastFm.shared() else null,
    )
}

@Composable
private fun rememberShareChoices(key: Any, take: Set<String>): ShareChoices {
    val discord by DiscordRpc.account.collectAsState()
    val lastFm by LastFm.account.collectAsState()
    return remember(key, discord?.id, lastFm?.user) { ShareChoices(take, discord, lastFm) }
}

@Composable
private fun AlsoShare(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ShareChoiceRows(choices: ShareChoices, enabled: Boolean) {
    choices.discordName?.let { AlsoShare(com.opentune.i18n.Tr.f("Also sign in to Discord ({0})", it), choices.discord, enabled) { on -> choices.discord = on } }
    choices.lastFmName?.let { AlsoShare(com.opentune.i18n.Tr.f("Also sign in to Last.fm ({0})", it), choices.lastFm, enabled) { on -> choices.lastFm = on } }
}

@Composable
fun ConfirmPairing() {
    ConfirmWebShare()
    val problem by PairingLinks.problem.collectAsState()
    problem?.let { text ->
        FloatingCard(onDismiss = { PairingLinks.problem.value = null }, title = "Couldn't use that code") {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(text)
                SheetButton("OK", onClick = { PairingLinks.problem.value = null }, tone = SheetTone.Primary, closes = true)
            }
        }
        return
    }
    val offer by PairingLinks.pending.collectAsState()
    val current = offer ?: return
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // TVs announcing themselves on this network: if none of the code's addresses answers, the TV
    // may still answer at the one it announced (its sign-in code listens on all of them).
    val discovery = remember { TvDiscovery(context) }
    DisposableEffect(discovery) { discovery.start(); onDispose { discovery.stop() } }
    val choices = rememberShareChoices(current, current.take)
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
            Text(com.opentune.i18n.Tr.f("Share your YouTube Music session with the device at {0}? Approve only if you just scanned the QR code on your own TV.", current.host))
            Text(account?.email?.takeIf { it.isNotBlank() } ?: if (signedIn) "Your signed-in YouTube Music account" else "Sign in on this phone first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (signedIn && choices.offered) Column { ShareChoiceRows(choices, enabled = !busy) }
            status?.let { Text(it) }
            if (!signedIn) SheetButton("Sign in to YouTube Music", onClick = { login = true }, tone = SheetTone.Primary)
            else if (!busy) SheetButton("Sign this TV in", tone = SheetTone.Primary, onClick = {
                busy = true; status = "Connecting securely…"
                scope.launch {
                    try {
                        val cookie = Innertube.cookie?.takeIf { AccountStore.hasSession(it) }
                        if (cookie == null) { status = "This phone's YouTube Music sign-in isn't complete. Sign out and in again on this phone, then try again."; return@launch }
                        val extras = choices.extras()
                        val announced = discovery.found.value.map { it.host }
                        val took = withContext(Dispatchers.IO) {
                            PairingSender.send(current, cookie, extras.takeUnless { it.isEmpty }, also = announced, sockets = LanNetworks.sockets(context))
                        }
                        val missed = extras.kinds - took
                        when {
                            missed.isNotEmpty() -> Pills.info(Tr.f("Signed in. {0} couldn't be shared: sign in to it on the device itself.", names(missed)), context)
                            took.isNotEmpty() -> Pills.done(Tr.f("Signed in, with {0} too.", names(took)), context)
                            else -> Pills.done("Signed in. Your account is on the TV now.", context)
                        }
                        status = "Your TV is signed in."; PairingLinks.dismiss()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        val network = withContext(Dispatchers.IO) { runCatching { LanNetworks.status(context, current.hosts) }.getOrNull() }
                        status = sendProblem(e, network, current.host)
                    }
                    finally { busy = false }
                }
            })
            if (!busy) SheetButton("Cancel", onClick = { PairingLinks.dismiss() }, closes = true)
        }
    }
}

/**
 * The web player's code: it has no YouTube Music session to take (it uses the browser's own),
 * only the integrations it asks for, sealed for the OpenTune extension in that browser.
 */
@Composable
private fun ConfirmWebShare() {
    val offer by PairingLinks.pendingWeb.collectAsState()
    val current = offer ?: return
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val choices = rememberShareChoices(current, current.take)
    var busy by remember(current) { mutableStateOf(false) }
    var status by remember(current) { mutableStateOf<String?>(null) }
    FloatingCard(onDismiss = { if (!busy) PairingLinks.dismissWeb() }, title = "Sign in the web player") {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Share this phone's sign-ins with the NepTune web player? Approve only if you just scanned the code in your own browser.")
            if (choices.offered) Column { ShareChoiceRows(choices, enabled = !busy) }
            else Text("This phone isn't signed in to anything the web player asked for. Sign in to Discord on this phone first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Sealed so that only the NepTune extension in that browser can open it. The web page and the relays that carry it see none of it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status?.let { Text(it) }
            if (!busy && choices.offered) SheetButton(
                "Sign it in", tone = SheetTone.Primary,
                enabled = (choices.discord && choices.discordName != null) || (choices.lastFm && choices.lastFmName != null),
                onClick = {
                    busy = true; status = "Connecting securely…"
                    scope.launch {
                        try {
                            val extras = choices.extras()
                            val took = withContext(Dispatchers.IO) { WebShare.send(current, extras) }
                            if (took.isEmpty()) status = "The web player didn't take it. Make a new code there and try again."
                            else { Pills.done(com.opentune.i18n.Tr.f("The web player is signed in to {0}.", names(took)), context); PairingLinks.dismissWeb() }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { status = "Couldn't reach the web player. Keep its page open, make a fresh code there and scan it again." }
                        finally { busy = false }
                    }
                },
            )
            if (!busy) SheetButton("Cancel", onClick = { PairingLinks.dismissWeb() }, closes = true)
        }
    }
}
