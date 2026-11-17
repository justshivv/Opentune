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

/** What the app shell needs to show of Connect: whether the devices sheet is open. */
object ConnectUi {
    private val _devicesOpen = MutableStateFlow(false)
    val devicesOpen = _devicesOpen.asStateFlow()
    fun openDevices() { _devicesOpen.value = true }
    fun closeDevices() { _devicesOpen.value = false }
}

@Composable
private fun formIcon(form: String): ImageVector =
    ImageVector.vectorResource(if (form == "web") AppIcons.Laptop else AppIcons.Smartphone)

/** The line a device's song shows under its name: "Song · Artist", or what it's doing instead. */
private fun doing(d: Device): String? {
    val s = d.state ?: return null
    if (s.track == null) return if (s.private) Tr.t("Playing something of its own") else null
    val what = listOf(s.track!!.title, s.track!!.artist).filter { it.isNotBlank() }.joinToString(" · ")
    return if (s.playing) what else Tr.f("Paused · {0}", what)
}

/**
 * The person's devices, to move playback to one of them. Opened from the
 * player and from the mini player of a song playing elsewhere.
 */
@Composable
fun DevicesSheet(onDismiss: () -> Unit, onSettings: () -> Unit) {
    val view by Connect.view.collectAsState()
    val group by Connect.group.collectAsState()
    val name by Connect.deviceName.collectAsState()
    FloatingCard(onDismiss = onDismiss, title = "Your devices", icon = ImageVector.vectorResource(AppIcons.Devices)) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            val v = view
            if (group == null || v == null) {
                Text(
                    "Play on your computer from this phone, and the other way round. Add OpenTune on the web to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                SheetButton("Add a device", onClick = { onDismiss(); onSettings() }, tone = SheetTone.Primary, icon = ImageVector.vectorResource(AppIcons.Add))
                return@Column
            }
            DeviceRow(
                icon = formIcon("phone"),
                name = name,
                line = Tr.t("This phone"),
                active = v.activeId == v.myId,
                onClick = { Connect.transferTo(v.myId); onDismiss() },
            )
            for (d in v.devices.sortedBy { it.name }) {
                DeviceRow(
                    icon = formIcon(d.form),
                    name = d.name.ifBlank { Tr.t("Another device") },
                    line = doing(d),
                    active = v.activeId == d.id,
                    onClick = { Connect.transferTo(d.id); onDismiss() },
                )
            }
            if (v.devices.isEmpty()) {
                Text(
                    if (v.relays == 0) "Connecting…" else "Open OpenTune on your computer to see it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            SheetButton("Add a device", onClick = { onDismiss(); onSettings() }, icon = ImageVector.vectorResource(AppIcons.Add))
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, line: String?, active: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(26.dp), tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (active) accent else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (active) Icon(ImageVector.vectorResource(AppIcons.GraphicEq), null, Modifier.size(20.dp), tint = accent)
    }
}

/** Settings › Your devices: pairing, this device's name, the group, and syncing it through the account. */
@Composable
fun ConnectScreen(contentPadding: PaddingValues, joining: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val enabled by Connect.enabled.collectAsState()
    val group by Connect.group.collectAsState()
    val name by Connect.deviceName.collectAsState()
    val view by Connect.view.collectAsState()
    val accountSync by Connect.accountSync.collectAsState()
    var editingName by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var offered by remember { mutableStateOf(joining?.let(ConnectGroup::parse)) }

    fun join(g: ConnectGroup) {
        Connect.join(g)
        Pills.info(Tr.t("This phone is one of your devices now"), context)
    }

    // A link from another device: straight in when this phone has no group, asked first when it has another.
    LaunchedEffect(Unit) {
        val g = offered ?: return@LaunchedEffect
        if (Connect.group.value == null || Connect.group.value == g) { join(g); offered = null }
    }
    offered?.let { g ->
        AlertDialog(
            onDismissRequest = { offered = null },
            title = { Text("Join these devices?") },
            text = { Text("This phone is already with other devices. Joining leaves them.") },
            confirmButton = { TextButton({ join(g); offered = null }) { Text("Join") } },
            dismissButton = { TextButton({ offered = null }) { Text("Cancel") } },
        )
    }
    if (scanning) com.opentune.ui.account.QrScannerDialog(onDismiss = { scanning = false }) { text ->
        scanning = false
        val g = ConnectGroup.parse(text)
        if (g == null) Pills.error(Tr.t("That isn't a devices code"), context)
        else if (group != null && group != g) offered = g
        else join(g)
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
                "See what plays on your computer from this phone and control it, or move the music from one to the other. Only your devices can read what they say to each other.",
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
                    "In the web player, open Settings › Your devices and scan its code here, or send it this link.",
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
                accountSync,
                { Connect.setAccountSync(it) },
                summary = "Devices signed in to the same account join by themselves. Keeps a private, empty playlist named OpenTune Connect in your library.",
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
                SettingRow(d.name.ifBlank { Tr.t("Another device") }, summary = doing(d), icon = formIcon(d.form))
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
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OpenTune", text))
    Pills.info(Tr.t("Copied"), context)
}
