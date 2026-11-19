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
import com.opentune.data.account.PairingExtras
import com.opentune.data.account.PairingOffer
import com.opentune.data.account.PairingReceiver
import com.opentune.data.account.PairingSender
import com.opentune.data.account.WebShare
import com.opentune.data.account.WebShareOffer
import com.opentune.data.discord.DiscordRpc
import com.opentune.data.innertube.Innertube
import com.opentune.data.lastfm.LastFm
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
    /** The web player's "Sign in with your phone" code, waiting for approval. */
    val pendingWeb = MutableStateFlow<WebShareOffer?>(null)
    fun receive(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        val own = uri.scheme == "opentune" && uri.host == "pair"
        val site = uri.scheme == "https" && uri.host == "justshivv.github.io" && uri.path == "/Opentune/pair/"
        if (own && uri.path == "/web") {
            pendingWeb.value = runCatching { WebShareOffer.parse(uri.fragment.orEmpty()) }.getOrNull()
            return true
        }
        if (!own && !site) return false
        pending.value = runCatching { PairingOffer.parse(uri.fragment.orEmpty(), take = uri.getQueryParameter("take")) }.getOrNull()
        return true
    }
    fun dismiss() { pending.value = null }
    fun dismissWeb() { pendingWeb.value = null }
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
    val offer by PairingLinks.pending.collectAsState()
    val current = offer ?: return
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
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
                        val cookie = Innertube.cookie ?: error("No session")
                        val extras = choices.extras()
                        val took = withContext(Dispatchers.IO) { PairingSender.send(current, cookie, extras.takeUnless { it.isEmpty }) }
                        val missed = extras.kinds - took
                        when {
                            missed.isNotEmpty() -> Pills.info(com.opentune.i18n.Tr.f("Signed in. {0} couldn't be shared: sign in to it on the device itself.", names(missed)), context)
                            took.isNotEmpty() -> Pills.done(com.opentune.i18n.Tr.f("Signed in, with {0} too.", names(took)), context)
                        }
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
            Text("Share this phone's sign-ins with the OpenTune web player? Approve only if you just scanned the code in your own browser.")
            if (choices.offered) Column { ShareChoiceRows(choices, enabled = !busy) }
            else Text("This phone isn't signed in to anything the web player asked for. Sign in to Discord on this phone first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Sealed so that only the OpenTune extension in that browser can open it. The web page and the relays that carry it see none of it.",
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
