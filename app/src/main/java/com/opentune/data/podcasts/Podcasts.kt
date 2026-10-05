package com.opentune.data.podcasts

import android.content.Context
import com.opentune.data.DebugLog as Log
import com.opentune.data.innertube.Innertube
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Podcasts from YouTube Music: what's popular, shows to start with, search
 * by show or episode, show pages, and topics. Shows you subscribe to and
 * where you stopped in each episode are kept on the phone, so both work
 * without an account.
 */
object Podcasts {
    private const val TAG = "Podcasts"
    private const val SEARCH_SHOWS = "EgWKAQJQAWoKEAkQChAFEAMQBA=="
    private const val SEARCH_EPISODES = "EgWKAQJIAWoKEAkQChAFEAMQBA=="
    private const val MAX_PROGRESS = 300
    /** Resume only past the first half minute, and not in the last. */
    private const val RESUME_MIN_MS = 30_000L

    /** Topics offered as chips, each a show search. */
    val TOPICS = listOf(
        "News", "Comedy", "True crime", "Sports", "Business", "Technology", "Health", "Science",
        "History", "Society & culture", "Education", "Self-improvement", "Music", "TV & film", "Gaming", "Kids & family",
    )

    @Serializable
    data class Progress(val episode: PodcastEpisode, val positionMs: Long, val durationMs: Long, val updatedAt: Long, val finished: Boolean = false) {
        val fraction get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    @Serializable
    private class Saved(val shows: List<PodcastShow> = emptyList(), val progress: List<Progress> = emptyList())

    data class Home(val popular: List<PodcastEpisode>, val shows: List<PodcastShow>, val more: List<Pair<String, List<PodcastShow>>>)

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null

    private val _subscriptions = MutableStateFlow<List<PodcastShow>>(emptyList())
    val subscriptions: StateFlow<List<PodcastShow>> = _subscriptions.asStateFlow()
    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    fun init(context: Context) {
        val f = File(context.filesDir, "podcasts.json")
        file = f
        runCatching { json.decodeFromString(Saved.serializer(), f.readText()) }.getOrNull()?.let { s ->
            _subscriptions.value = s.shows
            _progress.value = s.progress.associateBy { it.episode.videoId }
        }
    }

    // ---- Subscriptions ------------------------------------------------------

    fun isSubscribed(browseId: String) = _subscriptions.value.any { it.browseId == browseId }

    fun setSubscribed(show: PodcastShow, on: Boolean) {
        _subscriptions.value = if (on) listOf(show.copy(description = null)) + _subscriptions.value.filterNot { it.browseId == show.browseId }
        else _subscriptions.value.filterNot { it.browseId == show.browseId }
        save()
    }

    // ---- Listening position ------------------------------------------------

    /** Remembers [episode] as being played from the podcasts pages, so its position is kept. */
    fun starting(episode: PodcastEpisode) {
        if (_progress.value.containsKey(episode.videoId)) return
        _progress.value = _progress.value + (episode.videoId to Progress(episode.copy(description = null), 0, 0, System.currentTimeMillis()))
        save()
    }

    fun isEpisode(videoId: String) = _progress.value.containsKey(videoId)

    /** Where to pick [videoId] up again, or null to start from the top. */
    fun resumeAt(videoId: String): Long? {
        val p = _progress.value[videoId] ?: return null
        if (p.finished || p.positionMs < RESUME_MIN_MS) return null
        if (p.durationMs > 0 && p.positionMs > p.durationMs - RESUME_MIN_MS) return null
        return p.positionMs
    }

    fun savePosition(videoId: String, positionMs: Long, durationMs: Long) {
        val p = _progress.value[videoId] ?: return
        val finished = durationMs > 0 && positionMs >= durationMs - RESUME_MIN_MS
        _progress.value = (_progress.value + (videoId to p.copy(positionMs = positionMs, durationMs = durationMs.coerceAtLeast(p.durationMs), updatedAt = System.currentTimeMillis(), finished = finished)))
            .entries.sortedByDescending { it.value.updatedAt }.take(MAX_PROGRESS).associate { it.toPair() }
        save()
    }

