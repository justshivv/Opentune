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
    MAX("Lossless", "Best available lossless from add-ons, with a lossy fallback", Int.MAX_VALUE),
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
    /** Soft patches of several of the cover's colours, drifting into each other. */
    MESH("Mesh"),
    /** The cover's main colour at the top, deepening to dark, with a faint glow under the controls. */
    GRADIENT("Gradient"),
    ECHO_GLOW("Echo glow"),
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

/** Where a preset sits on the equalizer page. */
enum class EqGroup(val label: String) { SIGNATURE("Signature"), SITUATION("For the moment"), GENRE("Genres") }

/**
 * Curves for the 15 bands. Gentle on purpose (no more than about 6 dB): the
 * equalizer takes the biggest boost back off the input, so a steep curve
 * mostly makes everything quieter. Matched by its bands.
 */
enum class EqPreset(val label: String, val bands: List<Float>, val summary: String = "", val group: EqGroup = EqGroup.GENRE) {
    FLAT("Flat", List(15) { 0f }, "The song as mastered", EqGroup.SIGNATURE),
    /** Deep, clean lows and open highs. */
    STUDIO("Studio", listOf(2.6f, 2.8f, 2.2f, 0.6f, -1.8f, -2.6f, -1.2f, 0f, 1.2f, 2.4f, 3.6f, 4.0f, 4.2f, 4.5f, 4.8f), "Firm lows, clear mids, open top", EqGroup.SIGNATURE),
    HIFI("Hi-Fi", listOf(2.5f, 2.2f, 1.6f, 0.8f, 0f, -0.6f, -0.8f, -0.4f, 0f, 0.4f, 1f, 1.6f, 2f, 2.4f, 2.6f), "A gentle smile: a touch more weight and sparkle", EqGroup.SIGNATURE),
    WARM("Warm", resampleBands(listOf(3f, 2f, 1f, 0f, -1f, -2f, -2f)), "Rounder, softer highs", EqGroup.SIGNATURE),
    BRIGHT("Bright", resampleBands(listOf(-1f, 0f, 0f, 1f, 2f, 4f, 5f)), "More detail and presence", EqGroup.SIGNATURE),
    TREBLE_AIR("Air", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1f, 2f, 3f, 4.5f, 5.5f), "Shimmer above 10 kHz, nothing else touched", EqGroup.SIGNATURE),
    LATE_NIGHT("Late night", listOf(5f, 4.5f, 3.5f, 2f, 0.5f, -0.5f, -1f, -1f, -0.5f, 0f, 0.5f, 1.5f, 2.5f, 3f, 3f), "Full sound at low volume, as ears hear less bass and treble when it's quiet", EqGroup.SITUATION),
    LOUDNESS("Loudness", resampleBands(listOf(5f, 3f, 0f, -1f, 0f, 3f, 4f)), "Punchy lows and highs for noisy places", EqGroup.SITUATION),
    PHONE_SPEAKER("Phone speaker", listOf(-6f, -6f, -3f, 1f, 2.5f, 2f, 1f, 0f, 0f, 1f, 2f, 2f, 1f, 0.5f, 0f), "Leaves out bass a small speaker can't play, so it doesn't rattle", EqGroup.SITUATION),
    CAR("Car", listOf(3f, 3f, 2.5f, 1f, 0f, -1f, -1f, 0f, 0.5f, 1f, 1.5f, 2f, 2f, 1.5f, 1f), "Lows and clarity that carry over road noise", EqGroup.SITUATION),
    SPEECH("Speech", listOf(-6f, -5f, -3f, -1f, 0f, 0.5f, 1f, 1.5f, 2f, 3f, 3f, 2f, 0f, -1f, -2f), "Podcasts and talk: voices forward, rumble gone", EqGroup.SITUATION),
    VOCAL_CLARITY("Vocal clarity", listOf(-1.5f, -1.5f, -1f, -0.5f, 0f, -0.5f, 0f, 1f, 2f, 3f, 3f, 2f, 1f, 0.5f, 0f), "Singers step out of the mix", EqGroup.SITUATION),
    BASS("Bass", resampleBands(listOf(6f, 4f, 1f, 0f, 0f, 0f, 0f)), "More low end", EqGroup.GENRE),
    DEEP_BASS("Deep bass", listOf(6f, 5.5f, 4.5f, 3f, 1.5f, 0f, -1f, -1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), "Sub-bass you feel", EqGroup.GENRE),
    HIPHOP("Hip-hop", listOf(5f, 5f, 4f, 2.5f, 0.5f, -1f, -1.5f, -1f, 0f, 1f, 1.5f, 1f, 1.5f, 2f, 2f), "Heavy kick, clear vocals", EqGroup.GENRE),
    VOCAL("Vocal", resampleBands(listOf(-2f, -1f, 1f, 3f, 3f, 1f, 0f)), "Warm, forward voices", EqGroup.GENRE),
    ROCK("Rock", listOf(4f, 4f, 3.5f, 2.5f, 1f, -0.5f, -1.5f, -1.5f, -0.5f, 1f, 2.5f, 3.5f, 4f, 4f, 4f), "Big drums, bite in the guitars", EqGroup.GENRE),
    POP("Pop", listOf(-1f, -0.5f, 0.5f, 1.5f, 2.5f, 3f, 2.5f, 1.5f, 0.5f, 0f, -0.5f, -0.5f, 0f, 0.5f, 1f), "Present, polished mids", EqGroup.GENRE),
    ELECTRONIC("Electronic", resampleBands(listOf(5f, 3f, 0f, -2f, 1f, 3f, 4f)), "Deep drops, crisp hats", EqGroup.GENRE),
    ACOUSTIC("Acoustic", resampleBands(listOf(3f, 2f, 1f, 1f, 2f, 2f, 1f)), "Natural body and string detail", EqGroup.GENRE),
    CLASSICAL("Classical", listOf(3f, 3f, 2.5f, 2f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, -1f, -1.5f, -2f, -2.5f), "A hall's warmth, gentle highs", EqGroup.GENRE),
}

