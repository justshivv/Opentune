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

/**
 * Where every setting lives. Each page holds one kind of thing, and each heading on it
 * a few settings that go together. A setting not named here lands under "More" on its
 * section's page, so a new one is never lost, but every setting today has its place.
 */
internal val SETTINGS_CATEGORIES = listOf(
    SettingsCategory(
        "account", "Accounts", "YouTube Music, Spotify and your other devices", Icons.Rounded.Person,
        listOf(
            "YouTube Music" to listOf("YouTube Music account", "Add another account", "Sign out"),
            "Listening history" to listOf("Sync listening history", "Pause account sync"),
            "Spotify" to listOf("Spotify account", "Spotify"),
            "Other devices" to listOf("Link a device"),
        ),
    ),
    SettingsCategory(
        "audio", "Sound", "Quality, loudness, effects and output", Icons.Rounded.GraphicEq,
        listOf(
            "Streaming quality" to listOf("On Wi-Fi", "On mobile data", "Upgrade quality while playing"),
            "Loudness" to listOf("Loudness normalization", "Volume level", "Normalize on the phone speaker"),
            "Effects" to listOf("Equalizer", "Clarity", "Spatial audio", "System audio effects"),
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
        "playback", "Playback", "Between songs, autoplay and headphones", Icons.Rounded.PlayCircle,
        listOf(
            "Between songs" to listOf("Crossfade", "Skip silence", "Preload upcoming songs"),
            "Autoplay and suggestions" to listOf("Autoplay", "Recommendations", "Don't repeat songs in current session", "Smart shuffle", "Hidden songs and artists"),
            "Headphones and the app" to listOf("Resume when headphones connect", "Pause at zero volume", "Background playback", "Stop music on close from recents"),
            "Music videos" to listOf("SponsorBlock", "SponsorBlock categories"),
        ),
    ),
    SettingsCategory(
        "lyrics", "Lyrics", "Sync, animation, size and sources", Icons.Rounded.Lyrics,
        listOf(
            "Display" to listOf("Synced lyrics", "Lyrics text size", "Lyrics alignment", "Lyrics take the screen"),
            "Effects" to listOf("Lyrics animation", "Glow on the sung line", "Blur unfocused lyrics"),
            "Sources" to listOf("Lyrics sources"),
        ),
    ),
    SettingsCategory(
        "look", "Appearance", "Theme, colour, glass, language and icon", Icons.Rounded.Palette,
        listOf(
            "Theme" to listOf("Theme", "Pure black", "Fonts & readability"),
            "Colour" to listOf("Color from artwork", "Mini player takes the cover's colour", "Artist pages take the photo's colour", "Material You", "Accent color", "Palette style"),
            "Glass" to listOf("Liquid Glass", "Glass style", "Frosted top edge", "Reduce dynamic blur"),
            "Home and tabs" to listOf("Separate Explore tab", "Speed dial", "Wide-screen layout"),
            "Language and icon" to listOf("App language", "App icon"),
        ),
    ),
    SettingsCategory(
        "player", "Player", "Layout, cover art, seek bar and what it shows", Icons.Rounded.Album,
        listOf(
            "Layout" to listOf("Player layout", "Player buttons", "Player background", "Player beside the page"),
            "Cover art" to listOf("Full-screen cover art", "Moving cover art", "Glow under the cover", "Song change", "Album covers", "Double-tap to skip 10 seconds"),
            "Animated covers" to listOf("Animated covers", "Animated covers on Wi-Fi only", "Moving covers on Home"),
            "Seek bar" to listOf("Wavy seek bar", "Lyric preview while seeking", "Seek bar thickness", "Seek bar handle", "Seek bar colour", "Seek bar time", "Song part marks"),
            "On the player" to listOf("Lyrics preview", "Audio quality label", "Skip to chorus", "Hide volume bar", "Hide song status", "Show stats for nerds"),
        ),
    ),
    SettingsCategory(
        "motion", "Motion and feel", "Animations, haptics and smoothness", Icons.Rounded.Animation,
        listOf(
            "Dock" to listOf("Dock animation", "Dock lens"),
            "Pages and player" to listOf("Opening animation", "Page transitions", "Player opening"),
            "Feel" to listOf("Haptic feedback"),
            "Smoothness" to listOf("High performance mode", "High refresh rate", "Reduce animation"),
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
            "Your listening" to listOf("Replay", "Wrapped", "Clear listening history"),
            "Backup" to listOf("Export data", "Import data"),
        ),
    ),
    SettingsCategory(
        "about", "About", "Updates, source code and diagnostics", Icons.Rounded.Info,
        listOf(
            "Updates" to listOf("What's new", "Check for updates", "Check for updates automatically"),
            "Community" to listOf("OpenTune community", "Support OpenTune", "Star on GitHub", "Source code"),
            "Help" to listOf("Show the welcome again", "Count this phone in usage numbers", "Export diagnostics"),
        ),
    ),
)

/** How the main Settings page groups its categories. */
internal val SETTINGS_HOME = listOf(
    "You" to listOf("account"),
    "Listening" to listOf("audio", "playback", "lyrics", "addons"),
    "Look and feel" to listOf("look", "player", "motion"),
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
