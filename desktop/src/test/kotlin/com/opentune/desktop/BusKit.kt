package com.opentune.desktop

/**
 * A sound card for tests: takes the bus's floats as fast as they come (or at most [perWrite]
 * frames at a time), and keeps them all, so a test can look at exactly what would be heard.
 */
internal class CaptureDevice(override val rate: Int = 48_000, private val perWrite: Int = Int.MAX_VALUE, private val paced: Boolean = false) : BusDevice {
    private val began = System.nanoTime()
    private val kept = java.io.ByteArrayOutputStream()
    @Volatile var frames = 0L
        private set
    override val description = "capture"
    override fun write(buf: FloatArray, off: Int, frames: Int): Int {
        val n = minOf(frames, perWrite)
        val b = java.nio.ByteBuffer.allocate(n * 2 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n * 2) b.putFloat(buf[off + i])
        synchronized(kept) { kept.write(b.array()) }
        this.frames += n
        if (paced) {
            // As a sound card would: no faster than the sound plays, a tenth of a second ahead at most.
            val due = began + this.frames * 1_000_000_000L / rate - 100_000_000L
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
        return n
    }
    override val latencyFrames = 0
    override fun start() = Unit
    override fun stop() = Unit
    override fun flush() = Unit
    override fun close() = Unit
    override val lost = false

    /** Everything heard, as interleaved stereo floats. */
    fun samples(): FloatArray {
        val bytes = synchronized(kept) { kept.toByteArray() }
        val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { b.getFloat() }
    }

    companion object {
        /** A fresh bus playing into a new capture device. */
        fun install(rate: Int = 48_000, perWrite: Int = Int.MAX_VALUE, paced: Boolean = false): CaptureDevice {
            val d = CaptureDevice(rate, perWrite, paced)
            AudioBus.reset()
            AudioBus.deviceFactory = { d }
            AudioBus.resume()
            return d
        }
    }
}
