package com.opentune.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.opentune.BuildConfig
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

/** Whether a newer OpenTune is published on GitHub Releases, and which of its APKs fits this phone. */
object UpdateCheck {
    private const val LATEST = "https://api.github.com/repos/justshivv/Opentune/releases/latest"
    const val RELEASES_PAGE = "https://github.com/justshivv/Opentune/releases"
    private const val AUTO_EVERY_MS = 24L * 60 * 60 * 1000

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
        runCatching {
            Http.client.newCall(
                Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build(),
            ).execute().use { r ->
                if (r.code == 404) return@use Result.NoReleases
                if (!r.isSuccessful) return@use Result.Failed("GitHub answered ${r.code}")
                val release = parse(Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject, Build.SUPPORTED_ABIS.toList())
                    ?: return@use Result.NoReleases
                if (isNewer(release.version, BuildConfig.VERSION_NAME)) Result.Newer(release) else Result.UpToDate
            }
        }.getOrElse { Result.Failed(it.message ?: "No connection") }
    }

    /**
     * Checks at most once a day, for the automatic check at start. Null when
     * it isn't time yet or there's nothing newer.
     */
    suspend fun checkIfDue(context: Context): Release? {
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checkedAt", 0) < AUTO_EVERY_MS) return null
        val result = check()
        if (result !is Result.Failed) prefs.edit { putLong("checkedAt", now) }
        val release = (result as? Result.Newer)?.release ?: return null
        // A version the user said "not now" to isn't offered again until the next one.
        if (prefs.getString("skipped", null) == release.version) return null
        return release
    }

    fun skip(context: Context, version: String) =
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit { putString("skipped", version) }

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
