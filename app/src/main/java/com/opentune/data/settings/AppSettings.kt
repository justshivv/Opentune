package com.opentune.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Audio quality ceilings [StreamResolver][com.opentune.data.innertube.StreamResolver] ranks candidate formats against. */
enum class AudioQuality(val label: String, val maxKbps: Int) {
    LOW("Low", 64),
    NORMAL("Normal", 128),
    HIGH("High", 256),
    AUTO("Best available", Int.MAX_VALUE),
}

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Mirrors com.materialkolor.PaletteStyle by name, so this layer stays free of UI types. */
enum class PaletteStyleOption(val label: String) {
    TonalSpot("Tonal"),
    Vibrant("Vibrant"),
    Expressive("Expressive"),
    Fidelity("Fidelity"),
    Content("Content"),
    Rainbow("Rainbow"),
    FruitSalad("Fruit salad"),
    Neutral("Neutral"),
    Monochrome("Monochrome"),
}

enum class PlayerBackground(val label: String) {
    GRADIENT("Artwork gradient"),
    BLUR("Blurred artwork"),
    PLAIN("Plain"),
}

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You wallpaper colors, on Android 12 and later. */
    val dynamicColor: Boolean = false,
    /** ARGB seed for the generated scheme when [dynamicColor] is off. */
    val seedColor: Int = SEED_COLORS.first(),
    val paletteStyle: PaletteStyleOption = PaletteStyleOption.TonalSpot,
    /** True black surfaces in dark mode, for OLED screens. */
    val pureBlack: Boolean = false,
    /** Re-seed the whole app from the playing track's artwork. */
    val colorFromArtwork: Boolean = true,
    val playerBackground: PlayerBackground = PlayerBackground.GRADIENT,
)

/** Reverb presets map onto android.media.audiofx.PresetReverb's. */
enum class ReverbLevel(val label: String, val preset: Short) {
    OFF("Off", 0),
    SMALL_ROOM("Small room", 1),
    MEDIUM_ROOM("Room", 2),
    LARGE_ROOM("Large room", 3),
    MEDIUM_HALL("Hall", 4),
    LARGE_HALL("Large hall", 5),
    PLATE("Plate", 6),
}

data class SoundSettings(
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val reverb: ReverbLevel = ReverbLevel.OFF,
    /** 0..1000, android.media.audiofx.BassBoost's strength scale. */
    val bassBoost: Int = 0,
) {
    val isDefault: Boolean get() = this == SoundSettings()
}

/** One-tap remixes. Speed and pitch move together, as on a turntable. */
enum class RemixPreset(val label: String, val sound: SoundSettings) {
    NORMAL("Normal", SoundSettings()),
    SLOWED_REVERB("Slowed + reverb", SoundSettings(0.82f, 0.82f, ReverbLevel.LARGE_HALL)),
    NIGHTCORE("Nightcore", SoundSettings(1.25f, 1.25f)),
    SPED_UP("Sped up", SoundSettings(1.15f, 1.15f)),
    DAYCORE("Daycore", SoundSettings(0.9f, 0.9f, ReverbLevel.MEDIUM_ROOM)),
    VAPORWAVE("Vaporwave", SoundSettings(0.75f, 0.75f, ReverbLevel.PLATE)),
    BASS_BOOSTED("Bass boosted", SoundSettings(bassBoost = 800)),
    ;

    companion object {
        fun matching(sound: SoundSettings): RemixPreset? = entries.firstOrNull { it.sound == sound }
    }
}

/** Accent choices offered when Material You is off. */
val SEED_COLORS = listOf(
    0xFFE53935.toInt(), // red
    0xFFFF6F00.toInt(), // amber
    0xFFFDD835.toInt(), // yellow
    0xFF43A047.toInt(), // green
    0xFF00897B.toInt(), // teal
    0xFF039BE5.toInt(), // sky
    0xFF3949AB.toInt(), // indigo
    0xFF8E24AA.toInt(), // purple
    0xFFD81B60.toInt(), // pink
    0xFF6D4C41.toInt(), // brown
)

/**
 * App-wide settings, kept in SharedPreferences and exposed as StateFlows so the
 * UI and [PlaybackService][com.opentune.playback.PlaybackService] (same
 * process) both react to changes. [init] runs from the Application.
 */
object AppSettings {
    private lateinit var prefs: SharedPreferences

    private val _theme = MutableStateFlow(ThemeSettings())
    val theme: StateFlow<ThemeSettings> = _theme.asStateFlow()

