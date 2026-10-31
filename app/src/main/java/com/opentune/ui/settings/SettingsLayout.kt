package com.opentune.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
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
 * Where every setting lives. Each page is named for what someone opening it is looking for,
 * and each setting is on the page they'd try first: everything about a service with that
 * service, every kind of quality with sound, everything about listening history together,
 * Home and Library's own options with Home and Library. A setting not named here lands under
 * "More" on its section's page, so a new one is never lost, but every setting today has its place.
 */
internal val SETTINGS_CATEGORIES = listOf(
    SettingsCategory(
        "account", "Accounts and services", "YouTube Music, Spotify, scrobbling and your other devices", Icons.Rounded.Person,
        listOf(
            "YouTube Music" to listOf("YouTube Music account", "Add another account", "Sign out"),
            "Spotify" to listOf("Spotify account", "Spotify"),
            "Scrobbling" to listOf("Last.fm scrobbling", "Disconnect Last.fm", "ListenBrainz", "Disconnect ListenBrainz"),
            "Your music server" to listOf("Music server", "Disconnect server"),
            "Other devices" to listOf("Link a device"),
        ),
    ),
    SettingsCategory(
        "audio", "Sound and quality", "Streaming and download quality, add-ons, loudness, effects and output", Icons.Rounded.GraphicEq,
        listOf(
            "Streaming quality" to listOf("On Wi-Fi", "On mobile data", "Upgrade quality while playing"),
            "Download quality" to listOf("Download quality", "Download on Wi-Fi only"),
            "Audio add-ons" to listOf("Audio add-ons", "Lossless streaming", "Unmetered networks only"),
            "Loudness" to listOf("Loudness normalization", "Volume level", "Normalize on the phone speaker"),
            "Effects" to listOf("Equalizer", "Clarity", "Spatial audio", "System audio effects"),
            "Output" to listOf("Output precision", "Prefer USB DAC", "Bit-perfect USB output"),
        ),
    ),
    SettingsCategory(
        "playback", "Playback", "Between songs, autoplay, headphones and music videos", Icons.Rounded.PlayCircle,
        listOf(
            "Between songs" to listOf("Crossfade", "Skip silence", "Preload upcoming songs"),
            "Autoplay and suggestions" to listOf("Autoplay", "Recommendations", "Don't repeat songs in current session", "Smart shuffle"),
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
            "On the player" to listOf("Lyrics preview", "Audio quality label", "Audio details page", "Skip to chorus", "Hide volume bar", "Hide song status", "Show stats for nerds"),
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
        "library", "Home and Library", "Tabs, playlists, artist pages and what's hidden", Icons.Rounded.LibraryMusic,
        listOf(
            "Home and tabs" to listOf("Separate Explore tab", "Speed dial", "Wide-screen layout"),
            "Playlists and songs" to listOf("Playlists in Library", "Import a playlist", "Swipe songs to queue them"),
            "Artist pages" to listOf("Artist signatures"),
            "New releases" to listOf("New-release alerts", "Followed artists"),
            "What's hidden" to listOf("Hide explicit content", "Hidden songs and artists"),
        ),
    ),
    SettingsCategory(
        "data", "Downloads and storage", "Downloads, music on this phone, space used and backups", Icons.Rounded.Storage,
        listOf(
            "Downloads" to listOf("Downloaded songs", "Stream downloads when online", "Export compatible downloads"),
            "Music on this phone" to listOf("Local music folder", "Filter non-music audio"),
            "Space used" to listOf("Song cache limit", "Clear song cache", "Clear image cache", "Delete downloaded updates"),
            "Backup" to listOf("Export data", "Import data"),
        ),
    ),
    SettingsCategory(
        "history", "Listening history", "Replay, Wrapped, syncing and clearing what you've played", Icons.Rounded.History,
        listOf(
            "Your year" to listOf("Replay", "Wrapped"),
            "Syncing" to listOf("Sync listening history", "Pause account sync"),
            "Clear" to listOf("Clear listening history"),
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

/** How the main Settings page groups its pages: what's yours, how it sounds, how it looks, the app. */
internal val SETTINGS_HOME = listOf(
    "You" to listOf("account", "history"),
    "Listening" to listOf("audio", "playback", "lyrics"),
    "Look and feel" to listOf("look", "player", "motion"),
    "Your music" to listOf("library", "data"),
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
    "Downloads" to "data",
    "Local music" to "data",
    "Storage" to "data",
    "Your data" to "data",
    "Your music server" to "account",
    "Last.fm (optional)" to "account",
    "ListenBrainz (optional)" to "account",
    "Import" to "library",
    "Miscellaneous" to "player",
    "Advanced" to "about",
)
