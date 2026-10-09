package com.opentune.data.lossless

import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.Song
import com.opentune.data.model.durationMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Shelf cards can omit runtime. Fetch metadata for the exact video before choosing audio. */
internal object CatalogMetadata {
    suspend fun duration(song: Song, known: Long, fetch: suspend (String) -> List<Song> = {
        InnertubeParser.parseWatchQueue(Innertube.next(it))
    }): Long {
        if (known > 0) return known
        return try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(5_000) {
                    fetch(song.videoId).firstOrNull { it.videoId == song.videoId }?.durationMillis()
                } ?: 0L
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0L
        }
    }
}
