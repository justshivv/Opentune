package com.opentune.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import coil3.toBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.opentune.data.covers.ArtistSignature
import com.opentune.data.covers.ArtistSignatures
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.ShelfItem
import com.opentune.data.settings.AppSettings

/**
 * An artist's page: their photo across the top (Apple Music's when it has
 * one), their handwritten signature over it in place of their name when the
 * signature service has one, play and shuffle, their top songs in columns,
 * the page's rows, and their story.
 */
@Composable
internal fun ArtistView(item: ShelfItem, onOpen: (ShelfItem) -> Unit) {
    val id = item.browseId!!
    val ui by AppSettings.ui.collectAsState()
    val page by produceState<ArtistPage?>(null, id) {
        value = runCatching { InnertubeParser.parseArtistPage(Innertube.browse(id)) }.getOrNull()
    }
    val name = page?.name ?: item.title
    val hero by produceState<String?>(null, name) {
        value = runCatching { com.opentune.data.covers.AppleArtistArtRepository.artFor(name)?.heroUrl }.getOrNull()
    }
    val signature by produceState<ArtistSignature?>(null, name, ui.artistSignatures, ui.signatureApi) {
        value = if (ui.artistSignatures) ArtistSignatures.signatureFor(name, ui.signatureApi) else null
    }
    val loaded = page
    val songs = loaded?.songs.orEmpty()
    LazyColumn(contentPadding = PaddingValues(bottom = LocalPageBottom.current), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item(key = "hero") {
            val background = MaterialTheme.colorScheme.background
            Box(Modifier.fillMaxWidth().height(460.dp)) {
                AnimatedContent(hero ?: loaded?.thumbnailUrl ?: item.thumbnailUrl, transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(300)) }, label = "artist photo") { url ->
                    Artwork(url, Modifier.fillMaxSize(), androidx.compose.ui.graphics.RectangleShape)
                }
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 0.55f to Color.Transparent, 1f to background)))
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NameOrSignature(name, signature)
                    (loaded?.monthlyListenerCount ?: loaded?.subscriberCountText)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(Icons.Rounded.Shuffle, "Shuffle", 46.dp, solid = false, enabled = songs.isNotEmpty()) { Player.playAll(songs.shuffled(), 0) }
                        RoundButton(Icons.Rounded.PlayArrow, "Play", 58.dp, solid = true, enabled = songs.isNotEmpty()) { Player.playAll(songs, 0) }
                    }
                }
            }
        }
        if (loaded == null) {
            items(3) { ShelfPlaceholder() }
        } else {
            if (songs.isNotEmpty()) item(key = "top") { SongGrid("Top songs", songs) }
            items(loaded.sections.filter { it.items.isNotEmpty() }) { shelf -> ShelfRow(Pages.Shelf(shelf.title, shelf.items), onOpen) }
            loaded.description?.takeIf { it.isNotBlank() }?.let { story ->
                item(key = "about") {
                    Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("About", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        var open by remember { mutableStateOf(false) }
                        Text(
                            story, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (open) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { open = !open },
                        )
                    }
                }
            }
        }
    }
}

/** The signature in white with "Signed by" under it, or the name in big type when there isn't one (or until it draws). */
@Composable
private fun NameOrSignature(name: String, signature: ArtistSignature?) {
    val context = LocalPlatformContext.current
    // Loaded and turned to white ink before it's shown, so the name doesn't blink out for an empty space.
    val ink by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, signature?.imageUrl) {
        value = signature?.let { s ->
            val result = coil3.SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(s.imageUrl).size(Size(1200, 600)).build())
            val bitmap = (result as? coil3.request.SuccessResult)?.image?.toBitmap() ?: return@let null
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { whiteInk(bitmap) }?.asComposeImageBitmap()
        }
    }
    val drawn = ink != null
    ink?.let {
        androidx.compose.foundation.Image(
            it, "$name's signature", contentScale = ContentScale.Fit,
            modifier = Modifier.padding(horizontal = 40.dp).widthIn(max = 460.dp).fillMaxWidth().height(130.dp),
        )
    }
    AnimatedContent(drawn, transitionSpec = { fadeIn(tween(420)) togetherWith fadeOut(tween(200)) }, label = "signed") { signed ->
        if (signed) {
            Text(
                buildAnnotatedString { append("Signed by "); withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) } },
                style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
            )
        } else {
            Text(
                name, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = Color.White,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, solid: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier.size(size).hoverLift(hover, 1.06f).shadow(if (solid) 12.dp else 0.dp, CircleShape).clip(CircleShape)
            .background(if (solid) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(enabled = enabled, interactionSource = hover, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (solid) Color.Black else Color.White, modifier = Modifier.size(size * 0.48f)) }
}

/**
 * A signature as white ink on clear, whatever it came as: strokes on
 * transparency keep their shape and turn white; strokes on paper take their
 * alpha from how far each pixel is from the paper's tone. Null when the
 * pixels can't be read.
 */
internal fun whiteInk(input: org.jetbrains.skia.Bitmap): org.jetbrains.skia.Bitmap? {
    val w = input.width
    val h = input.height
    val info = org.jetbrains.skia.ImageInfo(w, h, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
    val bytes = input.readPixels(info, w * 4, 0, 0) ?: return null
    var clear = 0
    for (i in 0 until w * h) if ((bytes[i * 4 + 3].toInt() and 0xFF) < 16) clear++
    fun luma(i: Int) = 0.2126f * (bytes[i * 4].toInt() and 0xFF) + 0.7152f * (bytes[i * 4 + 1].toInt() and 0xFF) + 0.0722f * (bytes[i * 4 + 2].toInt() and 0xFF)
    val onPaper = clear * 5 < w * h
    val paper = if (onPaper) listOf(0, w - 1, (h - 1) * w, h * w - 1).map(::luma).average().toFloat() else 0f
    val span = maxOf(paper, 255f - paper).coerceAtLeast(1f)
    for (i in 0 until w * h) {
        val alpha = bytes[i * 4 + 3].toInt() and 0xFF
        val a = if (onPaper) ((kotlin.math.abs(luma(i) - paper) / span).coerceIn(0f, 1f) * alpha).toInt() else alpha
        bytes[i * 4] = -1; bytes[i * 4 + 1] = -1; bytes[i * 4 + 2] = -1
        bytes[i * 4 + 3] = a.toByte()
    }
    val out = org.jetbrains.skia.Bitmap()
    out.allocPixels(info)
    out.installPixels(info, bytes, w * 4)
    return out
}
