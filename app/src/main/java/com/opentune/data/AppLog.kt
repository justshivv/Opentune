package com.opentune.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own recent log lines, kept in memory for "Copy log".
 *
 * Reading them back out of logcat doesn't work on every phone: Android 15
 * logs a line per frame for every Compose window, and a few seconds of that
 * pushes every playback line out of the slice logcat hands back. So
 * [DebugLog], [TrackLog] and Media3's logger also write here.
 */
object AppLog {
    private const val MAX_LINES = 4_000
    private const val MAX_TRACE_LINES = 14
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(level: Char, tag: String, message: String, error: Throwable? = null) {
        val line = buildString {
            append(time.format(Date())).append(' ').append(level).append(' ').append(tag).append(": ").append(message)
            if (error != null) {
                error.stackTraceToString().lineSequence().take(MAX_TRACE_LINES).forEach { append("\n    ").append(it.trim()) }
            }
        }
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
