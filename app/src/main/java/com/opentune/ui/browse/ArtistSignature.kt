package com.opentune.ui.browse

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
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
    // The vector when the signature came as one, drawn at the size it's shown; Wikimedia's
    // raster of it if the vector won't load.
    var vector by remember(sig) { mutableStateOf(sig.svgUrl != null) }
    val request = remember(sig, vector) {
        ImageRequest.Builder(context)
            .data(if (vector) sig.svgUrl else sig.imageUrl)
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
        contentDescription = com.opentune.i18n.Tr.f("{0}'s signature", name),
        contentScale = ContentScale.Fit,
        onSuccess = { onDrawn() },
        onError = { if (vector) vector = false else onFailed() },
        alpha = if (shown) 1f else 0f,
        modifier = Modifier.padding(horizontal = 40.dp).widthIn(max = 420.dp).fillMaxWidth().height(if (shown) height else 1.dp),
    )
}

/** A signature as white ink on clear, the paper of a scan dropped away ([ArtistSignatures.whiteInk]). */
internal object WhiteInk : Transformation() {
    override val cacheKey = "opentune.whiteInk.v3"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        val px = IntArray(w * h)
        input.getPixels(px, 0, w, 0, 0, w, h)
        // A photograph, not writing: failing here leaves the artist's name in its place.
        val ink = ArtistSignatures.whiteInk(px, w, h) ?: throw IllegalStateException("Not a signature")
        return Bitmap.createBitmap(ink, w, h, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Settings' artist-signature rows: the switch, and the service's address,
 * checked against its health address before it's kept. Blank goes back to
 * OpenTune's default service.
 */
@Composable
internal fun ArtistSignatureSettings(startEditing: Boolean = false) {
    val ui by AppSettings.ui.collectAsState()
    var editing by remember { mutableStateOf(startEditing) }
    com.opentune.ui.components.ToggleRow(
        "Artist signatures", ui.artistSignatures, { v -> AppSettings.updateUi { it.copy(artistSignatures = v) } },
        summary = "An artist's handwritten signature over their photo, in place of their name, when there's one",
        icon = androidx.compose.material.icons.Icons.Rounded.Draw,
        beta = true,
    )
    if (ui.artistSignatures) {
        com.opentune.ui.components.SettingRow(
            "Signature service",
            summary = ArtistSignatures.shown(ui.signatureApi).removePrefix("https://").removePrefix("http://"),
            icon = androidx.compose.material.icons.Icons.Rounded.Link,
            onClick = { editing = true },
        )
    }
    if (editing) {
        var address by remember { mutableStateOf(ArtistSignatures.shown(ui.signatureApi)) }
        var problem by remember { mutableStateOf<String?>(null) }
        var checking by remember { mutableStateOf(false) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        fun save() {
            val chosen = address.trim().ifEmpty { ArtistSignatures.DEFAULT_API }
            val base = ArtistSignatures.normalize(chosen)
            if (base == null) {
                problem = "Use an address starting with https://"
                return
            }
            checking = true
            problem = null
            scope.launch {
                val ok = ArtistSignatures.reachable(base)
                checking = false
                if (ok) {
                    ArtistSignatures.clear()
                    AppSettings.updateUi { it.copy(signatureApi = base) }
                    editing = false
                } else {
                    problem = "That service didn't answer. Check the address, or use the standard one."
                }
            }
        }
        com.opentune.ui.components.LinkEntryCard(
            title = "Signature service",
            description = "The service that draws each artist's signature, asked by the artist's name. Any service that answers the same way works.",
            input = address,
            onInput = { address = it.take(2048); problem = null },
            working = checking,
            error = problem,
            hint = "HTTPS only. Leave it empty for the standard service",
            workingLine = "Checking that it answers…",
            confirm = "Save", confirming = "Checking…",
            onConfirm = ::save,
            onDismiss = { if (!checking) editing = false },
            icon = androidx.compose.material.icons.Icons.Rounded.Draw,
            blankAllowed = true,
            extra = if (ArtistSignatures.normalize(address.trim()) != ArtistSignatures.normalize(ArtistSignatures.DEFAULT_API)) ({
                // One tap back to the standard service, shown while another is typed in.
                Row(
                    Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .clickable(enabled = !checking) { address = ArtistSignatures.DEFAULT_API; problem = null }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Restore, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Use the standard service", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(ArtistSignatures.DEFAULT_API.removePrefix("https://").removeSuffix("/"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }) else null,
        )
    }
}

/** The signature service card on its own, open, for tests. */
@Composable
internal fun SignatureCardPreview() = ArtistSignatureSettings(startEditing = true)
