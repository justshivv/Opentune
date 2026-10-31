package com.opentune.ui.browse

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Link
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import com.opentune.data.covers.ArtistSignature
import com.opentune.data.covers.ArtistSignatures
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.launch

/**
 * The artist's name over their photo: their handwritten signature in white
 * with "Signed by" and the name under it, when the signature service has one
 * (Settings → Appearance → Artist signatures), and the name in big type
 * otherwise, or until the signature has loaded.
 */
@Composable
internal fun ArtistNameOrSignature(name: String) {
    val ui by AppSettings.ui.collectAsState()
    val api = ui.signatureApi
    var signature by remember(name, api) { mutableStateOf(if (ui.artistSignatures) ArtistSignatures.cached(name, api) else null) }
    var drawn by remember(name, api) { mutableStateOf(false) }
    var failed by remember(name, api) { mutableStateOf(false) }
    LaunchedEffect(name, api, ui.artistSignatures) {
        signature = if (ui.artistSignatures) ArtistSignatures.signatureFor(name, api) else null
    }
    val sig = signature?.takeIf { !failed }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (sig != null) {
            // Loaded off screen first, so the name doesn't blink out for an empty space.
            SignatureImage(sig, name, onDrawn = { drawn = true }, onFailed = { failed = true }, shown = drawn)
        }
        AnimatedContent(sig != null && drawn, transitionSpec = { fadeIn(tween(420)) togetherWith fadeOut(tween(200)) }, label = "signed") { signed ->
            if (signed) {
                Text(
                    buildAnnotatedString {
                        append("Signed by ")
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            } else {
                Text(
                    name,
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 44.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun SignatureImage(sig: ArtistSignature, name: String, onDrawn: () -> Unit, onFailed: () -> Unit, shown: Boolean) {
    val context = LocalContext.current
    val request = remember(sig.imageUrl) {
        ImageRequest.Builder(context)
            .data(sig.imageUrl)
            // A signature is wide and thin; it never needs more than this across.
            .size(Size(1200, 600))
            .allowHardware(false)
            .transformations(WhiteInk)
            .build()
    }
    // Tall signatures stay short enough to leave the photo showing; wide ones fill the width.
    val height = if (sig.aspect >= 1.4f) 120.dp else 150.dp
    AsyncImage(
        request,
        contentDescription = "$name's signature",
        contentScale = ContentScale.Fit,
        onSuccess = { onDrawn() },
        onError = { onFailed() },
        alpha = if (shown) 1f else 0f,
        modifier = Modifier.padding(horizontal = 40.dp).widthIn(max = 420.dp).fillMaxWidth().height(if (shown) height else 1.dp),
    )
}

/** A signature as white ink on clear, the paper of a scan dropped away ([ArtistSignatures.whiteInk]). */
internal object WhiteInk : Transformation() {
    override val cacheKey = "opentune.whiteInk.v2"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        val px = IntArray(w * h)
        input.getPixels(px, 0, w, 0, 0, w, h)
        return Bitmap.createBitmap(ArtistSignatures.whiteInk(px, w, h), w, h, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Settings' artist-signature rows: the switch, and the service's address,
 * checked against its health address before it's kept. Blank goes back to
 * OpenTune's default service.
 */
@Composable
internal fun ArtistSignatureSettings() {
    val ui by AppSettings.ui.collectAsState()
    var editing by remember { mutableStateOf(false) }
    com.opentune.ui.components.ToggleRow(
        "Artist signatures", ui.artistSignatures, { v -> AppSettings.updateUi { it.copy(artistSignatures = v) } },
        summary = "An artist's handwritten signature over their photo, in place of their name, when there's one",
        icon = androidx.compose.material.icons.Icons.Rounded.Draw,
    )
    if (ui.artistSignatures) {
        com.opentune.ui.components.SettingRow(
            "Signature service",
            summary = ui.signatureApi.removePrefix("https://").removePrefix("http://"),
            icon = androidx.compose.material.icons.Icons.Rounded.Link,
            onClick = { editing = true },
        )
    }
    if (editing) {
        var address by remember { mutableStateOf(ui.signatureApi) }
        var problem by remember { mutableStateOf<String?>(null) }
        var checking by remember { mutableStateOf(false) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!checking) editing = false },
            title = {
                com.opentune.ui.components.FrostBehind(enabled = !ui.reduceBlur)
                Text("Signature service")
            },
            text = {
                Column {
                    Text("An artist-signature service, asked for each artist's signature by name.", style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.OutlinedTextField(
                        address, { address = it; problem = null },
                        singleLine = true,
                        placeholder = { Text(ArtistSignatures.DEFAULT_API) },
                        isError = problem != null,
                        supportingText = problem?.let { p -> { Text(p) } },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = !checking,
                    onClick = {
                        val chosen = address.trim().ifEmpty { ArtistSignatures.DEFAULT_API }
                        val base = ArtistSignatures.normalize(chosen)
                        if (base == null) {
                            problem = "Use an address starting with https://"
                            return@TextButton
                        }
                        checking = true
                        scope.launch {
                            val ok = ArtistSignatures.reachable(base)
                            checking = false
                            if (ok) {
                                ArtistSignatures.clear()
                                AppSettings.updateUi { it.copy(signatureApi = base) }
                                editing = false
                            } else {
                                problem = "That service didn't answer"
                            }
                        }
                    },
                ) { Text(if (checking) "Checking…" else "Save") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { editing = false }, enabled = !checking) { Text("Cancel") } },
        )
    }
}
