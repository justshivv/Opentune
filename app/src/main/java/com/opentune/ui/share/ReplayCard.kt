package com.opentune.ui.share

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import android.text.TextPaint
import android.text.TextUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.withClip
import com.materialkolor.ktx.themeColorOrNull
import com.opentune.R
import com.opentune.data.DebugLog as Log
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import com.opentune.data.history.ReplaySummary
import com.opentune.data.history.Wrapped
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.rememberHaptics
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Replay as one story-sized picture: the minutes large, the top five songs
 * and artists side by side, the top albums and genres, on a deep wash of
 * the top artist's or song's colour. Only what Replay counted goes on it;
 * a part with nothing in it is left off and the rest spreads out to fill.
 */
object ReplayCard {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    const val TOP = 5
    const val ALBUMS = 3
    const val GENRES = 6
    const val FOOTER = "Counted on this phone with WEAV"
    private const val TAG = "ReplayCard"
    private const val IMAGE_WAIT_MS = 6_000L
    private const val PHOTO_WAIT_MS = 3_000L

    /** One line of a top list, ready to print. [song] helps find an artist's photo. */
    data class Item(val title: String, val subtitle: String, val minutes: String, val thumbnailUrl: String?, val song: Song? = null)

    data class Content(
        /** "Replay ’26". */
        val title: String,
        /** The Replay page's period, like "Last 4 weeks". */
        val period: String,
        /** The signed-in account's name, or null. */
        val name: String?,
        val minutes: Long,
        val plays: Int,
        val songs: Int,
        val artists: Int,
        val topSongs: List<Item>,
        val topArtists: List<Item>,
        val topAlbums: List<Item>,
        val genres: List<String>,
    )

    /** The parts under the minutes, each drawn only when it has something in it. */
    enum class Section { LISTS, ALBUMS, GENRES }

    fun title(year: Int) = "Replay ’%02d".format(year % 100)

    /**
     * What goes on the card, from [summary]. Replay doesn't keep genres, so
     * [genres] is empty unless a caller has real ones; nothing is guessed.
     */
    fun content(summary: ReplaySummary, year: Int, period: String, name: String?, genres: List<String> = emptyList()): Content {
        val songs = summary.topSongs.filter { it.title.isNotBlank() }.take(TOP)
        return Content(
            title = title(year),
            period = period,
            name = name?.trim()?.takeIf { it.isNotEmpty() },
            minutes = TimeUnit.MILLISECONDS.toMinutes(summary.listenedMs),
            plays = summary.totalPlays,
            songs = summary.songCount,
            artists = summary.artistCount,
            topSongs = songs.map { Item(it.title, it.subtitle, minutes(it.listenedMs), it.thumbnailUrl, it.song) },
            topArtists = summary.topArtists.filter { it.title.isNotBlank() }.take(TOP).map { a ->
                // One of their songs, so the photo can be looked up; Replay keeps none for an artist.
                val song = summary.topSongs.firstOrNull { Wrapped.mainArtist(it.subtitle) == a.title }?.song
                Item(a.title, "", minutes(a.listenedMs), a.thumbnailUrl, song)
            },
            topAlbums = summary.topAlbums.filter { it.title.isNotBlank() }.take(ALBUMS).map { Item(it.title, it.subtitle, minutes(it.listenedMs), it.thumbnailUrl) },
            genres = genres.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(GENRES),
        )
    }

    /** "52 min", "1,240 min"; a listen too short to count a minute reads "<1 min". */
    fun minutes(ms: Long): String {
        val m = TimeUnit.MILLISECONDS.toMinutes(ms)
        return if (m < 1) "<1 min" else "%,d min".format(m)
    }

    /** "91 plays · 156 songs · 86 artists", in the singular where it's one. */
    fun counts(c: Content): String = listOf(
        count(c.plays, "play"),
        count(c.songs, "song"),
        count(c.artists, "artist"),
    ).joinToString(" · ")

    private fun count(n: Int, word: String) = "%,d %s".format(n, if (n == 1) word else word + "s")

