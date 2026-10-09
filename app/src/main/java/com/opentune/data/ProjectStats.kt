package com.opentune.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenTune's public numbers, from GitHub: how often each release's APKs
 * have been downloaded (in-app updates included, since they download from
 * the same place), stars and forks. Nothing is counted on the phone or sent
 * anywhere; this only reads what GitHub shows everyone. The last answer is
 * kept, so the page has numbers offline or when GitHub limits requests.
 */
object ProjectStats {
    private const val REPO = "https://api.github.com/repos/justshivv/Opentune"

    data class Release(val tag: String, val publishedAt: String, val downloads: Int)

    data class Numbers(
        val releases: List<Release>,
        val stars: Int,
        val forks: Int,
        val watchers: Int,
        val createdAt: String?,
        /** When GitHub was asked. */
        val fetchedAt: Long,
    ) {
        val downloads: Int get() = releases.sumOf { it.downloads }
    }

    private val _numbers = MutableStateFlow<Numbers?>(null)
    val numbers: StateFlow<Numbers?> = _numbers
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("project_stats", Context.MODE_PRIVATE)
        prefs = p
        if (_numbers.value == null) _numbers.value = p.getString("last", null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
    }

    /** Shows [n] without asking GitHub, for screenshots. */
    @androidx.annotation.VisibleForTesting
    internal fun show(n: Numbers) {
        _numbers.value = n
    }

    /** Asks GitHub again; keeps the last numbers if it can't. Returns whether it got fresh ones. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            coroutineScope {
                val releases = async { get("$REPO/releases?per_page=100") }
                val repo = async { get(REPO) }
                parse(JSONArray(releases.await()), JSONObject(repo.await()), System.currentTimeMillis())
            }
        }.onSuccess { n ->
            _numbers.value = n
            prefs?.edit()?.putString("last", toJson(n).toString())?.apply()
        }.isSuccess
    }

    private fun get(url: String): String =
        Http.client.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()).execute().use { r ->
            check(r.isSuccessful) { "GitHub answered ${r.code}" }
            r.body?.string().orEmpty()
        }

    /** Reads GitHub's releases list and repository answer. Only APKs count as downloads, not the checksum file. */
    fun parse(releases: JSONArray, repo: JSONObject, now: Long): Numbers {
        val list = (0 until releases.length()).mapNotNull { i ->
            val r = releases.optJSONObject(i) ?: return@mapNotNull null
            if (r.optBoolean("draft")) return@mapNotNull null
            val assets = r.optJSONArray("assets") ?: JSONArray()
            val count = (0 until assets.length()).sumOf { k ->
                val a = assets.optJSONObject(k)
                if (a != null && a.optString("name").endsWith(".apk")) a.optInt("download_count") else 0
            }
            Release(r.optString("tag_name"), r.optString("published_at"), count)
        }
        return Numbers(
            releases = list,
            stars = repo.optInt("stargazers_count"),
            forks = repo.optInt("forks_count"),
            watchers = repo.optInt("subscribers_count"),
            createdAt = repo.optString("created_at").takeIf { it.isNotBlank() },
            fetchedAt = now,
        )
    }

    internal fun toJson(n: Numbers): JSONObject = JSONObject()
        .put("releases", JSONArray().apply { n.releases.forEach { put(JSONObject().put("tag", it.tag).put("at", it.publishedAt).put("n", it.downloads)) } })
        .put("stars", n.stars).put("forks", n.forks).put("watchers", n.watchers)
        .put("created", n.createdAt).put("fetched", n.fetchedAt)

    internal fun fromJson(o: JSONObject): Numbers {
        val rs = o.optJSONArray("releases") ?: JSONArray()
        return Numbers(
            releases = (0 until rs.length()).map { i -> rs.getJSONObject(i).let { Release(it.optString("tag"), it.optString("at"), it.optInt("n")) } },
            stars = o.optInt("stars"),
            forks = o.optInt("forks"),
            watchers = o.optInt("watchers"),
            createdAt = o.optString("created").takeIf { it.isNotBlank() && it != "null" },
            fetchedAt = o.optLong("fetched"),
        )
    }
}
