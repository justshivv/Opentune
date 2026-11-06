package com.opentune.tv

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Two colours of a cover, made dark enough to sit behind white text: [top] the
 * colour that fills the most of it, [bottom] a second, further from the first.
 * Both keep the cover's own hue; only how light they are is set.
 */
class CoverColors(val top: Color, val bottom: Color) {
    companion object {
        val Neutral = CoverColors(Color(0xFF1B1B24), Color(0xFF0B0B0F))
    }
}

private val cache = ConcurrentHashMap<String, IntArray>()

/** The colours of the cover at [url], easing from one song's to the next; neutral until it's read. */
@Composable
fun rememberCoverColors(url: String?): CoverColors {
    val context = LocalContext.current
    var picked by remember(url) { mutableStateOf(url?.let(cache::get)?.let(::colorsOf) ?: CoverColors.Neutral) }
    LaunchedEffect(url) {
        if (url == null || cache.containsKey(url)) return@LaunchedEffect
        val argb = read(context, url) ?: return@LaunchedEffect
        cache[url] = argb
        picked = colorsOf(argb)
    }
    val spec = tween<Color>(900)
    val top by animateColorAsState(picked.top, spec, label = "coverTop")
    val bottom by animateColorAsState(picked.bottom, spec, label = "coverBottom")
    return remember(top, bottom) { CoverColors(top, bottom) }
}

private fun colorsOf(argb: IntArray): CoverColors {
    val hcts = argb.map { Hct.fromInt(it) }
    val first = hcts.firstOrNull() ?: return CoverColors.Neutral
    val second = hcts.getOrNull(1) ?: Hct.from((first.hue + 30) % 360, first.chroma, first.tone)
    fun at(c: Hct, tone: Double, chroma: Double) = Color(Hct.from(c.hue, minOf(c.chroma, chroma), tone).toInt())
    return CoverColors(top = at(first, 26.0, 60.0), bottom = at(second, 10.0, 30.0))
}

/** The cover's main colours, the ones that fill most of it first, or null if it can't be loaded. */
private suspend fun read(context: Context, url: String): IntArray? {
    val request = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
    val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)
        ?.let { runCatching { it.image.toBitmap() }.getOrNull() } ?: return null
    return withContext(Dispatchers.Default) {
        runCatching {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val counts = QuantizerCelebi.quantize(pixels, 10)
            val total = counts.values.sum().toFloat().coerceAtLeast(1f)
            val picked = mutableListOf<Hct>()
            counts.entries
                .filter { it.value / total >= 0.02f }
                .map { Hct.fromInt(it.key) to it.value / total }
                // Colour counts as well as area: a small bright patch beats a big grey one.
                .sortedByDescending { (hct, share) -> share * (1f + hct.chroma.toFloat() / 24f) }
                .forEach { (hct, _) ->
                    if (picked.size < 2 && picked.none { kotlin.math.abs(it.hue - hct.hue) < 30 }) picked += hct
                }
            picked.map { it.toInt() }.toIntArray().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
