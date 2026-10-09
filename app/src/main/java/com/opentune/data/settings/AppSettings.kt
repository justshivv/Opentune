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
    /** A gradient from the cover's colour, with soft lights drifting under the controls. */
    GRADIENT("Gradient"),
    BLUR("Blur"),
    PLAIN("Plain"),
}

/** How the full-screen player is laid out. */
@Serializable
enum class PlayerStyle(val label: String, val summary: String) {
    CLASSIC("Classic", "Big cover, title, lyric preview and every control"),
    VINYL("Vinyl", "The cover turns as a record while the song plays"),
    LYRICS_FIRST("Lyrics first", "A small cover on top and the lyrics taking the rest"),
    MINIMAL("Minimal", "Cover, title, seek bar and controls; lyrics and queue a tap away"),
    CASSETTE("Cassette", "A tape whose reels turn and wind on as the song plays"),
    HALO("Halo", "A round cover inside a ring of moving bars"),
    POLAROID("Polaroid", "The cover as an instant photo, swaying gently"),
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
    /** The sound circling around the listener's head. */
    val eightD: EightD = EightD.OFF,
    /** The sound travelling all round the listener, above and below too; takes over from [eightD]. */
    val space: Space3DSpeed = Space3DSpeed.OFF,
) {
    val isDefault: Boolean get() = this == SoundSettings()
}

/** How fast 8D audio circles: once around in this many seconds. */
@Serializable
enum class EightD(val label: String, val periodSeconds: Float) {
    OFF("Off", 0f),
    SLOW("Slow", 16f),
    MEDIUM("Medium", 10f),
    FAST("Fast", 6f),
}

/** How fast 3D sound goes round: once in this many seconds, tipping over and under every few turns. */
@Serializable
enum class Space3DSpeed(val label: String, val periodSeconds: Float) {
    OFF("Off", 0f),
    SLOW("Slow", 14f),
    MEDIUM("Medium", 9f),
    FAST("Fast", 5f),
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
    EIGHT_D("8D", SoundSettings(eightD = EightD.MEDIUM)),
    SPACE_3D("3D", SoundSettings(space = Space3DSpeed.MEDIUM)),
    ;

    companion object {
        fun matching(sound: SoundSettings): RemixPreset? = entries.firstOrNull { it.sound == sound }
    }
}

/** Band centres for the 7-band equalizer, in Hz. */
/** Band centres for the 15-band equalizer: the ISO 2/3-octave series, in Hz. */
val EQ_BANDS_HZ = listOf(25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f, 1_000f, 1_600f, 2_500f, 4_000f, 6_300f, 10_000f, 16_000f)

/** The seven bands older versions saved, kept to carry those curves over. */
private val SEVEN_BAND_HZ = listOf(60f, 150f, 400f, 1_000f, 2_400f, 6_000f, 15_000f)

/**
 * [gains] at [from] frequencies, redrawn at [EQ_BANDS_HZ] by interpolating
 * on a log-frequency axis, flat beyond the ends.
 */
fun resampleBands(gains: List<Float>, from: List<Float> = SEVEN_BAND_HZ): List<Float> {
    if (gains.size == EQ_BANDS_HZ.size && from.size != gains.size) return gains
    val points = from.zip(gains).sortedBy { it.first }
    if (points.isEmpty()) return List(EQ_BANDS_HZ.size) { 0f }
    return EQ_BANDS_HZ.map { f ->
        val x = kotlin.math.ln(f)
        when {
            f <= points.first().first -> points.first().second
            f >= points.last().first -> points.last().second
            else -> {
                val hi = points.indexOfFirst { it.first >= f }
                val (f0, g0) = points[hi - 1]
                val (f1, g1) = points[hi]
                val t = (x - kotlin.math.ln(f0)) / (kotlin.math.ln(f1) - kotlin.math.ln(f0))
                (g0 + (g1 - g0) * t).let { (it * 10).toInt() / 10f }
            }
        }
    }
}

