package com.opentune.data.history

import com.opentune.data.MusicRepository
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import java.util.concurrent.ConcurrentHashMap

/**
 * An artist's own photo, for places that only know them from songs played (Replay):
 * from their YouTube Music page when the song named them, otherwise from a search for
 * an artist with exactly that name. Kept for the session, misses included.
 */
object ArtistFaces {
    private val found = ConcurrentHashMap<String, Optional>()
    private class Optional(val url: String?)

    fun cached(name: String): String? = found[key(name)]?.url

    /** [name]'s photo, looked up once; [artistId] is their YouTube Music id when a song gave it. */
    suspend fun photoFor(name: String, artistId: String?): String? {
        found[key(name)]?.let { return it.url }
        val url = runCatching {
            artistId?.takeIf { it.startsWith("UC") }?.let { MusicRepository.artist(it).thumbnailUrl }
                ?: MusicRepository.search(name, SearchFilter.ARTISTS).firstNotNullOfOrNull { r ->
                    (r as? SearchResult.Browse)?.item?.takeIf { it.type == BrowseType.ARTIST && it.title.equals(name.trim(), ignoreCase = true) }?.thumbnailUrl
                }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
        // A failed lookup isn't kept, so it's tried again next time.
        if (url != null || artistId != null) found[key(name)] = Optional(url)
        return url
    }

    private fun key(name: String) = name.trim().lowercase()
}
