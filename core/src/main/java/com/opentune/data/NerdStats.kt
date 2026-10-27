package com.opentune.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Figures for "Stats for nerds": the bitrate [StreamResolver][com.opentune.data.innertube.StreamResolver]
 * picked for the track playing, and how long the last track took from being
 * asked for to making sound.
 */
object NerdStats {
    /**
     * What the player itself reports about the decode and output stages: the
     * codec it opened, the PCM it hands on, and the AudioTrack it plays through.
     */
    data class Pipeline(
        val decoderName: String? = null,
        /** The decoder's PCM out, as "16-bit PCM", "24-bit PCM" or "Float32". */
        val decoderOutput: String? = null,
        val outputSampleRateHz: Int? = null,
        /** The AudioTrack's encoding, the same way. */
        val outputEncoding: String? = null,
        val outputChannels: Int? = null,
        val bufferMs: Int? = null,
        val bufferFrames: Int? = null,
        val offload: Boolean = false,
    )
    private val _pipeline = MutableStateFlow(Pipeline())
    val pipeline = _pipeline.asStateFlow()
    fun onPipeline(change: (Pipeline) -> Pipeline) { _pipeline.value = change(_pipeline.value) }

    private val _upgrading = MutableStateFlow<String?>(null)
    val upgrading = _upgrading.asStateFlow()
    fun onUpgrade(mediaId: String?) { _upgrading.value = mediaId }

    data class Source(val provider: String, val bits: Int? = null, val sampleRate: Int? = null, val kbps: Int? = null,
        val selectionDetail: String? = null, val mediaId: String? = null)
    private val _playbackSource = MutableStateFlow<Source?>(null)
    val playbackSource = _playbackSource.asStateFlow()
    fun onPlaybackSource(source: Source) { _playbackSource.value = source }

    private val _externalSource = MutableStateFlow<String?>(null)
    val externalSource = _externalSource.asStateFlow()

    /** Provider of the playing external stream, never its requested-quality label. */
    fun onExternalSource(provider: String?) { _externalSource.value = provider }

    private val _lastPicked = MutableStateFlow<Pair<String, Int>?>(null)
    val lastPicked = _lastPicked.asStateFlow()

    fun onStreamPicked(videoId: String, kbps: Int) {
        _lastPicked.value = videoId to kbps
    }

    /** The loudness-normalization gain on the current track, in dB, or null when none. */
    private val _loudnessGainDb = MutableStateFlow<Float?>(null)
    val loudnessGainDb = _loudnessGainDb.asStateFlow()

    /** True while normalization is on but held off because the sound is on the phone speaker. */
    private val _loudnessOffOnSpeaker = MutableStateFlow(false)
    val loudnessOffOnSpeaker = _loudnessOffOnSpeaker.asStateFlow()

    fun onLoudnessOffOnSpeaker(off: Boolean) {
        _loudnessOffOnSpeaker.value = off
    }

    fun onLoudnessGain(db: Float?) {
        _loudnessGainDb.value = db
    }

    /** Which engine found the playing track's stream: OpenTune's walk or InnerTubeX. */
    private val _engine = MutableStateFlow<String?>(null)
    val engine = _engine.asStateFlow()

    fun onEngine(label: String) {
        _engine.value = label
    }

    private val _startupMs = MutableStateFlow<Long?>(null)
    val startupMs = _startupMs.asStateFlow()

    fun onStartup(ms: Long) {
        _startupMs.value = ms
    }
}