@Serializable
data class EqualizerSettings(
    val enabled: Boolean = false,
    /** Gain per band in dB, -12..12, one per [EQ_BANDS_HZ]. */
    val bands: List<Float> = List(15) { 0f },
    val preampDb: Float = 0f,
    /** Tone controls: shelves at 120 Hz and 8 kHz, dB. */
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    /** -1 (left only) .. 1 (right only). */
    val balance: Float = 0f,
    /**
     * A correction for the listener's headphones from AutoEq. Runs on its own,
     * whether or not the equalizer above is on.
     */
    val headphone: HeadphoneEq? = null,
)

/** One AutoEq parametric profile: its preamp and filters, as published. */
@Serializable
data class HeadphoneEq(
    val name: String,
    /** Who measured it, e.g. "oratory1990". */
    val source: String,
    val preampDb: Float,
    val filters: List<ParametricFilter>,
)

@Serializable
data class ParametricFilter(val type: FilterType, val freqHz: Float, val gainDb: Float, val q: Float)

@Serializable
enum class FilterType { PEAK, LOW_SHELF, HIGH_SHELF }

enum class EqPreset(val label: String, val bands: List<Float>) {
    FLAT("Flat", List(15) { 0f }),
    /** Deep, clean lows and open highs. */
    STUDIO("Studio", listOf(2.6f, 2.8f, 2.2f, 0.6f, -1.8f, -2.6f, -1.2f, 0f, 1.2f, 2.4f, 3.6f, 4.0f, 4.2f, 4.5f, 4.8f)),
    BASS("Bass", resampleBands(listOf(6f, 4f, 1f, 0f, 0f, 0f, 0f))),
    DEEP_BASS("Deep bass", listOf(6f, 5.5f, 4.5f, 3f, 1.5f, 0f, -1f, -1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    WARM("Warm", resampleBands(listOf(3f, 2f, 1f, 0f, -1f, -2f, -2f))),
    VOCAL("Vocal", resampleBands(listOf(-2f, -1f, 1f, 3f, 3f, 1f, 0f))),
    BRIGHT("Bright", resampleBands(listOf(-1f, 0f, 0f, 1f, 2f, 4f, 5f))),
    TREBLE_AIR("Air", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1f, 2f, 3f, 4.5f, 5.5f)),
    LOUDNESS("Loudness", resampleBands(listOf(5f, 3f, 0f, -1f, 0f, 3f, 4f))),
    ROCK("Rock", listOf(4f, 4f, 3.5f, 2.5f, 1f, -0.5f, -1.5f, -1.5f, -0.5f, 1f, 2.5f, 3.5f, 4f, 4f, 4f)),
    POP("Pop", listOf(-1f, -0.5f, 0.5f, 1.5f, 2.5f, 3f, 2.5f, 1.5f, 0.5f, 0f, -0.5f, -0.5f, 0f, 0.5f, 1f)),
    ELECTRONIC("Electronic", resampleBands(listOf(5f, 3f, 0f, -2f, 1f, 3f, 4f))),
    ACOUSTIC("Acoustic", resampleBands(listOf(3f, 2f, 1f, 1f, 2f, 2f, 1f))),
    CLASSICAL("Classical", listOf(3f, 3f, 2.5f, 2f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, -1f, -1.5f, -2f, -2.5f)),
}

@Serializable
data class PlaybackSettings(
    /** The sleep timer fades the music out over its last minutes instead of stopping it dead. */
    val sleepWindDown: Boolean = true,
    val wifiQuality: AudioQuality = AudioQuality.MAX,
    val mobileQuality: AudioQuality = AudioQuality.MAX,
    /** Keep hi-res local files in 32-bit float to the output; bypasses effects for them. */
    val floatOutput: Boolean = false,
    val preferUsbDac: Boolean = true,
    /** Slowly level every track toward the same loudness. */
    val loudnessNormalization: Boolean = true,
    /**
     * Level songs on the phone's own speaker too. Off by default: there it
     * mostly turns loud masters down, and a small speaker needs the level.
     */
    val normalizeOnSpeaker: Boolean = false,
    /** Where normalization sets songs: below, at or above YouTube's reference level. */
    val volumeLevel: VolumeLevel = VolumeLevel.LOUD,
    val skipSilence: Boolean = false,
    /** Mid/side stereo widening. */
    val spatialAudio: Boolean = false,
    /** The "Clarity" tone curve in the app's DSP. */
    val clarity: Boolean = false,
    val autoplay: Boolean = true,
    /** Autoplay won't add anything already played or queued this session. */
    val noRepeatInSession: Boolean = false,
    /** Whose suggestions autoplay and Home's "Because you played" follow. */
    val recommender: Recommender = Recommender.YOUTUBE,
    val stopOnTaskRemoved: Boolean = false,
    /** Swiping a row queues it to play next; off queues it at the end. */
    val playNextOnSwipe: Boolean = true,
    /** Buffer the next track and resolve its neighbours early, for instant skips. */
    val preloadUpcoming: Boolean = true,
    /** Swap to a clearly better stream mid-song when one turns up. */
    val qualityUpgrade: Boolean = true,
    /** Community FLAC lookup runs after playback starts. Downloads remain YouTube audio. */
    val losslessStreaming: Boolean = false,
    val losslessUnmeteredOnly: Boolean = true,
    val losslessHiRes: Boolean = false,
    /** Overlap the end of a song with the start of the next; 0 is off. */
    val crossfadeSeconds: Int = 0,
    /** Bit-perfect output to a USB DAC (Android 14+): no mixing, effects or resampling. */
    val bitPerfectUsb: Boolean = false,
    /** Let the phone's own effects (Dolby, SoundAlive, system EQ) process playback. */
    val systemEffects: Boolean = true,
    /** Carry on playing when headphones are plugged in or a Bluetooth device connects. */
    val resumeOnConnect: Boolean = false,
    /** Pause when the media volume is turned all the way down, and resume when it comes back up. */
    val pauseAtZeroVolume: Boolean = false,
    /** Skip the non-music parts of music videos, from SponsorBlock. */
    val sponsorBlock: Boolean = true,
    /** SponsorBlock categories to skip, by API name. */
    val sponsorBlockCategories: Set<String> = DEFAULT_SPONSORBLOCK_CATEGORIES,
)

val DEFAULT_SPONSORBLOCK_CATEGORIES = setOf("music_offtopic", "sponsor", "selfpromo", "interaction")

@Serializable
data class InterfaceSettings(
    val reduceAnimation: Boolean = false,
    /** The mark's dive into the app when it starts. */
    val openingAnimation: Boolean = true,
    /** Solid fills instead of frosted glass. */
    val reduceBlur: Boolean = false,
    val fullScreenCover: Boolean = false,
    val hideVolumeBar: Boolean = false,
    val hideSongStatus: Boolean = false,
    val syncedLyrics: Boolean = true,
    val blurLyrics: Boolean = true,
    val statsForNerds: Boolean = false,
    val recentsAsGrid: Boolean = false,
    /** Refracting Liquid Glass on Android 13+; frosted blur otherwise. */
    val liquidGlass: Boolean = true,
    /** Draw the played part of the seek bar as a moving wave. */
    val wavySeekbar: Boolean = false,
    /** The seek bar drawn as the song's waveform, loud and quiet parts. */
    val waveformSeekbar: Boolean = false,
    /** A tapped song's cover flies down into the now-playing card. */
    val coverFlight: Boolean = true,
    /** A soft light in the cover's colour under the player's cover. */
    val coverGlow: Boolean = true,
    /** While the seek bar is dragged, the lyric line at that point shows above the finger. */
    val lyricScrubPreview: Boolean = true,
    /** Proper album covers from MusicBrainz for music videos and local files without art. */
    val albumCovers: Boolean = true,
    val lyricsAnimation: LyricsAnimation = LyricsAnimation.FLUID,
    /** Lyrics text size, as a fraction of the standard size. */
    val lyricsTextScale: Float = 1f,
    val playerStyle: PlayerStyle = PlayerStyle.CLASSIC,
    val dockMotion: DockMotion = DockMotion.MINIMIZE,
    val controlStyle: ControlStyle = ControlStyle.BLOOM,
    /** The cover drifts and zooms slowly in the player while a song plays. */
    val movingCover: Boolean = true,
    /** How strong taps and buzzes are, 0 (off) to 1. */
    val hapticStrength: Float = 0.6f,
    /** Look for a new release once a day when the app opens. */
    val checkForUpdates: Boolean = true,
    /** Add this phone to the app's anonymous usage counts (see Usage). */
    val countUsage: Boolean = true,
    /** How the glass of the dock, buttons and bars looks. */
    val glassStyle: GlassStyle = GlassStyle.FROSTED,
    /** Pages blur and fade out as they scroll under the status bar. */
    val frostedTopEdge: Boolean = true,
    /** How the dock's highlight travels between tabs. */
    val dockLens: DockLens = DockLens.STRETCH,
    /** How one page gives way to the next. */
    val pageTransition: PageTransition = PageTransition.SLIDE,
    val lyricsAlign: LyricsAlign = LyricsAlign.LEFT,
    /** A soft glow around the line being sung, whatever the lyrics animation. */
    val lyricsGlow: Boolean = false,
    /** Ask for the screen's fastest refresh rate while the app is open. */
    val highRefreshRate: Boolean = true,
    /** How the full player rises over the page and drops away. */
    val playerMotion: PlayerMotion = PlayerMotion.SPRING,
    /** How the player's cover gives way to the next song's. */
    val coverChange: CoverChange = CoverChange.FADE,
)

/** How the cover changes when the song does. */
@Serializable
enum class CoverChange(val label: String, val summary: String) {
    FADE("Fade", "The new cover fades in as it settles to size"),
    CAROUSEL("Carousel", "Covers slide past in the direction you skipped"),
    FLIP("Flip", "The cover turns over like a card to show the next one"),
    DECK("Deck", "The next cover drops onto the pile and the old one sinks under it"),
    ZOOM("Zoom through", "The old cover rushes past you as the new one grows in from behind it"),
    DISSOLVE("Dissolve", "The old cover blurs away while the new one comes into focus"),
    CUBE("Cube", "The covers turn like two faces of a cube, hinged on the edge they share"),
    REVEAL("Reveal", "The new cover opens out of the middle as a growing circle"),
    TOSS("Toss", "The old cover is flung off to the side and the new one drops into place"),
}

/** How the full-screen player opens and closes. */
@Serializable
enum class PlayerMotion(val label: String, val summary: String) {
    SPRING("Spring", "Rises quickly and settles with a hint of give"),
    SMOOTH("Smooth", "One slow, even glide with no bounce"),
    BOUNCY("Bouncy", "Overshoots the top a little and bounces into place"),
    SNAPPY("Snappy", "Up and down in a blink"),
}

/** The glass the floating parts of the app are made of. */
@Serializable
enum class GlassStyle(val label: String, val summary: String) {
    FROSTED("Frosted", "Soft blur under a light film"),
    CLEAR("Clear", "Thin glass that lets most of the colour through"),
    HEAVY("Heavy frost", "Thick, milky glass that hides what's behind it"),
    TINTED("Tinted", "Glass washed with your accent colour"),
    SMOKE("Smoke", "Dark smoked glass with deep contrast"),
    LIQUID("Liquid", "Apple's Liquid Glass: softly frosted, the edges bend and split the light, with a bright rim (Android 13+)"),
}

/** How the dock's highlight moves to the tab you pick. */
@Serializable
enum class DockLens(val label: String, val summary: String) {
    STRETCH("Stretch", "The front edge leads and the back catches up, like a drop pulled along"),
    JELLY("Jelly", "Stretches, overshoots and wobbles into place"),
    GLIDE("Glide", "Slides across in one piece"),
}

/** How pages come and go. */
@Serializable
enum class PageTransition(val label: String, val summary: String) {
    SLIDE("Slide", "New pages slide in a little from the side"),
    FADE("Fade", "Pages cross-fade in place"),
    ZOOM("Zoom", "New pages grow into view; going back, they shrink away"),
    RISE("Rise", "New pages rise up from below"),
}

@Serializable
enum class LyricsAlign(val label: String) {
    LEFT("Left"),
    CENTER("Centre"),
}

@Serializable
data class LibrarySettings(
    /** A MediaStore relative path ("Music/"), or null for every folder. */
    val localFolder: String? = null,
    val filterNonMusic: Boolean = true,
    val songCacheMb: Int = 512,
    val downloadQuality: AudioQuality = AudioQuality.MAX,
    val downloadWifiOnly: Boolean = true,
    /** Leave out songs and albums marked explicit; see [com.opentune.data.ContentFilter]. */
    val hideExplicit: Boolean = false,
    /** Notify about new albums and singles from followed artists. */
    val releaseAlerts: Boolean = true,
)

/**
 * The level loudness normalization aims for, against YouTube's reference
 * (about −14 LUFS). [offsetDb] moves every song by the same amount; [maxGainDb]
 * caps how far a quiet song is lifted. Lifts go through a limiter, so Loud
 * doesn't clip.
 */
@Serializable
enum class VolumeLevel(val label: String, val summary: String, val offsetDb: Float, val maxGainDb: Float) {
    QUIET("Quiet", "About 5 dB under YouTube's level, for quiet rooms and long listens", -5f, 3f),
    NORMAL("Normal", "YouTube's own reference level", 0f, 3f),
    LOUD("Loud", "About 5 dB over YouTube's level, held by a limiter so peaks don't clip", 5f, 9f),
}

/** Whose recommendations pick the songs autoplay adds. Every song still plays from YouTube Music. */
@Serializable
enum class Recommender(val label: String, val summary: String) {
    YOUTUBE("YouTube Music", "YouTube Music's own radio for the last song"),
    SPOTIFY("Spotify", "The songs Spotify recommends after the last one, found on YouTube Music"),
    JIOSAAVN("JioSaavn", "JioSaavn's picks after the last song, strong on Indian music, found on YouTube Music"),
}

/** How the player's back, play/pause and forward buttons look and move. */
@Serializable
enum class ControlStyle(val label: String, val summary: String) {
    BLOOM("Bloom", "Large bare glyphs; a soft light blooms behind each press and play folds into pause"),
    CAPSULE("Capsule", "Play stretches into a wide accent pill while it plays; the skips tilt as you tap"),
    ORBIT("Orbit", "Play sits inside the song's progress ring, with a comet of light circling while it plays"),
    MORPH("Morph", "A round button that ripples into a slowly turning wavy shape while it plays, with outlined glyphs"),
}

/**
 * How the dock tucks away while a page scrolls down, and comes back. Each
 * one either ends somewhere different or travels a different way.
 */
@Serializable
enum class DockMotion(val label: String, val summary: String) {
    MINIMIZE("Minimize", "Like iOS 26: the tabs shrink to the open one, with the song in a slim bar beside it"),
    FOLD("Bubble", "The dock sinks away and the song shrinks into a round cover in the corner"),
    GLIDE("Glide", "The dock slides off the bottom and springs back up"),
    RETRACT("Retract", "The dock pulls into the Search button and unrolls from it"),
    CASCADE("Cascade", "The tabs drop away one by one and come back in a wave"),
}

/** How synced lyrics move as the song plays. */
@Serializable
enum class LyricsAnimation(val label: String, val summary: String) {
    FLUID("Fluid", "The current line grows and the rest soften"),
    KARAOKE("Karaoke", "Words fill with a glow; lines stay still"),
    SLIDE("Slide", "The current line glides into place"),
    ZOOM("Focus zoom", "A big current line, the rest blurred away"),
    MINIMAL("Minimal", "Only the colour changes"),
    BOUNCE("Bounce", "Each word hops up as it's sung"),
    POP("Pop", "Words swell and glow as they're sung"),
    REVEAL("Reveal", "Words appear only as they're sung"),
}

/** Where lyrics can come from. */
@Serializable
enum class LyricsSource(val label: String, val summary: String) {
    LRCLIB("LRCLIB", "Community lyrics, line by line and sometimes word by word"),
    KUGOU("KuGou", "Line-synced lyrics from KuGou's large catalogue, strong on Asian music"),
    NETEASE("NetEase", "Line-synced lyrics from NetEase Cloud Music, a large catalogue of every kind of music"),
    UNISON("Unison", "Better Lyrics' community lyrics, written and timed by its users, often word by word"),
    YOUTUBE_CAPTIONS("YouTube captions", "A music video's own hand-made subtitles, timed line by line to the video"),
    YOUTUBE_MUSIC("YouTube Music", "YouTube Music's own lyrics, as plain text"),
}

@Serializable
data class LyricsSourceEntry(val source: LyricsSource, val enabled: Boolean = true)

@Serializable
data class LyricsSettings(
    /** Tried in this order; the first with lyrics wins unless [preferWordSynced]. */
    val sources: List<LyricsSourceEntry> = DEFAULT_LYRICS_SOURCES,
    /** Keep looking past a line-synced match for word-by-word lyrics. */
    val preferWordSynced: Boolean = false,
    /** Per-song timing shift in ms, set from the player's "Lyrics offset". */
    val offsets: Map<String, Long> = emptyMap(),
) {
    /** The saved order, with any source added since appended at the end. */
    val ordered: List<LyricsSourceEntry>
        get() = sources.distinctBy { it.source } + LyricsSource.entries.filter { s -> sources.none { it.source == s } }.map(::LyricsSourceEntry)
}

val DEFAULT_LYRICS_SOURCES = LyricsSource.entries.map(::LyricsSourceEntry)

/** Everything persisted, as one document: what's stored, exported and imported. */
@Serializable
data class SettingsState(
    val theme: ThemeSettings = ThemeSettings(),
    val sound: SoundSettings = SoundSettings(),
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val playback: PlaybackSettings = PlaybackSettings(),
    val ui: InterfaceSettings = InterfaceSettings(),
    val library: LibrarySettings = LibrarySettings(),
    val lyrics: LyricsSettings = LyricsSettings(),
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

    // coerceInputValues: a choice a later version dropped reads back as that setting's default
    // instead of failing the whole file.
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

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
    private val _lyrics = MutableStateFlow(LyricsSettings())
    val lyrics: StateFlow<LyricsSettings> = _lyrics.asStateFlow()
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

    private fun publish(stored: SettingsState) {
        // Curves saved by the seven-band equalizer are redrawn on fifteen bands.
        val s = if (stored.equalizer.bands.size == EQ_BANDS_HZ.size) stored
        else stored.copy(equalizer = stored.equalizer.copy(bands = resampleBands(stored.equalizer.bands)))
        _state.value = s
        _theme.value = s.theme
        _sound.value = s.sound
        _equalizer.value = s.equalizer
        _playback.value = s.playback
        _ui.value = s.ui
        _library.value = s.library
        _lyrics.value = s.lyrics
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
    fun updateLyrics(t: (LyricsSettings) -> LyricsSettings) = update { it.copy(lyrics = t(it.lyrics)) }

    fun lyricsOffsetFor(videoId: String): Long = _lyrics.value.offsets[videoId] ?: 0L

    fun setLyricsOffset(videoId: String, ms: Long) = updateLyrics { l ->
        l.copy(offsets = if (ms == 0L) l.offsets - videoId else (l.offsets + (videoId to ms)).entries.toList().takeLast(500).associate { it.toPair() })
    }

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
