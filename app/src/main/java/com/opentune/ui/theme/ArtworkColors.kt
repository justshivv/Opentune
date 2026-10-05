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
import com.materialkolor.ktx.themeColorOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val seedCache = ConcurrentHashMap<String, Int>()
private const val NO_COLOR = 0

/**
 * The dominant usable color of an artwork URL, for seeding a color scheme.
 * Null until it has been read, and for artwork without a usable color.
 * Results are cached per URL, so the same cover is only read once.
 */
@Composable
fun rememberArtworkSeed(url: String?): Color? {
    val context = LocalContext.current
    var seed by remember(url) { mutableStateOf(url?.let(seedCache::get)?.takeIf { it != NO_COLOR }?.let(::Color)) }
    LaunchedEffect(url) {
        if (url == null || seedCache.containsKey(url)) return@LaunchedEffect
        val argb = extractSeed(context, url)
        seedCache[url] = argb ?: NO_COLOR
        seed = argb?.let(::Color)
    }
    return seed
}

private suspend fun extractSeed(context: Context, url: String): Int? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(128)
        .allowHardware(false)
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    return withContext(Dispatchers.Default) {
        runCatching {
            result.image.toBitmap().asImageBitmap().themeColorOrNull()?.let { color ->
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
