package com.opentune.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.lightColorScheme
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

/**
 * The phone app's look as it comes out of the box: dark with true black
 * surfaces, the default red accent, the Outfit typeface and its corner sizes.
 */
@Composable
fun DesktopTheme(content: @Composable () -> Unit) {
    val settings by AppSettings.theme.collectAsState()
    val dark = when (settings.mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    val colors = if (dark) { if (settings.pureBlack) Scheme else Scheme.copy(background = Color(0xff17171b), surface = Color(0xff17171b)) }
        else lightColorScheme(primary = Color(0xff904a44), primaryContainer = Color(0xffffdad6), secondary = Color(0xff775652))
    val family = when (settings.font) {
        AppFont.OUTFIT -> Outfit
        AppFont.SERIF -> FontFamily.Serif
        AppFont.MONO -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
    val type = remember(family, settings.textWeight) { readableType(family, settings.textWeight.minimum) }
    MaterialTheme(colorScheme = colors, typography = type, shapes = AppShapes, content = content)
}

/**
 * The phone's default scheme: what material-kolor makes of its first accent
 * colour (red, 0xFFE53935, tonal spot, dark), written out so the desktop
 * build needn't carry that library.
 */
private val Scheme = darkColorScheme(
    primary = Color(0xFFFFB4AC),
    onPrimary = Color(0xFF561E1A),
    primaryContainer = Color(0xFF73332E),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFE7BDB8),
    onSecondary = Color(0xFF442927),
    secondaryContainer = Color(0xFF5D3F3C),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFE0C38C),
    onTertiary = Color(0xFF3F2E04),
    tertiaryContainer = Color(0xFF584419),
    onTertiaryContainer = Color(0xFFFEDFA6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    inversePrimary = Color(0xFF904A44),
    surfaceTint = Color(0xFFFFB4AC),
).toPureBlack()

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
