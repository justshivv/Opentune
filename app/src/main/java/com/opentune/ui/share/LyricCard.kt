package com.opentune.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Smartphone
import com.opentune.ui.theme.Icon
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentune.data.model.Song
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A picture of a few lines of a song to share: the lines large on a dark
 * card washed with the cover's colour, the OpenTune mark and name at the top,
 * and the cover, title and artist at the foot. As a post it's 4:5; as a story
 * it's 9:16, the size Instagram and WhatsApp stories take.
 */
object LyricCard {
    const val WIDTH = 1080
    const val HEIGHT = 1350
    const val STORY_HEIGHT = 1920
    const val MAX_LINES = 6

    fun height(story: Boolean) = if (story) STORY_HEIGHT else HEIGHT

    fun draw(cover: Bitmap?, lines: List<String>, title: String, artist: String, story: Boolean = false): Bitmap {
        val w = WIDTH.toFloat()
        val h = height(story).toFloat()
        val out = Bitmap.createBitmap(WIDTH, height(story), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val square = cover?.let(ShareCard::centerSquare)
        val tone = square?.let(::averageColour) ?: Color.rgb(70, 60, 90)

        // The background: the cover's colour at the top, deepening to near black.
        paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(shade(tone, 0.55f), shade(tone, 0.28f), Color.rgb(14, 12, 14)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        // A soft glow of the colour from the top corner, so it isn't a flat fill.
        paint.shader = RadialGradient(w * 0.15f, 0f, w * 0.9f, Color.argb(70, Color.red(tone), Color.green(tone), Color.blue(tone)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val margin = w * 0.075f
        val ink = Color.WHITE
        val soft = Color.argb(185, 255, 255, 255)

        // The app's mark and name, top right.
        val brandH = w * 0.04f
        val brandY = margin + brandH / 2f
        ShareCard.drawBrand(c, w - margin, brandY, brandH, Color.argb(235, 255, 255, 255))

        // At the foot: the cover, small and rounded, with the title and artist.
        val thumb = w * 0.12f
        val footTop = h - margin - thumb
        val thumbRect = RectF(margin, footTop, margin + thumb, footTop + thumb)
        if (square != null) {
            c.save()
            c.clipPath(Path().apply { addRoundRect(thumbRect, thumb * 0.16f, thumb * 0.16f, Path.Direction.CW) })
            c.drawBitmap(square, null, thumbRect, image)
            c.restore()
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = w * 0.036f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; textSize = w * 0.03f }
        val textX = if (square != null) thumbRect.right + w * 0.035f else margin
        val textMax = w - textX - margin
        c.drawText(TextUtils.ellipsize(title, titlePaint, textMax, TextUtils.TruncateAt.END).toString(), textX, footTop + thumb * 0.44f, titlePaint)
        c.drawText(TextUtils.ellipsize(artist, artistPaint, textMax, TextUtils.TruncateAt.END).toString(), textX, footTop + thumb * 0.84f, artistPaint)

        // The lines, as large as fits, each its own paragraph so a wrapped line still reads as one.
        val area = RectF(margin, brandY + brandH + h * 0.03f, w - margin, footTop - h * 0.04f)
        val linePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        var size = w * 0.078f
        var layouts: List<StaticLayout>
        var gap: Float
        while (true) {
            linePaint.textSize = size
            gap = size * 0.42f
            layouts = lines.map { line ->
                StaticLayout.Builder.obtain(line, 0, line.length, linePaint, area.width().toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.04f)
                    .build()
            }
            val total = layouts.sumOf { it.height } + gap * (layouts.size - 1)
            if (total <= area.height() || size < w * 0.035f) break
            size *= 0.93f
        }
        // A post starts the lines at the top; a story, with height to spare, centres them.
        val total = layouts.sumOf { it.height } + gap * (layouts.size - 1)
        var y = if (story) area.top + ((area.height() - total) / 2f).coerceAtLeast(0f) else area.top
        for (layout in layouts) {
            c.save()
            c.translate(area.left, y)
            layout.draw(c)
            c.restore()
            y += layout.height + gap
        }
        return out
    }

    /** The cover's overall colour, from a tiny copy of it. */
    internal fun averageColour(b: Bitmap): Int {
        val tiny = Bitmap.createScaledBitmap(b, 8, 8, true)
        var r = 0; var g = 0; var bl = 0
        for (x in 0 until 8) for (y in 0 until 8) {
            val p = tiny.getPixel(x, y)
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p)
        }
        return Color.rgb(r / 64, g / 64, bl / 64)
    }

    private fun shade(colour: Int, by: Float): Int =
        Color.rgb((Color.red(colour) * by).toInt().coerceIn(0, 255), (Color.green(colour) * by).toInt().coerceIn(0, 255), (Color.blue(colour) * by).toInt().coerceIn(0, 255))
}

/**
 * Shares lines of a song as a lyric card. With [pickable], tap up to
 * [LyricCard.MAX_LINES] of [lines] first (starting from [startAt]); without,
 * [lines] are already the chosen ones. Then see the card, switch it to a
 * story, save it, or share it. Only the picture goes out, no text with it.
 */
@Composable
fun LyricCardSheet(song: Song, lines: List<String>, startAt: Int, onDismiss: () -> Unit, pickable: Boolean = true) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val picked = remember {
        mutableStateListOf<Int>().apply {
            if (!pickable) addAll(lines.indices.take(LyricCard.MAX_LINES))
            else if (startAt in lines.indices) add(startAt)
        }
    }
    var story by remember { mutableStateOf(false) }
    // This song's cover, and whether looking for it is over; the card waits
    // for it, so it never goes out with another song's picture.
    var cover by remember(song.videoId) { mutableStateOf<Bitmap?>(null) }
    var coverDone by remember(song.videoId) { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(song.videoId, song.thumbnailUrl) {
        cover = null
        coverDone = false
        cover = kotlinx.coroutines.withTimeoutOrNull(8_000) { runCatching { ShareCard.loadImage(context, song.thumbnailUrl, 720) }.getOrNull() }
        coverDone = true
    }
    val chosen = picked.sorted().map { lines[it] }
    LaunchedEffect(chosen, story, cover, coverDone) {
        preview = if (chosen.isEmpty() || !coverDone) null else withContext(Dispatchers.Default) { LyricCard.draw(cover, chosen, song.title, song.artist, story) }
    }
    val list = rememberLazyListState()
    // Open on the line being sung, a little way down so the lines before it show too.
    LaunchedEffect(Unit) { if (pickable && startAt > 0) list.scrollToItem((startAt - 2).coerceAtLeast(0)) }
    val fileName = "OpenTune-lyrics-${song.videoId}${if (story) "-story" else ""}.png"
    FloatingCard(
        onDismiss = onDismiss,
        title = "Share lyrics",
        icon = Icons.Rounded.FormatQuote,
        subtitle = when {
            !pickable -> "The lines you picked, laid out on a card with the cover."
            picked.isEmpty() -> "Tap the lines to put on the card"
            else -> com.opentune.i18n.Tr.f("{0} of {1} lines picked", picked.size, LyricCard.MAX_LINES)
        },
        startExpanded = !pickable,
        actions = {
            SheetButton(
                "Save",
                icon = Icons.Rounded.Download,
                enabled = preview != null,
                modifier = Modifier.weight(1f),
                onClick = {
                    val card = preview ?: return@SheetButton
                    haptics.press()
                    scope.launch {
                        val saved = runCatching { ShareCard.saveImage(context, card, fileName) }.isSuccess
                        if (saved) com.opentune.data.Pills.done("Saved to Pictures/OpenTune", context) else com.opentune.data.Pills.error("Couldn't save the card", context)
                    }
                },
            )
            SheetButton(
                "Share",
                icon = Icons.Rounded.IosShare,
                tone = SheetTone.Primary,
                enabled = preview != null,
                modifier = Modifier.weight(1f),
                onClick = {
                    val card = preview ?: return@SheetButton
                    haptics.press()
                    // The picture alone: apps that take text as well would paste the lines under it.
                    scope.launch { ShareCard.sendImage(context, card, fileName, text = null, label = song.title) }
                },
            )
        },
    ) {
        // The card as it will look.
        val ratio by animateFloatAsState(LyricCard.WIDTH / LyricCard.height(story).toFloat(), label = "cardRatio")
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            val p = preview
            val frame = Modifier.width(if (pickable) 150.dp else 200.dp).aspectRatio(ratio).clip(RoundedCornerShape(18.dp))
            if (p != null) {
                Image(p.asImageBitmap(), "Lyric card", frame, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            } else {
                Box(frame.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.FormatQuote, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SheetButton(
            if (story) "Make it a card" else "Make it a story",
            icon = if (story) Icons.AutoMirrored.Rounded.Article else Icons.Rounded.Smartphone,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            onClick = { haptics.tick(); story = !story },
        )
        if (pickable) {
            LazyColumn(Modifier.weight(1f, fill = false).padding(top = 4.dp), state = list) {
                itemsIndexed(lines) { i, line ->
                    val on = i in picked
                    val full = !on && picked.size >= LyricCard.MAX_LINES
                    val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent, label = "pick")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(bg)
                            .clickable(enabled = !full) {
                                haptics.tick()
                                if (on) picked.remove(i) else picked.add(i)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            if (on) "Picked" else "Not picked",
                            Modifier.size(22.dp),
                            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (full) 0.35f else 0.8f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            line,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                on -> MaterialTheme.colorScheme.onSurface
                                full -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}
