package com.opentune.data.together

import java.io.ByteArrayOutputStream

/**
 * AAC frames packed for a message: each a two-byte big-endian length, then
 * the frame. About a second of 24 kbps speech is some 3 KB.
 */
object VoiceFrames {
    fun pack(frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        frames.forEach { f ->
            require(f.size <= 0xFFFF)
            out.write(f.size ushr 8); out.write(f.size and 0xFF); out.write(f)
        }
        return out.toByteArray()
    }

    /** The frames in [data], or null if it isn't a packing of them. */
    fun unpack(data: ByteArray): List<ByteArray>? {
        val frames = mutableListOf<ByteArray>()
        var i = 0
        while (i < data.size) {
            if (i + 2 > data.size) return null
            val n = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
            if (n == 0 || i + n > data.size) return null
            frames += data.copyOfRange(i, i + n)
            i += n
        }
        return frames
    }
}
