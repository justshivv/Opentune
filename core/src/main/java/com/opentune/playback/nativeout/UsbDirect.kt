package com.opentune.playback.nativeout

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.opentune.data.DebugLog as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OpenTune driving a USB DAC itself: it finds the DAC, asks for permission to
 * use it, takes its audio interfaces from Android (so nothing else plays on
 * it meanwhile), sets the DAC to each track's sample rate, picks the stream
 * format that carries the track's bits whole, and hands the native side (see
 * usb_iso.cpp) the endpoint to stream on. Works on any Android version the app
 * runs on, not only 14+.
 */
object UsbDirect {
    sealed interface Status {
        data object Off : Status
        data object NoDac : Status
        data class NeedsPermission(val device: String) : Status
        data class Ready(val device: String) : Status
        data class Streaming(val device: String, val rate: Int, val bits: Int) : Status
        data class Failed(val device: String, val why: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    private const val PERMISSION = "com.opentune.USB_DAC_PERMISSION"
    private var app: Context? = null
    private var usb: UsbManager? = null
    @Volatile var wanted = false
        private set

    // The DAC in use, held open between songs.
    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var described: UsbAudioDescriptors.Device? = null
    private val claimed = mutableListOf<UsbInterface>()
    private var rateSet = 0

    fun init(context: Context) {
        val c = context.applicationContext
        app = c
        usb = c.getSystemService(UsbManager::class.java)
        val filter = IntentFilter().apply {
            addAction(PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> { close(); refresh() }
                    else -> refresh()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) c.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") c.registerReceiver(receiver, filter)
    }

    fun setWanted(on: Boolean) {
        wanted = on
        if (!on) { close(); _status.value = Status.Off } else refresh()
    }

    /** A USB device with an audio streaming interface: a DAC, or a headset with one inside. */
    private fun findDac(): UsbDevice? = usb?.deviceList?.values?.firstOrNull { d ->
        (0 until d.interfaceCount).any { d.getInterface(it).let { i -> i.interfaceClass == UsbConstants.USB_CLASS_AUDIO && i.interfaceSubclass == 2 } }
    }

    private fun name(d: UsbDevice) = d.productName?.takeIf { it.isNotBlank() } ?: d.manufacturerName ?: "USB DAC"

    fun refresh() {
        if (!wanted) return
        val d = findDac()
        _status.value = when {
            d == null -> Status.NoDac
            usb?.hasPermission(d) != true -> Status.NeedsPermission(name(d))
            _status.value is Status.Streaming -> _status.value
            else -> Status.Ready(name(d))
        }
    }

    /** Asks Android for the DAC; the answer comes back through [refresh]. */
    fun requestPermission() {
        val c = app ?: return
        val d = findDac() ?: return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val intent = PendingIntent.getBroadcast(c, 0, Intent(PERMISSION).setPackage(c.packageName), flags)
        usb?.requestPermission(d, intent)
    }

    /** What a stream for one song needs: the open connection and the setting chosen. */
    class Session internal constructor(val fd: Int, val setting: UsbAudioDescriptors.StreamSetting, val highSpeed: Boolean, val rate: Int, val deviceName: String)

    /**
     * Gets the DAC ready for a track at [rate] Hz with [bits]-bit samples on
     * [channels] channels: the interface set to a format that holds them, and the
     * DAC's clock set to the rate. Null when there's no DAC, no permission, or it
     * can't take this track; the track then plays through Android as usual.
     */
    @Synchronized
    fun open(rate: Int, channels: Int, bits: Int): Session? {
        if (!wanted || !NativeAudio.loaded) return null
        val manager = usb ?: return null
        val d = findDac() ?: run { _status.value = Status.NoDac; return null }
        if (!manager.hasPermission(d)) { _status.value = Status.NeedsPermission(name(d)); return null }
        try {
            if (device?.deviceId != d.deviceId || connection == null) {
                close()
                val conn = manager.openDevice(d) ?: error("Android wouldn't open the DAC")
                device = d
                connection = conn
                described = UsbAudioDescriptors.parse(conn.rawDescriptors ?: ByteArray(0)) ?: error("No USB audio description")
            }
            val conn = connection!!
            val desc = described!!
            val setting = UsbAudioDescriptors.choose(desc, channels, bits, rate) ?: run {
                _status.value = Status.Failed(name(d), com.opentune.i18n.Tr.f("No {0}-bit format at {1} kHz", bits, rate / 1000.0))
                return null
            }
            // The control interface too: with Android's own driver still on it, setting the rate is refused.
            val control = (0 until d.interfaceCount).map(d::getInterface).firstOrNull { it.id == desc.controlInterface && it.alternateSetting == 0 }
            val streaming = (0 until d.interfaceCount).map(d::getInterface).firstOrNull { it.id == setting.interfaceNumber && it.alternateSetting == setting.alternate }
                ?: error("The DAC's interface wasn't found")
            for (i in listOfNotNull(control, streaming)) {
                if (claimed.none { it.id == i.id }) {
                    check(conn.claimInterface(i, true)) { "The DAC is busy" }
                    claimed += i
                }
            }
            check(conn.setInterface(streaming)) { "The DAC wouldn't take the format" }
            if (rateSet != rate) {
                setRate(conn, desc, setting, rate)
                rateSet = rate
            }
            val hs = UsbAudioDescriptors.highSpeed(desc)
            _status.value = Status.Streaming(name(d), rate, setting.bits)
            return Session(conn.fileDescriptor, setting, hs, rate, name(d))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the DAC", e)
            _status.value = Status.Failed(name(d), e.message ?: "The DAC couldn't be opened")
            close()
            return null
        }
    }

    /** Back to idle between songs: the DAC keeps its setting, the stream stops. */
    @Synchronized
    fun idle(session: Session) {
        val conn = connection ?: return
        val d = device ?: return
        (0 until d.interfaceCount).map(d::getInterface).firstOrNull { it.id == session.setting.interfaceNumber && it.alternateSetting == 0 }
            ?.let { runCatching { conn.setInterface(it) } }
    }

    @Synchronized
    fun close() {
        val conn = connection
        claimed.forEach { runCatching { conn?.releaseInterface(it) } }
        claimed.clear()
        runCatching { conn?.close() }
        connection = null
        device = null
        described = null
        rateSet = 0
        if (_status.value is Status.Streaming) refresh()
    }

    /**
     * Sets the DAC's sample rate: on the endpoint for a class 1 DAC, on its
     * clock source for class 2. Read back where the DAC allows, since playing at a
     * rate it didn't take would play the music too fast or too slow.
     */
    private fun setRate(conn: UsbDeviceConnection, desc: UsbAudioDescriptors.Device, s: UsbAudioDescriptors.StreamSetting, rate: Int) {
        if (desc.uacVersion == 2) {
            val clock = desc.clockSources.firstOrNull() ?: error("The DAC has no clock to set")
            val index = (clock shl 8) or desc.controlInterface
            val data = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte(), (rate shr 24).toByte())
            check(conn.controlTransfer(0x21, 0x01, 0x0100, index, data, 4, TIMEOUT) == 4) { com.opentune.i18n.Tr.f("The DAC refused {0} kHz", rate / 1000.0) }
            val back = ByteArray(4)
            if (conn.controlTransfer(0xA1, 0x01, 0x0100, index, back, 4, TIMEOUT) == 4) {
                val got = (back[0].toInt() and 0xFF) or ((back[1].toInt() and 0xFF) shl 8) or ((back[2].toInt() and 0xFF) shl 16) or ((back[3].toInt() and 0xFF) shl 24)
                check(got == rate) { com.opentune.i18n.Tr.f("The DAC stayed at {0} kHz", got / 1000.0) }
            }
        } else {
            val data = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte())
            check(conn.controlTransfer(0x22, 0x01, 0x0100, s.endpoint, data, 3, TIMEOUT) == 3) { com.opentune.i18n.Tr.f("The DAC refused {0} kHz", rate / 1000.0) }
        }
    }

    private const val TAG = "UsbDirect"
    private const val TIMEOUT = 1_000
}
