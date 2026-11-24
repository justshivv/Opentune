package com.opentune.tv

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The TV app's design tokens. Every screen draws from these and from TvKit, so the whole app
 * keeps one look: dark, solid colours, one accent, large type, nothing that moves on its own.
 * It is made for cheap boxes and old TVs: no blur, no shadows, no gradients that animate.
 */

/** What every TV screen keeps clear of: the edges a television may crop (about 5% of 960×540 dp). */
object TvSafe {
    val Horizontal = 48.dp
    val Vertical = 27.dp
}

/** The colours: always dark, as a living-room screen is, all solid, with one accent. */
object TvColors {
    /** The page behind everything. */
    val Background = Color(0xFF0F1115)
    /** A resting card, row or button. */
    val Surface = Color(0xFF1A1D23)
    /** A focused row or button's fill; a resting one that should stand out a little. */
    val SurfaceHigh = Color(0xFF262A32)
    /** The lightest fill: a focused button that rests on [SurfaceHigh]. */
    val SurfaceTop = Color(0xFF333843)
    /** Hairlines and placeholders' second tone. */
    val Line = Color(0xFF2E323B)

    /** The one accent: the focus ring, the selected thing, the primary button. */
    val Accent = Color(0xFF8AB4F8)
    /** The accent a little lighter, for a focused primary button. */
    val AccentBright = Color(0xFFB0CCFA)
    /** Text and icons on the accent. */
    val OnAccent = Color(0xFF0A1C38)
    /** The ring a focused thing wears. */
    val Focus = Accent

    /** Titles and body text: about 17:1 on [Background]. */
    val Text = Color(0xFFF3F4F6)
    /** Secondary text (artists, durations, notes): about 8:1 on [Background]. */
    val TextDim = Color(0xFFA9AFBB)
    /** Tertiary text only (counters, hints); still about 5:1. */
    val TextFaint = Color(0xFF80869A)

    /** A liked song's heart. */
    val Like = Color(0xFFFF5C7A)
    /** Errors. */
    val Error = Color(0xFFFF8A80)

    /**
     * Solid tile colours for things without art (moods, genres): muted, so white text reads on
     * each, and picked by [tileColor] from a name so a tile keeps its colour.
     */
    val Tiles = listOf(
        Color(0xFF2D4A7A), Color(0xFF5A3A7A), Color(0xFF7A3A52), Color(0xFF2E6A58),
        Color(0xFF7A5A2C), Color(0xFF365E7C), Color(0xFF6E3030), Color(0xFF4C5C2C),
    )

    fun tileColor(name: String): Color = Tiles[Math.floorMod(name.hashCode(), Tiles.size)]
}

/**
 * Type read from a sofa, ten feet away. Body text is never under 18sp; titles are 28–40sp.
 * In sp, so they stay readable on 1080p and 4K alike. The system font: the TV module bundles none.
 */
object TvType {
    /** A page's hero title: Now Playing's song, an album's name. */
    val Display = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
    /** A page's title. */
    val Headline = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)
    /** A shelf's or section's title. */
    val Title = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
    /** A card's or row's title; the bar's tabs. */
    val ItemTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
    /** Running text. */
    val Body = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)
    /** Secondary lines: an artist under a title, a duration, a shelf's strapline. */
    val Meta = TextStyle(fontSize = 18.sp, lineHeight = 24.sp)
    /** Buttons and tabs. */
    val Label = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
}

/** The gaps, on a 4dp grid. Rows of cards are [L] apart; sections [XL]. */
object TvSpace {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 20.dp
    val XL = 32.dp
    val XXL = 48.dp
}

/** Corner shapes: cards and rows are softly rounded, buttons and tabs are pills. */
object TvShapes {
    val Card: Shape = RoundedCornerShape(12.dp)
    val Small: Shape = RoundedCornerShape(8.dp)
    val Row: Shape = RoundedCornerShape(12.dp)
    val Pill: Shape = CircleShape
    val Round: Shape = CircleShape
}

/** Fixed sizes of the kit's pieces, so lists never relayout as images arrive. */
object TvSizes {
    /** A square card's cover (album, playlist, song). About 4½ fit across the screen. */
    val CardSquare = 176.dp
    /** A wide card's cover width (videos); its height is 9/16 of it. */
    val CardWide = 288.dp
    /** An artist's round face. */
    val CardRound = 160.dp
    /** A mood or genre tile: wide and short. */
    val TileWidth = 240.dp
    val TileHeight = 96.dp
    /** A song row's cover. */
    val RowCover = 52.dp
    /** Pill buttons, tabs and round icon buttons. */
    val Button = 52.dp
    val IconButton = 56.dp
    val Icon = 26.dp
}

/** The pixel sizes artwork is asked for: small for rows, large only for headers and Now Playing. */
object TvArt {
    /** A song row's cover. */
    const val Small = 128
    /** A card in a shelf. */
    const val Row = 320
    /** Now Playing's cover, a detail page's header. */
    const val Large = 720
}

/** The only motion there is: the focused thing's small grow. Page changes are instant. */
object TvMotion {
    const val FocusScale = 1.05f
    const val FocusMs = 100
}

/** The focus ring: solid, in the accent, drawn just outside the thing with a dark gap so it reads on any cover. */
object TvFocus {
    val RingWidth = 3.dp
    val RingGap = 3.dp
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
    outline = TvColors.Line,
    error = TvColors.Error,
)

/**
 * [TvType] in Material's slots, for screens that still read MaterialTheme.typography:
 * display = [TvType.Display], headlineMedium = [TvType.Headline], headlineSmall = [TvType.Title],
 * titleMedium = [TvType.ItemTitle], bodyMedium = [TvType.Body], bodySmall = [TvType.Meta],
 * labelLarge = [TvType.Label]. Nothing is under 18sp.
 */
private val type = Typography(
    displaySmall = TvType.Display,
    headlineMedium = TvType.Headline,
    headlineSmall = TvType.Title,
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TvType.ItemTitle,
    bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    bodyMedium = TvType.Body,
    bodySmall = TvType.Meta,
    labelLarge = TvType.Label,
    labelMedium = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    // A TV app is always dark: it's the room that's lit, not the app.
    MaterialTheme(colorScheme = scheme, typography = type) {
        // Text on a TV screen is light unless a screen says otherwise; without this, anything
        // outside a Material surface would draw black on the dark background.
        CompositionLocalProvider(
            LocalContentColor provides TvColors.Text,
            LocalTextStyle provides TvType.Body,
            content = content,
        )
    }
}
