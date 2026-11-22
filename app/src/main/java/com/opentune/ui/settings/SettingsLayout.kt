package com.opentune.ui.settings

import com.opentune.ui.theme.AppIcons

/** A page of settings, reached from the list on the main Settings page. */
internal class SettingsCategory(
    val key: String,
    val title: String,
    val summary: String,
    val iconRes: Int,
    /** Subsections, each a heading and the titles of the settings under it, in order. */
    val groups: List<Pair<String, List<String>>>,
)

/**
 * Subsections that show only their first few settings until "See more" is
 * tapped, by heading. Search always shows every match.
 */
internal val FOLDED_GROUPS = mapOf("Seek bar" to 2)

/** Subsections drawn as a card with no heading over it: the page's title already says what it is. */
internal val UNLABELLED_GROUPS = setOf("YouTube Music", "Updates")

/** Subsections whose settings sit side by side in one row instead of stacked: the two developers. */
internal val SIDE_BY_SIDE_GROUPS = setOf("Developers")

/** Settings shown as a row on the main Settings page instead of on their category's page; search still finds them. */
internal val MAIN_PAGE_ONLY = setOf("Audio sources")

/** A short line under a subsection's card, saying what it's for. */
internal val GROUP_NOTES = mapOf(
    "YouTube Music" to "A Google account can have Personal and Brand profiles. Each has its own library, likes and history.",
    "Connected services" to "Each one is optional. Spotify brings over your playlists; songs still play through OpenTune's own sources.",
)

/**
 * Where every setting lives. Each page is named for what someone opening it is looking for,
 * and each setting is on the page they'd try first: everything about a service with that
 * service, every kind of quality with sound, everything about listening history together,
 * Home and Library's own options with Home and Library. A setting not named here lands under
 * "More" on its section's page, so a new one is never lost, but every setting today has its place
 * (SettingsLayoutTest checks it).
 */
