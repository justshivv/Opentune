package com.opentune.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.opentune.data.Pills
import com.opentune.data.account.AccountStore
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.rememberHaptics

/** The other screens OpenTune can reach from this one. */
internal enum class LinkTarget(val label: String, val icon: ImageVector) {
    TV("TV", Icons.Rounded.Tv),
    COMPUTER("Computer", Icons.Rounded.Laptop),
    SPEAKER("Speaker", Icons.Rounded.Speaker),
    FRIEND("Friend", Icons.Rounded.Groups),
    WATCH("Watch", Icons.Rounded.Watch),
}

/**
 * Link a device: one place for everything that reaches past this phone.
 * A row of devices at the top picks one; under it, that device's steps in
 * order, each with the button that does it. Signing a TV in, the web player,
 * casting, listening together and the watch app all start from here.
 */
@Composable
fun LinkDeviceScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onTogether: () -> Unit,
) {
    val context = LocalContext.current
    // On a TV the page opens on the TV, with signing this one in first.
    val onTv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    var target by rememberSaveable { mutableStateOf(LinkTarget.TV) }
    var receiving by remember { mutableStateOf(false) }
    var casting by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    // The TV's code read here goes the same way as one scanned with the phone's camera app:
    // a sign-in code asks to approve, any other OpenTune link opens.
    if (scanning) com.opentune.ui.account.QrScannerDialog(onDismiss = { scanning = false }) { text ->
        scanning = false
        val view = Intent(Intent.ACTION_VIEW, text.toUri())
        if (!com.opentune.ui.account.PairingLinks.receive(view)) {
            if (com.opentune.ui.Links.parse(text) != null || text.startsWith("opentune://")) com.opentune.ui.Links.receive(view)
            else Pills.error("That isn't an OpenTune code", context)
        }
    }
    val signedIn by AccountStore.signedIn.collectAsState()
    if (receiving) com.opentune.ui.account.ReceiveSessionSheet { receiving = false }
    if (casting) com.opentune.ui.cast.CastSheet { casting = false }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            .onFailure { Pills.error("No app here can open that link", context) }
    }
    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { context.startActivity(Intent.createChooser(send, "Send the link")) }
    }
    fun camera() {
        // Scanned here, in the app; a phone without a camera can still scan with any other phone's camera app.
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) scanning = true
        else Pills.error("This device has no camera. Scan the code with the phone's camera app", context)
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Link a device", onBack = onBack) }
        item {
            Text(
                "Sign in your TV, open OpenTune on a computer, play on a speaker or listen with a friend.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            )
        }
        item { DevicePicker(target) { target = it } }
        item {
            AnimatedContent(
                targetState = target,
                transitionSpec = {
                    val way = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 420f)) { it / 6 * way } + fadeIn(tween(220, delayMillis = 60)))
                        .togetherWith(slideOutHorizontally(tween(180)) { -it / 8 * way } + fadeOut(tween(140)))
                },
                label = "device",
            ) { shown ->
                Column(Modifier.padding(top = 20.dp)) {
                    when (shown) {
                        LinkTarget.TV -> TvSteps(onTv, signedIn, onSignIn, ::camera, { receiving = true }, ::open, ::share)
                        LinkTarget.COMPUTER -> DeviceCard(
                            "In your browser",
                            "Browse, search and listen on a computer. Your library there stays in that browser.",
                        ) {
                            Step(1, "Open the web player", "On the computer, go to the address below, or send it there from this phone.") {
                                Text(
                                    WEB_PLAYER.removePrefix("https://"),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                                Buttons(
                                    { SheetButton("Share", onClick = { share(WEB_PLAYER) }, icon = Icons.Rounded.Share, tone = SheetTone.Primary) },
                                    { SheetButton("Open here", onClick = { open(WEB_PLAYER) }, icon = Icons.AutoMirrored.Rounded.OpenInNew) },
                                )
                            }
                            Step(2, "Add the companion extension", "It lets the page reach YouTube Music from your own browser. Choose Get the Extension in the web player.", last = true)
                            Note(Icons.Rounded.Lock, "A preview. What you save there stays in that browser; it doesn't sync with this phone.")
                        }
                        LinkTarget.SPEAKER -> DeviceCard(
                            "On a speaker or smart TV",
                            "Send what's playing to a Chromecast, a Google speaker or a TV that plays over the network.",
                        ) {
                            Step(1, "Join the same Wi-Fi", "The speaker or TV and this phone need to be on the same network.")
                            Step(2, "Pick it from the list", "OpenTune looks for devices nearby. The phone stays the remote.", last = true) {
                                Buttons({ SheetButton("Find devices", onClick = { casting = true }, icon = Icons.Rounded.Cast, tone = SheetTone.Primary) })
                            }
                        }
                        LinkTarget.FRIEND -> DeviceCard(
                            "With a friend",
                            "Listen to the same song at the same moment on two phones, wherever each of you is, and chat or talk as it plays.",
                        ) {
                            Step(1, "Start a room", "You get a code and a link to send.")
                            Step(2, "Your friend joins", "They open the link, or type the code in OpenTune.", last = true) {
                                Buttons({ SheetButton("Listen together", onClick = onTogether, icon = Icons.Rounded.Groups, tone = SheetTone.Primary) })
                            }
                        }
                        LinkTarget.WATCH -> DeviceCard(
                            "On your watch",
                            "A separate Wear OS app plays on the watch itself, over its Wi-Fi or LTE and Bluetooth headphones, with no phone needed.",
                        ) {
                            Step(1, "Build the watch app", "It isn't in the releases yet. It's the wear folder in the source code.", last = true) {
                                Buttons({ SheetButton("Source code", onClick = { open(SOURCE) }, icon = Icons.Rounded.Code) })
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** The steps for a TV: the phone signs the TV in, or (on the TV) the TV shows its code. */
@Composable
private fun TvSteps(
    onTv: Boolean,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    camera: () -> Unit,
    showCode: () -> Unit,
    open: (String) -> Unit,
    share: (String) -> Unit,
) {
    if (onTv) {
        DeviceCard("Sign this TV in", "Use the YouTube Music account on your phone. No typing with the remote.") {
            Step(1, "Show a code here", "A QR code comes up on this screen.") {
                Buttons({ SheetButton("Show the code", onClick = showCode, icon = Icons.Rounded.QrCode2, tone = SheetTone.Primary) })
            }
            Step(2, "Scan it with your phone", "Use the phone's camera. OpenTune opens on the phone and asks you to approve.", last = true)
            Note(Icons.Rounded.Lock, "Both on the same Wi-Fi. The code lasts two minutes, and the session goes straight from the phone to this TV, encrypted.")
        }
        return
    }
    DeviceCard("Android TV", "Sign the TV in with this phone's YouTube Music account. No typing with the remote.") {
        Step(1, "Install OpenTune on the TV", "The same app as this one. Get the universal APK from the latest release.") {
            Buttons(
                { SheetButton("Get the app", onClick = { open(RELEASES) }, icon = Icons.Rounded.Download) },
                { SheetButton("Share", onClick = { share(RELEASES) }, icon = Icons.Rounded.Share) },
            )
        }
        Step(2, "Show the code on the TV", "On the TV, open Settings, then Link a device, and choose Show the code.")
        Step(3, "Scan it with this phone", "Scan it right here in OpenTune, or with the phone's own camera app, then approve on this phone.", last = true) {
            if (signedIn) {
                Buttons({ SheetButton("Open the camera", onClick = camera, icon = Icons.Rounded.CameraAlt, tone = SheetTone.Primary) })
            } else {
                Text(
                    "Sign in to YouTube Music on this phone first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Buttons({ SheetButton("Sign in", onClick = onSignIn, tone = SheetTone.Primary) })
            }
        }
        Buttons({ SheetButton("TV information", onClick = { open(com.opentune.data.config.RemoteConfig.current.tv) }, icon = Icons.AutoMirrored.Rounded.OpenInNew) })
        Note(Icons.Rounded.Lock, "Both on the same Wi-Fi. The code lasts two minutes, and the session goes straight to the TV, encrypted. Each keeps its own library.")
    }
    DeviceCard("Is this the TV?", "Show a code here and scan it from a signed-in phone.", compact = true) {
        Buttons({ SheetButton("Show a sign-in code", onClick = showCode, icon = Icons.Rounded.QrCode2) })
    }
}

/** The row of devices, one tile each; the chosen one is filled. */
@Composable
private fun DevicePicker(selected: LinkTarget, onPick: (LinkTarget) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinkTarget.entries.forEach { t ->
            DeviceTile(t, t == selected, Modifier.weight(1f)) { onPick(t) }
        }
    }
}

@Composable
private fun DeviceTile(target: LinkTarget, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "press")
    val fill by animateColorAsState(if (selected) scheme.primary else scheme.surfaceContainerHigh, tween(220), label = "fill")
    val ink by animateColorAsState(if (selected) scheme.onPrimary else scheme.onSurface, tween(220), label = "ink")
    Column(
        modifier
            .graphicsLayer { scaleX = press; scaleY = press }
            .clip(RoundedCornerShape(22.dp))
            .background(fill)
            .clickable(interaction, indication = null, role = Role.Tab) { haptics.tick(); onClick() }
            .semantics { this.selected = selected }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(target.icon, null, Modifier.size(26.dp), tint = ink)
        Spacer(Modifier.height(6.dp))
        Text(target.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** A device's card: a title, a line on what it does, then its steps. */
@Composable
private fun DeviceCard(title: String, about: String, compact: Boolean = false, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = if (compact) 18.dp else 22.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 4.dp),
    ) {
        Text(title, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!compact) Spacer(Modifier.height(14.dp))
        content()
    }
}

/** One numbered step, joined to the next by a line down the left. */
@Composable
private fun Step(n: Int, title: String, body: String, last: Boolean = false, extra: (@Composable () -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(scheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("$n", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = scheme.primary)
            }
            if (!last) Box(Modifier.width(2.dp).weight(1f).padding(vertical = 4.dp).clip(CircleShape).background(scheme.outlineVariant))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = 4.dp, bottom = if (last) 4.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            extra?.invoke()
        }
    }
}

/** One or two buttons side by side. */
@Composable
private fun Buttons(vararg buttons: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { b -> Box(Modifier.weight(1f)) { b() } }
    }
}

/** A small note at the bottom of a card. */
@Composable
private fun Note(icon: ImageVector, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val WEB_PLAYER get() = com.opentune.data.config.RemoteConfig.current.web
private const val RELEASES = "https://github.com/justshivv/Opentune/releases/latest"
private const val SOURCE = "https://github.com/justshivv/Opentune/tree/main/wear"
