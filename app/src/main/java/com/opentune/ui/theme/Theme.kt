package com.opentune.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.opentune.R
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import com.opentune.data.settings.PaletteStyleOption
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings

@Composable
fun ThemeSettings.isDark(): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

fun PaletteStyleOption.toPaletteStyle(): PaletteStyle = PaletteStyle.valueOf(name)

/**
 * The app theme. The scheme comes from, in order: the playing track's artwork
 * (when that option is on and a color has been read from it), Material You
 * wallpaper colors (Android 12+), or the chosen accent. Color changes animate,
 * so a new track's colors ease in rather than snap.
 */
@Composable
fun OpenTuneTheme(
    settings: ThemeSettings,
    artworkSeed: Color? = null,
    content: @Composable () -> Unit,
) {
    val dark = settings.isDark()
    val seed = artworkSeed?.takeIf { settings.colorFromArtwork }
    val useWallpaper = seed == null && settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val generated = rememberDynamicColorScheme(
        seedColor = seed ?: Color(settings.seedColor),
        isDark = dark,
        isAmoled = settings.pureBlack,
        style = settings.paletteStyle.toPaletteStyle(),
    )
    val base = if (useWallpaper) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        generated
    }
    val scheme = if (dark && settings.pureBlack) base.toPureBlack() else base

    MaterialTheme(
        colorScheme = scheme.animated(),
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/**
 * A dark scheme seeded from artwork, for the full player: it always reads as
 * a dark, immersive surface whatever the app theme is.
 */
@Composable
fun PlayerTheme(seed: Color?, settings: ThemeSettings, content: @Composable () -> Unit) {
    val scheme = rememberDynamicColorScheme(
        seedColor = seed ?: Color(settings.seedColor),
        isDark = true,
        isAmoled = settings.pureBlack,
        style = PaletteStyle.Vibrant,
    )
    MaterialTheme(
        colorScheme = scheme.animated(),
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/**
 * Black backgrounds with neutral gray cards, so color comes only from the
 * accent and the artwork, never from tinted surfaces.
 */
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

private val baseType = Typography()

/**
 * Outfit, OpenTune's typeface: a geometric sans with round, open letters
 * (SIL Open Font License; see third_party/outfit). One variable font file,
 * instanced at the weights the app uses.
 */
@OptIn(ExperimentalTextApi::class)
val Outfit = FontFamily(
    listOf(300, 400, 500, 600, 700, 800).map { w ->
        Font(R.font.outfit_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private fun TextStyle.outfit(weight: FontWeight? = null, spacing: Float? = null) =
    copy(fontFamily = Outfit, fontWeight = weight ?: fontWeight, letterSpacing = spacing?.sp ?: letterSpacing)

val AppTypography = Typography(
    displayLarge = baseType.displayLarge.outfit(FontWeight.Bold, -1.2f),
    displayMedium = baseType.displayMedium.outfit(FontWeight.Bold, -1f),
    displaySmall = baseType.displaySmall.outfit(FontWeight.Bold, -0.6f),
    headlineLarge = baseType.headlineLarge.outfit(FontWeight.Bold, -0.4f),
    headlineMedium = baseType.headlineMedium.outfit(FontWeight.SemiBold, -0.3f),
    headlineSmall = baseType.headlineSmall.outfit(FontWeight.SemiBold, -0.2f),
    titleLarge = baseType.titleLarge.outfit(FontWeight.SemiBold, -0.1f),
    titleMedium = baseType.titleMedium.outfit(FontWeight.Medium),
    titleSmall = baseType.titleSmall.outfit(FontWeight.Medium),
    bodyLarge = baseType.bodyLarge.outfit(),
    bodyMedium = baseType.bodyMedium.outfit(),
    bodySmall = baseType.bodySmall.outfit(),
    labelLarge = baseType.labelLarge.outfit(FontWeight.Medium),
    labelMedium = baseType.labelMedium.outfit(FontWeight.Medium),
    labelSmall = baseType.labelSmall.outfit(FontWeight.Medium),
)

/** Large, bold lyric lines. */
val LyricsTextStyle = TextStyle(
    fontFamily = Outfit,
    fontSize = 28.sp,
    lineHeight = 36.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-0.3).sp,
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun rememberSeedSwatches(colors: List<Int>): List<Color> = remember(colors) { colors.map { Color(it) } }
