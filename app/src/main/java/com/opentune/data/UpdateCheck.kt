package com.opentune.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opentune.BuildConfig
import com.opentune.data.DebugLog as Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * Whether a newer OpenTune is published on GitHub Releases, and which of its
 * APKs fits this phone.
 *
 * GitHub's API allows 60 requests an hour from one address, and on mobile
 * data many phones share one, so it often says no. Then the latest release
 * is read from where github.com/…/releases/latest redirects to, which has no
 * such limit, and the APK's address is built from the release's file names.
 */
object UpdateCheck {
    private const val LATEST = "https://api.github.com/repos/justshivv/Opentune/releases/latest"
    private const val LATEST_PAGE = "https://github.com/justshivv/Opentune/releases/latest"
    private const val DOWNLOADS = "https://github.com/justshivv/Opentune/releases/download"
    const val RELEASES_PAGE = "https://github.com/justshivv/Opentune/releases"
    /** How often the automatic check goes online, at most. */
    private const val AUTO_EVERY_MS = 6L * 60 * 60 * 1000
    /** The processors releases carry their own APK for; anything else gets the universal one. */
    private val BUILT_FOR = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    private val TAG_IN_URL = Regex("""/releases/tag/v?([0-9][0-9A-Za-z.\-]*)$""")

    /** One file attached to a release. */
    data class Asset(val name: String, val url: String, val bytes: Long)

    data class Release(
        val version: String,
        val page: String,
        val notes: String,
        /** The APK for this phone's processor, or the universal one; null when neither is attached. */
        val apk: Asset?,
        /** SHA256SUMS.txt, when the release has one. */
        val sums: Asset?,
    )

    sealed interface Result {
        data class Newer(val release: Release) : Result
        data object UpToDate : Result
        data object NoReleases : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val abis = Build.SUPPORTED_ABIS.toList()
        val viaApi = runCatching {
            Http.client.newCall(
                Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build(),
            ).execute().use { r ->
                if (r.code == 404) return@use Result.NoReleases
                if (!r.isSuccessful) return@use Result.Failed("GitHub answered ${r.code}")
                val release = parse(Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject, abis)
                    ?: return@use Result.NoReleases
                compare(release)
            }
        }.getOrElse { Result.Failed(it.message ?: "No connection") }
        if (viaApi !is Result.Failed) return@withContext viaApi
        // The API said no (usually its hourly limit); the release page's redirect has none.
        Log.w(TAG, "Release API failed (${viaApi.reason}); reading the releases page")
        runCatching {
            Http.client.newCall(Request.Builder().url(LATEST_PAGE).build()).execute().use { r ->
                if (!r.isSuccessful) return@use Result.Failed("GitHub answered ${r.code}")
                val release = fromPage(r.request.url.toString(), abis) ?: return@use Result.NoReleases
                compare(release)
            }
        }.getOrElse { viaApi }
    }

    private fun compare(release: Release): Result =
        if (isNewer(release.version, BuildConfig.VERSION_NAME)) Result.Newer(release) else Result.UpToDate

    /**
     * The release that github.com/…/releases/latest led to ([finalUrl] ends
     * in /releases/tag/vX.Y.Z), with its files' addresses built from the
     * names every release uses. Sizes and notes aren't known this way.
     */
    internal fun fromPage(finalUrl: String, abis: List<String>): Release? {
        val version = TAG_IN_URL.find(finalUrl.substringBefore('?'))?.groupValues?.get(1) ?: return null
        val abi = abis.firstOrNull { it in BUILT_FOR } ?: "universal"
        val name = "OpenTune-v$version-$abi.apk"
        return Release(
            version = version,
            page = "$RELEASES_PAGE/tag/v$version",
            notes = "",
            apk = Asset(name, "$DOWNLOADS/v$version/$name", 0),
            sums = Asset("SHA256SUMS.txt", "$DOWNLOADS/v$version/SHA256SUMS.txt", 0),
        )
    }

