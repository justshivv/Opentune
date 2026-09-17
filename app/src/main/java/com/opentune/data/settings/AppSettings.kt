package com.opentune.data.settings

/** Audio quality ceilings [StreamResolver][com.opentune.data.innertube.StreamResolver] ranks candidate formats against. */
enum class AudioQuality(val maxKbps: Int) {
    LOW(64),
    NORMAL(128),
    HIGH(256),
    AUTO(Int.MAX_VALUE),
}

/**
 * App-wide settings. Trimmed to what the MVP playback path reads; there is no
 * persistence yet, so this resets on process death.
 */
object AppSettings {
    @Volatile
    var effectiveAudioQuality: AudioQuality = AudioQuality.AUTO
}
