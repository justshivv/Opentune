package com.opentune.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material.icons.rounded.Radio
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    val hero by produceState<String?>(null, name, page) {
        // Apple's photo only for the artist whose page this is, not a namesake (see artFor).
        val p = page ?: return@produceState
        val titles = p.songs.map { it.title } + p.sections.flatMap { sec -> sec.items.map { it.title } }
        value = runCatching { com.opentune.data.covers.AppleArtistArtRepository.artFor(name, id, titles)?.heroUrl }.getOrNull()
    }
    val signature by produceState<ArtistSignature?>(null, name, ui.artistSignatures, ui.signatureApi) {
        value = if (ui.artistSignatures) ArtistSignatures.signatureFor(name, ui.signatureApi) else null
    }
    val still = stillMotion()
    val loaded = page
    val songs = loaded?.songs.orEmpty()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val pageWidth = maxWidth
    // The web's hero: the photo across the page at about 1.93:1, never taller than 70% of the window.
    val heroHeight = minOf(pageWidth / 1.93f, maxHeight * 0.7f).coerceAtLeast(320.dp)
    CompositionLocalProvider(LocalGutter provides Web.gutter(pageWidth), LocalPageWidth provides pageWidth) {
    LazyColumn(state = list, contentPadding = PaddingValues(bottom = LocalPageBottom.current), verticalArrangement = Arrangement.spacedBy(36.dp)) {
        item(key = "hero") {
            val background = MaterialTheme.colorScheme.background
            Box(Modifier.fillMaxWidth().height(heroHeight).clipToBounds()) {
                // The photo itself fades out at its foot, into whatever is behind the page (its
                // tinted backdrop): fading to the plain page colour left a hard line where that
                // met the tint below, sharper still as the photo drifted down on scrolling.
                Box(
                    Modifier.matchParentSize()
                        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(ARTIST_HERO_MASK, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                        },
                ) {
                AnimatedContent(hero ?: loaded?.thumbnailUrl ?: item.thumbnailUrl, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(300)) }, label = "artist photo") { url ->
                    // As on the web (.ot-parallax): over the first 640px of scrolling the photo drifts down
                    // by 28% of its height and grows to 1.08, so it slides away slower than the page.
                    Artwork(url, Modifier.fillMaxSize().graphicsLayer {
                        if (list.firstVisibleItemIndex == 0 && !still) {
                            val t = (list.firstVisibleItemScrollOffset / 640.dp.toPx()).coerceIn(0f, 1f)
                            translationY = size.height * 0.28f * t
                            val grow = 1f + 0.08f * t
                            scaleX = grow; scaleY = grow
                        }
                    }, androidx.compose.ui.graphics.RectangleShape)
                }
                }
                // A soft shade behind the name and buttons, back to clear at the very foot so it leaves no edge.
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 0.78f to Color.Black.copy(alpha = 0.32f), 1f to Color.Transparent)))
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = LocalGutter.current, end = LocalGutter.current, bottom = 24.dp).riseIn(delayMs = 70),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NameOrSignature(name, signature, pageWidth)
                    (loaded?.monthlyListenerCount ?: loaded?.subscriberCountText)?.let {
                        Text(it, style = WebType.calloutEmph, color = Color.White.copy(alpha = 0.64f))
                    }
                    // Shuffle · Play · Radio, as the web's artist page has them.
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(Icons.Rounded.Shuffle, "Shuffle $name", 48.dp, solid = false, enabled = songs.isNotEmpty()) { Player.playAll(songs.shuffled(), 0) }
                        RoundButton(Icons.Rounded.PlayArrow, "Play $name", 62.dp, solid = true, enabled = songs.isNotEmpty()) { Player.playAll(songs, 0) }
                        // A radio from the artist's best-known song: their music and music like it.
                        RoundButton(Icons.Rounded.Radio, "$name radio", 48.dp, solid = false, enabled = songs.isNotEmpty()) {
                            Player.startRadio(songs.first()); Notice.show("Radio from $name")
                        }
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
                    // About (ArtistPage.tsx): the title as a shelf's, the story in 13px grey, at most 720 wide.
                    Column(Modifier.padding(horizontal = LocalGutter.current).padding(bottom = 4.dp).widthIn(max = 720.dp)) {
                        Text("About $name", style = WebType.title2, modifier = Modifier.semantics { heading() })
                        var open by remember { mutableStateOf(false) }
                        Text(
                            story, style = WebType.bodyTall, color = WebInk.secondary,
                            maxLines = if (open) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = if (open) "Show less" else "Show more") { open = !open },
                        )
                    }
                }
            }
        }
    }
    }
    }
}

/** The signature in white with "Signed by" under it, or the name in big type when there isn't one (or until it draws). */
@Composable
private fun NameOrSignature(name: String, signature: ArtistSignature?, pageWidth: androidx.compose.ui.unit.Dp) {
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
                style = WebType.title3, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
            )
        } else {
            // The web's artist name: 800 weight, clamp(34px, 5vw, 64px), a soft shadow under it over the photo.
            val size = ((pageWidth + Web.Sidebar).value * 0.05f).coerceIn(34f, 64f)
            Text(
                name, color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = webType(size, size * 1.05f, 800).copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(0f, 2f), 20f)),
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

/**
 * The hero's round buttons, as a list's head has them: Play [solid], white with a black
 * triangle; the others of dark glass over the photograph, a little darker under the pointer.
 * Each gives a little when pressed.
 */
@Composable
private fun RoundButton(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, solid: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by androidx.compose.animation.animateColorAsState(
        when {
            solid -> if (hovered && enabled) Color(0xFFE6E6E6) else Color.White
            hovered && enabled -> Color.Black.copy(alpha = 0.45f)
            else -> Color.Black.copy(alpha = 0.3f)
        },
        tween(PAGE_HOVER_MS), label = "artist button",
    )
    Box(
        Modifier.pressScale(source).size(size).shadow(if (solid) 14.dp else 0.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.65f), spotColor = Color.Black.copy(alpha = 0.65f))
            .clip(CircleShape).background(fill)
            .hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (solid) Color.Black else Color.White, modifier = Modifier.size(if (solid) 28.dp else 20.dp)) }
}

/**
 * A signature as white ink on clear, the paper of a scan dropped away
 * ([ArtistSignatures.whiteInk]). Null when the pixels can't be read.
 */
internal fun whiteInk(input: org.jetbrains.skia.Bitmap): org.jetbrains.skia.Bitmap? {
    val w = input.width
    val h = input.height
    val info = org.jetbrains.skia.ImageInfo(w, h, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
    val bytes = input.readPixels(info, w * 4, 0, 0) ?: return null
    val argb = IntArray(w * h) { i ->
        ((bytes[i * 4 + 3].toInt() and 0xFF) shl 24) or ((bytes[i * 4].toInt() and 0xFF) shl 16) or
            ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or (bytes[i * 4 + 2].toInt() and 0xFF)
    }
    // A photograph, not writing: the name stays in its place.
    val ink = ArtistSignatures.whiteInk(argb, w, h) ?: return null
    for (i in 0 until w * h) {
        bytes[i * 4] = -1; bytes[i * 4 + 1] = -1; bytes[i * 4 + 2] = -1
        bytes[i * 4 + 3] = (ink[i] ushr 24).toByte()
    }
    val out = org.jetbrains.skia.Bitmap()
    out.allocPixels(info)
    out.installPixels(info, bytes, w * 4)
    return out
}

/** The artist photo's own fade: whole down to just past the middle, gone at its foot. */
private val ARTIST_HERO_MASK = Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black, 0.86f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent)