    /**
     * The automatic check: online at most every few hours, otherwise the
     * newer release a recent check found (in the app or in the background),
     * so a release found while the app was closed is still offered when it
     * opens. Null when there's nothing newer, or the user said "not now" to it.
     */
    suspend fun checkIfDue(context: Context): Release? {
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checkedAt", 0) >= AUTO_EVERY_MS) {
            val result = check()
            if (result !is Result.Failed) prefs.edit { putLong("checkedAt", now) }
            when (result) {
                is Result.Newer -> remember(prefs, result.release)
                Result.UpToDate, Result.NoReleases -> prefs.edit { remove(K_FOUND) }
                is Result.Failed -> Log.w(TAG, "Update check failed: ${result.reason}")
            }
        }
        val release = found(prefs)?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) } ?: return null
        // A version the user said "not now" to isn't offered again until the next one.
        if (prefs.getString("skipped", null) == release.version) return null
        return release
    }

    fun skip(context: Context, version: String) =
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit { putString("skipped", version) }

    private const val TAG = "UpdateCheck"
    private const val K_FOUND = "found"

    private fun remember(prefs: android.content.SharedPreferences, r: Release) = prefs.edit {
        putString(K_FOUND, listOf(r.version, r.page, r.notes, r.apk?.name, r.apk?.url, r.apk?.bytes, r.sums?.url).joinToString(SEP) { it?.toString().orEmpty() })
    }

    private fun found(prefs: android.content.SharedPreferences): Release? {
        val f = prefs.getString(K_FOUND, null)?.split(SEP) ?: return null
        if (f.size < 7) return null
        return Release(
            version = f[0],
            page = f[1],
            notes = f[2],
            apk = if (f[3].isNotEmpty() && f[4].isNotEmpty()) Asset(f[3], f[4], f[5].toLongOrNull() ?: 0) else null,
            sums = f[6].takeIf { it.isNotEmpty() }?.let { Asset("SHA256SUMS.txt", it, 0) },
        )
    }

    /** Joins a remembered release's fields; not a character release notes contain. */
    private const val SEP = "\u001F"

    /** Background checks, twice a day while automatic checks are on. */
    fun schedule(context: Context) {
        val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!com.opentune.data.settings.AppSettings.ui.value.checkForUpdates) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<Worker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(2, TimeUnit.HOURS)
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private const val WORK = "update-check"
    private const val CHANNEL = "updates"

    /** Checks in the background and, when there's a new version, says so in a notification. */
    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): androidx.work.ListenableWorker.Result {
            if (!com.opentune.data.settings.AppSettings.ui.value.checkForUpdates) return androidx.work.ListenableWorker.Result.success()
            val prefs = applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
            val before = prefs.getString("notified", null)
            val release = checkIfDue(applicationContext) ?: return androidx.work.ListenableWorker.Result.success()
            if (release.version != before) {
                notify(applicationContext, release)
                prefs.edit { putString("notified", release.version) }
            }
            return androidx.work.ListenableWorker.Result.success()
        }
    }

    private fun notify(context: Context, release: Release) {
        if (!com.opentune.data.releases.NewReleases.canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        // Opening the app offers the update, from what this check remembered.
        val open = PendingIntent.getActivity(
            context, CHANNEL.hashCode(),
            Intent(context, com.opentune.MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.opentune.R.drawable.ic_stat_opentune)
            .setContentTitle("OpenTune ${release.version} is out")
            .setContentText("Tap to update from ${BuildConfig.VERSION_NAME}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(CHANNEL.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to notify", e)
        }
    }

    /** A GitHub release object, with the APK picked for [abis] (most preferred first). */
    internal fun parse(obj: JsonObject, abis: List<String>): Release? {
        val tag = obj.str("tag_name")?.removePrefix("v") ?: return null
        val assets = (obj["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
            val o = a as? JsonObject ?: return@mapNotNull null
            Asset(
                name = o.str("name") ?: return@mapNotNull null,
                url = o.str("browser_download_url") ?: return@mapNotNull null,
                bytes = (o["size"] as? JsonPrimitive)?.longOrNull ?: 0,
            )
        }
        val apks = assets.filter { it.name.endsWith(".apk") }
        val apk = abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.name.endsWith("-$abi.apk") } }
            ?: apks.firstOrNull { it.name.endsWith("-universal.apk") }
        return Release(
            version = tag,
            page = obj.str("html_url") ?: RELEASES_PAGE,
            notes = obj.str("body").orEmpty(),
            apk = apk,
            sums = assets.firstOrNull { it.name.startsWith("SHA256SUMS") },
        )
    }

    /** Compares dotted versions number by number: 1.10 is newer than 1.9. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
