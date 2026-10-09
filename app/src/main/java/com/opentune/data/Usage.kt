package com.opentune.data

import android.content.Context
import android.content.SharedPreferences
import com.opentune.BuildConfig
import com.opentune.data.settings.AppSettings
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request

/**
 * Anonymous usage numbers for the app's maker: how many installs there
 * are, how many are used each day, week and month, how many songs are
 * played and which versions are running. There's no ID of any kind. Each
 * phone remembers which counters it has already added one to, and adds one
 * to a public counter at most once per day, week or month (and once per
 * song played). Nothing about the person or the songs goes anywhere: the
 * counter only learns that some phone was here. Off with the setting, and
 * never from debug builds.
 *
 * The counters live on Abacus (abacus.jasoncameron.dev), a free counting
 * service; the dashboard at /Opentune/usage/ on the website reads them.
 */
object Usage {
    const val NAMESPACE = "opentune-justshivv"
    private const val BASE = "https://abacus.jasoncameron.dev/hit/$NAMESPACE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("usage", Context.MODE_PRIVATE)
    }

    private val enabled: Boolean
        get() = !BuildConfig.DEBUG && AppSettings.ui.value.countUsage && prefs != null

    /** The app is in use: counts this phone once for today, this week and this month, and once ever. */
    fun active(now: Long = System.currentTimeMillis()) {
        if (!enabled) return
        scope.launch {
            lock.withLock {
                val p = prefs ?: return@withLock
                for (key in periodKeys(now, BuildConfig.VERSION_NAME)) {
                    if (p.getBoolean("done:$key", false)) continue
                    if (hit(key)) p.edit().putBoolean("done:$key", true).apply()
                }
                // Forget the days that have passed, so the list doesn't grow.
                val keep = periodKeys(now, BuildConfig.VERSION_NAME).map { "done:$it" }.toSet()
                val stale = p.all.keys.filter { it.startsWith("done:") && it !in keep && !it.startsWith("done:installs") && !it.startsWith("done:ver-") }
                if (stale.isNotEmpty()) p.edit().apply { stale.forEach(::remove) }.apply()
            }
        }
    }

    /** A song was listened to (the same moment it goes into the history). */
    fun played(now: Long = System.currentTimeMillis()) {
        if (!enabled) return
        active(now)
        scope.launch {
            hit("plays")
            hit("plays-${day(now)}")
        }
    }

    /** The counters a phone adds itself to at [now], once each. */
    internal fun periodKeys(now: Long, version: String): List<String> = listOf(
        "installs",
        "ver-${version.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.take(40)}",
        "dau-${day(now)}",
        "wau-${week(now)}",
        "mau-${month(now)}",
    )

    private fun utc(now: Long): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
        firstDayOfWeek = Calendar.MONDAY
        minimalDaysInFirstWeek = 4
        timeInMillis = now
    }

    /** "20261009", in UTC so every phone agrees on when a day starts. */
    internal fun day(now: Long): String = utc(now).let { "%04d%02d%02d".format(Locale.ROOT, it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }

    /** "2026w41", the ISO week. */
    internal fun week(now: Long): String = utc(now).let { "%04dw%02d".format(Locale.ROOT, it.weekYear, it.get(Calendar.WEEK_OF_YEAR)) }

    /** "202610". */
    internal fun month(now: Long): String = utc(now).let { "%04d%02d".format(Locale.ROOT, it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1) }

    private fun hit(key: String): Boolean = runCatching {
        Http.client.newCall(Request.Builder().url("$BASE/$key").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)
}
