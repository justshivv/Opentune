package com.opentune.ui.connect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.Pills
import com.opentune.data.connect.Connect
import com.opentune.data.connect.ConnectGroup
import com.opentune.data.connect.Device
import com.opentune.i18n.Tr
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SettingRow
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.ToggleRow
import com.opentune.ui.theme.AppIcons
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text
import com.opentune.ui.together.QrCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Asked once, after signing in to YouTube: may Connect keep its key in the
 * account? Until the answer is yes, nothing is created there.
 */
@Composable
fun AccountSyncAsk() {
    val answer by Connect.accountSync.collectAsState()
    val signedIn by com.opentune.data.account.AccountStore.signedIn.collectAsState()
    val enabled by Connect.enabled.collectAsState()
    if (answer != null || !signedIn || !enabled) return
    com.opentune.ui.components.FloatingDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { Icon(ImageVector.vectorResource(AppIcons.Sync), null) },
        title = { Text("Find your devices through YouTube?") },
        text = { Text("NepTune keeps a private playlist in your YouTube account that holds a key, so your devices find each other.") },
        confirmButton = { com.opentune.ui.components.SheetButton("Turn on", onClick = { Connect.setAccountSync(true) }) },
        dismissButton = { com.opentune.ui.components.SheetButton("Not now", onClick = { Connect.setAccountSync(false) }, closes = true) },
    )
}

/** A device's icon by its form: "web", "watch" or "phone" (and the phone's for any other). */
@Composable
internal fun formIcon(form: String): ImageVector =
    ImageVector.vectorResource(when (form) { "web" -> AppIcons.Laptop; "watch" -> AppIcons.Watch; else -> AppIcons.Smartphone })

/** The line a device's song shows under its name: "Song · Artist", or what it's doing instead. */
internal fun doing(d: Device): String? {
    val s = d.state ?: return null
    if (s.track == null) return if (s.private) Tr.t("Playing something of its own") else null
    val what = listOf(s.track!!.title, s.track!!.artist).filter { it.isNotBlank() }.joinToString(" · ")
    return if (s.playing) what else Tr.f("Paused · {0}", what)
}

/** Settings › Your devices: pairing, this device's name, the group, and syncing it through the account. */
@Composable
fun ConnectScreen(contentPadding: PaddingValues, joining: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val enabled by Connect.enabled.collectAsState()
    val group by Connect.group.collectAsState()
    val name by Connect.deviceName.collectAsState()
    val view by Connect.view.collectAsState()
    val watches by Connect.watches.collectAsState()
    val waiting by Connect.waiting.collectAsState()
    val accountSync by Connect.accountSync.collectAsState()
    var editingName by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }

    fun join(g: ConnectGroup) {
        val already = Connect.group.value == g
        Connect.pair(g)
        if (!already) Pills.info(Tr.t("This phone is one of your devices now"), context)
    }

    // A link from another device: straight in. This phone's other devices come along.
    LaunchedEffect(joining) { joining?.let(ConnectGroup::parse)?.let(::join) }
    if (scanning) com.opentune.ui.account.QrScannerDialog(onDismiss = { scanning = false }) { text ->
        scanning = false
        val watch = com.opentune.data.connect.WatchPairingKey.parse(text)
        val g = ConnectGroup.parse(text)
        if (watch != null) com.opentune.ui.account.PairingLinks.watch.value = watch
        else if (g == null) Pills.error(Tr.t("That isn't a devices code"), context) else join(g)
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Disconnect all devices?") },
        text = { Text("Every other device stops seeing this one until you add it again.") },
        confirmButton = { TextButton({ Connect.disconnectAll(); confirmReset = false }) { Text("Disconnect") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } },
    )
    if (editingName) {
        var draft by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("This device's name") },
            text = { OutlinedTextField(draft, { draft = it.take(40) }, singleLine = true) },
            confirmButton = { TextButton({ Connect.setDeviceName(draft); editingName = false }) { Text("Save") } },
            dismissButton = { TextButton({ editingName = false }) { Text("Cancel") } },
        )
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Your devices", onBack = onBack) }
        item {
            Text(
                "See what plays on your computer or watch from this phone and control it, or move the music from one to the other. Only your devices can read what they say to each other.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            )
        }
        item { ToggleRow("Connect to your devices", enabled, { Connect.setEnabled(it) }, icon = ImageVector.vectorResource(AppIcons.Devices)) }
        item { SettingRow("This device's name", summary = name, icon = ImageVector.vectorResource(AppIcons.Smartphone), onClick = { editingName = true }) }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Add a device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "In the web player, open Settings › Your devices and scan its code here, or send it this link. On a watch, choose Pair with phone and scan the code it shows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetButton("Scan a code", onClick = {
                        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) scanning = true
                        else Pills.error(Tr.t("This device has no camera"), context)
                    }, tone = SheetTone.Primary, icon = ImageVector.vectorResource(AppIcons.QrCodeScanner), modifier = Modifier.weight(1f))
                    SheetButton("Send link", onClick = { share(context, Connect.ensureGroup().webLink) }, icon = ImageVector.vectorResource(AppIcons.Share), modifier = Modifier.weight(1f))
                }
                group?.let { g ->
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Or scan this from another phone", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            SheetButton("Copy link", onClick = { copy(context, g.webLink) }, icon = ImageVector.vectorResource(AppIcons.ContentCopy))
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.size(120.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(10.dp)) {
                            QrCode(g.appLink, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        item {
            ToggleRow(
                "Sync through my YouTube account",
                accountSync == true,
                { Connect.setAccountSync(it) },
                summary = "Devices signed in to the same account join by themselves. Keeps a private, empty playlist named NepTune Connect in your library.",
                icon = ImageVector.vectorResource(AppIcons.Sync),
            )
        }
        val devices = view?.devices.orEmpty()
        if (group != null) {
            item {
                Text(
                    if (devices.isEmpty()) Tr.t("No other device is here right now") else Tr.t("Here now"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(devices.size) { i ->
                val d = devices[i]
                val sent = waiting[d.id]?.let { Tr.f("{0} sent to download, on the way", it) }
                SettingRow(d.name.ifBlank { Tr.t("Another device") }, summary = listOfNotNull(doing(d), sent).joinToString(" · ").ifBlank { null }, icon = formIcon(d.form))
            }
            // Watches that aren't here now: what's waiting for them to come online.
            val away = watches.filter { w -> devices.none { it.id == w.id } }
            items(away.size) { i ->
                val w = away[i]
                SettingRow(
                    w.name.ifBlank { Tr.t("Watch") },
                    summary = waiting[w.id]?.let { Tr.f("Away · {0} waiting to download once it's online", it) } ?: Tr.t("Away"),
                    icon = formIcon("watch"),
                )
            }
            item {
                SettingRow(
                    "Disconnect all devices",
                    summary = "A new key: the others need adding again.",
                    icon = ImageVector.vectorResource(AppIcons.Logout),
                    onClick = { confirmReset = true },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, Tr.t("Send the link"))) }
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NepTune", text))
    Pills.info(Tr.t("Copied"), context)
}
