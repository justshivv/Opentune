package com.opentune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toArgb
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.themeColorOrNull
import com.materialkolor.rememberDynamicColorScheme
import com.opentune.data.subsonic.Subsonic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AppFont
import com.opentune.data.settings.ThemeMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The colour read from the playing song's cover, or null with nothing playing. */
internal val LocalArtworkSeed = staticCompositionLocalOf<Color?> { null }

/**
 * The phone's theme: its colour scheme made the same way, by material-kolor
 * from the chosen accent and palette style, or from the playing song's cover
 * when "Colour from artwork" is on; true black when asked; the Outfit
 * typeface and its corner sizes. Colours ease to a new song's over 600ms.
 */
@Composable
fun DesktopTheme(artworkSeed: Color? = null, content: @Composable () -> Unit) {
    val settings by AppSettings.theme.collectAsState()
    val dark = when (settings.mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    val seed = artworkSeed?.takeIf { settings.colorFromArtwork }
    val generated = rememberDynamicColorScheme(
        seedColor = seed ?: Color(settings.seedColor),
        isDark = dark,
        isAmoled = settings.pureBlack,
        style = PaletteStyle.valueOf(settings.paletteStyle.name),
    )
    val scheme = if (dark && settings.pureBlack) generated.toPureBlack() else generated
    val type = rememberType(settings)
    MaterialTheme(colorScheme = scheme.animated(), typography = type, shapes = AppShapes) {
        CompositionLocalProvider(LocalArtworkSeed provides artworkSeed, content = content)
    }
}

/**
 * The player's own dark scheme, seeded from the cover as on the phone, so it
 * reads as a dark, immersive surface whatever the app's theme is.
 */
@Composable
internal fun PlayerTheme(seed: Color?, content: @Composable () -> Unit) {
    val settings by AppSettings.theme.collectAsState()
    val scheme = rememberDynamicColorScheme(
        seedColor = seed ?: Color(settings.seedColor),
        isDark = true,
        isAmoled = settings.pureBlack,
        style = PaletteStyle.Vibrant,
    )
    val amoled = settings.playerBackground == com.opentune.data.settings.PlayerBackground.AMOLED
    MaterialTheme(
        colorScheme = if (amoled) scheme.toPureBlack() else scheme.animated(),
        typography = rememberType(settings),
        shapes = AppShapes,
        content = content,
    )
}

@Composable
private fun rememberType(settings: com.opentune.data.settings.ThemeSettings): Typography {
    val family = when (settings.font) {
        AppFont.OUTFIT -> Outfit
        AppFont.SERIF -> FontFamily.Serif
        AppFont.MONO -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
    return remember(family, settings.textWeight) { readableType(family, settings.textWeight.minimum) }
}

/** As on the phone: true black, with greys that don't lean toward the accent. */
private fun ColorScheme.toPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF242426),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0E0E0F),
    surfaceContainer = Color(0xFF1B1B1D),
    surfaceContainerHigh = Color(0xFF232325),
    surfaceContainerHighest = Color(0xFF2D2D30),
    onSurface = Color(0xFFF2F2F3),
    onBackground = Color(0xFFF2F2F3),
    onSurfaceVariant = Color(0xFF9A9AA0),
    outline = Color(0xFF4A4A4F),
    outlineVariant = Color(0xFF2E2E32),
)

/** The scheme with every colour easing to its new value, so a change of seed fades rather than snaps. */
@Composable
private fun ColorScheme.animated(): ColorScheme {
    val spec = tween<Color>(durationMillis = 600)
    @Composable
    fun Color.a() = animateColorAsState(this, spec, label = "scheme").value
    return copy(
        primary = primary.a(),
        onPrimary = onPrimary.a(),
        primaryContainer = primaryContainer.a(),
        onPrimaryContainer = onPrimaryContainer.a(),
        secondary = secondary.a(),
        onSecondary = onSecondary.a(),
        secondaryContainer = secondaryContainer.a(),
        onSecondaryContainer = onSecondaryContainer.a(),
        tertiary = tertiary.a(),
        tertiaryContainer = tertiaryContainer.a(),
        onTertiaryContainer = onTertiaryContainer.a(),
        background = background.a(),
        onBackground = onBackground.a(),
        surface = surface.a(),
        onSurface = onSurface.a(),
        surfaceVariant = surfaceVariant.a(),
        onSurfaceVariant = onSurfaceVariant.a(),
        surfaceContainerLowest = surfaceContainerLowest.a(),
        surfaceContainerLow = surfaceContainerLow.a(),
        surfaceContainer = surfaceContainer.a(),
        surfaceContainerHigh = surfaceContainerHigh.a(),
        surfaceContainerHighest = surfaceContainerHighest.a(),
        outline = outline.a(),
        outlineVariant = outlineVariant.a(),
    )
}