    private val _sound = MutableStateFlow(SoundSettings())
    val sound: StateFlow<SoundSettings> = _sound.asStateFlow()

    private val _autoplay = MutableStateFlow(true)
    val autoplay: StateFlow<Boolean> = _autoplay.asStateFlow()

    private val _audioQuality = MutableStateFlow(AudioQuality.AUTO)
    val audioQuality: StateFlow<AudioQuality> = _audioQuality.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    /** Read by StreamResolver on every resolve. */
    val effectiveAudioQuality: AudioQuality get() = _audioQuality.value

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _theme.value = ThemeSettings(
            mode = enumOf(prefs.getString(K_THEME_MODE, null), ThemeMode.SYSTEM),
            dynamicColor = prefs.getBoolean(K_DYNAMIC, false),
            seedColor = prefs.getInt(K_SEED, SEED_COLORS.first()),
            paletteStyle = enumOf(prefs.getString(K_STYLE, null), PaletteStyleOption.TonalSpot),
            pureBlack = prefs.getBoolean(K_PURE_BLACK, false),
            colorFromArtwork = prefs.getBoolean(K_ART_COLOR, true),
            playerBackground = enumOf(prefs.getString(K_PLAYER_BG, null), PlayerBackground.GRADIENT),
        )
        _sound.value = SoundSettings(
            speed = prefs.getFloat(K_SPEED, 1f),
            pitch = prefs.getFloat(K_PITCH, 1f),
            reverb = enumOf(prefs.getString(K_REVERB, null), ReverbLevel.OFF),
            bassBoost = prefs.getInt(K_BASS, 0),
        )
        _autoplay.value = prefs.getBoolean(K_AUTOPLAY, true)
        _audioQuality.value = enumOf(prefs.getString(K_QUALITY, null), AudioQuality.AUTO)
        _recentSearches.value = prefs.getString(K_RECENT, null)
            ?.split(RECENT_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
    }

    fun updateTheme(transform: (ThemeSettings) -> ThemeSettings) {
        val t = transform(_theme.value)
        _theme.value = t
        prefs.edit {
            putString(K_THEME_MODE, t.mode.name)
            putBoolean(K_DYNAMIC, t.dynamicColor)
            putInt(K_SEED, t.seedColor)
            putString(K_STYLE, t.paletteStyle.name)
            putBoolean(K_PURE_BLACK, t.pureBlack)
            putBoolean(K_ART_COLOR, t.colorFromArtwork)
            putString(K_PLAYER_BG, t.playerBackground.name)
        }
    }

    fun updateSound(transform: (SoundSettings) -> SoundSettings) {
        val s = transform(_sound.value)
        _sound.value = s
        prefs.edit {
            putFloat(K_SPEED, s.speed)
            putFloat(K_PITCH, s.pitch)
            putString(K_REVERB, s.reverb.name)
            putInt(K_BASS, s.bassBoost)
        }
    }

    fun setAutoplay(enabled: Boolean) {
        _autoplay.value = enabled
        prefs.edit {
            putBoolean(K_AUTOPLAY, enabled)
        }
    }

    fun setAudioQuality(quality: AudioQuality) {
        _audioQuality.value = quality
        prefs.edit {
            putString(K_QUALITY, quality.name)
        }
    }

    fun addRecentSearch(query: String) {
        val list = (listOf(query) + _recentSearches.value.filterNot { it.equals(query, true) })
            .take(MAX_RECENT)
        saveRecent(list)
    }

    fun removeRecentSearch(query: String) = saveRecent(_recentSearches.value - query)

    fun clearRecentSearches() = saveRecent(emptyList())

    private fun saveRecent(list: List<String>) {
        _recentSearches.value = list
        prefs.edit {
            putString(K_RECENT, list.joinToString(RECENT_SEPARATOR))
        }
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private const val MAX_RECENT = 12
    private const val RECENT_SEPARATOR = "\u001F"
    private const val K_THEME_MODE = "theme_mode"
    private const val K_DYNAMIC = "dynamic_color"
    private const val K_SEED = "seed_color"
    private const val K_STYLE = "palette_style"
    private const val K_PURE_BLACK = "pure_black"
    private const val K_ART_COLOR = "color_from_artwork"
    private const val K_PLAYER_BG = "player_background"
    private const val K_SPEED = "sound_speed"
    private const val K_PITCH = "sound_pitch"
    private const val K_REVERB = "sound_reverb"
    private const val K_BASS = "sound_bass"
    private const val K_AUTOPLAY = "autoplay"
    private const val K_QUALITY = "audio_quality"
    private const val K_RECENT = "recent_searches"
}