@Serializable
data class PlaybackSettings(
    /** The sleep timer fades the music out over its last minutes instead of stopping it dead. */
    val sleepWindDown: Boolean = true,
    val wifiQuality: AudioQuality = AudioQuality.MAX,
    val mobileQuality: AudioQuality = AudioQuality.MAX,
    /**
     * Carry the signal to the output as 32-bit float. With float output on, the
     * decoder is asked for high-res PCM rather than 16-bit (Android 12+ FLAC
     * decoders oblige), so a 24-bit source keeps its precision to the mixer
     * instead of being cut to 16-bit at the decoder. On by default for
     * end-to-end hi-res; a device whose mixer dislikes float can turn it off and
     * fall back to dithered 16-bit.
     */
    val floatOutput: Boolean = true,
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
    /**
     * Smart shuffle can be chosen: the player's shuffle button gets a third
     * state, and a long press on a playlist or album's Shuffle starts it.
     * Plain shuffle never mixes anything in, so this is on by default; off
     * takes the extra state and the long press away.
     */
    val smartShuffle: Boolean = true,
    val stopOnTaskRemoved: Boolean = true,
    /** Swiping a song row plays it next (toward the end of the line) or adds it to the queue (toward the start). */
    val swipeToQueue: Boolean = true,
    /** Buffer the next track and resolve its neighbours early, for instant skips. */
    val preloadUpcoming: Boolean = true,
    /** A downloaded song streams in its best quality while there's internet; its file plays offline. */
    val streamDownloadsOnline: Boolean = true,
    /** Swap to a clearly better stream mid-song when one turns up. */
    val qualityUpgrade: Boolean = true,
    /** Community FLAC lookup runs after playback starts. Downloads remain YouTube audio. */
    val losslessStreaming: Boolean = false,
    val losslessUnmeteredOnly: Boolean = false,
    val losslessHiRes: Boolean = false,
    val jioSaavnQuality: Boolean = false,
    /** Optional base URL implementing Meld's /api/get-music and /api/download-music protocol. */
    val qobuzRelayUrl: String = "",
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
    /** On the lyrics view, the controls slide away after a few seconds untouched, leaving the lyrics. */
    val lyricsFocus: Boolean = true,
    /** The mini player's glass takes the cover's colour; off keeps it plain. */
    val miniPlayerTint: Boolean = true,
    /** An artist's page is washed with a deep shade of their photo's colour; off keeps the theme's own. */
    val artistPageTint: Boolean = true,
    val statsForNerds: Boolean = false,
    val recentsAsGrid: Boolean = false,
    /** Speed dial at the top of Home: the most-played songs, albums and artists. */
    val speedDial: Boolean = true,
    /** Refracting Liquid Glass on Android 13+; frosted blur otherwise. */
    val liquidGlass: Boolean = true,
    /** Draw the played part of the seek bar as a moving wave. */
    val wavySeekbar: Boolean = false,
    /** How thick the seek bar's track is. */
    val seekbarThickness: SeekbarThickness = SeekbarThickness.REGULAR,
    /** What marks the place on the seek bar. */
    val seekbarThumb: SeekbarThumb = SeekbarThumb.DOT,
    /** The played part's colour. */
    val seekbarColor: SeekbarColor = SeekbarColor.PLAIN,
    /** What the time on the right of the seek bar shows. */
    val seekbarTime: SeekbarTime = SeekbarTime.LEFT,
    /** Small dots on the seek bar where the song's parts (verse, chorus) begin. */
    val seekbarMarks: Boolean = true,
    /** A "Skip to chorus" button beside the source pill, when the lyrics mark the chorus. */
    val skipToChorus: Boolean = false,
    /** The pill under the title naming where the audio comes from and its quality. */
    val sourcePill: Boolean = true,
    /** A soft light in the cover's colour under the player's cover. */
    val coverGlow: Boolean = true,
    /** While the seek bar is dragged, the lyric line at that point shows above the finger. */
    val lyricScrubPreview: Boolean = true,
    /** Sharp album covers from Apple Music, Deezer or the Cover Art Archive in the player. */
    val albumCovers: Boolean = true,
    /** Apple Music's animated album covers loop, muted, in the player's cover. */
    val animatedCovers: Boolean = true,
    /** Animated covers and Spotify Canvas only load on Wi-Fi or another unmetered network. */
    val animatedCoversWifiOnly: Boolean = true,
    /** With Spotify connected, its Canvas loops behind the player. */
    val spotifyCanvas: Boolean = true,
    val lyricsAnimation: LyricsAnimation = LyricsAnimation.FLUID,
    /** Lyrics text size, as a fraction of the standard size. */
    val lyricsTextScale: Float = 1f,
    val playerStyle: PlayerStyle = PlayerStyle.CLASSIC,
    val dockMotion: DockMotion = DockMotion.MINIMIZE,
    val controlStyle: ControlStyle = ControlStyle.BLOOM,
    /** The cover drifts and zooms slowly in the player while a song plays. */
    val movingCover: Boolean = true,
    /** One or two of Home's song and album cards drift and zoom the same way while the page sits still. */
    val homeMovingCovers: Boolean = true,
    /** How strong taps and buzzes are, 0 (off) to 1. */
    val hapticStrength: Float = 0.6f,
    /** Look for a new release once a day when the app opens. */
    val checkForUpdates: Boolean = true,
    /** Add this phone to the app's anonymous usage counts (see Usage). */
    val countUsage: Boolean = true,
    /** How the glass of the dock, buttons and bars looks. */
    val glassStyle: GlassStyle = GlassStyle.LIQUID,
    /** Pages blur and fade out as they scroll under the status bar. */
    val frostedTopEdge: Boolean = true,
    /** How the dock's highlight travels between tabs. */
    val dockLens: DockLens = DockLens.STRETCH,
    /** How one page gives way to the next. */
    val pageTransition: PageTransition = PageTransition.SLIDE,
    val lyricsAlign: LyricsAlign = LyricsAlign.LEFT,
    /** A soft glow around the line being sung, whatever the lyrics animation. */
    val lyricsGlow: Boolean = false,
    /** High performance mode: ask for a fast refresh rate while the app is open. */
    val highRefreshRate: Boolean = true,
    /** The rate high performance mode asks for, in Hz; 0 for the screen's fastest. */
    val refreshRateHz: Int = 0,
    /** How the full player rises over the page and drops away. */
    val playerMotion: PlayerMotion = PlayerMotion.SPRING,
    /** How the player's cover gives way to the next song's. */
    val coverChange: CoverChange = CoverChange.FADE,
    /** The first-run welcome: whether it's still to be shown. See [com.opentune.data.Welcome]. */
    val welcome: WelcomeState = WelcomeState.UNDECIDED,
    /** On wide windows (tablets, unfolded phones, phones on their side): the tabs in a rail and pages in a readable column. */
    val wideLayout: Boolean = true,
    /** Explore has its own tab; off, it leaves the dock and fills the Search page until a search starts. */
    val separateExplore: Boolean = true,
    /** On large screens, the player stays docked beside the page while a song is loaded. */
    val sidePlayer: Boolean = true,
    /** Your top songs play behind the Wrapped story; off is its mute button, kept for next time. */
    val wrappedMusic: Boolean = true,
)

