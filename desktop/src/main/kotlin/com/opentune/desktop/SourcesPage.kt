package com.opentune.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.opentune.data.settings.AppSettings
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Audio sources, as the phone's Add-ons page and the web player's Audio
 * sources: JioSaavn, the add-ons OpenTune publishes and your own, and
 * verifying this computer for OpenTune's own source. Laid out in the web's
 * grouped rows: the two switches, then the add-ons in their order, then
 * adding one by its address.
 */
@Composable
internal fun SourcesSection() {
    val sources by Addons.sources.collectAsState()
    val saavn by Addons.jioSaavn.collectAsState()
    val lossless by Addons.lossless.collectAsState()
    val status by Addons.status.collectAsState()
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Addons.syncPublished(force = true) }
    Sections("Audio sources") {
        Text(
            "Songs start from YouTube straight away. These are asked at the same time, and the best answer takes over at the same point in the song.",
            style = rowDetailStyle().copy(lineHeight = 18.sp), color = inkSecondary(),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
        RowsCard {
            ToggleRow(
                "JioSaavn", "320 kbps AAC, when no add-on has the song in FLAC", saavn, Addons::setJioSaavn,
                leading = { LetterTile("J", Brand.JioSaavn, Brand.JioSaavnInk) },
            )
            ToggleRow(
                "Lossless from add-ons",
                "Verified FLAC, sent to your sound card at its own sample rate and bit depth. The equalizer and speed don't change it.",
                lossless, Addons::setLossless,
                leading = { IconTile(Icons.Rounded.GraphicEq, Color(0xFFE5483A)) },
            )
        }
        // The add-ons only matter with lossless on; they fold away with it (or cut, with smooth motion off).
        AnimatedVisibility(
            lossless,
            enter = if (still) fadeIn(snap()) else expandVertically() + fadeIn(),
            exit = if (still) fadeOut(snap()) else shrinkVertically() + fadeOut(),
        ) {
            Column {
                CardHeading("Lossless add-ons")
                RowsCard {
                    if (sources.isEmpty()) NoteRow("No add-ons yet. OpenTune's own appear here when they're published; you can add others below.")
                    sources.forEachIndexed { i, source -> SourceRow(source, first = i == 0, last = i == sources.lastIndex) }
                    if (status.isNotBlank()) SettingRow("Last song", detail = status)
                }
                if (sources.size > 1) CardFooter("Asked in this order: move one up or down with its arrows.")
                CardHeading("Add a source")
                RowsCard {
                    AddSource()
                    ButtonsRow { QuietPill("Check for published add-ons", { scope.launch { Addons.syncPublished(force = true) } }) }
                }
                if (sources.any { it.enabled && !it.ownerOff && AddonPairing.protects(it.url.replace("manifest.json", "search")) }) {
                    CardHeading("OpenTune's own source")
                    RowsCard { VerifyComputer() }
                }
            }
        }
    }
}

/** An add-on as the web's source list has it: its square, its name and where it's from, the arrows that move it, its switch. */
@Composable
private fun SourceRow(source: Addons.Source, first: Boolean, last: Boolean) {
    ToggleRow(
        source.name,
        when {
            source.ownerOff -> "Turned off by OpenTune for now"
            source.admin -> "Added by OpenTune"
            else -> runCatching { java.net.URI(source.url).host }.getOrDefault(source.url)
        },
        source.enabled && !source.ownerOff,
        { Addons.setEnabled(source.id, it) },
        enabled = !source.ownerOff,
        leading = { IconTile(Icons.Rounded.Extension, fillQuaternary(), MaterialTheme.colorScheme.onSurface) },
        extra = {
            SmallIconButton(Icons.Rounded.KeyboardArrowUp, "Ask ${source.name} earlier", enabled = !first) { Addons.move(source.id, -1) }
            SmallIconButton(Icons.Rounded.KeyboardArrowDown, "Ask ${source.name} later", enabled = !last) { Addons.move(source.id, 1) }
            if (!source.admin) SmallIconButton(Icons.Rounded.Close, "Remove ${source.name}") { Addons.remove(source.id) }
        },
    )
}

/** An add-on's address and the Add button beside it, as the web's Add a source card. */
@Composable
private fun AddSource() {
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.hairline(TEXT_INSET).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WebField(
                address, { address = it; problem = null }, "Add-on address",
                modifier = Modifier.weight(1f), placeholder = "https://example.com/manifest.json", error = problem != null,
            )
            KeyPill(
                if (busy) "Adding…" else "Add",
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
            )
        }
        problem?.let { Text(it, style = rowDetailStyle(), color = MaterialTheme.colorScheme.error) }
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
    NoteRow("It plays only for devices the official app vouches for. Approve this computer once from OpenTune on your Android phone or TV; access lasts up to 12 hours.")
    val now = System.currentTimeMillis()
    val waiting = pair
    when {
        waiting != null -> Row(
            Modifier.hairline(TEXT_INSET).fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            QrCode(waiting.link, 168.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Code", style = rowDetailStyle(), color = inkSecondary())
                Text(waiting.code, fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text(
                    "Scan this with your phone's camera, then allow it in OpenTune when the app shows this same code.",
                    style = rowTitleStyle(), modifier = Modifier.widthIn(max = 320.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietPill("Copy link", { clipboard.setText(AnnotatedString(waiting.link)); message = "Link copied. Open it on your phone." })
                    QuietPill("Cancel", { pair = null; message = "Cancelled. The request expires by itself in five minutes." })
                }
                Text("Waiting for your phone…", style = rowDetailStyle(), color = keyColour())
            }
        }
        until > now -> SettingRow("Verified until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))}") {
            LiveDot()
            RowAction("Disconnect", { AddonPairing.disconnect(); message = "This computer's key and session were removed." })
        }
        else -> ButtonsRow {
            KeyPill(
                if (busy) "Starting…" else if (until > 0) "Verify again" else "Verify this computer",
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        runCatching { AddonPairing.start() }.onSuccess { pair = it }.onFailure { message = it.message ?: "Couldn't start" }
                        busy = false
                    }
                },
                enabled = !busy,
            )
        }
    }
    message?.let { NoteRow(it) }
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
