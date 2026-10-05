package com.opentune.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Stream quality ceilings [StreamResolver][com.opentune.data.innertube.StreamResolver]
 * ranks YouTube's formats against. YouTube's audio is lossy: Opus at about
 * 130-160 kbps is the best most accounts get, AAC 256 only with Premium.
 */
@Serializable
enum class AudioQuality(val label: String, val summary: String, val maxKbps: Int) {
    LOW("Low", "About 64 kbps", 64),
    NORMAL("Normal", "About 128 kbps", 128),
    HIGH("High", "Opus up to 160 kbps", 160),
    MAX("Max", "Best stream offered", Int.MAX_VALUE),
}

@Serializable
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Mirrors com.materialkolor.PaletteStyle by name, so this layer stays free of UI types. */
@Serializable
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

@Serializable
enum class PlayerBackground(val label: String) {
    MESH("Mesh"),
    GRADIENT("Gradient"),
    BLUR("Blur"),
    PLAIN("Plain"),
}

@Serializable
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You wallpaper colors, on Android 12 and later. */
    val dynamicColor: Boolean = false,
    /** ARGB seed for the generated scheme when [dynamicColor] is off. */
    val seedColor: Int = SEED_COLORS.first(),
    val paletteStyle: PaletteStyleOption = PaletteStyleOption.TonalSpot,
    /** True black surfaces in dark mode, for OLED screens. */
    val pureBlack: Boolean = true,
    /** Re-seed the whole app from the playing track's artwork. */
    val colorFromArtwork: Boolean = true,
    val playerBackground: PlayerBackground = PlayerBackground.MESH,
)

/** Reverb presets map onto android.media.audiofx.PresetReverb's. */
@Serializable
enum class ReverbLevel(val label: String, val preset: Short) {
    OFF("Off", 0),
    SMALL_ROOM("Small room", 1),
    MEDIUM_ROOM("Room", 2),
    LARGE_ROOM("Large room", 3),
    MEDIUM_HALL("Hall", 4),
    LARGE_HALL("Large hall", 5),
    PLATE("Plate", 6),
}

