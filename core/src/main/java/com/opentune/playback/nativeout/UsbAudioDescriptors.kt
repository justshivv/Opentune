package com.opentune.playback.nativeout

/**
 * A USB DAC's description of itself, read from its raw descriptors: whether
 * it speaks USB Audio Class 1 or 2, its clocks, and each way it can stream
 * (an alternate setting of an AudioStreaming interface): channels, bytes per
 * sample and bits used, the sample rates (listed outright in class 1), the
 * isochronous OUT endpoint with its packet size and timing, and the feedback
 * endpoint an asynchronous DAC reports its clock on.
 *
 * Plain parsing of bytes, so it's tested without a device.
 */
object UsbAudioDescriptors {
    data class Device(val uacVersion: Int, val controlInterface: Int, val clockSources: List<Int>, val streams: List<StreamSetting>)

    data class StreamSetting(
        val interfaceNumber: Int,
        val alternate: Int,
        val channels: Int,
        /** Bytes each sample takes in a packet (2, 3 or 4). */
        val subslotBytes: Int,
        /** Bits of each sample that carry sound (16, 24, 32). */
        val bits: Int,
        /** Rates listed by a class 1 DAC; empty for class 2, whose rates come from its clock. */
        val rates: List<Int>,
        /** The clock this setting runs from (class 2), via its terminal; 0 if unknown. */
        val terminalLink: Int,
        val endpoint: Int,
        val maxPacket: Int,
        /** Packets per USB frame or microframe, as bInterval gives it. */
        val interval: Int,
        /** Synchronisation: 1 async, 2 adaptive, 3 sync. */
        val sync: Int,
        val feedbackEndpoint: Int,
        val feedbackPacket: Int,
        /** PCM (not DSD or other formats). */
        val pcm: Boolean,
    ) {
        val frameBytes: Int get() = subslotBytes * channels
    }

    private const val INTERFACE = 4
    private const val ENDPOINT = 5
    private const val CS_INTERFACE = 0x24
    private const val CLASS_AUDIO = 1
    private const val SUBCLASS_CONTROL = 1
    private const val SUBCLASS_STREAMING = 2

    fun parse(raw: ByteArray): Device? {
        var version = 0
        var control = -1
        val clocks = mutableListOf<Int>()
        val streams = mutableListOf<StreamSetting>()
        // The interface being read, and what's been gathered for its current alternate setting.
        var ifClass = -1; var ifSub = -1; var ifNum = -1; var alt = 0
        var channels = 0; var subslot = 0; var bits = 0; var rates = emptyList<Int>(); var link = 0; var pcm = true
        var ep = 0; var maxPacket = 0; var interval = 1; var sync = 0; var fbEp = 0; var fbPacket = 0
        fun flush() {
            if (ifClass == CLASS_AUDIO && ifSub == SUBCLASS_STREAMING && alt > 0 && ep != 0 && subslot > 0 && channels > 0) {
                streams += StreamSetting(ifNum, alt, channels, subslot, bits, rates, link, ep, maxPacket, interval, sync, fbEp, fbPacket, pcm)
            }
            channels = 0; subslot = 0; bits = 0; rates = emptyList(); link = 0; pcm = true
            ep = 0; maxPacket = 0; interval = 1; sync = 0; fbEp = 0; fbPacket = 0
        }
        var i = 0
        while (i + 1 < raw.size) {
            val len = raw[i].u()
            if (len < 2 || i + len > raw.size) break
            val type = raw[i + 1].u()
            val d = raw.copyOfRange(i, i + len)
            when (type) {
                INTERFACE -> {
                    flush()
                    ifNum = d.at(2); alt = d.at(3); ifClass = d.at(5); ifSub = d.at(6)
                    if (ifClass == CLASS_AUDIO && ifSub == SUBCLASS_CONTROL && control < 0) control = ifNum
                }
                CS_INTERFACE -> if (ifClass == CLASS_AUDIO) {
                    val sub = d.at(2)
                    if (ifSub == SUBCLASS_CONTROL) {
                        if (sub == 0x01 && len >= 5) version = if (d.u16(3) >= 0x0200) 2 else 1 // HEADER: bcdADC
                        if (sub == 0x0A && version == 2) clocks += d.at(3) // CLOCK_SOURCE
                    } else if (ifSub == SUBCLASS_STREAMING) {
                        when (sub) {
                            0x01 -> if (version == 2 && len >= 16) { // AS_GENERAL (class 2)
                                link = d.at(3)
                                pcm = d.at(5) == 1 && (d.u32(6) and 1L) == 1L
                                channels = d.at(10)
                            } else if (len >= 7) { // AS_GENERAL (class 1): wFormatTag 1 is PCM
                                link = d.at(3)
                                pcm = d.u16(5) == 1
                            }
                            0x02 -> if (version == 2 && len >= 6) { // FORMAT_TYPE I (class 2)
                                subslot = d.at(4); bits = d.at(5)
                            } else if (len >= 8) { // FORMAT_TYPE I (class 1), with its rates
                                channels = d.at(4); subslot = d.at(5); bits = d.at(6)
                                val count = d.at(7)
                                rates = if (count == 0 && len >= 14) listOf(d.u24(8), d.u24(11)) // a continuous range: its ends
                                else (0 until count).mapNotNull { k -> if (8 + k * 3 + 3 <= len) d.u24(8 + k * 3) else null }
                            }
                        }
                    }
                }
                ENDPOINT -> if (ifClass == CLASS_AUDIO && ifSub == SUBCLASS_STREAMING && len >= 7) {
                    val address = d.at(2)
                    val attributes = d.at(3)
                    val isIso = (attributes and 0x03) == 1
                    val usage = (attributes shr 4) and 0x03
                    if (isIso && address and 0x80 == 0) {
                        ep = address; maxPacket = d.u16(4) and 0x7FF; interval = d.at(6).coerceAtLeast(1)
                        sync = (attributes shr 2) and 0x03
                    } else if (isIso && address and 0x80 != 0 && (usage == 1 || ep != 0)) {
                        fbEp = address; fbPacket = d.u16(4) and 0x7FF
                    }
                }
            }
            i += len
        }
        flush()
        if (control < 0 && streams.isEmpty()) return null
        return Device(if (version == 0) 1 else version, control, clocks, streams)
    }

