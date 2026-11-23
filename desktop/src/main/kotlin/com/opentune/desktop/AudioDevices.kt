package com.opentune.desktop

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the sound goes: the system's default output, or a device chosen in Settings, as
 * BitChord's desktop lets you choose. Kept by name, not by place in the list, since the
 * order changes as devices come and go; a chosen device that's gone plays on the default.
 * The list is miniaudio's where the native output loads ([NativeAudio]), else Java Sound's.
 */
internal object AudioDevices {
    data class Device(val name: String, val description: String)

    private val prefs by lazy { appContext.getSharedPreferences("audio", android.content.Context.MODE_PRIVATE) }
    private val _selected = MutableStateFlow(runCatching { prefs.getString("device", "").orEmpty() }.getOrDefault(""))
    /** The chosen device's name, or blank for the system's default. */
    val selected: StateFlow<String> = _selected.asStateFlow()

    private val _bitPerfect = MutableStateFlow(runCatching { prefs.getBoolean("bit_perfect", false) }.getOrDefault(false))
    /**
     * Settings → Playback → Bit-perfect lossless: an add-on's FLAC goes straight to the device
     * at its own rate and depth, exclusive where the system allows (Windows, ALSA), with no
     * equalizer, crossfade or volume levelling on it.
     */
    val bitPerfect: StateFlow<Boolean> = _bitPerfect.asStateFlow()

    fun setBitPerfect(on: Boolean) {
        if (on == _bitPerfect.value) return
        _bitPerfect.value = on
        runCatching { prefs.edit().putBoolean("bit_perfect", on).apply() }
    }

    /** Every output that can play, as the system lists them now. */
    fun available(): List<Device> {
        if (NativeAudio.available) {
            return NativeAudio.devices().map { Device(it.name, if (it.isDefault) "System default" else "") }.distinctBy { it.name }
        }
        return runCatching {
            AudioSystem.getMixerInfo()
                .filter { info ->
                    runCatching { AudioSystem.getMixer(info).isLineSupported(DataLine.Info(SourceDataLine::class.java, null)) }.getOrDefault(false)
                }
                .map { Device(it.name, it.description.orEmpty()) }
                .distinctBy { it.name }
        }.getOrDefault(emptyList())
    }

    /** [available], read off the window's thread: listing the outputs asks the sound system, which can take a while. */
    suspend fun read(): List<Device> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { available() }

    fun select(name: String) {
        if (name == _selected.value) return
        _selected.value = name
        runCatching { prefs.edit().putString("device", name).apply() }
        // The song playing moves to the new device where it is.
        Player.restartOutput()
    }

    /** The Java Sound mixer to open, or null for the default (nothing chosen, or the chosen one is gone). */
    fun mixer(): Mixer? {
        val name = _selected.value.takeIf { it.isNotBlank() } ?: return null
        return runCatching { AudioSystem.getMixerInfo().firstOrNull { it.name == name }?.let(AudioSystem::getMixer) }.getOrNull()
    }
}
