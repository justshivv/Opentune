package com.opentune.data.podcasts

import com.opentune.data.model.Song
import kotlinx.serialization.Serializable

@Serializable
data class PodcastShow(
    val browseId: String,
    val title: String,
    val author: String = "",
    val thumbnailUrl: String? = null,
    val description: String? = null,
)

@Serializable
data class PodcastEpisode(
    val videoId: String,
    val title: String,
    val showTitle: String,
    val showBrowseId: String? = null,
    val thumbnailUrl: String? = null,
    val description: String? = null,
    /** "11h ago" or "Aug 23, 2022", as YouTube Music writes it. */
    val published: String? = null,
    /** "3 hr 8 min". */
    val durationText: String? = null,
    /** How much the signed-in account has heard, 0..100. */
    val playedPercent: Int = 0,
) {
    /** Plays like any YouTube track, credited to the show. */
    fun toSong() = Song(videoId = videoId, title = title, artist = showTitle, thumbnailUrl = thumbnailUrl, albumName = showTitle)
}

data class PodcastShowPage(val show: PodcastShow, val episodes: List<PodcastEpisode>, val continuation: String?)