private val seedCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
private const val NO_COLOR = 0

/**
 * A cover's dominant usable colour, for seeding a scheme, read the way the
 * phone reads it (material-kolor's theme colour of a small copy). Null until
 * read and for covers without one; each cover is read once.
 */
@Composable
internal fun rememberArtworkSeed(url: String?): Color? {
    val context = LocalPlatformContext.current
    var seed by remember(url) { mutableStateOf(url?.let(seedCache::get)?.takeIf { it != NO_COLOR }?.let(::Color)) }
    LaunchedEffect(url) {
        if (url == null || seedCache.containsKey(url)) return@LaunchedEffect
        val result = SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(Subsonic.resolveCover(url)).size(128).build())
        val bitmap = (result as? SuccessResult)?.image?.toBitmap()
        val argb = bitmap?.let {
            withContext(Dispatchers.Default) { runCatching { it.asComposeImageBitmap().themeColorOrNull()?.toArgb() }.getOrNull() }
        }
        seedCache[url] = argb ?: NO_COLOR
        seed = argb?.let(::Color)
    }
    return seed
}

private val outfitData: ByteArray by lazy {
    Thread.currentThread().contextClassLoader.getResourceAsStream("font/outfit_variable.ttf")!!.use { it.readBytes() }
}

/** Outfit, OpenTune's typeface (SIL Open Font License; see third_party/outfit), one variable file. */
private val Outfit: FontFamily by lazy {
    FontFamily(
        listOf(400, 500, 600, 700, 800).map { w ->
            Font("Outfit-$w", outfitData, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )
}

private val base = Typography()

private fun TextStyle.outfit(weight: FontWeight? = null, spacing: Float? = null) =
    copy(fontFamily = Outfit, fontWeight = weight ?: fontWeight, letterSpacing = spacing?.sp ?: letterSpacing)

private val Type: Typography by lazy {
    Typography(
        displayLarge = base.displayLarge.outfit(FontWeight.Bold, -1.2f),
        displayMedium = base.displayMedium.outfit(FontWeight.Bold, -1f),
        displaySmall = base.displaySmall.outfit(FontWeight.Bold, -0.6f),
        headlineLarge = base.headlineLarge.outfit(FontWeight.Bold, -0.4f),
        headlineMedium = base.headlineMedium.outfit(FontWeight.SemiBold, -0.3f),
        headlineSmall = base.headlineSmall.outfit(FontWeight.SemiBold, -0.2f),
        titleLarge = base.titleLarge.outfit(FontWeight.SemiBold, -0.1f),
        titleMedium = base.titleMedium.outfit(FontWeight.Medium),
        titleSmall = base.titleSmall.outfit(FontWeight.Medium),
        bodyLarge = base.bodyLarge.outfit(),
        bodyMedium = base.bodyMedium.outfit(),
        bodySmall = base.bodySmall.outfit(),
        labelLarge = base.labelLarge.outfit(FontWeight.Medium),
        labelMedium = base.labelMedium.outfit(FontWeight.Medium),
        labelSmall = base.labelSmall.outfit(FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private fun readableType(family: FontFamily, minimum: Int): Typography {
    fun TextStyle.readable() = copy(fontFamily = family, fontWeight = FontWeight(maxOf(fontWeight?.weight ?: 400, minimum)))
    return Typography(
        displayLarge = Type.displayLarge.readable(), displayMedium = Type.displayMedium.readable(), displaySmall = Type.displaySmall.readable(),
        headlineLarge = Type.headlineLarge.readable(), headlineMedium = Type.headlineMedium.readable(), headlineSmall = Type.headlineSmall.readable(),
        titleLarge = Type.titleLarge.readable(), titleMedium = Type.titleMedium.readable(), titleSmall = Type.titleSmall.readable(),
        bodyLarge = Type.bodyLarge.readable(), bodyMedium = Type.bodyMedium.readable(), bodySmall = Type.bodySmall.readable(),
        labelLarge = Type.labelLarge.readable(), labelMedium = Type.labelMedium.readable(), labelSmall = Type.labelSmall.readable(),
    )
}
