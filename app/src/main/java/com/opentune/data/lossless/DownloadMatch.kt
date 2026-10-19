package com.opentune.data.lossless

import com.opentune.data.model.Song
import com.opentune.data.model.durationMillis

/** Never substitute a remix/live recording or an unrelated first search result. */
internal fun matchingDownloads(song: Song, candidates: List<LosslessTrack>): List<LosslessTrack> {
    val duration = song.durationMillis()
    val identity = CatalogIdentity(song.title, song.artist, null, duration)
    return candidates.filter { identity.matches(it.title, listOf(it.artist), null, it.durationMs) }.distinctBy { it.key }.map { it.copy(sourceIds = it.sourceIds + song.videoId) }
}
