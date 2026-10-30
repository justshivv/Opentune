package com.opentune.data

import android.content.Context
import android.content.SharedPreferences
import com.opentune.core.BuildConfig
import com.opentune.data.settings.AppSettings
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Random installation identity, no hardware IDs or song information. Never backed up.
 * Daily cumulative totals are durable before upload; the server merges with MAX so lost
 * acknowledgements and out-of-order retries cannot inflate plays. Up to 30 days offline.
 */
object Usage {
    private const val CONFIG = "https://justshivv.github.io/Opentune/usage-config.json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var prefs: SharedPreferences? = null
    private var api: String? = null
    private var configAt = 0L
    private var lastAttempt = 0L
    private val client by lazy { Http.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("usage_v2", Context.MODE_PRIVATE)
        if (!BuildConfig.DEBUG) {
            UsageUploadWorker.schedule(context)
            // A new installation is counted as soon as there's a network, even if the
            // app is closed straight away; plays after that wait for the four-hour batch.
            if (prefs?.getLong("last-report", 0L) == 0L) UsageUploadWorker.scheduleFirst(context)
        }
    }
    private val enabled get() = !BuildConfig.DEBUG && AppSettings.ui.value.countUsage && prefs != null

    fun active(now: Long = System.currentTimeMillis()) = record(false, now)
    fun played(now: Long = System.currentTimeMillis()) = record(true, now)

    fun consentChanged(enabled: Boolean) {
        if (enabled) active() else scope.launch { lock.withLock {
            // Keep the installation ID so opting back in doesn't count another install.
            // Discard pending uploads while preserving cumulative values already reported.
            prefs?.edit()?.remove("pending")?.commit()
        } }
    }

    private fun record(play: Boolean, now: Long) {
        if (!enabled) return
        scope.launch { lock.withLock {
            if (!enabled) return@withLock
            val p = prefs ?: return@withLock
            persistDaily(p, now, BuildConfig.VERSION_NAME, play) ?: return@withLock
            sendPending(p, now)
        } }
    }

    /** Whether this installation has been counted, or isn't being counted at all (no consent). */
    internal fun counted(): Boolean = !enabled || (prefs?.getLong("last-report", 0L) ?: 0L) > 0L

    /** Background upload does not create activity for a day when the app was not used. */
    internal suspend fun flushPending() = lock.withLock {
        if (enabled) prefs?.let { sendPending(it, System.currentTimeMillis()) }
    }

