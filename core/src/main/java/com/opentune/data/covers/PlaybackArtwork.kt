package com.opentune.data.covers

import com.opentune.data.download.Downloads
import com.opentune.data.model.PLAYER_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings

/**
 * One high-resolution image per playing track, even if metadata arrives again
 * later. The one exception is [better]: a sharper catalogue cover found for
 * this very track while it plays, which replaces the first image once.
 */
class PlaybackArtwork(
    private val preferred: (Song) -> String? = { song ->
        Downloads.artFor(song.videoId)?.toURI()?.toString()
            ?: if (AppSettings.ui.value.albumCovers) AlbumCovers.cached(song) else null
    },
) {
    private var id: String? = null
    private var url: String? = null
    private var upgraded = false
    fun choose(song: Song, better: String? = null): String? {
        if (id != song.videoId || url == null) {
            id = song.videoId
            upgraded = false
            url = (preferred(song) ?: song.thumbnailUrl).artworkAt(PLAYER_ART_PX)
        }
        // A downloaded song's own saved cover stays: it's what was kept with the file.
        if (better != null && !upgraded && url?.startsWith("file:") != true) {
            upgraded = true
            url = better.artworkAt(PLAYER_ART_PX)
        }
        return url
    }
    fun clear() { id = null; url = null; upgraded = false }
}
