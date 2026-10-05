package com.opentune.data

import com.opentune.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** Whether a newer OpenTune is published on GitHub Releases. */
object UpdateCheck {
    private const val LATEST = "https://api.github.com/repos/justshivv/Opentune/releases/latest"
    const val RELEASES_PAGE = "https://github.com/justshivv/Opentune/releases"

    sealed interface Result {
        data class Newer(val version: String, val url: String) : Result
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
                val obj = Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                val tag = obj["tag_name"]?.jsonPrimitive?.content?.removePrefix("v") ?: return@use Result.NoReleases
                val url = obj["html_url"]?.jsonPrimitive?.content ?: RELEASES_PAGE
                if (isNewer(tag, BuildConfig.VERSION_NAME)) Result.Newer(tag, url) else Result.UpToDate
            }
        }.getOrElse { Result.Failed(it.message ?: "No connection") }
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
}
