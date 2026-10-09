package com.opentune.data

import android.content.Context
import android.content.SharedPreferences
import com.opentune.BuildConfig
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
            val (id, pending) = persistDaily(p, now, BuildConfig.VERSION_NAME, play) ?: return@withLock
            // Activity can be called frequently; at most one request per 15 minutes,
            // except a completed play also flushes the updated totals.
            val monotonic = System.nanoTime() / 1_000_000
            if (!play && lastAttempt != 0L && monotonic - lastAttempt < 900000) return@withLock
            lastAttempt = monotonic
            try {
                val base = endpoint(monotonic) ?: return@withLock
                if (!enabled) return@withLock
                val batch = pending.sorted().take(64)
                val payload = buildJsonObject {
                    put("installationId", id)
                    put("days", buildJsonArray { batch.forEach { entry ->
                        val d = entry.substringBefore('|')
                        add(buildJsonObject {
                            put("day", "${d.take(4)}-${d.substring(4,6)}-${d.takeLast(2)}")
                            put("version", entry.substringAfter('|'))
                            put("plays", p.getInt("total:$entry", 0))
                        })
                    } })
                }
                client.newCall(Request.Builder().url("$base/v1/activity")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
                    if (response.isSuccessful) {
                        pending.removeAll(batch.toSet())
                        p.edit().putStringSet("pending", pending).commit()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Retain the exact totals for the next foreground/play retry. */ }
        } }
    }

    internal fun persistDaily(p: SharedPreferences, now: Long, version: String, play: Boolean): Pair<String, MutableSet<String>>? {
            val id = p.getString("installation", null) ?: UUID.randomUUID().toString().also {
                if (!p.edit().putString("installation", it).commit()) return null
            }
            val key = "${day(now)}|${version}"
            val total = (p.getInt("total:$key", 0) + if (play) 1 else 0).coerceAtMost(10000)
            val pending = p.getStringSet("pending", emptySet()).orEmpty().toMutableSet()
            pending.add(key)
            val oldest = day(now - 30L * 86400000)
            pending.removeAll { it.substringBefore('|') < oldest }
            val editor = p.edit().putInt("total:$key", total).putStringSet("pending", pending)
            p.all.keys.filter { it.startsWith("total:") && it.removePrefix("total:").substringBefore('|') < oldest }.forEach(editor::remove)
            if (!editor.commit()) return@withLock
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

    /** "20261009", in UTC so every phone agrees on when a day starts. */
    internal fun day(now: Long): String = utc(now).let { "%04d%02d%02d".format(Locale.ROOT, it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }

    /** "2026w41", the ISO week. */
    internal fun week(now: Long): String = utc(now).let { "%04dw%02d".format(Locale.ROOT, it.weekYear, it.get(Calendar.WEEK_OF_YEAR)) }

    /** "202610". */
    internal fun month(now: Long): String = utc(now).let { "%04d%02d".format(Locale.ROOT, it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1) }

}
