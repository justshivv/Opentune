package com.opentune.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.opentune.core.BuildConfig
import com.opentune.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.Request

/**
 * Keeps the app from being used once it is more than [MAX_BEHIND] releases
 * behind the newest one: the app shows only its update screen until it's
 * updated. Two releases behind is still fine; the ordinary update prompt
 * offers those.
 *
 * The releases come from the list the website publishes (pages.yml writes
 * releases.json), which has no rate limit, with GitHub's API as the fallback.
 * What a check found is kept, so the lock holds offline; a check that can't
 * reach either changes nothing, and a phone that has never checked is never
 * locked.
 */
object UpdateLock {
    /** How many releases the installed version may trail the newest one by. */
    const val MAX_BEHIND = 2
    private const val SITE_LIST = "https://justshivv.github.io/Opentune/releases.json"
    private const val API_LIST = "https://api.github.com/repos/justshivv/Opentune/releases?per_page=20"
    private const val EVERY_MS = 60L * 60 * 1000
    private const val PREFS = "update-lock"
    private const val K_VERSIONS = "versions"
    private const val K_LATEST = "latest"
    private const val K_CHECKED = "checkedAt"
    private const val SEP = "\u001F"

    /** The release to update to while the app is locked; null while it may be used. */
    private val _required = MutableStateFlow<UpdateCheck.Release?>(null)
    val required: StateFlow<UpdateCheck.Release?> = _required.asStateFlow()

    /** How many releases the installed version trails by, per the last check. */
    private val _behind = MutableStateFlow(0)
    val behind: StateFlow<Int> = _behind.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    /** How many of [published] are newer than [current]. */
    internal fun behind(published: List<String>, current: String): Int =
        published.distinct().count { UpdateCheck.isNewer(it, current) }

    /** The lock from what the last check remembered, before anything goes online. */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val versions = prefs.getString(K_VERSIONS, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val latest = prefs.getString(K_LATEST, null)?.split(SEP)
        apply(versions, latest?.let(::releaseOf))
    }

    /** Checks the releases again, at most once an hour unless [force]d. */
    suspend fun refresh(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(K_CHECKED, 0) < EVERY_MS) return@withContext
        val list = runCatching { fetch(SITE_LIST) }.getOrNull()
            ?: runCatching { fetch(API_LIST) }.onFailure { Log.w(TAG, "Couldn't read the releases: ${it.message}") }.getOrNull()
            ?: return@withContext
        val releases = list.mapNotNull { it as? JsonObject }
            .filter { r -> r.bool("draft") != true && r.bool("prerelease") != true }
            .mapNotNull { r -> UpdateCheck.parse(r, Build.SUPPORTED_ABIS.toList())?.let { it to r } }
            // Only Android releases count: a tag with no APK (the iPhone build's) isn't one.
            .filter { (release, _) -> release.apk != null && release.version.first().isDigit() }
        if (releases.isEmpty()) return@withContext
        val versions = releases.map { it.first.version }
        val newest = releases.maxWithOrNull { a, b -> if (UpdateCheck.isNewer(a.first.version, b.first.version)) 1 else -1 }!!
        // The site's list carries the release's highlights rather than its full notes.
        val notes = newest.second["highlights"]?.let { h -> (h as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
            ?.joinToString("\n") { "• $it" }
        val latest = if (notes != null && newest.first.notes.isBlank()) newest.first.copy(notes = notes) else newest.first
        prefs.edit {
            putLong(K_CHECKED, now)
            putString(K_VERSIONS, versions.joinToString(","))
            putString(K_LATEST, listOf(latest.version, latest.page, latest.notes, latest.apk?.name, latest.apk?.url, latest.apk?.bytes, latest.sums?.url).joinToString(SEP) { it?.toString().orEmpty() })
        }
        apply(versions, latest)
    }

    private fun apply(versions: List<String>, latest: UpdateCheck.Release?) {
        val count = behind(versions, BuildConfig.VERSION_NAME)
        _behind.value = count
        _required.value = latest?.takeIf { count > MAX_BEHIND && UpdateCheck.isNewer(it.version, BuildConfig.VERSION_NAME) }
    }

    private fun fetch(url: String): JsonArray =
        Http.client.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("$url answered ${r.code}")
            json.parseToJsonElement(r.body?.string().orEmpty()).jsonArray
        }

    private fun releaseOf(f: List<String>): UpdateCheck.Release? {
        if (f.size < 7 || f[0].isBlank()) return null
        return UpdateCheck.Release(
            version = f[0], page = f[1], notes = f[2],
            apk = if (f[3].isNotEmpty() && f[4].isNotEmpty()) UpdateCheck.Asset(f[3], f[4], f[5].toLongOrNull() ?: 0) else null,
            sums = f[6].takeIf { it.isNotEmpty() }?.let { UpdateCheck.Asset("SHA256SUMS.txt", it, 0) },
        )
    }

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private const val TAG = "UpdateLock"
}