    private fun sendPending(p: SharedPreferences, now: Long) {
            if (!enabled) return
            val id = p.getString("installation", null) ?: return
            val pending = p.getStringSet("pending", emptySet()).orEmpty().toMutableSet()
            if (pending.isEmpty()) return
            // The totals are cumulative and kept on the phone until they're taken, so they go
            // out at most every few hours (including across midnight), not after every song:
            // one report per play was most of the Worker's daily allowance.
            val monotonic = System.nanoTime() / 1_000_000
            val lastReport = p.getLong("last-report", 0L)
            if (!due(lastReport, now, pending, istDay(now))) return
            // Until the installation has been counted once, a failed try is repeated
            // within minutes; after that, plays only ever go out in the four-hour batch.
            val retry = retryAfter(lastReport)
            if (lastAttempt != 0L && monotonic - lastAttempt < retry) return
            val attempted = p.getLong("last-attempt", 0L)
            if (attempted > 0L && now >= attempted && now - attempted < retry) return
            lastAttempt = monotonic
            p.edit().putLong("last-attempt", now).commit()
            try {
                val base = endpoint(monotonic) ?: return
                if (!enabled) return
                val batch = pending.sorted().take(64)
                val payload = buildJsonObject {
                    put("installationId", id)
                    // Separate current installation state from historical daily plays.
                    put("currentVersion", BuildConfig.VERSION_NAME)
                    put("versionRevision", p.getLong("version-revision", 1L))
                    put("days", buildJsonArray { batch.forEach { entry ->
                        val d = entry.substringBefore('|')
                        add(buildJsonObject {
                            put("day", "${d.take(4)}-${d.substring(4,6)}-${d.takeLast(2)}")
                            put("version", entry.split('|')[1])
                            if (entry.endsWith("|IST")) put("timeZone", "Asia/Kolkata")
                            put("plays", p.getInt("total:$entry", 0))
                        })
                    } })
                }
                client.newCall(Request.Builder().url("$base/v1/activity")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
                    if (response.isSuccessful) {
                        pending.removeAll(batch.toSet())
                        p.edit().putStringSet("pending", pending).putLong("last-report", now).commit()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Retain the exact totals for the next foreground/play retry. */ }
    }

    /** How long totals wait on the phone before they're sent. */
    internal const val REPORT_EVERY_MS = 4 * 60 * 60_000L
    /** The least time between two tries in one run, after one that failed. */
    private const val RETRY_MS = REPORT_EVERY_MS
    /** The same, for an installation not yet counted: it should show on the dashboard straight away. */
    internal const val FIRST_RETRY_MS = 15 * 60_000L

    /** How long a failed report waits before the next try: minutes for the first one, the batch interval after. */
    internal fun retryAfter(lastReport: Long): Long = if (lastReport <= 0L) FIRST_RETRY_MS else RETRY_MS

    /**
     * Whether the totals are worth sending now: never sent, sent long enough
     * ago, with a four-hour gap even when a day ends. [today] is kept for old callers.
     */
    internal fun due(lastReport: Long, now: Long, pending: Set<String>, today: String): Boolean =
        pending.isNotEmpty() && (lastReport <= 0L || now - lastReport >= REPORT_EVERY_MS || now < lastReport)

    internal fun persistDaily(p: SharedPreferences, now: Long, version: String, play: Boolean): Pair<String, MutableSet<String>>? {
            val id = p.getString("installation", null) ?: UUID.randomUUID().toString().also {
                if (!p.edit().putString("installation", it).commit()) return null
            }
            val key = "${istDay(now)}|${version}|IST"
            val total = (p.getInt("total:$key", 0) + if (play) 1 else 0).coerceAtMost(10000)
            val pending = p.getStringSet("pending", emptySet()).orEmpty().toMutableSet()
            pending.add(key)
            val oldestUtc = day(now - 30L * 86400000)
            val oldestIst = istDay(now - 30L * 86400000)
            fun expired(entry: String) = entry.substringBefore('|') < if (entry.endsWith("|IST")) oldestIst else oldestUtc
            pending.removeAll(::expired)
            val editor = p.edit().putInt("total:$key", total).putStringSet("pending", pending)
            if (p.getString("current-version", null) != version) {
                editor.putString("current-version", version)
                    .putLong("version-revision", p.getLong("version-revision", 0L) + 1)
            }
            p.all.keys.filter { it.startsWith("total:") && expired(it.removePrefix("total:")) }.forEach(editor::remove)
            if (!editor.commit()) return null
        return id to pending
    }

    private fun endpoint(now: Long): String? {
        if (configAt != 0L && now - configAt < 3600000) return api
        client.newCall(Request.Builder().url(CONFIG).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body?.source() ?: return null
            source.request(4097)
            if (source.buffer.size > 4096) return null
            val value = Json.parseToJsonElement(source.readUtf8()).jsonObject["apiBase"]?.jsonPrimitive?.contentOrNull
            val url = value?.toHttpUrlOrNull()
            api = url?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null }?.toString()?.trimEnd('/')
            configAt = now
            return api
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

    /** New daily reports use IST; pending legacy UTC keys keep their original meaning. */
    internal fun istDay(now: Long): String = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"), Locale.ROOT).apply {
        timeInMillis = now
    }.let { "%04d%02d%02d".format(Locale.ROOT, it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }

    /** "20261009", in UTC so every phone agrees on when a day starts. */
    internal fun day(now: Long): String = utc(now).let { "%04d%02d%02d".format(Locale.ROOT, it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }

    /** "2026w41", the ISO week. */
    internal fun week(now: Long): String = utc(now).let { "%04dw%02d".format(Locale.ROOT, it.weekYear, it.get(Calendar.WEEK_OF_YEAR)) }

    /** "202610". */
    internal fun month(now: Long): String = utc(now).let { "%04d%02d".format(Locale.ROOT, it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1) }

}