/**
 * Where the first-run welcome stands. Settings saved before it existed read
 * back as [UNDECIDED], like a fresh install's, and the first start after
 * that decides once which of the other two it is.
 */
@Serializable
enum class WelcomeState {
    UNDECIDED,
    /** To be shown: a fresh install, or "Show the welcome again". */
    DUE,
    /** Seen, skipped, or not needed because the app was already in use. */
    SEEN,
}

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
    ECHO("Echo", "A soft spring that settles without bouncing"),
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
    SLIDE("Push", "Pages push in from the side, as on iPhone"),
    FADE("Fade through", "The page fades out, then the next fades in and settles"),
    ZOOM("Depth", "Pages come forward from behind; going back, they sink away"),
    RISE("Rise", "Pages rise like cards over the one before, which sinks back a little"),
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
    /** Also copy each download into the phone's Music folder, in a format other apps play; see [com.opentune.data.download.DownloadExport]. */
    val exportDownloads: Boolean = false,
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
    ECHO("Echo", "An accent-coloured wavy play button that turns gently while playing"),
}

/**
 * How the dock tucks away while a page scrolls down, and comes back. Each
 * ends in a different shape and gets there a different way. A saved choice
 * that has since been dropped reads back as [MINIMIZE], the default.
 */
@Serializable
enum class DockMotion(val label: String, val summary: String) {
    MINIMIZE("Minimize", "Like iOS 26: the tabs shrink to the open one, with the song in a slim bar beside it"),
    CAPSULE("Capsule", "The tabs and the song gather into one small capsule in the middle"),
    SINK("Sink", "The tabs sink away into a blur and the song settles into their place"),
}

