package com.opentune.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Audio sources, as the phone's Add-ons page and the web player's Audio
 * sources: JioSaavn, the add-ons OpenTune publishes and your own, and
 * verifying this computer for OpenTune's own source.
 */
@Composable
internal fun SourcesSection() {
    val sources by Addons.sources.collectAsState()
    val saavn by Addons.jioSaavn.collectAsState()
    val lossless by Addons.lossless.collectAsState()
    val status by Addons.status.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Addons.syncPublished(force = true) }
    SettingsCard("Audio sources") {
        Text(
            "Songs start from YouTube straight away. These are asked at the same time, and the best answer takes over at the same point in the song.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("JioSaavn", "320 kbps AAC, when no add-on has the song in FLAC", saavn, Addons::setJioSaavn)
        SwitchRow(
            "Lossless from add-ons",
            "Verified FLAC, sent to your sound card at its own sample rate and bit depth. The equalizer and speed don't change it.",
            lossless, Addons::setLossless,
        )
        AnimatedVisibility(lossless, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sources.isEmpty()) {
                    Text("No add-ons yet. OpenTune's own appear here when they're published; you can add others below.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                sources.forEachIndexed { i, source -> SourceRow(source, first = i == 0, last = i == sources.lastIndex) }
                AddSource()
                if (sources.any { it.enabled && !it.ownerOff && AddonPairing.protects(it.url.replace("manifest.json", "search")) }) VerifyComputer()
                if (status.isNotBlank()) {
                    Text("Last song", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { scope.launch { Addons.syncPublished(force = true) } }) { Text("Check for published add-ons") }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SourceRow(source: Addons.Source, first: Boolean, last: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surfaceContainerHigh).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    source.ownerOff -> "Turned off by OpenTune for now"
                    source.admin -> "Added by OpenTune"
                    else -> runCatching { java.net.URI(source.url).host }.getOrDefault(source.url)
                },
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { Addons.move(source.id, -1) }, enabled = !first) { Icon(Icons.Rounded.KeyboardArrowUp, "Ask earlier") }
        IconButton(onClick = { Addons.move(source.id, 1) }, enabled = !last) { Icon(Icons.Rounded.KeyboardArrowDown, "Ask later") }
        if (!source.admin) IconButton(onClick = { Addons.remove(source.id) }) { Icon(Icons.Rounded.Close, "Remove") }
        Switch(source.enabled && !source.ownerOff, { Addons.setEnabled(source.id, it) }, enabled = !source.ownerOff)
    }
}

@Composable
private fun AddSource() {
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            address, { address = it; problem = null }, singleLine = true, modifier = Modifier.weight(1f),
            label = { Text("Add-on address") }, placeholder = { Text("https://example.com/manifest.json") },
            isError = problem != null, supportingText = problem?.let { { Text(it) } },
        )
        Button(
            onClick = {
                busy = true
                scope.launch {
                    runCatching { Addons.add(address) }
                        .onSuccess { address = "" }
                        .onFailure { problem = it.message ?: "Couldn't add it" }
                    busy = false
                }
            },
            enabled = !busy && address.isNotBlank(),
        ) { Text(if (busy) "Adding…" else "Add") }
    }
}

/** Approving this computer from the phone, for OpenTune's own add-on: a QR code the phone's camera opens in the app. */
@Composable
private fun VerifyComputer() {
    val until by AddonPairing.until.collectAsState()
    var pair by remember { mutableStateOf<AddonPairing.Pairing?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(pair) {
        val waiting = pair ?: return@LaunchedEffect
        while (true) {
            delay(2_000)
            val done = runCatching { AddonPairing.poll(waiting) }.getOrElse { message = it.message; pair = null; return@LaunchedEffect }
            if (done) {
                pair = null
                message = "This computer is verified."
                return@LaunchedEffect
            }
        }
    }
    Surface(color = scheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("OpenTune's own source", style = MaterialTheme.typography.titleMedium)
            Text(
                "It plays only for devices the official app vouches for. Approve this computer once from OpenTune on your Android phone or TV; access lasts up to 12 hours.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
            )
            val now = System.currentTimeMillis()
            val waiting = pair
            when {
                waiting != null -> Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrCode(waiting.link, 168.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Code", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        Text(waiting.code, fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                        Text(
                            "Scan this with your phone's camera, then allow it in OpenTune when the app shows this same code.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(320.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(waiting.link)); message = "Link copied. Open it on your phone." }) { Text("Copy link") }
                            TextButton(onClick = { pair = null; message = "Cancelled. The request expires by itself in five minutes." }) { Text("Cancel") }
                        }
                        Text("Waiting for your phone…", style = MaterialTheme.typography.bodySmall, color = scheme.primary)
                    }
                }
                until > now -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Verified until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { AddonPairing.disconnect(); message = "This computer's key and session were removed." }) { Text("Disconnect") }
                }
                else -> Button(
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            runCatching { AddonPairing.start() }.onSuccess { pair = it }.onFailure { message = it.message ?: "Couldn't start" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Text(if (busy) "Starting…" else if (until > 0) "Verify again" else "Verify this computer") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
        }
    }
}

/** [text] as a QR code, dark on white with a quiet border, as phone cameras read best. */
@Composable
internal fun QrCode(text: String, size: Dp) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0)) }
    Canvas(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) {
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
