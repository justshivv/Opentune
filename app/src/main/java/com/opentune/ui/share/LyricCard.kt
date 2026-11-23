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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

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

        if (square != null) {
            // The background: the cover, blurred over the whole card, darkened enough that
            // white lines read on any cover, and most at the foot where the title sits.
            ShareCard.drawBlurredCover(c, square, w, h)
            paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.argb(95, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.argb(190, 0, 0, 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
        } else {
            // No cover: a colour deepening to near black, with a soft glow from the top corner.
            paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(shade(tone, 0.55f), shade(tone, 0.28f), Color.rgb(14, 12, 14)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(w * 0.15f, 0f, w * 0.9f, Color.argb(70, Color.red(tone), Color.green(tone), Color.blue(tone)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
        }
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

/** A picture of the lines, or a video of them sung over the song. */
private enum class ShareKind(val label: String) { VIDEO("Video"), IMAGE("Image") }

/** What's behind the lines in a video: the album's own moving cover, or its colours flowing. */
private enum class Backdrop(val label: String) { ANIMATED("Animated cover"), AMBIENT("Ambient colours") }

/**
 * Shares lines of a song. With [pickable], tap up to [LyricCard.MAX_LINES] of [lines] first
 * (starting from [startAt]); without, [lines] are already the chosen ones.
 *
 * As an image: the lines on a card or a story, saved or shared as a picture. As a video,
 * where the lines are synced ([timed], one for each of [lines]): the part of the song they
 * cover, its words lighting as they're sung, over the album's animated cover ([canvasUrl],
 * Spotify's, or [motion], Apple's) or the cover's colours flowing, with the song itself under
 * it. [offsetMs] is the song's lyrics offset, so the audio lines up with the words.
 */
@Composable
fun LyricCardSheet(
    song: Song,
    lines: List<String>,
    startAt: Int,
    onDismiss: () -> Unit,
    pickable: Boolean = true,
    timed: List<com.opentune.data.lyrics.LyricLine>? = null,
    offsetMs: Long = 0,
    canvasUrl: String? = null,
    motion: com.opentune.data.covers.AppleMotionArt.Motion? = null,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val picked = remember {
        mutableStateListOf<Int>().apply {
            if (!pickable) addAll(lines.indices.take(LyricCard.MAX_LINES))
            else if (startAt in lines.indices) add(startAt)
        }
    }
    val canVideo = timed != null && timed.size == lines.size
    var kind by remember { mutableStateOf(if (canVideo) ShareKind.VIDEO else ShareKind.IMAGE) }
    var story by remember { mutableStateOf(false) }
    // The video's own choices.
    var videoCard by remember { mutableStateOf(false) }
    var showLogo by remember { mutableStateOf(true) }
    var withSong by remember { mutableStateOf(true) }
    var backdrop by remember { mutableStateOf(Backdrop.ANIMATED) }
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
    // The album's animated cover for the video: the Canvas the player found, else Apple's.
    var moving by remember(song.videoId) { mutableStateOf<MotionFrames?>(null) }
    var movingDone by remember(song.videoId) { mutableStateOf(false) }
    LaunchedEffect(song.videoId, kind) {
        if (kind != ShareKind.VIDEO || movingDone) return@LaunchedEffect
        moving = canvasUrl?.let { MotionFrames.loadVideo(it, context.cacheDir) }
            ?: (motion ?: runCatching { com.opentune.data.covers.AppleMotionArt.forSong(song, ShareCard.durationOf(song.durationText)) }.getOrNull())
                ?.let { MotionFrames.load(it, context.cacheDir) }
        movingDone = true
    }
    val chosen = picked.sorted().map { lines[it] }
    // The video's lines: everything from the first picked to the last, so the song's words
    // between them aren't skipped while it plays.
    val videoLines = remember(picked.toList(), timed) {
        val t = timed ?: return@remember emptyList()
        val sorted = picked.sorted()
        if (sorted.isEmpty()) emptyList() else LyricVideo.span(t.subList(sorted.first(), sorted.last() + 1))
    }
    LaunchedEffect(chosen, story, cover, coverDone, kind) {
        if (kind != ShareKind.IMAGE) return@LaunchedEffect
        preview = if (chosen.isEmpty() || !coverDone) null else withContext(Dispatchers.Default) { LyricCard.draw(cover, chosen, song.title, song.artist, story) }
    }
    val useMotion = backdrop == Backdrop.ANIMATED && moving != null
    val video = remember(videoLines, cover, coverDone, useMotion, moving, videoCard, showLogo) {
        if (videoLines.isEmpty() || !coverDone) null
        else LyricVideo(videoLines, cover, moving.takeIf { useMotion }, song.title, song.artist, if (videoCard) LyricVideo.Layout.CARD else LyricVideo.Layout.STORY, showLogo)
    }
    // Making the video: how far along, 0 to 1, while it runs.
    var making by remember { mutableStateOf<Float?>(null) }
    // The video playing in the sheet, drawn small from the same frames it will be made of.
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(video, kind) {
        frame = null
        val v = video ?: return@LaunchedEffect
        if (kind != ShareKind.VIDEO) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val scale = 1 / 3f
            val bitmaps = List(2) { Bitmap.createBitmap((v.width * scale).toInt(), (v.height * scale).toInt(), Bitmap.Config.ARGB_8888) }
            val began = System.nanoTime()
            var n = 0
            while (true) {
                // Held while the video is being made, so the making has every core.
                if (making != null) { kotlinx.coroutines.delay(200); continue }
                val t = ((System.nanoTime() - began) / 1_000_000) % v.lengthMs
                val b = bitmaps[n++ % 2]
                val c = android.graphics.Canvas(b)
                c.scale(scale, scale)
                v.draw(c, t)
                frame = b.asImageBitmap()
                kotlinx.coroutines.delay(33)
            }
        }
    }
    var made by remember { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(video, withSong) { made = null }
    fun makeVideo(then: (java.io.File) -> Unit) {
        made?.let { return then(it) }
        if (videoLines.isEmpty() || making != null) return
        val lines = videoLines
        making = 0f
        scope.launch {
            val result = runCatching {
                // Its frames at full size, drawn side by side by copies of it.
                val make = { LyricVideo(lines, cover, moving.takeIf { useMotion }, song.title, song.artist, if (videoCard) LyricVideo.Layout.CARD else LyricVideo.Layout.STORY, showLogo) }
                val startMs = LyricVideo.startOf(lines)
                val lengthMs = LyricVideo.lengthOf(lines)
                // The song is read while the frames are drawn, not before them.
                val audio = if (withSong) async(Dispatchers.IO) {
                    // A song that can't be read leaves the video without it, never fails it.
                    runCatching { VideoFiles.audioSource(song.videoId)?.let { source -> LyricVideoEncoder.audio(source, startMs - offsetMs, startMs - offsetMs + lengthMs) } }.getOrNull()
                } else null
                val file = VideoFiles.fresh(context, "NepTune-lyrics-${song.videoId}.mp4")
                // OpenGL wants one thread for all of its calls.
                val thread = java.util.concurrent.Executors.newSingleThreadExecutor()
                try {
                    withContext(thread.asCoroutineDispatcher()) {
                        LyricVideoEncoder.encode(make, audio, file) { p -> making = p }
                    }
                } finally {
                    thread.shutdown()
                }
                if (withSong && audio?.await() == null) com.opentune.data.Pills.error("Made without the song: it couldn't be read", context)
                file
            }
            making = null
            result.onSuccess { made = it; then(it) }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) com.opentune.data.Pills.error("Couldn't make the video", context) }
        }
    }
    val list = rememberLazyListState()
    // Open on the line being sung, a little way down so the lines before it show too.
    LaunchedEffect(Unit) { if (pickable && startAt > 0) list.scrollToItem((startAt - 2).coerceAtLeast(0)) }
    val fileName = "NepTune-lyrics-${song.videoId}${if (story) "-story" else ""}.png"
    val isVideo = kind == ShareKind.VIDEO
    val ready = if (isVideo) video != null && making == null else preview != null
    FloatingCard(
        onDismiss = onDismiss,
        title = "Share lyrics",
        icon = ImageVector.vectorResource(AppIcons.FormatQuote),
        subtitle = when {
            making != null -> com.opentune.i18n.Tr.f("Making the video… {0}%", ((making ?: 0f) * 100).toInt())
            isVideo && !pickable -> "The lines you picked, sung over the song."
            !pickable -> "The lines you picked, laid out on a card with the cover."
            picked.isEmpty() -> "Tap the lines to put on the card"
            else -> com.opentune.i18n.Tr.f("{0} of {1} lines picked", picked.size, LyricCard.MAX_LINES)
        },
        startExpanded = !pickable,
        actions = {
            SheetButton(
                "Save",
                icon = ImageVector.vectorResource(AppIcons.Download),
                enabled = ready,
                loading = isVideo && making != null,
                modifier = Modifier.weight(1f),
                onClick = {
                    haptics.press()
                    if (isVideo) {
                        makeVideo { file ->
                            scope.launch {
                                val saved = runCatching { VideoFiles.save(context, file) }.isSuccess
                                if (saved) com.opentune.data.Pills.done("Saved to Movies/NepTune", context) else com.opentune.data.Pills.error("Couldn't save the video", context)
                            }
                        }
                        return@SheetButton
                    }
                    val card = preview ?: return@SheetButton
                    scope.launch {
                        val saved = runCatching { ShareCard.saveImage(context, card, fileName) }.isSuccess
                        if (saved) com.opentune.data.Pills.done("Saved to Pictures/NepTune", context) else com.opentune.data.Pills.error("Couldn't save the card", context)
                    }
                },
            )
            SheetButton(
                "Share",
                icon = ImageVector.vectorResource(AppIcons.IosShare),
                tone = SheetTone.Primary,
                enabled = ready,
                modifier = Modifier.weight(1f),
                onClick = {
                    haptics.press()
                    if (isVideo) {
                        makeVideo { file -> VideoFiles.share(context, file, song.title) }
                        return@SheetButton
                    }
                    val card = preview ?: return@SheetButton
                    // The picture alone: apps that take text as well would paste the lines under it.
                    scope.launch { ShareCard.sendImage(context, card, fileName, text = null, label = song.title) }
                },
            )
        },
    ) {
        // Everything above the lines to pick. It scrolls when it's taller than the room it has:
        // in video mode the pills, the preview, the button and the switches don't fit every
        // screen, and squeezed into the card they slid under Save and Share.
        val controls: @Composable () -> Unit = {
            if (canVideo) {
                com.opentune.ui.components.PillSegmented(
                    ShareKind.entries, kind, { it.label }, { haptics.tick(); kind = it },
                    Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            if (isVideo) {
                com.opentune.ui.components.PillSegmented(
                    Backdrop.entries, if (moving == null && movingDone) Backdrop.AMBIENT else backdrop, { it.label },
                    { if (it == Backdrop.AMBIENT || moving != null) { haptics.tick(); backdrop = it } },
                    Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                if (movingDone && moving == null) {
                    Text(
                        "This album has no animated cover, so its colours flow behind the words.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
            // The card or video as it will look.
            val ratio by animateFloatAsState(if (isVideo) LyricVideo.WIDTH / LyricVideo.HEIGHT.toFloat() else LyricCard.WIDTH / LyricCard.height(story).toFloat(), label = "cardRatio")
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                val frameModifier = Modifier.width(if (pickable) 150.dp else 200.dp).aspectRatio(ratio).clip(RoundedCornerShape(18.dp))
                val shown = if (isVideo) frame else preview?.asImageBitmap()
                if (shown != null) {
                    Image(shown, if (isVideo) "Lyric video" else "Lyric card", frameModifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                } else {
                    Box(frameModifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        Icon(ImageVector.vectorResource(AppIcons.FormatQuote), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (isVideo) {
                SheetButton(
                    if (videoCard) "Make it a story" else "Make it a card",
                    icon = if (videoCard) ImageVector.vectorResource(AppIcons.Smartphone) else ImageVector.vectorResource(AppIcons.Article),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    onClick = { haptics.tick(); videoCard = !videoCard },
                )
                com.opentune.ui.components.ToggleRow("Show the NepTune logo", showLogo, { haptics.tick(); showLogo = it })
                com.opentune.ui.components.ToggleRow(
                    "Include the song", withSong, { haptics.tick(); withSong = it },
                    summary = "The part of the song the lines are sung in, under the video",
                )
            } else {
                SheetButton(
                    if (story) "Make it a card" else "Make it a story",
                    icon = if (story) ImageVector.vectorResource(AppIcons.Article) else ImageVector.vectorResource(AppIcons.Smartphone),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    onClick = { haptics.tick(); story = !story },
                )
            }
        }
        val controlsScroll = androidx.compose.foundation.rememberScrollState()
        if (!pickable) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(controlsScroll)) {
                controls()
                Spacer(Modifier.height(4.dp))
            }
        } else {
            // Over the list of lines, at most half the screen, so there's room to pick.
            val half = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.5f).dp
            Column(Modifier.heightIn(max = half).verticalScroll(controlsScroll)) { controls() }
        }
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
                            if (on) ImageVector.vectorResource(AppIcons.CheckCircle) else ImageVector.vectorResource(AppIcons.RadioButtonUnchecked),
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
}