/** How synced lyrics move as the song plays. */
@Serializable
enum class SeekbarThickness(val label: String, val dp: Float) {
    THIN("Thin", 3f),
    REGULAR("Regular", 5f),
    THICK("Thick", 9f),
}

enum class SeekbarThumb(val label: String) {
    DOT("Dot"),
    LINE("Line"),
    TOUCH("While dragging"),
    NONE("None"),
}

enum class SeekbarColor(val label: String) {
    PLAIN("Plain"),
    ACCENT("Accent"),
    GRADIENT("Gradient"),
}

enum class SeekbarTime(val label: String) {
    LEFT("Time left"),
    TOTAL("Length"),
    HIDDEN("Hidden"),
}

enum class LyricsAnimation(val label: String, val summary: String) {
    FLUID("Fluid", "The current line grows and the rest soften"),
    KARAOKE("Karaoke", "Words fill with a glow; lines stay still"),
    ZOOM("Focus zoom", "A big current line, the rest blurred away"),
    MINIMAL("Minimal", "Only the colour changes"),
}

/** Where lyrics can come from. */
@Serializable
enum class LyricsSource(val label: String, val summary: String) {
    LRC_RED("lrc.red", "Word-by-word lyrics filed by recording, as BitChord uses them first"),
    PAXSENIX("Paxsenix", "Apple Music's word-by-word lyrics, with duets and backing vocals"),
    LRCLIB("LRCLIB", "Community lyrics, line by line and sometimes word by word"),
    LYRICSPLUS("LyricsPlus", "Word-by-word lyrics from the servers behind YouLyPlus, as Echo Music uses them"),
    BETTERLYRICS("Better Lyrics", "Word-by-word lyrics from Better Lyrics, for songs its listeners have already looked up"),
    SIMPMUSIC("SimpMusic", "Community lyrics for YouTube songs, line by line and word by word"),
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
    val preferWordSynced: Boolean = true,
    /** Per-song timing shift in ms, set from the player's "Lyrics offset". */
    val offsets: Map<String, Long> = emptyMap(),
    /** A line in Latin letters under each one in another script. */
    val romanize: Boolean = true,
    /** Per-song source picked from the player's "Lyrics source"; the others aren't asked first. */
    val chosen: Map<String, LyricsSource> = emptyMap(),
) {
    /**
     * The saved order, with any source added since appended at the end; lrc.red
     * and Paxsenix, the most exact of them, go in first when a saved order
     * hasn't met them yet.
     */
    val ordered: List<LyricsSourceEntry>
        get() {
            val missing = LyricsSource.entries.filter { s -> sources.none { it.source == s } }.map(::LyricsSourceEntry)
            val (front, back) = missing.partition { it.source == LyricsSource.LRC_RED || it.source == LyricsSource.PAXSENIX }
            return front + sources.distinctBy { it.source } + back
        }
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
    /** Searches made in Search's Add-ons tab, kept apart from the YouTube ones. */
    val recentAddonSearches: List<String> = emptyList(),
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
    private val _recentAddonSearches = MutableStateFlow<List<String>>(emptyList())
    val recentAddonSearches: StateFlow<List<String>> = _recentAddonSearches.asStateFlow()

    /** Read by StreamResolver on every resolve: the ceiling for the network in use. */
    val effectiveAudioQuality: AudioQuality
        get() = _playback.value.let { if (onMeteredNetwork()) it.mobileQuality else it.wifiQuality }

    /** Whether the phone has a working internet connection right now. */
    fun isOnline(): Boolean {
        val cm = connectivity ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

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
        _recentAddonSearches.value = s.recentAddonSearches
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

    /** Sets [videoId]'s lyrics offset; 0 clears it, unless [keepZero] (a 0 that overrides the automatic shift). */
    fun setLyricsOffset(videoId: String, ms: Long, keepZero: Boolean = false) = updateLyrics { l ->
        l.copy(offsets = if (ms == 0L && !keepZero) l.offsets - videoId else (l.offsets + (videoId to ms)).entries.toList().takeLast(500).associate { it.toPair() })
    }

    /** Pins [videoId]'s lyrics to [source]; null goes back to the usual order. */
    fun setLyricsSource(videoId: String, source: LyricsSource?) = updateLyrics { l ->
        l.copy(chosen = if (source == null) l.chosen - videoId else (l.chosen - videoId + (videoId to source)).entries.toList().takeLast(500).associate { it.toPair() })
    }

    fun setAutoplay(enabled: Boolean) = updatePlayback { it.copy(autoplay = enabled) }

    fun addRecentSearch(query: String) = update { s ->
        s.copy(recentSearches = (listOf(query) + s.recentSearches.filterNot { it.equals(query, true) }).take(MAX_RECENT))
    }

    fun removeRecentSearch(query: String) = update { it.copy(recentSearches = it.recentSearches - query) }

    fun clearRecentSearches() = update { it.copy(recentSearches = emptyList()) }

    fun addRecentAddonSearch(query: String) = update { s ->
        s.copy(recentAddonSearches = (listOf(query) + s.recentAddonSearches.filterNot { it.equals(query, true) }).take(MAX_RECENT))
    }

    fun removeRecentAddonSearch(query: String) = update { it.copy(recentAddonSearches = it.recentAddonSearches - query) }

    fun clearRecentAddonSearches() = update { it.copy(recentAddonSearches = emptyList()) }

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

