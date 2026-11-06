package com.opentune.tv

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What every TV screen keeps clear of: the edges a television may crop. */
object TvSafe {
    val Horizontal = 56.dp
    val Vertical = 28.dp
}

/** The colours of the TV app: always dark, as a living-room screen is, with one accent. */
object TvColors {
    val Background = Color(0xFF0B0B0F)
    val Surface = Color(0xFF17171D)
    val SurfaceHigh = Color(0xFF22222B)
    val Accent = Color(0xFF9CC2FF)
    val OnAccent = Color(0xFF06223F)
    val Text = Color(0xFFF2F2F5)
    val TextDim = Color(0xFFA6A6B2)
    val Like = Color(0xFFFF5C7A)
}

private val scheme = darkColorScheme(
    primary = TvColors.Accent,
    onPrimary = TvColors.OnAccent,
    background = TvColors.Background,
    onBackground = TvColors.Text,
    surface = TvColors.Surface,
    onSurface = TvColors.Text,
    surfaceVariant = TvColors.SurfaceHigh,
    onSurfaceVariant = TvColors.TextDim,
    error = TvColors.Like,
)

/**
 * Type read from a sofa: nothing under 16sp, and body text at 20. A TV's
 * density differs by model, so these are in sp and stay readable on 1080p
 * and 4K alike.
 */
private val type = Typography(
    displaySmall = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    bodySmall = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    // A TV app is always dark: it's the room that's lit, not the app.
    MaterialTheme(colorScheme = scheme, typography = type) {
        // Text on a TV screen is light unless a screen says otherwise; without this, anything
        // outside a Material surface would draw black on the dark background.
        CompositionLocalProvider(LocalContentColor provides TvColors.Text, content = content)
    }
}