internal val SETTINGS_CATEGORIES = listOf(
    SettingsCategory(
        "account", "Account & integrations", "YouTube Music, Spotify, scrobbling, Discord and your other devices", AppIcons.Person,
        listOf(
            "YouTube Music" to listOf("YouTube Music account", "Listen as", "Other accounts", "Add another account", "Sign out"),
            "Connected services" to listOf("Spotify account", "Spotify", "Show on Discord", "Last.fm scrobbling", "ListenBrainz", "Music server"),
            "Other devices" to listOf("Link a device"),
        ),
    ),
    SettingsCategory(
        "audio", "Sound and quality", "Streaming quality, lossless, loudness, effects and bit-perfect output", AppIcons.GraphicEq,
        listOf(
            "Streaming quality" to listOf("On Wi-Fi", "On mobile data", "Upgrade quality while playing"),
            "Lossless" to listOf("Lossless streaming", "Audio sources", "Unmetered networks only"),
            "Loudness" to listOf("Loudness normalization", "Volume level", "Normalize on the phone speaker"),
            "Effects" to listOf("Equalizer", "Clarity", "Spatial audio", "System audio effects"),
            "Output and bit-perfect" to listOf("Bit-perfect output", "Output precision", "Prefer USB DAC"),
        ),
    ),
    SettingsCategory(
        "playback", "Playback", "Automix and crossfade, loading ahead, autoplay, headphones and music videos", AppIcons.PlayCircle,
        listOf(
            "Between songs" to listOf("Automix", "Automix performance", "Crossfade", "Skip silence"),
            "Loading ahead" to listOf("Preload upcoming songs", "Boost preloading on Wi-Fi"),
            "Autoplay and suggestions" to listOf("Autoplay", "Recommendations", "Smart shuffle", "Songs, not music videos", "Don't repeat songs in current session"),
            "Headphones and background" to listOf("Resume when headphones connect", "Pause at zero volume", "Background playback", "Stop music on close from recents"),
            "Music videos" to listOf("SponsorBlock", "SponsorBlock categories"),
        ),
    ),
    SettingsCategory(
        "lyrics", "Lyrics", "Sync, animation, size and sources", AppIcons.Lyrics,
        listOf(
            "Display" to listOf("Synced lyrics", "Lyrics text size", "Lyrics alignment"),
            "Effects" to listOf("Lyrics animation", "Glow on the sung line", "Blur unfocused lyrics"),
            "Lyrics page" to listOf("Lyrics take the screen", "Keep the screen on for lyrics", "Hide the navigation bar on the lyrics page", "Hide the status bar on the lyrics page"),
            "Sources" to listOf("Lyrics sources"),
        ),
    ),
    SettingsCategory(
        "look", "Appearance", "Theme, colour, page layout, glass, language and icon", AppIcons.Palette,
        listOf(
            "Theme" to listOf("Theme", "Pure black", "Fonts & readability"),
            "Colour" to listOf("Color from artwork", "Material You", "Accent color", "Palette style", "Mini player takes the cover's colour"),
            "Pages" to listOf(
                "Page layout", "Page buttons", "Full-screen playlist covers", "Playlist page background", "Liked Music cover",
                "Artist pages take the photo's colour", "Artist page shade", "Artist signatures", "Choice menus",
            ),
            "Glass" to listOf("Glass style", "Lighter Liquid Glass", "Frosted top edge", "Reduce dynamic blur", "Sheet style"),
            "Language and icon" to listOf("App language", "App icon"),
        ),
    ),
    SettingsCategory(
        "player", "Player", "Layout, background, cover art, Canvas, seek bar and what it shows", AppIcons.Album,
        listOf(
            "Layout" to listOf("Player layout", "Player background", "Player buttons", "Player beside the page"),
            "Cover art" to listOf("Full-screen cover art", "Match background to cover", "Cover corners", "Glow under the cover", "Song change", "Album covers"),
            "Animated covers and Canvas" to listOf("Animated covers", "Moving cover art", "Animated covers on Wi-Fi only", "Moving covers on Home"),
            "Seek bar" to listOf("Wavy seek bar", "Lyric preview while seeking", "Seek bar thickness", "Seek bar handle", "Seek bar colour", "Seek bar time"),
            "On the player" to listOf("Lyrics preview", "Audio quality label", "Audio details page", "Comments button", "Skip to chorus", "Double-tap to skip 10 seconds", "Show stats for nerds"),
            "Hide from the player" to listOf("Hide volume bar", "Hide device volume bar", "Hide song status", "Hide output device"),
        ),
    ),
    SettingsCategory(
        "motion", "Motion and feel", "Animations, haptics, smoothness and battery", AppIcons.Animation,
        listOf(
            "Dock" to listOf("Dock animation", "Dock lens", "Dock slides away"),
            "Pages and player" to listOf("Opening animation", "Page transitions"),
            "Feel" to listOf("Haptic feedback"),
            "Smoothness" to listOf("Performance profile", "High performance mode", "Lite mode", "Reduce animation", "Lite three-dot menu"),
        ),
    ),
    SettingsCategory(
        "library", "Home and Library", "Tabs, Home, Library, new releases and what's hidden", AppIcons.LibraryMusic,
        listOf(
            "Home and tabs" to listOf("Separate Explore tab", "Speed dial", "Streaks on Home", "Wide-screen layout"),
            "Library" to listOf("Playlists in Library", "Import a playlist", "Swipe songs to queue them"),
            "New releases" to listOf("New-release alerts", "Followed artists"),
            "What's hidden" to listOf("Hide explicit content", "Hidden songs and artists"),
        ),
    ),
    SettingsCategory(
        "data", "Downloads and storage", "Download quality, offline songs, music on this phone, space used and backups", AppIcons.Storage,
        listOf(
            "Downloads" to listOf(
                "Downloaded songs", "Download quality", "Download on Wi-Fi only", "Stream downloaded songs",
                "Prefer the download when it's as good", "Export compatible downloads",
            ),
            "Smart downloads" to listOf("Smart downloads", "Keep today's mixes offline", "Smart download limit", "Smart download battery"),
            "Music on this phone" to listOf("Local music folder", "Filter non-music audio"),
            "Space used" to listOf("Song cache limit", "Clear song cache", "Clear image cache", "Delete downloaded updates"),
            "Backup" to listOf("Export data", "Import data"),
        ),
    ),
    SettingsCategory(
        "history", "Listening history", "Replay, Wrapped, syncing and clearing what you've played", AppIcons.History,
        listOf(
            "Your year" to listOf("Replay", "Wrapped"),
            "Syncing" to listOf("Sync listening history", "Pause account sync", "Listens from YouTube Music"),
            "Clear" to listOf("Clear listening history"),
        ),
    ),
    SettingsCategory(
        "updates", "Updates", "What's new and checking for new versions", AppIcons.Update,
        listOf(
            "Updates" to listOf("What's new", "Check for updates", "Check for updates automatically"),
        ),
    ),
    SettingsCategory(
        "about", "About", "Source code, community, help and privacy", AppIcons.Info,
        listOf(
            "Developers" to listOf("justshivv", "aerluc"),
            "Community" to listOf("OpenTune community", "Source code", "Star on GitHub", "Support OpenTune"),
            "Help" to listOf("Show the welcome again", "Export diagnostics"),
            "Privacy" to listOf("Count this phone in usage numbers"),
        ),
    ),
)

/** How the main Settings page groups its pages: what's yours, how it sounds, how it looks, the app. */
internal val SETTINGS_HOME = listOf(
    "You" to listOf("account", "history"),
    "Listening" to listOf("audio", "playback", "lyrics"),
    "Look and feel" to listOf("look", "player", "motion"),
    "Your music" to listOf("library", "data"),
    "App" to listOf("updates", "about"),
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
    "Discord" to "account",
    "ListenBrainz (optional)" to "account",
    "Import" to "library",
    "Miscellaneous" to "player",
    "Advanced" to "about",
)
