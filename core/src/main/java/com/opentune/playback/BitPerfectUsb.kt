package com.opentune.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import com.opentune.data.DebugLog as Log
import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Exclusive, bit-perfect output to a USB DAC on Android 14 and newer, through
 * the platform's own mixer bypass ([AudioManager.setPreferredMixerAttributes]
 * with [AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT]). While it's on, the
 * DAC takes nothing but this app's music, unmixed, unresampled and without
 * system effects.
 *
 * Each time a song's output opens ([prepare], from [ExclusiveUsbOutputProvider]),
 * the DAC is switched to that song's own sample rate and to the sample format
 * that carries its bit depth whole: 16-bit stays 16-bit where the DAC takes it,
 * 24-bit goes out as 24-bit packed or 32-bit, never cut to 16. So a 44.1 kHz
 * album followed by a 96 kHz one plays each at its own rate, as USB Audio
 * Player PRO or HiBy do with their own drivers.
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
        /** The DAC has no bit-perfect format for this song: Android's normal mixer plays it instead. */
        data class NoMatch(val sampleRate: Int, val bits: Int = 0) : Status
        data class Active(val device: String, val sampleRate: Int, val bits: Int, val format: String = "") : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    val active: Boolean get() = _status.value is Status.Active

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Whether the listener has asked for it (Settings, Sound). */
    @Volatile var wanted = false
        private set

    /**
     * The decoder's own sample format (a [C] PCM encoding) for the song whose
     * output is about to open, so the DAC is asked for a format that holds it whole.
     */
    @Volatile var sourceEncoding: Int = C.ENCODING_PCM_16BIT

    // Made when first needed, so the decoder side can tell this object a song's format off a device too.
    private val media: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    private var audioManager: AudioManager? = null
    private var applied: AudioDeviceInfo? = null
    /** The mixer last asked for, so the same song's output opening again doesn't ask twice. */
    private var appliedKey: Triple<Int, Int, Int>? = null

    fun init(am: AudioManager) { audioManager = am }

    /** Turns it on or off; on, it takes hold as the next output opens. */
    fun setWanted(wanted: Boolean) {
        this.wanted = wanted
        val am = audioManager
        when {
            !wanted -> { am?.let(::clear); _status.value = Status.Off }
            !supported -> _status.value = Status.Unsupported
            am == null || usbDac(am) == null -> { am?.let(::clear); _status.value = Status.NoDac }
        }
    }

    /** A USB device came or went: drop a mixer set on a DAC that's gone. */
    fun devicesChanged() {
        val am = audioManager ?: return
        if (!wanted || !supported) return
        if (usbDac(am) == null) {
            clear(am)
            _status.value = Status.NoDac
        }
    }

    /**
     * For an output about to open at [sampleRate] with [channels]: the sample
     * format (a [C] PCM encoding) the DAC takes this song in bit for bit, with
     * the DAC already set to it. Null leaves the output on Android's normal path.
     */
    @Synchronized
    fun prepare(sampleRate: Int, channels: Int): Int? {
        val am = audioManager ?: return null
        if (!wanted) return null
        if (!supported) { _status.value = Status.Unsupported; return null }
        return prepareApi34(am, sampleRate, channels, sourceEncoding)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun prepareApi34(am: AudioManager, sampleRate: Int, channels: Int, source: Int): Int? {
        val dac = usbDac(am)
        if (dac == null) {
            clear(am)
            _status.value = Status.NoDac
            return null
        }
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return null.also { clear(am); _status.value = Status.NoMatch(sampleRate, bitsOf(source)) }
        }
        val offered = runCatching { am.getSupportedMixerAttributes(dac) }.getOrDefault(emptyList())
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT && it.format.sampleRate == sampleRate && it.format.channelMask == mask }
        // The first format, in order, that holds the song's samples whole.
        val choice = carriers(source).firstNotNullOfOrNull { encoding -> offered.firstOrNull { it.format.encoding == encoding } }
        if (choice == null) {
            // Asking for a format the DAC doesn't list makes it buzz or go quiet; leave Android's mixer.
            clear(am)
            _status.value = Status.NoMatch(sampleRate, bitsOf(source))
            return null
        }
        val key = Triple(dac.id, sampleRate, choice.format.encoding)
        if (key != appliedKey || applied?.id != dac.id) {
            val ok = runCatching { am.setPreferredMixerAttributes(media, dac, choice) }
                .onFailure { Log.w(TAG, "Couldn't set the bit-perfect mixer", it) }
                .getOrDefault(false)
            if (!ok) {
                clear(am)
                _status.value = Status.NoMatch(sampleRate, bitsOf(source))
                return null
            }
            applied = dac
            appliedKey = key
        }
        val encoding = choice.format.encoding
        _status.value = Status.Active(dac.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB DAC", sampleRate, bitsOf(encoding), label(encoding))
        return encoding
    }

    private fun usbDac(am: AudioManager): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

    private fun clear(am: AudioManager) {
        val dac = applied ?: return
        if (supported) runCatching { clearApi34(am, dac) }
        applied = null
        appliedKey = null
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun clearApi34(am: AudioManager, dac: AudioDeviceInfo) {
        am.clearPreferredMixerAttributes(media, dac)
    }

    /**
     * The DAC formats that carry [source] without losing a bit, best first.
     * A 16-bit song goes out as 16-bit where it can, else padded into a wider
     * word (the same value, exactly); a 24-bit one never goes out as 16.
     */
    internal fun carriers(source: Int): List<Int> = when (source) {
        C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT -> listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
        C.ENCODING_PCM_24BIT -> listOf(C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
        C.ENCODING_PCM_32BIT -> listOf(C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
        else -> listOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_24BIT)
    }

    internal fun bitsOf(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }

    private fun label(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_16BIT -> "16-bit PCM"
        C.ENCODING_PCM_24BIT -> "24-bit PCM"
        C.ENCODING_PCM_32BIT -> "32-bit PCM"
        C.ENCODING_PCM_FLOAT -> "32-bit float"
        else -> ""
    }

    /**
     * The volume the app applies while bit-perfect: 0 dB at the top step,
     * falling on a power curve (-62 dB + 62 dB * v^0.75), silent at 0.
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
