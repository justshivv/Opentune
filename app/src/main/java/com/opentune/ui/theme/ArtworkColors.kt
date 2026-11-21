package com.opentune.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.hct.Hct
import com.materialkolor.ktx.themeColorOrNull
import com.materialkolor.quantize.QuantizerCelebi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val seedCache = ConcurrentHashMap<String, Int>()
private const val NO_COLOR = 0

/**
 * The dominant usable color of an artwork URL, for seeding a color scheme.
 * Null until it has been read, and for artwork without a usable color.
 * Results are cached per URL, so the same cover is only read once.
 */
@Composable
fun rememberArtworkSeed(url: String?, keepLast: Boolean = false): Color? {
    val context = LocalContext.current
    fun cached() = url?.let(seedCache::get)?.takeIf { it != NO_COLOR }?.let(::Color)
    // With [keepLast], the colour so far stays until the new cover's is read: the player's
    // colours then ease from one song's straight to the next's, rather than by way of the
    // app's own accent for the moment the new cover takes to read.
    val held = remember { mutableStateOf(cached()) }
    var seed by if (keepLast) held else remember(url) { mutableStateOf(cached()) }
    LaunchedEffect(url) {
        if (url == null) { seed = null; return@LaunchedEffect }
        if (seedCache.containsKey(url)) { seed = cached(); return@LaunchedEffect }
        val argb = extractSeed(context, url)
        seedCache[url] = argb ?: NO_COLOR
        seed = argb?.let(::Color)
    }
    return seed
}

/** The cover, small, as a bitmap that can be read. */
private suspend fun smallArtwork(context: Context, url: String): android.graphics.Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(128)
        .allowHardware(false)
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    return runCatching { result.image.toBitmap() }.getOrNull()
}

private suspend fun extractSeed(context: Context, url: String): Int? {
    val bitmap = smallArtwork(context, url) ?: return null
    return withContext(Dispatchers.Default) {
        runCatching {
            bitmap.asImageBitmap().themeColorOrNull()?.let { color ->
                val c = color
                android.graphics.Color.argb(
                    255,
                    (c.red * 255).toInt(),
                    (c.green * 255).toInt(),
                    (c.blue * 255).toInt(),
                )
            }
        }.getOrNull()
    }
}

private val paletteCache = ConcurrentHashMap<String, IntArray>()

/**
 * A few of an artwork URL's own colours, each clearly apart from the
 * others, the ones that fill most of the cover first. Empty until read, or
 * when the cover can't be loaded; cached per URL like [rememberArtworkSeed].
 */
@Composable
fun rememberArtworkPalette(url: String?, keepLast: Boolean = false): List<Color> {
    val context = LocalContext.current
    fun cached() = url?.let(paletteCache::get)?.map { Color(it) }.orEmpty()
    // With [keepLast], the colours so far stay until the new cover's are read (see rememberArtworkSeed).
    val held = remember { mutableStateOf(cached()) }
    var palette by if (keepLast) held else remember(url) { mutableStateOf(cached()) }
    LaunchedEffect(url) {
        if (url == null) { palette = emptyList(); return@LaunchedEffect }
        if (paletteCache.containsKey(url)) { palette = cached(); return@LaunchedEffect }
        val argb = extractPalette(context, url)
        if (argb == null) { if (keepLast) palette = emptyList(); return@LaunchedEffect }
        paletteCache[url] = argb
        palette = argb.map { Color(it) }
    }
    return palette
}

private suspend fun extractPalette(context: Context, url: String): IntArray? {
    val bitmap = smallArtwork(context, url) ?: return null
    return withContext(Dispatchers.Default) {
        runCatching {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val counts = QuantizerCelebi.quantize(pixels, 12)
            val total = counts.values.sum().toFloat().coerceAtLeast(1f)
            val picked = mutableListOf<Hct>()
            counts.entries
                .filter { it.value / total >= 0.01f }
                .map { Hct.fromInt(it.key) to it.value / total }
                // Colour counts as well as area: a small bright patch beats a big grey one.
                .sortedByDescending { (hct, share) -> share * (1f + hct.chroma.toFloat() / 24f) }
                .forEach { (hct, _) -> if (picked.size < PALETTE_SIZE && picked.none { it.near(hct) }) picked += hct }
            picked.map { it.toInt() }.toIntArray()
        }.getOrNull()
    }
}

private const val PALETTE_SIZE = 4

/** Too alike to both be worth showing: greys of about the same lightness, or about the same hue and lightness. */
private fun Hct.near(other: Hct): Boolean {
    val toneGap = abs(tone - other.tone)
    if (chroma < 12 || other.chroma < 12) return toneGap < 18 && abs(chroma - other.chroma) < 16
    val hueGap = abs(hue - other.hue).let { minOf(it, 360 - it) }
    return hueGap < 24 && toneGap < 24
}
