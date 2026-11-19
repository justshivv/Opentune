package com.opentune.desktop

import com.opentune.core.BuildConfig
import com.opentune.data.Http
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where library logging goes: SLF4J's simple logger, to standard error, which
 * [CrashLog] copies into desktop.log. Without it the libraries that log
 * through SLF4J (Ktor, NewPipe's) said nothing, after a warning saying so.
 * A -D on the command line wins over these.
 */
internal object Logging {
    fun install() {
        fun default(key: String, value: String) { if (System.getProperty(key) == null) System.setProperty(key, value) }
        default("org.slf4j.simpleLogger.logFile", "System.err")
        default("org.slf4j.simpleLogger.defaultLogLevel", "info")
        default("org.slf4j.simpleLogger.showDateTime", "true")
        default("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS")
        default("org.slf4j.simpleLogger.showThreadName", "false")
        default("org.slf4j.simpleLogger.log.io.ktor", "warn")
    }
}

/**
 * Whether a newer OpenTune is out. Read from where GitHub's "latest release"
 * page redirects to, which carries the version in its address and, unlike
 * GitHub's API, has no hourly limit. Nothing is downloaded or installed: a
 * newer one shows a banner with a link to its release page.
 */
internal object Updates {
    const val LATEST_PAGE = "https://github.com/justshivv/Opentune/releases/latest"
    private const val EVERY_MS = 12L * 60 * 60 * 1000
    private val TAG = Regex("""/releases/tag/v?([0-9][0-9A-Za-z.\-]*)$""")

    data class Found(val version: String, val page: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { appContext.getSharedPreferences("updates", 0) }
    private val client by lazy { Http.client.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(10, TimeUnit.SECONDS).build() }

    private val _enabled = MutableStateFlow(true)
    /** Settings → About → Check for updates. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _found = MutableStateFlow<Found?>(null)
    /** A newer release, when one was found. */
    val found: StateFlow<Found?> = _found.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _checked = MutableStateFlow(0L)
    /** When the last check answered, or 0. */
    val checked: StateFlow<Long> = _checked.asStateFlow()

    private val _dismissed = MutableStateFlow<String?>(null)
    /** The version whose banner was closed: not shown again for that one. */
    val dismissed: StateFlow<String?> = _dismissed.asStateFlow()

    fun start() {
        _enabled.value = prefs.getBoolean("enabled", true)
        _dismissed.value = prefs.getString("dismissed", null)
        _checked.value = prefs.getLong("checked", 0L)
        prefs.getString("found", null)?.let { v -> if (newer(v, BuildConfig.VERSION_NAME)) _found.value = Found(v, pageFor(v)) }
        if (_enabled.value && System.currentTimeMillis() - _checked.value > EVERY_MS) scope.launch { check() }
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean("enabled", on).apply()
        if (!on) _found.value = null else scope.launch { check() }
    }

    fun dismiss(version: String) {
        _dismissed.value = version
        prefs.edit().putString("dismissed", version).apply()
    }

    fun checkNow() { scope.launch { check() } }

    private fun pageFor(version: String) = "https://github.com/justshivv/Opentune/releases/tag/v$version"

    private suspend fun check() = withContext(Dispatchers.IO) {
        if (_checking.value) return@withContext
        _checking.value = true
        try {
            val latest = runCatching { latestVersion() }.onFailure { System.err.println("[updates] ${it.message}") }.getOrNull() ?: return@withContext
            val now = System.currentTimeMillis()
            _checked.value = now
            val edit = prefs.edit().putLong("checked", now)
            if (newer(latest, BuildConfig.VERSION_NAME)) {
                _found.value = Found(latest, pageFor(latest))
                edit.putString("found", latest)
            } else {
                _found.value = null
                edit.remove("found")
            }
            edit.apply()
        } finally {
            _checking.value = false
        }
    }

    private fun latestVersion(): String {
        client.newCall(Request.Builder().url(LATEST_PAGE).head().build()).execute().use { r ->
            val location = r.header("Location") ?: error("No redirect (HTTP ${r.code})")
            return versionIn(location) ?: error("No version in $location")
        }
    }

    /** The version in a release page's address, without its "v". */
    fun versionIn(url: String): String? = TAG.find(url.substringBefore('?'))?.groupValues?.get(1)

    /**
     * Whether [candidate] is a later version than [current]: compared number by number, and a
     * release later than its own pre-release ("1.2.0" after "1.2.0-beta").
     */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return !candidate.contains('-') && current.contains('-')
    }
}

/**
 * The anonymous install count, off unless the listener turns it on (Settings →
 * About). When on, once a day: a random installation ID made on this computer
 * and the app's version, to the same service the phone reports to. No songs,
 * no account, no hardware IDs, no play counts.
 */
internal object DesktopUsage {
    private const val CONFIG = "https://justshivv.github.io/Opentune/usage-config.json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { appContext.getSharedPreferences("usage_desktop", 0) }
    private val client by lazy { Http.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }
    private val ist = ZoneId.of("Asia/Kolkata")

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun start() {
        _enabled.value = prefs.getBoolean("enabled", false)
        if (_enabled.value) scope.launch { report() }
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean("enabled", on).commit()
        if (on) scope.launch { report() }
    }

    /** The version as reported: marked as the desktop's, so the dashboard can tell it from the phone's. */
    fun reportedVersion(name: String = BuildConfig.VERSION_NAME) = if ('-' in name) "$name.desktop" else "$name-desktop"

    /** What goes out, and nothing else. */
    fun payload(id: String, version: String, revision: Long, day: LocalDate): JSONObject = JSONObject()
        .put("installationId", id)
        .put("currentVersion", version)
        .put("versionRevision", revision)
        .put("days", JSONArray().put(JSONObject().put("day", day.toString()).put("version", version).put("timeZone", "Asia/Kolkata").put("plays", 0)))

    private fun report() {
        if (!_enabled.value) return
        val today = LocalDate.now(ist)
        if (prefs.getString("sent-day", null) == today.toString()) return
        val id = prefs.getString("installation", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("installation", it).commit() }
        val version = reportedVersion()
        var revision = prefs.getLong("revision", 0L)
        if (prefs.getString("version", null) != version || revision < 1) {
            revision += 1
            prefs.edit().putString("version", version).putLong("revision", revision).commit()
        }
        runCatching {
            val base = endpoint() ?: return
            val body = payload(id, version, revision, today).toString().toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url("$base/v1/activity").post(body).build()).execute().use { r ->
                if (r.isSuccessful) prefs.edit().putString("sent-day", today.toString()).commit()
            }
        }.onFailure { System.err.println("[usage] not sent: ${it.message}") }
    }

    /** The service's address from the published configuration: HTTPS only. */
    private fun endpoint(): String? {
        client.newCall(Request.Builder().url(CONFIG).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val text = r.body?.string()?.takeIf { it.length <= 4096 } ?: return null
            val url = JSONObject(text).optString("apiBase").toHttpUrlOrNull() ?: return null
            if (!url.isHttps) return null
            return url.toString().trimEnd('/')
        }
    }
}
