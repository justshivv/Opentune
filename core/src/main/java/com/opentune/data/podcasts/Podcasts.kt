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
    private class Saved(
        val shows: List<PodcastShow> = emptyList(),
        val progress: List<Progress> = emptyList(),
        /** How often each topic chip was picked. */
        val topics: Map<String, Int> = emptyMap(),
    )

    data class Home(
        val popular: List<PodcastEpisode>,
        val shows: List<PodcastShow>,
        val more: List<Pair<String, List<PodcastShow>>>,
        /** Shows like those listened to, by the show they're like ("Because you listened to …"). */
        val becauseOf: List<Pair<String, List<PodcastShow>>> = emptyList(),
        /** Episodes like those listened to. */
        val suggested: List<PodcastEpisode> = emptyList(),
    )

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
            _topicUse.value = s.topics
            _progress.value = s.progress.associateBy { it.episode.videoId }
        }
    }

    // ---- Topics -------------------------------------------------------------

    private val _topicUse = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** How often each topic was picked, to put the reader's first. */
    val topicUse: StateFlow<Map<String, Int>> = _topicUse.asStateFlow()

    fun pickedTopic(topic: String) {
        if (topic !in TOPICS) return
        _topicUse.value = _topicUse.value + (topic to (_topicUse.value[topic] ?: 0) + 1)
        save()
    }

    /** The topics, those picked most first; the rest keep their order. */
    fun topicsInOrder(use: Map<String, Int> = _topicUse.value): List<String> =
        TOPICS.withIndex().sortedWith(compareByDescending<IndexedValue<String>> { use[it.value] ?: 0 }.thenBy { it.index }).map { it.value }

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
    private val homeVisits = java.util.concurrent.atomic.AtomicInteger(0)

    suspend fun home(): Home = withContext(Dispatchers.IO) {
        coroutineScope {
            val page = async { runCatching { Innertube.browse("FEmusic_non_music_audio") }.getOrNull() }
            val explore = async { runCatching { Innertube.browse("FEmusic_explore") }.getOrNull() }
            val home = async { runCatching { Innertube.browse("FEmusic_home") }.getOrNull() }
            val personal = async { runCatching { forYou() }.onFailure { Log.w(TAG, "suggestions failed", it) }.getOrNull() }
            val own = page.await()
            val popular = (own?.let { PodcastParser.episodesIn(it) }.orEmpty() + explore.await()?.let { PodcastParser.episodesIn(it) }.orEmpty())
                .distinctBy { it.videoId }
            val shows = (own?.let { PodcastParser.showsIn(it) }.orEmpty() + home.await()?.let { PodcastParser.showsIn(it) }.orEmpty())
                .distinctBy { it.browseId }
            val mine = personal.await() ?: ForYou(emptyList(), emptyList())
            // Topics filled in up front, different ones each day: two beside other picks, more
            // when there are none (signed out, YouTube Music's podcasts page answers 404 and
            // Explore and Home carry no podcasts), since topics are found by search, which works.
            val day = (System.currentTimeMillis() / 86_400_000L).toInt() + homeVisits.getAndIncrement()
            val nothingElse = popular.isEmpty() && shows.isEmpty() && mine.shows.isEmpty() && mine.episodes.isEmpty()
            val favourites = topicsInOrder().filter { (_topicUse.value[it] ?: 0) > 0 }
            val picks = topicsToShow(day, nothingElse, enoughFor(mine), favourites)
            val more = picks.map { topic -> async { topic to runCatching { searchShows(topic) }.getOrDefault(emptyList()) } }.awaitAll()
                .filter { it.second.isNotEmpty() }
            // Nothing at all came back: that's the connection, or YouTube Music refusing.
            if (nothingElse && more.isEmpty()) throw java.io.IOException("YouTube Music sent no podcasts")
            Home(popular, shows, more, mine.shows, mine.episodes)
        }
    }

    /** Suggestions from what was listened to: shows under the show that brought them, and episodes. */
    data class ForYou(val shows: List<Pair<String, List<PodcastShow>>>, val episodes: List<PodcastEpisode>)

    /**
     * The episodes suggestions are drawn from: the last few listened to, one a show; with none
     * yet, the newest of the subscribed shows.
     */
    fun seeds(all: Map<String, Progress> = _progress.value, limit: Int = 3): List<PodcastEpisode> =
        all.values.sortedByDescending { it.updatedAt }.map { it.episode }
            .distinctBy { it.showBrowseId ?: it.showTitle }.take(limit)

    /** What YouTube Music lists beside [videoId] (its Related tab): similar episodes, and suggested shows. */
    suspend fun related(videoId: String): Pair<List<PodcastEpisode>, List<PodcastShow>> = withContext(Dispatchers.IO) {
        val r = Innertube.browse("MPPR$videoId")
        PodcastParser.episodesIn(r) to PodcastParser.showsIn(r)
    }

    private suspend fun forYou(): ForYou = coroutineScope {
        val seeds = seeds().ifEmpty {
            _subscriptions.value.take(2).map { s -> async { runCatching { show(s.browseId).episodes.firstOrNull() }.getOrNull() } }.awaitAll().filterNotNull()
        }
        if (seeds.isEmpty()) return@coroutineScope ForYou(emptyList(), emptyList())
        val related = seeds.map { s -> async { runCatching { related(s.videoId) }.getOrNull() } }.awaitAll()
        suggestions(seeds, related, _subscriptions.value.map { it.browseId }.toSet(), _progress.value.keys)
    }

    /**
     * The suggestions to show for [seeds] and what's related to each ([related], null where it
     * didn't load): shows not already [subscribed] to, each shown once, under the first seed
     * that brought it; and the related episodes, taken from each seed in turn, leaving out
     * those already [heard] and the seeds' own shows' (those are in Continue listening).
     */
    internal fun suggestions(
        seeds: List<PodcastEpisode>,
        related: List<Pair<List<PodcastEpisode>, List<PodcastShow>>?>,
        subscribed: Set<String>,
        heard: Set<String>,
    ): ForYou {
        val seedShows = seeds.mapNotNull { it.showBrowseId }.toSet()
        val placed = HashSet<String>()
        val shows = seeds.zip(related).mapNotNull { (seed, r) ->
            val list = r?.second.orEmpty().filter { it.browseId !in subscribed && it.browseId !in seedShows && placed.add(it.browseId) }.take(MAX_SUGGESTED)
            if (list.isEmpty()) null else seed.showTitle.ifBlank { seed.title } to list
        }
        val lists = related.map { r -> r?.first.orEmpty().filter { it.videoId !in heard && it.showBrowseId !in seedShows } }
        val episodes = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
            .distinctBy { it.videoId }.take(MAX_SUGGESTED)
        return ForYou(shows, episodes)
    }

    private const val MAX_SUGGESTED = 10

    /** Suggestions enough to fill the page on their own: two shelves' worth or more. */
    internal fun enoughFor(mine: ForYou): Boolean =
        mine.shows.size + (if (mine.episodes.size >= 3) 1 else 0) >= 2

    /**
     * The topics on the front page: four of the day's when there's nothing else; with
     * [enough] suggestions, only the reader's favourite one (no others); otherwise two, the
     * reader's favourite first.
     */
    internal fun topicsToShow(day: Int, nothingElse: Boolean, enough: Boolean, favourites: List<String>): List<String> = when {
        nothingElse -> (favourites.take(2) + topicPicks(day, 6)).distinct().take(4)
        enough -> favourites.take(1)
        else -> (favourites.take(1) + topicPicks(day, 3)).distinct().take(2)
    }

    /** [count] different topics for [day]; a step of 7 through the 16 topics brings each up in turn. */
    internal fun topicPicks(day: Int, count: Int): List<String> =
        (0 until count.coerceAtMost(TOPICS.size)).map { TOPICS[Math.floorMod(day + it * 7, TOPICS.size)] }

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
        val saved = Saved(_subscriptions.value, _progress.value.values.toList(), _topicUse.value)
        scope.launch {
            runCatching {
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(json.encodeToString(Saved.serializer(), saved))
                tmp.renameTo(f)
            }
        }
    }
}
