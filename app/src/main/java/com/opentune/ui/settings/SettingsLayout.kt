package com.opentune.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Extension
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

/**
 * Subsections that show only their first few settings until "See more" is
 * tapped, by heading. Search always shows every match.
 */
internal val FOLDED_GROUPS = mapOf("Seek bar" to 2)

/** Where every setting lives. A setting not named here lands under "More" on its section's page. */
internal val SETTINGS_CATEGORIES = listOf(
    SettingsCategory(
        "account", "Account", "YouTube Music sign-in and your other devices", Icons.Rounded.Person,
        listOf(
            "YouTube Music" to listOf("YouTube Music account", "Sign out"),
            "Other devices" to listOf("Link a device"),
        ),
    ),
    SettingsCategory(
        "audio", "Audio", "Quality, loudness, effects and output", Icons.Rounded.GraphicEq,
        listOf(
            "Streaming quality" to listOf("On Wi-Fi", "On mobile data", "Upgrade quality while playing"),
            "Loudness" to listOf("Loudness normalization", "Volume level", "Normalize on the phone speaker"),
            "Sound" to listOf("Equalizer", "Clarity", "Spatial audio", "System audio effects"),
            "Output" to listOf("Output precision", "Prefer USB DAC", "Bit-perfect USB output"),
        ),
    ),
    SettingsCategory(
        "addons", "Add-ons", "Audio sources you add by URL, and when they're used", Icons.Rounded.Extension,
        listOf(
            "Audio add-ons" to listOf("Audio add-ons"),
            "When they're used" to listOf("Lossless streaming", "Unmetered networks only"),
        ),
    ),
    SettingsCategory(
        "playback", "Playback", "Crossfade, autoplay, recommendations and devices", Icons.Rounded.PlayCircle,
        listOf(
            "Between songs" to listOf("Crossfade", "Skip silence", "Preload upcoming songs"),
            "Autoplay" to listOf("Autoplay", "Recommendations", "Don't repeat songs in current session", "Smart shuffle", "Hidden songs and artists"),
            "Headphones and devices" to listOf("Resume when headphones connect", "Pause at zero volume", "Background playback", "Stop music on close from recents"),
            "Music videos" to listOf("SponsorBlock", "SponsorBlock categories"),
        ),
    ),
    SettingsCategory(
        "look", "Look and feel", "Theme, colour, glass and haptics", Icons.Rounded.Palette,
        listOf(
            "Tabs" to listOf("Separate Explore tab"),
            "Home" to listOf("Speed dial"),
            "Theme" to listOf("Theme", "Pure black"),
            "Colour" to listOf("Color from artwork", "Mini player takes the cover's colour", "Artist pages take the photo's colour", "Material You", "Accent color", "Palette style"),
            "Glass" to listOf("Liquid Glass", "Glass style", "Frosted top edge", "Reduce dynamic blur"),
            "Feel" to listOf("Haptic feedback", "High refresh rate", "Wide-screen layout"),
        ),
    ),
    SettingsCategory(
        "player", "Player", "Layout, buttons, cover and background", Icons.Rounded.Album,
        listOf(
            "Layout" to listOf("Player layout", "Player buttons", "Player background", "Player beside the page"),
            "Cover art" to listOf("Moving cover art", "Moving covers on Home", "Glow under the cover", "Song change", "Full-screen cover art", "Album covers"),
            "Seek bar" to listOf("Wavy seek bar", "Lyric preview while seeking", "Seek bar thickness", "Seek bar handle", "Seek bar colour", "Seek bar time", "Song part marks"),
            "Controls" to listOf("Audio quality label", "Skip to chorus", "Hide volume bar", "Hide song status", "Show stats for nerds"),
        ),
    ),
    SettingsCategory(
        "motion", "Motion", "How the dock, pages and player move", Icons.Rounded.Animation,
        listOf(
            "Dock" to listOf("Dock animation", "Dock lens"),
            "Pages and player" to listOf("Opening animation", "Page transitions", "Player opening"),
            "Less motion" to listOf("Reduce animation"),
        ),
    ),
    SettingsCategory(
        "lyrics", "Lyrics", "Sync, animation, size and sources", Icons.Rounded.Lyrics,
        listOf(
            "Display" to listOf("Synced lyrics", "Lyrics animation", "Lyrics text size", "Lyrics alignment", "Glow on the sung line", "Blur unfocused lyrics", "Lyrics take the screen"),
            "Sources" to listOf("Lyrics sources"),
        ),
    ),
    SettingsCategory(
        "library", "Library", "Downloads, local files and new releases", Icons.Rounded.LibraryMusic,
        listOf(
            "Downloads" to listOf("Downloaded songs", "Download quality", "Download on Wi-Fi only", "Stream downloads when online", "Export compatible downloads"),
            "Local music" to listOf("Local music folder", "Filter non-music audio"),
            "New releases" to listOf("New-release alerts", "Followed artists"),
            "Songs and lists" to listOf("Swipe songs to queue them", "Hide explicit content", "Import a playlist"),
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
            "Storage" to listOf("Song cache limit", "Clear song cache", "Clear image cache", "Delete downloaded updates"),
            "Your data" to listOf("Replay", "Wrapped", "Export data", "Import data", "Clear listening history"),
        ),
    ),
    SettingsCategory(
        "about", "About", "Updates, source code and diagnostics", Icons.Rounded.Info,
        listOf(
            "Updates" to listOf("What's new", "Check for updates", "Check for updates automatically"),
            "OpenTune" to listOf("OpenTune community", "Show the welcome again", "Support OpenTune", "Star on GitHub", "Count this phone in usage numbers", "Source code", "Export diagnostics"),
        ),
    ),
)

/** How the main Settings page groups its categories. */
internal val SETTINGS_HOME = listOf(
    "You" to listOf("account"),
    "Sound" to listOf("audio", "addons", "playback", "lyrics"),
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