    fun sections(c: Content): List<Section> = buildList {
        if (c.topSongs.isNotEmpty() || c.topArtists.isNotEmpty()) add(Section.LISTS)
        if (c.topAlbums.isNotEmpty()) add(Section.ALBUMS)
        if (c.genres.isNotEmpty()) add(Section.GENRES)
    }

    /** The pictures for the card, in the order of [Content]'s lists; any may be null. */
    class Images(
        val songs: List<Bitmap?> = emptyList(),
        val artists: List<Bitmap?> = emptyList(),
        val albums: List<Bitmap?> = emptyList(),
        val logo: Bitmap? = null,
    )

    /**
     * Loads every cover and photo at once, each with a time limit, so a slow
     * one is drawn as a placeholder instead of holding the card up. Artists
     * get their own photo when it can be found quickly, else a song's cover.
     */
    suspend fun loadImages(context: Context, c: Content): Images = coroutineScope {
        suspend fun load(url: String?, size: Int): Bitmap? = url?.let {
            withTimeoutOrNull(IMAGE_WAIT_MS) { runCatching { ShareCard.loadImage(context, it, size) }.getOrNull() }
        }
        val songs = c.topSongs.map { async { load(it.thumbnailUrl, 200) } }
        val albums = c.topAlbums.map { async { load(it.thumbnailUrl, 400) } }
        val artists = c.topArtists.map { a ->
            async {
                val photo = a.song?.let { song ->
                    withTimeoutOrNull(PHOTO_WAIT_MS) {
                        runCatching { MusicRepository.artistIdFor(song)?.let { MusicRepository.artist(it).thumbnailUrl } }.getOrNull()
                    }
                }
                photo?.let { load(it, 200) } ?: load(a.thumbnailUrl, 200)
            }
        }
        val logo = runCatching { ContextCompat.getDrawable(context, com.opentune.core.R.drawable.ic_stat_opentune)?.toBitmap(96, 96) }.getOrNull()
        Images(songs.awaitAll(), artists.awaitAll(), albums.awaitAll(), logo)
    }

    /** The colour to build the background from: the top artist's photo, else the top song's cover. */
    fun seedOf(images: Images): Int? =
        listOfNotNull(images.artists.firstOrNull(), images.songs.firstOrNull()).firstNotNullOfOrNull { b ->
            runCatching { b.asImageBitmap().themeColorOrNull()?.toArgb() }.getOrNull()
        }

    /** Deep tones of [seed] for the background, top to bottom, and a light one for the first places. */
    internal fun tones(seed: Int): IntArray {
        val hsv = FloatArray(3)
        Color.colorToHSV(seed, hsv)
        // A grey cover stays grey; anything with colour is given enough to read as that colour.
        val s = if (hsv[1] < 0.12f) hsv[1] else hsv[1].coerceIn(0.5f, 0.85f)
        fun tone(sat: Float, v: Float) = Color.HSVToColor(floatArrayOf(hsv[0], sat, v))
        // Grey reads lighter than a colour at the same value, so it starts darker.
        val start = if (s < 0.12f) 0.3f else 0.42f
        return intArrayOf(tone(s, start), tone(s, 0.2f), tone(s * 0.9f, 0.08f), tone(minOf(s, 0.5f), 1f))
    }

