package com.opentune.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * A picture of a few lines of a song to share, like a lyric card: the
 * lines large over the song's blurred cover (or its colour), with the
 * cover, title and artist above them.
 */
object LyricCard {
    const val WIDTH = 1080
    const val HEIGHT = 1350
    const val MAX_LINES = 5

    enum class Look(val label: String) { BLURRED("Blurred cover"), COLOUR("Cover colour"), LIGHT("Light") }

    fun draw(cover: Bitmap?, lines: List<String>, title: String, artist: String, look: Look): Bitmap {
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val square = cover?.let(ShareCard::centerSquare)
        val tone = square?.let(::averageColour) ?: Color.rgb(70, 60, 90)
        val light = look == Look.LIGHT

        // The background.
        when (look) {
            Look.BLURRED -> if (square != null) {
                val side = h * 1.15f
                c.drawBitmap(ShareCard.blurred(square), null, RectF((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f), image)
                c.drawColor(Color.argb(95, 0, 0, 0))
            } else {
                c.drawColor(tone)
            }
            Look.COLOUR -> {
                paint.shader = LinearGradient(0f, 0f, w, h, shade(tone, 1.15f), shade(tone, 0.55f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, paint)
                paint.shader = null
            }
            Look.LIGHT -> {
                c.drawColor(Color.rgb(246, 244, 240))
                paint.color = Color.argb(40, Color.red(tone), Color.green(tone), Color.blue(tone))
                c.drawCircle(w * 0.85f, h * 0.1f, w * 0.55f, paint)
            }
        }
        val ink = if (light) Color.rgb(24, 22, 28) else Color.WHITE
        val soft = if (light) Color.argb(160, 24, 22, 28) else Color.argb(190, 255, 255, 255)
        val margin = w * 0.085f

        // The song: its cover small, title and artist beside it.
        val thumb = w * 0.13f
        val top = h * 0.08f
        val thumbRect = RectF(margin, top, margin + thumb, top + thumb)
        if (square != null) {
            c.save()
            c.clipPath(Path().apply { addRoundRect(thumbRect, thumb * 0.18f, thumb * 0.18f, Path.Direction.CW) })
            c.drawBitmap(square, null, thumbRect, image)
            c.restore()
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = w * 0.04f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; textSize = w * 0.034f }
        val textX = if (square != null) thumbRect.right + w * 0.035f else margin
        val textMax = w - textX - margin
        c.drawText(TextUtils.ellipsize(title, titlePaint, textMax, TextUtils.TruncateAt.END).toString(), textX, top + thumb * 0.45f, titlePaint)
        c.drawText(TextUtils.ellipsize(artist, artistPaint, textMax, TextUtils.TruncateAt.END).toString(), textX, top + thumb * 0.85f, artistPaint)

        // The lines, as large as fits, each its own paragraph so a wrapped line still reads as one.
        val area = RectF(margin, top + thumb + h * 0.07f, w - margin, h * 0.86f)
        val linePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        var size = w * 0.085f
        var layouts: List<StaticLayout>
        var gap: Float
        while (true) {
            linePaint.textSize = size
            gap = size * 0.45f
            layouts = lines.map { line ->
                StaticLayout.Builder.obtain(line, 0, line.length, linePaint, area.width().toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.08f)
                    .build()
            }
            val total = layouts.sumOf { it.height } + gap * (layouts.size - 1)
            if (total <= area.height() || size < w * 0.04f) break
            size *= 0.92f
        }
        var y = area.top
        for (layout in layouts) {
            c.save()
            c.translate(area.left, y)
            layout.draw(c)
            c.restore()
            y += layout.height + gap
        }

        // A quote mark and where it's from, quietly.
        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; textSize = w * 0.026f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.14f }
        c.drawText("OPENTUNE", margin, h - h * 0.05f, brand)
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
 * Picks the lines for a lyric card: tap up to [LyricCard.MAX_LINES] lines,
 * pick a look, and see the card as it will be sent.
 */
@Composable
fun LyricCardSheet(song: Song, lines: List<String>, startAt: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val picked = remember { mutableStateListOf<Int>().apply { if (startAt in lines.indices) add(startAt) } }
    var look by remember { mutableStateOf(LyricCard.Look.BLURRED) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(song.thumbnailUrl) { cover = runCatching { ShareCard.loadImage(context, song.thumbnailUrl, 720) }.getOrNull() }
    val chosen = picked.sorted().map { lines[it] }
    LaunchedEffect(chosen, look, cover) {
        preview = if (chosen.isEmpty()) null else withContext(Dispatchers.Default) { LyricCard.draw(cover, chosen, song.title, song.artist, look) }
    }
    val list = rememberLazyListState()
    // Open on the line being sung, a little way down so the lines before it show too.
    LaunchedEffect(Unit) { if (startAt > 0) list.scrollToItem((startAt - 2).coerceAtLeast(0)) }
    FloatingCard(
        onDismiss = onDismiss,
        title = "Share lyrics",
        icon = Icons.Rounded.FormatQuote,
        subtitle = if (picked.isEmpty()) "Tap the lines to put on the card" else "${picked.size} of ${LyricCard.MAX_LINES} lines picked",
        actions = {
            SheetButton(
                "Share",
                tone = SheetTone.Primary,
                enabled = preview != null,
                modifier = Modifier.weight(1f),
                onClick = {
                    val card = preview ?: return@SheetButton
                    haptics.press()
                    scope.launch {
                        ShareCard.sendImage(context, card, "OpenTune-lyrics-${song.videoId}.png", "${chosen.joinToString(" / ")}\n— ${song.title}, ${song.artist}\nhttps://music.youtube.com/watch?v=${song.videoId}", song.title)
                    }
                },
            )
        },
    ) {
        // The card as it will look, small, beside its looks; the lines to pick below.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val p = preview
            val thumb = Modifier.width(104.dp).aspectRatio(LyricCard.WIDTH / LyricCard.HEIGHT.toFloat()).clip(RoundedCornerShape(12.dp))
            if (p != null) {
                Image(p.asImageBitmap(), "Lyric card", thumb)
            } else {
                Box(thumb.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.FormatQuote, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Look", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LyricCard.Look.entries.forEach { l ->
                    FilterChip(selected = look == l, onClick = { haptics.tick(); look = l }, label = { Text(l.label) })
                }
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false).padding(top = 8.dp), state = list) {
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
    }
}
