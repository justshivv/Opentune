package com.opentune.playback

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Shared by the player and on-device PCM regression tests. */
@UnstableApi
internal class MusicRenderersFactory(context: Context, private val floatOutput: Boolean,
    private val processors: Array<AudioProcessor>) : DefaultRenderersFactory(context) {
    init { setMediaCodecSelector(AudioDecoders.selector); setEnableDecoderFallback(true) }
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context).setEnableFloatOutput(floatOutput)
            .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(*processors)).build()
}
