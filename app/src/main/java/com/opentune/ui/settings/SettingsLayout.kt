package com.opentune.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.ui.graphics.vector.ImageVector

/** A page of settings, reached from the list on the main Settings page. */
internal class SettingsCategory(
    val key: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    /** Subsections, each a heading and the titles of the settings under it, in order. */
    val groups: List<Pair<String, List<String>>>,
)

/** Where every setting lives. A setting not named here lands under "More" on its section's page. */
internal val SETTINGS_CATEGORIES = listOf(
    SettingsCategory(
        "account", "Account", "YouTube Music sign-in", Icons.Rounded.Person,
        listOf("YouTube Music" to listOf("YouTube Music account", "Sign out")),
    ),
    SettingsCategory(
        "audio", "Audio", "Quality, loudness, effects and output", Icons.Rounded.GraphicEq,
        listOf(
            "Streaming quality" to listOf("On Wi-Fi", "On mobile data", "Upgrade quality while playing"),
            "Higher-quality sources" to listOf("Lossless streaming", "JioSaavn quality upgrade", "Unmetered networks only", "Prefer hi-res lossless", "Qobuz relay", "SpotiFLAC verification", "Disconnect SpotiFLAC"),
            "Loudness" to listOf("Loudness normalization", "Volume level", "Normalize on the phone speaker"),
            "Sound" to listOf("Equalizer", "Clarity", "Spatial audio", "System audio effects"),
            "Output" to listOf("Output precision", "Prefer USB DAC", "Bit-perfect USB output"),
        ),
    ),
    SettingsCategory(
        "playback", "Playback", "Crossfade, autoplay, recommendations and devices", Icons.Rounded.PlayCircle,
        listOf(
            "Between songs" to listOf("Crossfade", "Skip silence", "Preload upcoming songs"),
            "Autoplay" to listOf("Autoplay", "Recommendations", "Don't repeat songs in current session"),
            "Headphones and devices" to listOf("Resume when headphones connect", "Pause at zero volume", "Background playback", "Stop music on close from recents"),
            "Music videos" to listOf("SponsorBlock", "SponsorBlock categories"),
        ),
    ),
    SettingsCategory(
        "look", "Look and feel", "Theme, colour, glass and haptics", Icons.Rounded.Palette,
        listOf(
            "Theme" to listOf("Theme", "Pure black"),
            "Colour" to listOf("Color from artwork", "Material You", "Accent color", "Palette style"),
            "Glass" to listOf("Liquid Glass", "Glass style", "Frosted top edge", "Reduce dynamic blur"),
            "Feel" to listOf("Haptic feedback", "High refresh rate"),
        ),
    ),
    SettingsCategory(
        "player", "Player", "Layout, buttons, cover and background", Icons.Rounded.Album,
        listOf(
            "Layout" to listOf("Player layout", "Player buttons", "Player background"),
            "Cover art" to listOf("Moving cover art", "Glow under the cover", "Song change", "Full-screen cover art", "Album covers"),
            "Controls" to listOf("Wavy seek bar", "Waveform seek bar", "Lyric preview while seeking", "Hide volume bar", "Hide song status", "Show stats for nerds"),
        ),
    ),
    SettingsCategory(
        "motion", "Motion", "How the dock, pages and player move", Icons.Rounded.Animation,
        listOf(
            "Presets" to listOf("Echo motion preset"),
            "Dock" to listOf("Dock animation", "Dock lens"),
            "Pages and player" to listOf("Opening animation", "Page transitions", "Player opening", "Cover flies to the player"),
            "Less motion" to listOf("Reduce animation"),
        ),
    ),
    SettingsCategory(
        "lyrics", "Lyrics", "Sync, animation, size and sources", Icons.Rounded.Lyrics,
        listOf(
            "Display" to listOf("Synced lyrics", "Lyrics animation", "Lyrics text size", "Lyrics alignment", "Glow on the sung line", "Blur unfocused lyrics"),
            "Sources" to listOf("Lyrics sources"),
        ),
    ),
    SettingsCategory(
        "library", "Library", "Downloads, local files and new releases", Icons.Rounded.LibraryMusic,
        listOf(
            "Downloads" to listOf("Downloaded songs", "Download quality", "Download on Wi-Fi only"),
            "Local music" to listOf("Local music folder", "Filter non-music audio"),
            "New releases" to listOf("New-release alerts", "Followed artists"),
            "Songs and lists" to listOf("Play next on swipe", "Hide explicit content", "Import a playlist"),
        ),
    ),
    SettingsCategory(
        "services", "Connected services", "Music server, Last.fm and ListenBrainz", Icons.Rounded.Cable,
        listOf(
            "Your music server" to listOf("Music server", "Disconnect server"),
            "Last.fm" to listOf("Last.fm scrobbling", "Disconnect Last.fm"),
            "ListenBrainz" to listOf("ListenBrainz", "Disconnect ListenBrainz"),
        ),
    ),
    SettingsCategory(
        "data", "Storage and data", "Cache, backups and listening history", Icons.Rounded.Storage,
        listOf(
            "Storage" to listOf("Song cache limit", "Clear song cache", "Clear image cache"),
            "Your data" to listOf("Replay", "Wrapped", "Export data", "Import data", "Clear listening history"),
        ),
    ),
    SettingsCategory(
        "about", "About", "Updates, source code and diagnostics", Icons.Rounded.Info,
        listOf(
            "Updates" to listOf("Check for updates", "Check for updates automatically"),
            "OpenTune" to listOf("Count this phone in usage numbers", "Source code", "Export diagnostics"),
        ),
    ),
)

/** How the main Settings page groups its categories. */
internal val SETTINGS_HOME = listOf(
    "You" to listOf("account"),
    "Sound" to listOf("audio", "playback", "lyrics"),
    "Look" to listOf("look", "player", "motion"),
    "Your music" to listOf("library", "services", "data"),
    "App" to listOf("about"),
)

/** For a setting no category names: the page its old section belongs on. */
internal val SECTION_HOME = mapOf(
    "Account" to "account",
    "Audio quality" to "audio",
    "Playback" to "playback",
    "Appearance" to "look",
    "Lyrics" to "lyrics",
    "New releases" to "library",
    "Content" to "library",
    "Downloads" to "library",
    "Local music" to "library",
    "Storage" to "data",
    "Your data" to "data",
    "Your music server" to "services",
    "Last.fm (optional)" to "services",
    "ListenBrainz (optional)" to "services",
    "Import" to "library",
    "Miscellaneous" to "player",
    "Advanced" to "about",
)
