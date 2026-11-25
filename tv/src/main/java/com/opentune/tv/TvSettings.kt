package com.opentune.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.account.AccountStore
import com.opentune.data.account.PairingFailed
import com.opentune.data.account.PairingReceiver
import com.opentune.data.model.Account
import com.opentune.data.remote.LanNetworks
import com.opentune.data.remote.RemoteInvite
import com.opentune.i18n.Tr
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** What Settings shows over its list: nothing, the sign-in code, or the pair-a-phone code. */
private enum class Panel { None, SignIn, PairPhone }

/**
 * Settings, one list under its title: Account, Playback, the phone remote and About. OK on a row
 * changes it (a switch flips, streaming quality steps to the next). Sign in and Pair a phone open
 * a code to scan in place of the list; Back (or Cancel) closes it, and the focus goes back to the
 * row that opened it.
 */
@Composable
fun SettingsScreen(nav: TvNav) {
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val playback by AppSettings.playback.collectAsState()
    val remote = nav.remote
    val paired by (remote?.phones?.phones ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    val connected by (remote?.controllers ?: remember { MutableStateFlow(emptyList()) }).collectAsState()

    var panel by rememberSaveable { mutableStateOf(Panel.None) }
    var closed by remember { mutableStateOf<Panel?>(null) }
    val accountRow = remember { FocusRequester() }
    val pairRow = remember { FocusRequester() }
    val list = rememberLazyListState()
    fun close() { closed = panel; panel = Panel.None }

    when {
        panel == Panel.SignIn -> { SignInPanel(onClose = ::close); return }
        panel == Panel.PairPhone && remote != null -> { PairPhonePanel(remote, onClose = ::close); return }
    }
    // Back from a code: the focus goes to the row that opened it.
    LaunchedEffect(closed) {
        val from = closed ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { (if (from == Panel.PairPhone) pairRow else accountRow).requestFocus() }
        closed = null
    }

    TvPage(title = "Settings", spacing = TvSpace.S, scroll = TvScroll.List, state = list) {
        item(key = "account:title") { TvSectionTitle("Account") }
        item(key = "account") {
            if (signedIn) AccountRow(account, onSignOut = AccountStore::signOut, focusRequester = accountRow)
            else TvRowButton(
                "Sign in", { panel = Panel.SignIn }, Modifier.safeSides(),
                subtitle = "Use your phone to bring your YouTube Music account here",
                icon = Icons.AutoMirrored.Rounded.Login, value = "Sign in",
                focusKey = "account", focusRequester = accountRow,
            )
        }
        item(key = "playback:title") { TvSectionTitle("Playback") }
        item(key = "quality") {
            TvRowButton(
                "Streaming quality", {
                    val all = AudioQuality.entries
                    val next = all[(all.indexOf(playback.wifiQuality) + 1) % all.size]
                    AppSettings.updatePlayback { it.copy(wifiQuality = next, mobileQuality = next) }
                }, Modifier.safeSides(),
                subtitle = playback.wifiQuality.summary, icon = Icons.Rounded.HighQuality,
                value = playback.wifiQuality.label, focusKey = "quality",
            )
        }
        item(key = "lossless") {
            TvToggleRow(
                "Lossless streaming", playback.losslessStreaming,
                { on -> AppSettings.updatePlayback { it.copy(losslessStreaming = on) } }, Modifier.safeSides(),
                subtitle = "Better than lossy where an add-on can supply it", icon = Icons.Rounded.GraphicEq, focusKey = "lossless",
            )
        }
        item(key = "loudness") {
            TvToggleRow(
                "Even loudness", playback.loudnessNormalization,
                { on -> AppSettings.updatePlayback { it.copy(loudnessNormalization = on) } }, Modifier.safeSides(),
                subtitle = "Songs play at about the same volume", icon = Icons.AutoMirrored.Rounded.VolumeUp, focusKey = "loudness",
            )
        }
        item(key = "autoplay") {
            TvToggleRow(
                "Autoplay", playback.autoplay,
                { on -> AppSettings.updatePlayback { it.copy(autoplay = on) } }, Modifier.safeSides(),
                subtitle = "Keep playing similar songs when the queue ends", icon = Icons.Rounded.PlayCircle, focusKey = "autoplay",
            )
        }
        item(key = "automix") {
            TvToggleRow(
                "Automix", playback.automix,
                { on -> AppSettings.updatePlayback { it.copy(automix = on) } }, Modifier.safeSides(),
                subtitle = "Blend each song into the next, beat to beat", icon = Icons.Rounded.Waves, focusKey = "automix",
            )
        }
        if (remote != null) {
            item(key = "remote:title") { TvSectionTitle("Phone remote") }
            item(key = "pair") {
                TvRowButton(
                    "Pair a phone", { panel = Panel.PairPhone }, Modifier.safeSides(),
                    subtitle = "Use your phone as a trackpad and remote. Scan a code to connect it once.",
                    icon = Icons.Rounded.Add, value = "Pair", focusKey = "pair", focusRequester = pairRow,
                )
            }
            itemsIndexed(paired, key = { _, p -> "phone:${p.id}" }) { _, phone ->
                TvRowButton(
                    phone.name, { remote.forget(phone) }, Modifier.safeSides(),
                    subtitle = if (phone.name in connected) "Connected now" else "Paired",
                    icon = Icons.Rounded.Smartphone, value = "Remove", focusKey = "phone:${phone.id}",
                )
            }
        }
        item(key = "about:title") { TvSectionTitle("About") }
        item(key = "about") {
            TvRowButton(
                "NepTune for TV", {}, Modifier.safeSides(),
                icon = Icons.Rounded.Info, value = com.opentune.core.BuildConfig.VERSION_NAME, focusKey = "about",
            )
        }
    }
}

/** The signed-in account: its photo, name and address, and Sign out at the end. */
@Composable
private fun AccountRow(account: Account?, onSignOut: () -> Unit, focusRequester: FocusRequester) {
    TvSurface(
        onClick = onSignOut, shape = TvShapes.Row, focusKey = "account", focusRequester = focusRequester,
        modifier = Modifier.safeSides().fillMaxWidth(),
    ) { _ ->
        Row(Modifier.padding(horizontal = TvSpace.L, vertical = TvSpace.M), verticalAlignment = Alignment.CenterVertically) {
            Cover(account?.thumbnailUrl, Modifier.size(TvSizes.RowCover), TvShapes.Round, px = TvArt.Small)
            Spacer(Modifier.width(TvSpace.L))
            Column(Modifier.weight(1f)) {
                val name = account?.name?.takeIf { it.isNotBlank() }
                Text(name ?: "Signed in", style = TvType.ItemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    account?.email?.takeIf { it.isNotBlank() } ?: "Signed in to YouTube Music",
                    style = TvType.Meta, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text("Sign out", style = TvType.Label, color = TvColors.Accent, maxLines = 1, modifier = Modifier.padding(start = TvSpace.L))
        }
    }
}

/** Where a code for the phone to scan is: on its way, up, used (done), or run out. */
internal enum class CodeState { Waiting, Ready, Done, Expired }

/**
 * A code for a phone to scan, filling the page: the QR code on a white square (which scanners
 * need) at the left; the [title], what to do ([message]) and the buttons at the right. The focus
 * goes to New code when the code ran out, else to Cancel (Done once it worked). Back closes it.
 */
@Composable
internal fun PhoneCodePanel(
    title: String,
    message: String,
    link: String?,
    state: CodeState,
    onNewCode: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val newCode = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    val expired = state == CodeState.Expired
    LaunchedEffect(expired) {
        withFrameNanos { }
        runCatching { (if (expired) newCode else close).requestFocus() }
    }
    Row(
        Modifier.fillMaxSize().safeSides().padding(bottom = TvSafe.Vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvSpace.XXL),
    ) {
        Box(
            Modifier.size(QR_SIZE).background(Color.White, RoundedCornerShape(16.dp)).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state == CodeState.Ready && link != null -> QrCode(link, Modifier.fillMaxSize())
                state == CodeState.Done -> Icon(Icons.Rounded.CheckCircle, "Done", tint = DoneInk, modifier = Modifier.size(120.dp))
                else -> Icon(Icons.Rounded.QrCode2, null, tint = Color(0xFFD5D8DE), modifier = Modifier.size(200.dp))
            }
        }
        Column(Modifier.weight(1f).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(TvSpace.L)) {
            Text(title, style = TvType.Headline)
            Text(message, style = TvType.Body, color = TvColors.TextDim)
            Row(Modifier.padding(top = TvSpace.S), horizontalArrangement = Arrangement.spacedBy(TvSpace.M)) {
                if (expired) TvPillButton("New code", onNewCode, icon = Icons.Rounded.Refresh, primary = true, focusRequester = newCode)
                TvPillButton(
                    if (state == CodeState.Done) "Done" else "Cancel", onClose,
                    icon = if (state == CodeState.Done) null else Icons.Rounded.Close,
                    primary = state == CodeState.Done, focusRequester = close,
                )
            }
        }
    }
}

private val QR_SIZE = 340.dp
/** The tick on the white square once it worked: dark, as the code was. */
private val DoneInk = Color(0xFF1B5E20)

/**
 * Pairing a phone: a code on the screen that the phone scans once. After that the phone finds
 * this TV by itself. The code lasts two minutes and works for one phone.
 */
@Composable
private fun PairPhonePanel(remote: TvRemote, onClose: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var link by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(true) }
    val before = remember { remote.phones.phones.value.size }
    val phones by remote.phones.phones.collectAsState()
    val done = phones.size > before

    DisposableEffect(Unit) { onDispose { remote.cancelInvite() } }
    var at by remember { mutableStateOf("") }
    LaunchedEffect(attempt) {
        problem = null; waiting = true
        link = runCatching { remote.invite().also { at = it.host }.link }
            .onFailure { problem = "This TV isn't connected to a home network. Connect it to Wi-Fi or Ethernet, then make a new code." }
            .getOrNull()
        waiting = false
        // The code runs out after two minutes; say so instead of leaving a dead one up.
        if (link != null) { delay(RemoteInvite.LIFETIME_MS); link = null }
    }
    LaunchedEffect(done) { if (done) { delay(1_800); onClose() } }

    PhoneCodePanel(
        title = "Pair a phone",
        message = when {
            done -> "Paired. Open NepTune on the phone, then Settings, Link a device, TV, to use it as a remote."
            problem != null -> problem!!
            waiting -> "Preparing a private connection…"
            link == null -> "That code ran out. Make a new one."
            else -> Tr.f(
                "On your phone, open NepTune, then Settings, Link a device, TV, Control the TV, and scan this code. Both must be on the same Wi-Fi. It lasts two minutes. This TV is at {0}.",
                at,
            )
        },
        link = link,
        state = when {
            done -> CodeState.Done
            waiting -> CodeState.Waiting
            link == null -> CodeState.Expired
            else -> CodeState.Ready
        },
        onNewCode = { attempt++ },
        onClose = onClose,
    )
}

/**
 * Signing in without typing: a code on the screen that the phone's app scans, which then hands
 * this TV its YouTube Music session directly over the home Wi-Fi, encrypted.
 */
@Composable
private fun SignInPanel(onClose: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var receiver by remember { mutableStateOf<PairingReceiver?>(null) }
    var status by remember { mutableStateOf("Preparing a private connection…") }
    var state by remember { mutableStateOf(CodeState.Waiting) }
    val signedIn by AccountStore.signedIn.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(attempt) { onDispose { receiver?.close() } }
    LaunchedEffect(attempt) {
        receiver = null; state = CodeState.Waiting
        status = "Preparing a private connection…"
        try {
            // Listens on Wi-Fi and Ethernet both; the code names the one the TV uses first, and the others for newer phones.
            val current = withContext(Dispatchers.IO) { PairingReceiver(addresses = { LanNetworks.ownAddresses(context) }) }
            receiver = current
            state = CodeState.Ready
            status = Tr.f(
                "On your phone, open NepTune, go to Settings, then Link a device, then TV, and scan this code. Both must be on the same Wi-Fi. It lasts two minutes. This TV is at {0}.",
                current.offer.host,
            )
            withContext(Dispatchers.IO) { current.receive { cookie -> AccountStore.signIn(cookie); true } }
            state = CodeState.Done
            status = "Signed in. Your YouTube Music account is on this TV."
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            state = CodeState.Expired
            status = signInProblem(e)
        } finally { receiver?.close(); receiver = null }
    }
    LaunchedEffect(signedIn, state) { if (state == CodeState.Done && signedIn) { delay(1_800); onClose() } }

    PhoneCodePanel(
        title = "Sign in with your phone",
        message = status,
        link = receiver?.offer?.link,
        state = state,
        onNewCode = { attempt++ },
        onClose = onClose,
    )
}

/** What the TV says when its sign-in code ended without a sign-in. */
internal fun signInProblem(e: Throwable): String = when ((e as? PairingFailed)?.reason) {
    PairingFailed.Reason.NO_NETWORK -> "This TV isn't connected to a home network. Connect it to Wi-Fi or Ethernet, then make a new code."
    PairingFailed.Reason.WRONG_CODE -> "A phone reached this TV, but with an older code. Make a new code and scan the one on the screen."
    PairingFailed.Reason.BAD_SESSION -> "A phone reached this TV, but its YouTube Music sign-in wasn't complete. Sign out and in again on the phone, then make a new code."
    PairingFailed.Reason.TOO_MANY_TRIES -> "Too many connections that weren't your phone. The code was closed to be safe. Make a new one."
    PairingFailed.Reason.STOPPED -> "The code was closed. Make a new one."
    // Expired, or anything else: no phone got through in time.
    else -> "No phone reached this TV in two minutes. Make a new code. If the phone said it couldn't reach the TV, connect both to the same Wi-Fi (not a guest network)."
}
