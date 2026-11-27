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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
 * The desktop's theme, in NepTune's own language as the phone and the web player speak it
 * (web/app/src/styles/opentune.css): warm tonal surfaces leaning a few percent toward the
 * accent, a warm off-white ink, the accent as the key colour for what's chosen and playing, so
 * the covers bring the rest of the colour; a light version in light mode, and the chosen
 * typeface. The phone's true-black setting is left to the player here: a whole desktop window
 * of black is harsh on a big screen.
 */
@Composable
fun DesktopTheme(artworkSeed: Color? = null, content: @Composable () -> Unit) {
    val settings by AppSettings.theme.collectAsState()
    val dark = when (settings.mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    val scheme = studioScheme(dark, Color(settings.seedColor))
    val type = rememberType(settings)
    MaterialTheme(colorScheme = scheme, typography = type, shapes = AppShapes) {
        CompositionLocalProvider(LocalArtworkSeed provides artworkSeed, content = content)
    }
}

/** Colours that keep their meaning in either theme. */
internal object Studio {
    val Destructive = Color(0xFFFF453A)
}

/** [base] leaning [amount] of the way toward [seed], as opentune.css's color-mix does. */
private fun lean(base: Long, seed: Color, amount: Float): Color = androidx.compose.ui.graphics.lerp(Color(base), seed, amount)

/**
 * The window's scheme from the accent [seed]: dark is a warm near-black (the web's #11100f,
 * a step lighter for a big screen) with layers rising in small steps, light a warm white.
 * The key colour, as the web's --keyColor, is pale on dark and deep on light.
 */
internal fun studioScheme(dark: Boolean, seed: Color = Color(0xFFE53935)): ColorScheme = if (dark) {
    val page = lean(0xFF151211, seed, 0.035f)
    val key = androidx.compose.ui.graphics.lerp(Color(0xFFFFE0D5), seed, 0.32f)
    darkColorScheme(
        primary = key,
        onPrimary = Color(0xFF26110D),
        primaryContainer = key.copy(alpha = 0.18f),
        onPrimaryContainer = Color(0xFFF6EDE9),
        secondary = Color(0xFFD9CCC6),
        onSecondary = Color(0xFF211511),
        secondaryContainer = lean(0xFF2A2422, seed, 0.05f),
        onSecondaryContainer = Color(0xFFF6EDE9),
        tertiary = Color(0xFFD9CCC6),
        tertiaryContainer = lean(0xFF2A2422, seed, 0.05f),
        onTertiaryContainer = Color(0xFFF6EDE9),
        background = page,
        onBackground = Color(0xFFF6EDE9),
        surface = page,
        onSurface = Color(0xFFF6EDE9),
        surfaceVariant = lean(0xFF2A2422, seed, 0.05f),
        onSurfaceVariant = Color(0xFFB9ACA6),
        surfaceContainerLowest = lean(0xFF0D0B0A, seed, 0.02f),
        surfaceContainerLow = lean(0xFF110F0E, seed, 0.03f),
        surfaceContainer = lean(0xFF1C1817, seed, 0.04f),
        surfaceContainerHigh = lean(0xFF25201F, seed, 0.05f),
        surfaceContainerHighest = lean(0xFF302A28, seed, 0.05f),
        outline = Color(0xFF5C504C),
        outlineVariant = Color(0xFF2E2725),
        error = Studio.Destructive,
        surfaceTint = key,
    )
} else {
    val page = lean(0xFFFFFAF6, seed, 0.025f)
    val key = androidx.compose.ui.graphics.lerp(Color(0xFF44211B), seed, 0.65f)
    lightColorScheme(
        primary = key,
        onPrimary = Color.White,
        primaryContainer = key.copy(alpha = 0.12f),
        onPrimaryContainer = Color(0xFF201A19),
        secondary = Color(0xFF4A3E3A),
        secondaryContainer = lean(0xFFEDE4DF, seed, 0.03f),
        onSecondaryContainer = Color(0xFF201A19),
        tertiary = Color(0xFF4A3E3A),
        tertiaryContainer = lean(0xFFEDE4DF, seed, 0.03f),
        onTertiaryContainer = Color(0xFF201A19),
        background = page,
        onBackground = Color(0xFF201A19),
        surface = page,
        onSurface = Color(0xFF201A19),
        surfaceVariant = lean(0xFFEDE4DF, seed, 0.03f),
        onSurfaceVariant = Color(0xFF655854),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = lean(0xFFF7F0EB, seed, 0.03f),
        surfaceContainer = lean(0xFFF1E9E4, seed, 0.03f),
        surfaceContainerHigh = lean(0xFFEBE1DB, seed, 0.03f),
        surfaceContainerHighest = lean(0xFFE2D7D0, seed, 0.03f),
        outline = Color(0xFFBFB1AB),
        outlineVariant = Color(0xFFE6DCD6),
        error = Color(0xFFE5332A),
        surfaceTint = key,
    )
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
        AppFont.SERIF -> FontFamily.Serif
        AppFont.MONO -> FontFamily.Monospace
        // NepTune's own typeface, the default here (the default choice, SYSTEM, and OUTFIT alike):
        // the system's sans (DejaVu on Linux, Segoe on Windows) looked unlike the rest of NepTune.
        else -> Outfit
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
