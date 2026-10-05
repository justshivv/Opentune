package com.opentune.playback

import android.media.audiofx.PresetReverb
import androidx.annotation.OptIn
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.data.DebugLog as Log
import com.opentune.data.settings.ReverbLevel
import com.opentune.data.settings.SoundSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Applies the Remix settings that aren't done in [dsp.DspAudioProcessor]:
 * speed and pitch through ExoPlayer's time-stretching, and reverb through
 * Android's PresetReverb. Reverb is device-dependent; when it can't be created
 * [reverbAvailable] goes false so the UI can say so. Bass boost is a filter in
 * the app's own DSP, so it works on every phone.
 */
@OptIn(UnstableApi::class)
class SoundEffects(private val player: ExoPlayer) {

    private var reverb: PresetReverb? = null

    fun apply(settings: SoundSettings) {
        player.playbackParameters = PlaybackParameters(settings.speed, settings.pitch)
        applyReverb(settings.reverb)
    }

    fun release() {
        reverb?.release()
        reverb = null
    }

    private fun applyReverb(level: ReverbLevel) {
        if (level == ReverbLevel.OFF) {
            reverb?.enabled = false
            player.setAuxEffectInfo(AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f))
            return
        }
        try {
            // An auxiliary effect lives on the output mix (session 0); the
            // player sends to it at full level.
            val effect = reverb ?: PresetReverb(0, 0).also { reverb = it }
            effect.preset = level.preset
            effect.enabled = true
            player.setAuxEffectInfo(AuxEffectInfo(effect.id, 1f))
            _reverbAvailable.value = true
        } catch (e: Exception) {
            Log.w(TAG, "Reverb unavailable", e)
            reverb?.release()
            reverb = null
            _reverbAvailable.value = false
        }
    }

    companion object {
        private const val TAG = "SoundEffects"

        private val _reverbAvailable = MutableStateFlow(true)
        val reverbAvailable = _reverbAvailable.asStateFlow()
    }
}