    /** The card, [WIDTH] by [HEIGHT]. [fallbackSeed] colours it when no picture gives a colour. */
    fun draw(c: Content, images: Images, fallbackSeed: Int, seed: Int? = seedOf(images)): Bitmap {
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val out = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(out)
        val (top, middle, bottom, accent) = tones(seed ?: fallbackSeed).toList()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // The background: the colour deepening downwards, with a soft light up in the corner.
        paint.shader = LinearGradient(0f, 0f, w * 0.25f, h, intArrayOf(top, middle, bottom), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(w * 0.92f, h * 0.06f, w * 0.75f, withAlpha(accent, 60), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val d = Drawer(canvas, c, images, accent)
        d.header()
        d.footer()
        d.body()
        return out
    }

    /** Draws the card's parts; sizes are in pixels of the 1080-wide card, times the body's scale. */
    private class Drawer(val c: Canvas, val content: Content, val images: Images, val accent: Int) {
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val margin = 76f
        val inner = w - margin * 2
        val white = Color.WHITE
        val soft = Color.argb(185, 255, 255, 255)
        val faint = Color.argb(130, 255, 255, 255)
        val bold: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val black: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        // Where the body may go: under the title, above the footer.
        val bodyTop = 270f
        val bodyBottom = h - 150f

        fun text(size: Float, color: Int, face: Typeface = Typeface.DEFAULT, spacing: Float = 0f) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = face; letterSpacing = spacing }

        fun eyebrow(k: Float) = text(23f * k, soft, bold, 0.16f)

        fun fit(s: String, p: TextPaint, max: Float) = TextUtils.ellipsize(s, p, max, TextUtils.TruncateAt.END).toString()

        fun header() {
            val titlePaint = text(64f, white, black, -0.01f)
            c.drawText(content.title, margin, 150f, titlePaint)
            val line = listOfNotNull(content.name, content.period).joinToString(" · ").uppercase()
            val brandPaint = text(32f, white, bold)
            val brandW = brandPaint.measureText("WEAV")
            val logo = 46f
            c.drawText(fit(line, eyebrow(1f), inner - brandW - logo - 40f), margin, 204f, eyebrow(1f))

            // The name and mark, small, at the top right, level with the title.
            val right = w - margin
            c.drawText("WEAV", right - brandW, 140f, brandPaint)
            val logoTop = 140f - 11f - logo / 2f
            images.logo?.let { c.drawBitmap(it, null, RectF(right - brandW - logo - 10f, logoTop, right - brandW - 10f, logoTop + logo), image) }
        }

        fun footer() {
            c.drawText(FOOTER, margin, h - 78f, text(24f, faint, bold, 0.02f))
        }

        /**
         * The minutes and whichever sections there are, as large as fits
         * between the header and footer, with the room left over shared out
         * as space between them, so a short card doesn't bunch up at the top.
         */
        fun body() {
            val sections = sections(content)
            val room = bodyBottom - bodyTop
            val minGap = 64f
            fun total(k: Float) = heroHeight(k) + sections.sumOf { height(it, k).toDouble() }.toFloat()
            val gaps = sections.size
            var k = 1.3f
            while (k > 0.8f && total(k) + minGap * gaps > room) k -= 0.05f
            val gap = if (gaps == 0) 0f else ((room - total(k)) / gaps).coerceIn(minGap, 160f)
            val used = total(k) + gap * gaps
            var y = bodyTop + ((room - used) / 2f).coerceAtLeast(0f)
            hero(y, k)
            y += heroHeight(k) + gap
            for (s in sections) {
                when (s) {
                    Section.LISTS -> lists(y, k)
                    Section.ALBUMS -> albums(y, k)
                    Section.GENRES -> genres(y, k)
                }
                y += height(s, k) + gap
            }
        }

        fun height(s: Section, k: Float) = when (s) {
            Section.LISTS -> listsHeight(k)
            Section.ALBUMS -> albumsHeight(k)
            Section.GENRES -> genresHeight(k)
        }

        // The minutes: one huge number, what it is, and the counts under it.
        fun heroNumber(k: Float): TextPaint {
            val p = text(230f * k, white, black, -0.03f)
            val n = "%,d".format(content.minutes)
            while (p.measureText(n) > inner && p.textSize > 80f) p.textSize *= 0.94f
            return p
        }

        fun heroHeight(k: Float) = heroNumber(k).textSize * 0.74f + 52f * k + 23f * k + 22f * k + 30f * k

        fun hero(y: Float, k: Float) {
            val number = heroNumber(k)
            val base = y + number.textSize * 0.74f
            c.drawText("%,d".format(content.minutes), margin - number.textSize * 0.03f, base, number)
            val label = base + 52f * k
            c.drawText(if (content.minutes == 1L) "MINUTE LISTENED" else "MINUTES LISTENED", margin, label, eyebrow(k))
            c.drawText(counts(content), margin, label + 22f * k + 30f * k, text(30f * k, soft))
        }

        // Top songs and top artists side by side; one alone takes the whole width.
        val rowH = 108f
        fun listsHeight(scale: Float): Float {
            // Two columns get cramped fast, so the lists grow less than the minutes.
            val k = minOf(scale, 1.08f)
            val rows = maxOf(content.topSongs.size, content.topArtists.size)
            return 23f * k + 34f * k + rows * rowH * k - 22f * k
        }

        fun lists(y: Float, scale: Float) {
            val k = minOf(scale, 1.08f)
            val both = content.topSongs.isNotEmpty() && content.topArtists.isNotEmpty()
            val colGap = 40f
            val colW = if (both) (inner - colGap) / 2f else inner
            var x = margin
            if (content.topSongs.isNotEmpty()) {
                column("TOP SONGS", content.topSongs, images.songs, round = false, x, y, colW, k)
                x += colW + colGap
            }
            if (content.topArtists.isNotEmpty()) column("TOP ARTISTS", content.topArtists, images.artists, round = true, x, y, colW, k)
        }

        fun column(heading: String, items: List<Item>, pictures: List<Bitmap?>, round: Boolean, x: Float, y: Float, colW: Float, k: Float) {
            c.drawText(heading, x, y + 23f * k, eyebrow(k))
            val rank = text(30f * k, soft, bold)
            val title = text(30f * k, white, bold)
            val sub = text(23f * k, soft)
            val side = 80f * k
            val rankW = 42f * k
            items.forEachIndexed { i, item ->
                val top = y + 23f * k + 34f * k + i * rowH * k
                val mid = top + side / 2f
                rank.color = if (i == 0) accent else soft
                c.drawText("${i + 1}", x, mid + rank.textSize * 0.36f, rank)
                val box = RectF(x + rankW, top, x + rankW + side, top + side)
                picture(pictures.getOrNull(i), item.title, box, round)
                val tx = box.right + 22f * k
                val max = x + colW - tx
                c.drawText(fit(item.title, title, max), tx, mid - 4f * k, title)
                val line = if (item.subtitle.isBlank()) item.minutes else "${item.subtitle} · ${item.minutes}"
                // The minutes stay whole; a long artist name gives way.
                val subLine = if (item.subtitle.isBlank() || sub.measureText(line) <= max) line
                else fit(item.subtitle, sub, max - sub.measureText(" · ${item.minutes}")) + " · ${item.minutes}"
                c.drawText(subLine, tx, mid + sub.textSize * 1.25f, sub)
            }
        }

        // Up to three album covers across the width, with their titles under them.
        val albumGap = 36f
        val cover = (inner - albumGap * 2) / 3f
        fun albumsHeight(k: Float) = 23f * k + 34f * k + cover + 46f * k + 32f * k

        fun albums(y: Float, k: Float) {
            c.drawText("TOP ALBUMS", margin, y + 23f * k, eyebrow(k))
            val top = y + 23f * k + 34f * k
            val title = text(27f * k, white, bold)
            val sub = text(22f * k, soft)
            content.topAlbums.forEachIndexed { i, a ->
                val x = margin + i * (cover + albumGap)
                picture(images.albums.getOrNull(i), a.title, RectF(x, top, x + cover, top + cover), round = false, corner = 22f)
                c.drawText(fit(a.title, title, cover), x, top + cover + 46f * k - 6f * k, title)
                if (a.subtitle.isNotBlank()) c.drawText(fit(a.subtitle, sub, cover), x, top + cover + 46f * k + 26f * k, sub)
            }
        }

        // Genres as small pills, as many as fit on one line.
        fun genresHeight(k: Float) = 23f * k + 34f * k + 54f * k

        fun genres(y: Float, k: Float) {
            c.drawText("TOP GENRES", margin, y + 23f * k, eyebrow(k))
            val top = y + 23f * k + 34f * k
            val p = text(24f * k, white, bold)
            val pillH = 54f * k
            val pad = 24f * k
            var x = margin
            for (g in content.genres) {
                val pw = p.measureText(g) + pad * 2
                if (x + pw > margin + inner) break
                val r = RectF(x, top, x + pw, top + pillH)
                fill.style = Paint.Style.FILL
                fill.color = Color.argb(34, 255, 255, 255)
                c.drawRoundRect(r, pillH / 2f, pillH / 2f, fill)
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = 2f
                fill.color = Color.argb(60, 255, 255, 255)
                c.drawRoundRect(r, pillH / 2f, pillH / 2f, fill)
                fill.style = Paint.Style.FILL
                c.drawText(g, x + pad, r.centerY() + p.textSize * 0.36f, p)
                x += pw + 14f * k
            }
        }

        /** A cover or photo, clipped; while there's none, a soft tile with the first letter. */
        fun picture(b: Bitmap?, name: String, r: RectF, round: Boolean, corner: Float = 14f) {
            val clip = Path().apply {
                if (round) addOval(r, Path.Direction.CW) else addRoundRect(r, corner, corner, Path.Direction.CW)
            }
            c.withClip(clip) {
                if (b != null) {
                    drawBitmap(ShareCard.centerSquare(b), null, r, image)
                } else {
                    fill.style = Paint.Style.FILL
                    fill.color = Color.argb(40, 255, 255, 255)
                    drawRect(r, fill)
                    val letter = name.trim().firstOrNull()?.uppercase() ?: ""
                    val p = text(r.width() * 0.42f, Color.argb(170, 255, 255, 255), bold)
                    drawText(letter, r.centerX() - p.measureText(letter) / 2f, r.centerY() + p.textSize * 0.36f, p)
                }
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    /** The picture's file name in the gallery: one per save, so an older one is never replaced. */
    internal fun fileName(nowMs: Long): String = "OpenTune-Replay-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs)) + ".png"
}

/**
 * The "Share my Replay" popup: the card as it will be sent, made from
 * [summary] for [period], with Save and Share under it.
 */
@Composable
fun ReplayShareSheet(summary: ReplaySummary, period: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val ui by AppSettings.ui.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val name = account?.name?.takeIf { signedIn }
    val content = remember(summary, period, name) { ReplayCard.content(summary, year, period, name) }
    val fallback = MaterialTheme.colorScheme.primary.toArgb()
    var card by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(content) {
        val images = ReplayCard.loadImages(context, content)
        card = runCatching { withContext(Dispatchers.Default) { ReplayCard.draw(content, images, fallback) } }
            .onFailure { Log.w("ReplayCard", "couldn't draw the card", it) }.getOrNull()
    }

    fun save() {
        val b = card ?: return
        saving = true
        scope.launch {
            val saved = runCatching { ShareCard.saveImage(context, b, ReplayCard.fileName(System.currentTimeMillis())) }
                .onFailure { Log.w("ReplayCard", "couldn't save the card", it) }.isSuccess
            saving = false
            if (saved) com.opentune.data.Pills.done("Saved to Pictures/WEAV", context) else com.opentune.data.Pills.error("Couldn't save the picture", context)
        }
    }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else com.opentune.data.Pills.error("Storage access is needed to save the picture", context)
    }

    FloatingCard(
        onDismiss = onDismiss,
        title = "Share my Replay",
        icon = Icons.Rounded.Share,
        subtitle = "Your minutes, songs, artists and albums, in one picture.",
        actions = {
            SheetButton(
                "Save",
                icon = Icons.Rounded.Download,
                tone = SheetTone.Neutral,
                enabled = card != null && !saving,
                loading = saving,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (ShareCard.saveNeedsPermission &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) {
                        storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        save()
                    }
                },
            )
            SheetButton(
                "Share",
                icon = Icons.Rounded.Share,
                tone = SheetTone.Primary,
                enabled = card != null,
                modifier = Modifier.weight(1f),
                onClick = {
                    val b = card ?: return@SheetButton
                    haptics.press()
                    scope.launch {
                        ShareCard.sendImage(context, b, "OpenTune-Replay.png", "My ${content.title} on WEAV · ${content.period}", "Replay")
                    }
                },
            )
        },
    ) {
        // The card itself, as large as the popup allows, story-shaped.
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).padding(top = 2.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
            val frame = Modifier
                .heightIn(max = 480.dp)
                .aspectRatio(ReplayCard.WIDTH / ReplayCard.HEIGHT.toFloat(), matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(22.dp))
            Crossfade(card, animationSpec = if (ui.reduceAnimation) snap() else tween(260), label = "card") { b ->
                if (b != null) {
                    Image(b.asImageBitmap(), "Your Replay card", frame)
                } else {
                    Box(frame.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
            }
        }
    }
}