    /**
     * The setting to stream [bits]-bit stereo (or [channels]) at [rate] on: PCM, the
     * right channel count, at least as many bits as the track, the fewest bytes that
     * hold them, and (for class 1) the rate listed.
     */
    fun choose(device: Device, channels: Int, bits: Int, rate: Int): StreamSetting? =
        device.streams
            .filter { it.pcm && it.channels == channels && it.bits >= bits }
            .filter { device.uacVersion == 2 || it.rates.isEmpty() || rate in it.rates || (it.rates.size == 2 && rate in it.rates[0]..it.rates[1]) }
            .sortedWith(compareBy<StreamSetting> { it.bits }.thenBy { it.subslotBytes })
            .firstOrNull { it.maxPacket >= framesPerPacketCeil(rate, it, highSpeed(device)) * it.frameBytes }

    /**
     * Whether the DAC streams at USB high speed (microframes of 125 µs). Android
     * doesn't say what speed a device runs at; class 2 DACs run at high speed and
     * class 1 at full speed, near enough always.
     */
    fun highSpeed(device: Device) = device.uacVersion == 2

    /** The most frames a packet of [s] carries at [rate]. */
    fun framesPerPacketCeil(rate: Int, s: StreamSetting, highSpeed: Boolean): Int {
        val perMs = packetsPerMs(s, highSpeed)
        return kotlin.math.ceil(rate / (1000.0 * perMs)).toInt() + 1
    }

    /** Packets each millisecond: one at full speed; at high speed, 8 for bInterval 1, 4 for 2, and so on. */
    fun packetsPerMs(s: StreamSetting, highSpeed: Boolean): Int =
        if (!highSpeed) 1 else (8 shr (s.interval - 1).coerceIn(0, 3)).coerceAtLeast(1)

    private fun Byte.u() = toInt() and 0xFF
    private fun ByteArray.at(i: Int) = if (i < size) this[i].u() else 0
    private fun ByteArray.u16(i: Int) = at(i) or (at(i + 1) shl 8)
    private fun ByteArray.u24(i: Int) = at(i) or (at(i + 1) shl 8) or (at(i + 2) shl 16)
    private fun ByteArray.u32(i: Int) = (at(i).toLong()) or (at(i + 1).toLong() shl 8) or (at(i + 2).toLong() shl 16) or (at(i + 3).toLong() shl 24)
}
