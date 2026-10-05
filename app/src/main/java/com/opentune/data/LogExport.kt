package com.opentune.data

import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * This app's own recent log lines, for "Copy log" when reporting a problem.
 * An app may read its own log without any permission. Cookies are never
 * logged, and lines carrying a stream URL are cut at the query so its
 * signature doesn't travel.
 */
object LogExport {
    suspend fun recent(lines: Int = 2_000): String = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("logcat", "-d", "-t", lines.toString(), "--pid", Process.myPid().toString())
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
                .lines()
                .joinToString("\n") { line -> STREAM_QUERY.replace(line, "googlevideo.com/…") }
                .takeLast(MAX_CHARS)
        }.getOrElse { "Couldn't read the log: ${it.message}" }
    }

    private val STREAM_QUERY = Regex("""googlevideo\.com/\S*""")
    private const val MAX_CHARS = 400_000
}
