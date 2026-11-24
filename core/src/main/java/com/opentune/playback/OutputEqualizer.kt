package com.opentune.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.EqSnapshot
import com.opentune.data.settings.EqualizerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The equalizer for each output, with Settings › Equalizer › Each output its own on: wired
 * headphones, every Bluetooth device by its name, a USB DAC and the phone's speaker keep their
 * own curve and headphone correction. A change made on the equalizer page goes to the output
 * playing then; when the sound moves to another, that one's settings come back. One heard for
 * the first time keeps the curve as it is but not the headphone correction, which was measured
 * for other headphones. Runs for the whole app, not only while music plays, so a change made
 * with nothing playing is kept for the right output too.
 */
object OutputEqualizer {
    /** One output, as the equalizer tells them apart. */
    data class Output(val key: String, val name: String)

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var audio: AudioManager? = null
    private var started = false

    /** The output the equalizer as it is now belongs to; null until the first is known. */
    private var appliedKey: String? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val am = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
        audio = am
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = sync()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = sync()
        }, main)
        scope.launch { AppSettings.equalizer.collect { sync() } }
    }

    /** Where the sound goes now; null before [start]. */
    fun current(): Output? = audio?.let { outputOf(it.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().toList()) }

    private fun sync() {
        val output = current() ?: return
        val eq = AppSettings.equalizer.value
        if (!eq.perOutput) { appliedKey = output.key; return }
        val next = step(eq, appliedKey, output.key)
        appliedKey = output.key
        if (next == eq) return
        val switched = next.snapshot() != eq.snapshot()
        AppSettings.updateEqualizer { next }
        if (switched) com.opentune.data.Pills.info(com.opentune.i18n.Tr.f("Equalizer for {0}", output.name))
    }

    /**
     * The equalizer after [eq] was seen on output [now], having belonged to [before]: on the same
     * output, its settings are kept for it; on another, that one's come back (or, for one not
     * heard before, the curve as it is without the headphone correction).
     */
    internal fun step(eq: EqualizerSettings, before: String?, now: String): EqualizerSettings {
        if (before == now) {
            val mine = eq.snapshot()
            return if (eq.outputs[now] == mine) eq else eq.copy(outputs = eq.outputs + (now to mine))
        }
        val target: EqSnapshot = eq.outputs[now] ?: eq.snapshot().copy(headphone = null)
        // What was playing keeps its settings too, in case they weren't saved yet.
        val kept = if (before != null) eq.outputs + (before to eq.snapshot()) else eq.outputs
        return eq.with(target).copy(outputs = kept + (now to target))
    }

    /** The output media plays on: Bluetooth first, then USB, wired headphones, the speaker. */
    internal fun outputOf(devices: List<AudioDeviceInfo>): Output {
        fun first(vararg types: Int) = devices.firstOrNull { it.type in types }
        fun name(d: AudioDeviceInfo, fallback: String) = d.productName?.toString()?.trim()?.takeIf(String::isNotEmpty) ?: fallback
        val bt = buildList {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
        }.toIntArray()
        first(*bt)?.let { val n = name(it, "Bluetooth"); return Output("bt:$n", n) }
        first(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)?.let { val n = name(it, "USB audio"); return Output("usb:$n", n) }
        first(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)?.let { return Output("wired", com.opentune.i18n.Tr.t("Headphones")) }
        return Output("speaker", com.opentune.i18n.Tr.t("Phone speaker"))
    }
}
