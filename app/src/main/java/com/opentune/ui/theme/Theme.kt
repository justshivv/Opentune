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
    val scheme = if (useWallpaper) {
        val context = LocalContext.current
        val wallpaper = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (dark && settings.pureBlack) wallpaper.toPureBlack() else wallpaper
    } else {
        generated
    }

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

private fun ColorScheme.toPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
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

private val base = Typography()

val AppTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium,
    labelSmall = base.labelSmall,
)

/** Large, bold lyric lines. */
val LyricsTextStyle = TextStyle(
    fontSize = 28.sp,
    lineHeight = 36.sp,
    fontWeight = FontWeight.ExtraBold,
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
