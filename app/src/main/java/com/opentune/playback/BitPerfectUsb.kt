package com.opentune.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import com.opentune.data.DebugLog as Log
import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bit-perfect output to a USB DAC on Android 14 and newer, through the
 * platform's own mixer bypass ([AudioManager.setPreferredMixerAttributes]
 * with [AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT]). The DAC then gets
 * the decoder's samples at the song's own rate: no Android resampling, no
 * mixing, no system effects.
 *
 * Android doesn't apply the system volume in this mode, so the app does it:
 * [softwareGainDb] follows the volume keys on a perceptual curve, and at
 * full volume the gain is exactly 0 dB, so the stream is bit-exact.
 */
object BitPerfectUsb {
    sealed interface Status {
        data object Off : Status
        data object Unsupported : Status
        data object NoDac : Status
        data class NoMatch(val sampleRate: Int) : Status
        data class Active(val device: String, val sampleRate: Int, val bits: Int) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    val active: Boolean get() = _status.value is Status.Active

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private val media: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var applied: AudioDeviceInfo? = null

    /**
     * Asks for (or drops) the bit-perfect mixer on the connected USB DAC, at
     * [sampleRate] and 16-bit or float to match what the player outputs.
     */
    fun apply(am: AudioManager, wanted: Boolean, sampleRate: Int, float: Boolean) {
        if (!supported) {
            _status.value = if (wanted) Status.Unsupported else Status.Off
            return
        }
        applyApi34(am, wanted, sampleRate, float)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyApi34(am: AudioManager, wanted: Boolean, sampleRate: Int, float: Boolean) {
        val dac = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        if (!wanted || dac == null) {
            clear(am)
            _status.value = if (!wanted) Status.Off else Status.NoDac
            return
        }
        val encoding = if (float) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val choice = runCatching { am.getSupportedMixerAttributes(dac) }.getOrDefault(emptyList())
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            .firstOrNull {
                it.format.sampleRate == sampleRate && it.format.encoding == encoding &&
                    it.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO
            }
        if (choice == null) {
            // Asking for a format that doesn't match makes the DAC buzz; leave the normal mixer.
            clear(am)
            _status.value = Status.NoMatch(sampleRate)
            return
        }
        val ok = runCatching { am.setPreferredMixerAttributes(media, dac, choice) }
            .onFailure { Log.w(TAG, "Couldn't set the bit-perfect mixer", it) }
            .getOrDefault(false)
        if (ok) {
            applied = dac
            _status.value = Status.Active(dac.productName?.toString() ?: "USB DAC", sampleRate, if (float) 32 else 16)
        } else {
            clear(am)
            _status.value = Status.NoMatch(sampleRate)
        }
    }

    private fun clear(am: AudioManager) {
        val dac = applied ?: return
        if (supported) runCatching { clearApi34(am, dac) }
        applied = null
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun clearApi34(am: AudioManager, dac: AudioDeviceInfo) {
        am.clearPreferredMixerAttributes(media, dac)
    }

    /**
     * The volume the app applies while bit-perfect: 0 dB at the top step,
     * falling on the curve LastWave uses (-62 dB + 62 dB * v^0.75), silent at 0.
     */
    fun softwareGainDb(am: AudioManager): Float {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        if (v <= 0f) return SILENT_DB
        return (-62f + 62f * v.pow(0.75f)).coerceAtMost(0f)
    }

    private const val TAG = "BitPerfectUsb"
    const val SILENT_DB = -120f
}
