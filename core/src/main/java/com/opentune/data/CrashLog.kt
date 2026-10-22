package com.opentune.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The last crash, kept on the phone so "Export diagnostics" can include it:
 * a crash otherwise leaves nothing to read once the app is open again. Only
 * the exception and its stack are written, the same as logcat would show.
 */
object CrashLog {
    private var file: File? = null

    fun install(context: Context) {
        val f = File(context.filesDir, "last-crash.txt")
        file = f
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                f.writeText("${java.util.Date()} on ${thread.name}\n${trace.take(MAX_CHARS)}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash written, or null when there's been none. */
    fun last(): String? = file?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    private const val MAX_CHARS = 20_000
}
