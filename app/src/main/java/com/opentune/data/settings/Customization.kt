package com.opentune.data.settings

/** Supported choices. Legacy enum values remain decodable for existing installs and backups. */
object Customization {
    val players = listOf(PlayerStyle.CLASSIC, PlayerStyle.MINIMAL, PlayerStyle.LYRICS_FIRST, PlayerStyle.VINYL)
    val controls = listOf(ControlStyle.CLASSIC, ControlStyle.MORPH, ControlStyle.SQUIRCLE)
    val docks = listOf(DockMotion.FOLD, DockMotion.GLIDE, DockMotion.RETRACT)
    val lenses = listOf(DockLens.STRETCH, DockLens.GLIDE)
    val glass = listOf(GlassStyle.FROSTED, GlassStyle.HEAVY, GlassStyle.TINTED)
    val pages = listOf(PageTransition.SLIDE, PageTransition.FADE, PageTransition.RISE)
    val playerMotion = listOf(PlayerMotion.SMOOTH, PlayerMotion.SPRING, PlayerMotion.SNAPPY)
    val lyrics = listOf(LyricsAnimation.FLUID, LyricsAnimation.KARAOKE, LyricsAnimation.MINIMAL)
    val palettes = listOf(PaletteStyleOption.TonalSpot, PaletteStyleOption.Vibrant, PaletteStyleOption.Neutral, PaletteStyleOption.Monochrome)
    val backgrounds = listOf(PlayerBackground.MESH, PlayerBackground.GRADIENT, PlayerBackground.PLAIN)
}

enum class AppearancePreset(val label: String, val summary: String, val accent: Int) {
    STUDIO("Studio", "Artwork colors, soft glass", 0xFF9B9FF5.toInt()),
    MIDNIGHT("Midnight", "Deep black, cool mint", 0xFF83D8C0.toInt()),
    PAPER("Paper", "Warm light, clean surfaces", 0xFFB05B40.toInt());

    fun theme(current: ThemeSettings): ThemeSettings = current.copy(
        mode = when (this) { STUDIO -> ThemeMode.SYSTEM; MIDNIGHT -> ThemeMode.DARK; PAPER -> ThemeMode.LIGHT },
        seedColor = accent,
        dynamicColor = false,
        colorFromArtwork = this == STUDIO,
        pureBlack = this != PAPER,
        paletteStyle = if (this == MIDNIGHT) PaletteStyleOption.Vibrant else PaletteStyleOption.TonalSpot,
        playerBackground = when (this) { STUDIO -> PlayerBackground.MESH; MIDNIGHT -> PlayerBackground.GRADIENT; PAPER -> PlayerBackground.PLAIN },
    )

    // Accessibility, haptics, dock behavior and motion choices are independent of the look.
    fun ui(current: InterfaceSettings): InterfaceSettings = current.copy(
        glassStyle = when (this) { STUDIO -> GlassStyle.FROSTED; MIDNIGHT -> GlassStyle.TINTED; PAPER -> GlassStyle.HEAVY },
    )

    fun matches(theme: ThemeSettings, ui: InterfaceSettings) = this.theme(theme) == theme && this.ui(ui) == ui
}

enum class MotionProfile(val label: String, val summary: String) {
    CALM("Calm", "Soft fades and a gentle player glide."),
    FLUID("Fluid", "Elastic tabs and flowing page transitions."),
    CRISP("Crisp", "Quick, precise movement with less travel.");

    fun apply(ui: InterfaceSettings): InterfaceSettings = ui.copy(
        dockMotion = if (this == CRISP) DockMotion.GLIDE else DockMotion.FOLD,
        dockLens = if (this == FLUID) DockLens.STRETCH else DockLens.GLIDE,
        pageTransition = when (this) { CALM -> PageTransition.FADE; FLUID -> PageTransition.SLIDE; CRISP -> PageTransition.RISE },
        playerMotion = when (this) { CALM -> PlayerMotion.SMOOTH; FLUID -> PlayerMotion.SPRING; CRISP -> PlayerMotion.SNAPPY },
        movingCover = this == FLUID,
        wavySeekbar = false,
    )

    fun matches(ui: InterfaceSettings) = apply(ui) == ui
}

/** Map retired visual effects to their closest supported choice, preserving unrelated settings. */
internal fun SettingsState.curated(): SettingsState = copy(
    theme = theme.copy(
        paletteStyle = theme.paletteStyle.takeIf { it in Customization.palettes } ?: PaletteStyleOption.TonalSpot,
        playerBackground = theme.playerBackground.takeIf { it in Customization.backgrounds } ?: PlayerBackground.GRADIENT,
    ),
    ui = ui.copy(
        liquidGlass = false,
        playerStyle = ui.playerStyle.takeIf { it in Customization.players } ?: PlayerStyle.CLASSIC,
        controlStyle = ui.controlStyle.takeIf { it in Customization.controls } ?: ControlStyle.CLASSIC,
        dockMotion = ui.dockMotion.takeIf { it in Customization.docks } ?: DockMotion.FOLD,
        dockLens = ui.dockLens.takeIf { it in Customization.lenses } ?: DockLens.STRETCH,
        glassStyle = ui.glassStyle.takeIf { it in Customization.glass } ?: GlassStyle.FROSTED,
        pageTransition = ui.pageTransition.takeIf { it in Customization.pages } ?: PageTransition.SLIDE,
        playerMotion = ui.playerMotion.takeIf { it in Customization.playerMotion } ?: PlayerMotion.SPRING,
        lyricsAnimation = ui.lyricsAnimation.takeIf { it in Customization.lyrics } ?: LyricsAnimation.FLUID,
    ),
)
