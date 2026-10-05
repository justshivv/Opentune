package com.opentune.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.PresetReverb
import androidx.annotation.OptIn
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.data.DebugLog as Log
import com.opentune.data.settings.ReverbLevel
import com.opentune.data.settings.SoundSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Applies the remix settings to the player: speed and pitch through
 * ExoPlayer's own time-stretching, reverb and bass through Android's audio
 * effects. The effects are device-dependent; when one can't be created its
 * availability flag goes false so the UI can say so instead of silently
 * doing nothing.
 */
@OptIn(UnstableApi::class)
class SoundEffects(private val player: ExoPlayer) {

    private var bassBoost: BassBoost? = null
    private var bassSession = C.AUDIO_SESSION_ID_UNSET
    private var reverb: PresetReverb? = null
    private var current = SoundSettings()

    fun apply(settings: SoundSettings) {
        current = settings
        player.playbackParameters = PlaybackParameters(settings.speed, settings.pitch)
        applyBass(settings.bassBoost)
        applyReverb(settings.reverb)
    }

    /** Insert effects are tied to a session; rebuild on a new one. */
    fun onAudioSessionIdChanged() {
        releaseBass()
        applyBass(current.bassBoost)
    }

    fun release() {
        releaseBass()
        reverb?.release()
        reverb = null
    }

    private fun applyBass(strength: Int) {
        if (strength <= 0) {
            bassBoost?.enabled = false
            return
        }
        val session = player.audioSessionId
        if (session == C.AUDIO_SESSION_ID_UNSET) return
        try {
            if (bassBoost == null || bassSession != session) {
                releaseBass()
                bassBoost = BassBoost(0, session)
                bassSession = session
            }
            bassBoost?.apply {
                if (strengthSupported) setStrength(strength.coerceIn(0, 1000).toShort())
                enabled = true
            }
            _bassAvailable.value = true
        } catch (e: Exception) {
            Log.w(TAG, "Bass boost unavailable", e)
            releaseBass()
            _bassAvailable.value = false
        }
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

    private fun releaseBass() {
        bassBoost?.release()
        bassBoost = null
        bassSession = C.AUDIO_SESSION_ID_UNSET
    }

    companion object {
        private const val TAG = "SoundEffects"

        private val _reverbAvailable = MutableStateFlow(true)
        val reverbAvailable = _reverbAvailable.asStateFlow()

        private val _bassAvailable = MutableStateFlow(true)
        val bassAvailable = _bassAvailable.asStateFlow()
    }
}
