package com.opentune.data

import android.os.Build
import android.os.Process
import com.opentune.BuildConfig
import com.opentune.data.account.AccountStore
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The log for "Copy log" when reporting a problem: a short header, the app's
 * own lines from [AppLog], then the warnings and errors logcat holds for this
 * process. Info lines from logcat are left out; on Android 15 they are mostly
 * one line per frame from the view system. Cookies are never logged, and
 * lines carrying a stream URL are cut at the query so its signature doesn't
 * travel. A music server URL's token and salt are blanked out too.
 */
object LogExport {
    suspend fun recent(): String = withContext(Dispatchers.IO) {
        val header = buildString {
            appendLine("OpenTune ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Signed in: ${AccountStore.signedIn.value}")
            appendLine("Settings: ${AppSettings.playback.value}")
        }
        val own = AppLog.snapshot().joinToString("\n").takeLast(OWN_CHARS)
        val system = runCatching {
            val process = ProcessBuilder(
                "logcat", "-d", "-t", "400", "--pid", Process.myPid().toString(), "*:W",
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }.takeLast(SYSTEM_CHARS)
        }.getOrElse { "Couldn't read logcat: ${it.message}" }
        val text = "$header\n== App log ==\n$own\n\n== System warnings and errors ==\n$system"
        STREAM_QUERY.replace(text, "googlevideo.com/…").replace(SERVER_SECRET, "$1…")
    }

    private val STREAM_QUERY = Regex("""googlevideo\.com/\S*""")
    /** A music server URL's token, salt and password, which let anyone sign in to it. */
    private val SERVER_SECRET = Regex("""([?&](?:t|s|p)=)[^&\s]+""")
    // Clipboard and share intents travel through a 1 MB binder buffer.
    private const val OWN_CHARS = 80_000
    private const val SYSTEM_CHARS = 20_000
}
