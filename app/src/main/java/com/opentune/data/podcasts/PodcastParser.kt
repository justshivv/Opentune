package com.opentune.data.podcasts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Podcasts as YouTube Music lays them out. A show is a browse page
 * (`MPSP` + its playlist id); an episode is a video with the
 * `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` type, shown as a
 * `musicMultiRowListItemRenderer` on show pages and shelves and as a list
 * row in search.
 */
object PodcastParser {
    private const val SHOW_PREFIX = "MPSP"
    private const val EPISODE_TYPE = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"

    /** A show page: its header and the first page of episodes. */
    fun parseShow(browseId: String, root: JsonObject): PodcastShowPage {
        val header = find(root, "musicResponsiveHeaderRenderer")
        val show = PodcastShow(
            browseId = browseId,
            title = header.o("title").runs().ifBlank { "Podcast" },
            author = header.o("straplineTextOne").runs(),
            thumbnailUrl = header.o("thumbnail").o("musicThumbnailRenderer").o("thumbnail").a("thumbnails").best(),
            description = header.o("description").o("musicDescriptionShelfRenderer").o("description").runs().takeIf { it.isNotBlank() },
        )
        val episodes = episodesIn(root, show)
        return PodcastShowPage(show, episodes, continuation(root))
    }

    /** More episodes from a show page's continuation. */
    fun parseMoreEpisodes(root: JsonObject, show: PodcastShow): Pair<List<PodcastEpisode>, String?> =
        episodesIn(root, show) to continuation(root)

    /** Every episode card under [root]; [show] fills in what a show page's rows leave out. */
    fun episodesIn(root: JsonElement, show: PodcastShow? = null): List<PodcastEpisode> =
        collect(root, "musicMultiRowListItemRenderer").mapNotNull { multiRow(it, show) }.distinctBy { it.videoId }

    private fun multiRow(r: JsonObject, show: PodcastShow?): PodcastEpisode? {
        val watch = r.o("onTap").o("watchEndpoint")
            ?: r.o("overlay").o("musicItemThumbnailOverlayRenderer").o("content").o("musicPlayButtonRenderer").o("playNavigationEndpoint").o("watchEndpoint")
        val videoId = watch.s("videoId") ?: return null
        val showRun = r.o("secondTitle").a("runs")?.firstOrNull()
        val progress = r.o("playbackProgress").o("musicPlaybackProgressRenderer")
        val subtitle = r.o("subtitle").a("runs").orEmpty().mapNotNull { it.s("text") }.filter { it.trim() != "•" }
        return PodcastEpisode(
            videoId = videoId,
            title = r.o("title").runs().ifBlank { return null },
            showTitle = showRun.s("text") ?: show?.title.orEmpty(),
            showBrowseId = showRun.o("navigationEndpoint").o("browseEndpoint").s("browseId") ?: show?.browseId,
            thumbnailUrl = r.o("thumbnail").o("musicThumbnailRenderer").o("thumbnail").a("thumbnails").best() ?: show?.thumbnailUrl,
            description = r.o("description").runs().takeIf { it.isNotBlank() },
            published = subtitle.lastOrNull { !it.endsWith("views") }?.trim(),
            durationText = progress.o("durationText").runs().removePrefix(" • ").trim().takeIf { it.isNotBlank() },
            playedPercent = (progress?.get("playbackProgressPercentage") as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    /** Episode rows in a search answer (the Episodes filter). */
    fun parseEpisodeSearch(root: JsonObject): List<PodcastEpisode> =
        collect(root, "musicResponsiveListItemRenderer").mapNotNull { r ->
            val watch = r.o("overlay").o("musicItemThumbnailOverlayRenderer").o("content").o("musicPlayButtonRenderer").o("playNavigationEndpoint").o("watchEndpoint")
            if (watch.o("watchEndpointMusicSupportedConfigs").o("watchEndpointMusicConfig").s("musicVideoType") != EPISODE_TYPE) return@mapNotNull null
            val videoId = watch.s("videoId") ?: return@mapNotNull null
            val columns = r.a("flexColumns").orEmpty().map { it.o("musicResponsiveListItemFlexColumnRenderer").o("text").a("runs").orEmpty() }
            val title = columns.getOrNull(0).orEmpty().joinToString("") { it.s("text").orEmpty() }.ifBlank { return@mapNotNull null }
            val second = columns.getOrNull(1).orEmpty()
            val showRun = second.firstOrNull { it.o("navigationEndpoint").o("browseEndpoint").s("browseId")?.startsWith(SHOW_PREFIX) == true }
            PodcastEpisode(
                videoId = videoId,
                title = title,
                showTitle = showRun.s("text").orEmpty(),
                showBrowseId = showRun.o("navigationEndpoint").o("browseEndpoint").s("browseId"),
                thumbnailUrl = r.o("thumbnail").o("musicThumbnailRenderer").o("thumbnail").a("thumbnails").best(),
                published = second.firstOrNull()?.s("text")?.takeIf { showRun == null || it != showRun.s("text") },
            )
        }.distinctBy { it.videoId }

    /** Show rows in a search answer (the Podcasts filter). */
    fun parseShowSearch(root: JsonObject): List<PodcastShow> =
        collect(root, "musicResponsiveListItemRenderer").mapNotNull { r ->
            val id = r.o("navigationEndpoint").o("browseEndpoint").s("browseId")?.takeIf { it.startsWith(SHOW_PREFIX) } ?: return@mapNotNull null
            val columns = r.a("flexColumns").orEmpty().map { it.o("musicResponsiveListItemFlexColumnRenderer").o("text").runs() }
            PodcastShow(
                browseId = id,
                title = columns.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                author = columns.getOrNull(1).orEmpty(),
                thumbnailUrl = r.o("thumbnail").o("musicThumbnailRenderer").o("thumbnail").a("thumbnails").best(),
            )
        }.distinctBy { it.browseId }

    /** Show cards (two-row items linking to a show page) anywhere under [root]. */
    fun showsIn(root: JsonElement): List<PodcastShow> =
        collect(root, "musicTwoRowItemRenderer").mapNotNull { r ->
            val id = r.o("navigationEndpoint").o("browseEndpoint").s("browseId")?.takeIf { it.startsWith(SHOW_PREFIX) } ?: return@mapNotNull null
            PodcastShow(
                browseId = id,
                title = r.o("title").runs().ifBlank { return@mapNotNull null },
                author = r.o("subtitle").runs(),
                thumbnailUrl = r.o("thumbnailRenderer").o("musicThumbnailRenderer").o("thumbnail").a("thumbnails").best(),
            )
        }.distinctBy { it.browseId }

    private fun continuation(root: JsonElement): String? =
        collect(root, "nextContinuationData").firstOrNull().s("continuation")
            ?: collect(root, "continuationItemRenderer").firstOrNull().o("continuationEndpoint").o("continuationCommand").s("token")

    private fun find(root: JsonElement, name: String): JsonObject? = collect(root, name).firstOrNull()

    private fun collect(root: JsonElement, name: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    (node[name] as? JsonObject)?.let(out::add)
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    private fun JsonElement?.o(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement?.a(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
    private fun JsonElement?.s(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.runs(): String = a("runs")?.joinToString("") { it.s("text").orEmpty() }.orEmpty()
    private fun JsonArray?.best(): String? = this?.lastOrNull().s("url")
}