    fun markPlayed(videoId: String, played: Boolean) {
        val p = _progress.value[videoId] ?: return
        _progress.value = _progress.value + (videoId to p.copy(finished = played, positionMs = if (played) p.positionMs else 0, updatedAt = System.currentTimeMillis()))
        save()
    }

    /** Episodes started and not finished, most recent first. */
    fun inProgress(all: Map<String, Progress> = _progress.value): List<Progress> =
        all.values.filter { !it.finished && it.positionMs >= RESUME_MIN_MS }.sortedByDescending { it.updatedAt }

    // ---- YouTube Music ------------------------------------------------------

    /**
     * The podcasts front page: YouTube Music's own podcasts page when it
     * answers (it does for signed-in accounts in many countries), and the
     * popular episodes and suggested shows from Explore and Home otherwise.
     */
    suspend fun home(): Home = withContext(Dispatchers.IO) {
        coroutineScope {
            val page = async { runCatching { Innertube.browse("FEmusic_non_music_audio") }.getOrNull() }
            val explore = async { runCatching { Innertube.browse("FEmusic_explore") }.getOrNull() }
            val home = async { runCatching { Innertube.browse("FEmusic_home") }.getOrNull() }
            val own = page.await()
            val popular = (own?.let { PodcastParser.episodesIn(it) }.orEmpty() + explore.await()?.let { PodcastParser.episodesIn(it) }.orEmpty())
                .distinctBy { it.videoId }
            val shows = (own?.let { PodcastParser.showsIn(it) }.orEmpty() + home.await()?.let { PodcastParser.showsIn(it) }.orEmpty())
                .distinctBy { it.browseId }
            if (popular.isEmpty() && shows.isEmpty()) throw java.io.IOException("YouTube Music sent no podcasts")
            // Two topics filled in up front, a different pair each day.
            val day = (System.currentTimeMillis() / 86_400_000L).toInt()
            val picks = listOf(day % TOPICS.size, (day * 7 + 3) % TOPICS.size).distinct().map { TOPICS[it] }
            val more = picks.map { topic -> async { topic to runCatching { searchShows(topic) }.getOrDefault(emptyList()) } }.awaitAll()
                .filter { it.second.isNotEmpty() }
            Home(popular, shows, more)
        }
    }

    suspend fun searchShows(query: String): List<PodcastShow> = withContext(Dispatchers.IO) {
        PodcastParser.parseShowSearch(Innertube.search(query, SEARCH_SHOWS))
    }

    suspend fun searchEpisodes(query: String): List<PodcastEpisode> = withContext(Dispatchers.IO) {
        PodcastParser.parseEpisodeSearch(Innertube.search(query, SEARCH_EPISODES))
    }

    suspend fun show(browseId: String): PodcastShowPage = withContext(Dispatchers.IO) {
        PodcastParser.parseShow(browseId, Innertube.browse(browseId))
    }

    suspend fun moreEpisodes(show: PodcastShow, token: String): Pair<List<PodcastEpisode>, String?> = withContext(Dispatchers.IO) {
        PodcastParser.parseMoreEpisodes(Innertube.browseContinuation(token), show)
    }

    /** The newest few episodes of each subscribed show, show by show. */
    suspend fun latestFromSubscriptions(perShow: Int = 2): List<PodcastEpisode> = coroutineScope {
        _subscriptions.value.take(12).map { s ->
            async {
                runCatching { show(s.browseId).episodes.take(perShow) }
                    .onFailure { Log.w(TAG, "latest of ${s.title} failed", it) }
                    .getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    private fun save() {
        val f = file ?: return
        val saved = Saved(_subscriptions.value, _progress.value.values.toList())
        scope.launch {
            runCatching {
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(json.encodeToString(Saved.serializer(), saved))
                tmp.renameTo(f)
            }
        }
    }
}