@Serializable
data class SoundSettings(
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val reverb: ReverbLevel = ReverbLevel.OFF,
    /** 0..1000; drives the low-shelf in [com.opentune.playback.dsp.AudioDsp]. */
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

/** Band centres for the 7-band equalizer, in Hz. */
val EQ_BANDS_HZ = listOf(60f, 150f, 400f, 1_000f, 2_400f, 6_000f, 15_000f)

@Serializable
data class EqualizerSettings(
    val enabled: Boolean = false,
    /** Gain per band in dB, -12..12, one per [EQ_BANDS_HZ]. */
    val bands: List<Float> = List(EQ_BANDS_HZ.size) { 0f },
    val preampDb: Float = 0f,
    /** Tone controls: shelves at 120 Hz and 8 kHz, dB. */
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    /** -1 (left only) .. 1 (right only). */
    val balance: Float = 0f,
)

enum class EqPreset(val label: String, val bands: List<Float>) {
    FLAT("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BASS("Bass", listOf(6f, 4f, 1f, 0f, 0f, 0f, 0f)),
    WARM("Warm", listOf(3f, 2f, 1f, 0f, -1f, -2f, -2f)),
    VOCAL("Vocal", listOf(-2f, -1f, 1f, 3f, 3f, 1f, 0f)),
    BRIGHT("Bright", listOf(-1f, 0f, 0f, 1f, 2f, 4f, 5f)),
    LOUDNESS("Loudness", listOf(5f, 3f, 0f, -1f, 0f, 3f, 4f)),
    ELECTRONIC("Electronic", listOf(5f, 3f, 0f, -2f, 1f, 3f, 4f)),
    ACOUSTIC("Acoustic", listOf(3f, 2f, 1f, 1f, 2f, 2f, 1f)),
}

@Serializable
data class PlaybackSettings(
    val wifiQuality: AudioQuality = AudioQuality.MAX,
    val mobileQuality: AudioQuality = AudioQuality.MAX,
    /** Keep hi-res local files in 32-bit float to the output; bypasses effects for them. */
    val floatOutput: Boolean = false,
    val preferUsbDac: Boolean = true,
    /** Slowly level every track toward the same loudness. */
    val loudnessNormalization: Boolean = true,
    val skipSilence: Boolean = false,
    /** Mid/side stereo widening. */
    val spatialAudio: Boolean = false,
    val autoplay: Boolean = true,
    /** Autoplay won't add anything already played or queued this session. */
    val noRepeatInSession: Boolean = false,
    val stopOnTaskRemoved: Boolean = false,
    /** Swiping a row queues it to play next; off queues it at the end. */
    val playNextOnSwipe: Boolean = true,
    /** Buffer the next track and resolve its neighbours early, for instant skips. */
    val preloadUpcoming: Boolean = true,
)

@Serializable
data class InterfaceSettings(
    val reduceAnimation: Boolean = false,
    /** Solid fills instead of frosted glass. */
    val reduceBlur: Boolean = false,
    val fullScreenCover: Boolean = false,
    val hideVolumeBar: Boolean = false,
    val hideSongStatus: Boolean = false,
    val syncedLyrics: Boolean = true,
    val blurLyrics: Boolean = true,
    val statsForNerds: Boolean = false,
    val recentsAsGrid: Boolean = false,
)

@Serializable
data class LibrarySettings(
    /** A MediaStore relative path ("Music/"), or null for every folder. */
    val localFolder: String? = null,
    val filterNonMusic: Boolean = true,
    val songCacheMb: Int = 512,
)

/** Everything persisted, as one document: what's stored, exported and imported. */
@Serializable
data class SettingsState(
    val theme: ThemeSettings = ThemeSettings(),
    val sound: SoundSettings = SoundSettings(),
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val playback: PlaybackSettings = PlaybackSettings(),
    val ui: InterfaceSettings = InterfaceSettings(),
    val library: LibrarySettings = LibrarySettings(),
    val recentSearches: List<String> = emptyList(),
)

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
 * App-wide settings, stored as one JSON document in SharedPreferences and
 * exposed as StateFlows so the UI and the playback service (same process)
 * both react to changes. [init] runs from the Application.
 */
object AppSettings {
    private var prefs: SharedPreferences? = null
    private var connectivity: ConnectivityManager? = null

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _theme = MutableStateFlow(ThemeSettings())
    val theme: StateFlow<ThemeSettings> = _theme.asStateFlow()
    private val _sound = MutableStateFlow(SoundSettings())
    val sound: StateFlow<SoundSettings> = _sound.asStateFlow()
    private val _equalizer = MutableStateFlow(EqualizerSettings())
    val equalizer: StateFlow<EqualizerSettings> = _equalizer.asStateFlow()
    private val _playback = MutableStateFlow(PlaybackSettings())
    val playback: StateFlow<PlaybackSettings> = _playback.asStateFlow()
    private val _ui = MutableStateFlow(InterfaceSettings())
    val ui: StateFlow<InterfaceSettings> = _ui.asStateFlow()
    private val _library = MutableStateFlow(LibrarySettings())
    val library: StateFlow<LibrarySettings> = _library.asStateFlow()
    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    /** Read by StreamResolver on every resolve: the ceiling for the network in use. */
    val effectiveAudioQuality: AudioQuality
        get() = _playback.value.let { if (onMeteredNetwork()) it.mobileQuality else it.wifiQuality }

    fun onMeteredNetwork(): Boolean {
        val cm = connectivity ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun init(context: Context) {
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        connectivity = context.getSystemService(ConnectivityManager::class.java)
        val stored = p.getString(K_STATE, null)?.let { runCatching { json.decodeFromString<SettingsState>(it) }.getOrNull() }
        publish(stored ?: migrateLegacy(p))
    }

    private fun publish(s: SettingsState) {
        _state.value = s
        _theme.value = s.theme
        _sound.value = s.sound
        _equalizer.value = s.equalizer
        _playback.value = s.playback
        _ui.value = s.ui
        _library.value = s.library
        _recentSearches.value = s.recentSearches
    }

    private fun update(transform: (SettingsState) -> SettingsState) {
        val s = transform(_state.value)
        publish(s)
        prefs?.edit { putString(K_STATE, json.encodeToString(SettingsState.serializer(), s)) }
    }

    fun updateTheme(t: (ThemeSettings) -> ThemeSettings) = update { it.copy(theme = t(it.theme)) }
    fun updateSound(t: (SoundSettings) -> SoundSettings) = update { it.copy(sound = t(it.sound)) }
    fun updateEqualizer(t: (EqualizerSettings) -> EqualizerSettings) = update { it.copy(equalizer = t(it.equalizer)) }
    fun updatePlayback(t: (PlaybackSettings) -> PlaybackSettings) = update { it.copy(playback = t(it.playback)) }
    fun updateUi(t: (InterfaceSettings) -> InterfaceSettings) = update { it.copy(ui = t(it.ui)) }
    fun updateLibrary(t: (LibrarySettings) -> LibrarySettings) = update { it.copy(library = t(it.library)) }

    fun setAutoplay(enabled: Boolean) = updatePlayback { it.copy(autoplay = enabled) }

    fun addRecentSearch(query: String) = update { s ->
        s.copy(recentSearches = (listOf(query) + s.recentSearches.filterNot { it.equals(query, true) }).take(MAX_RECENT))
    }

    fun removeRecentSearch(query: String) = update { it.copy(recentSearches = it.recentSearches - query) }

    fun clearRecentSearches() = update { it.copy(recentSearches = emptyList()) }

    fun exportJson(): JsonElement = json.encodeToJsonElement(SettingsState.serializer(), _state.value)

    fun importJson(element: JsonElement) {
        val s = json.decodeFromJsonElement(SettingsState.serializer(), element)
        update { s }
    }

    /** Settings saved by the previous version, one key per value. */
    private fun migrateLegacy(p: SharedPreferences): SettingsState {
        fun <E : Enum<E>> enumOf(values: Array<E>, name: String?, default: E): E =
            values.firstOrNull { it.name == name } ?: default
        if (!p.contains("theme_mode")) return SettingsState()
        return SettingsState(
            theme = ThemeSettings(
                mode = enumOf(ThemeMode.entries.toTypedArray(), p.getString("theme_mode", null), ThemeMode.SYSTEM),
                dynamicColor = p.getBoolean("dynamic_color", false),
                seedColor = p.getInt("seed_color", SEED_COLORS.first()),
                paletteStyle = enumOf(PaletteStyleOption.entries.toTypedArray(), p.getString("palette_style", null), PaletteStyleOption.TonalSpot),
                pureBlack = p.getBoolean("pure_black", true),
                colorFromArtwork = p.getBoolean("color_from_artwork", true),
                playerBackground = enumOf(PlayerBackground.entries.toTypedArray(), p.getString("player_background", null), PlayerBackground.MESH),
            ),
            sound = SoundSettings(
                speed = p.getFloat("sound_speed", 1f),
                pitch = p.getFloat("sound_pitch", 1f),
                reverb = enumOf(ReverbLevel.entries.toTypedArray(), p.getString("sound_reverb", null), ReverbLevel.OFF),
                bassBoost = p.getInt("sound_bass", 0),
            ),
            playback = PlaybackSettings(autoplay = p.getBoolean("autoplay", true)),
            recentSearches = p.getString("recent_searches", null)?.split('\u001F')?.filter { it.isNotBlank() }.orEmpty(),
        )
    }

    private const val MAX_RECENT = 12
    private const val K_STATE = "state_v2"
}
