// Podcasts as the shared lyrics code asks about them (see Downloads.kt in this folder).
package com.opentune.data.podcasts

/** The desktop has no podcasts, so nothing it plays is an episode. */
object Podcasts {
    @Suppress("UNUSED_PARAMETER")
    fun isEpisode(videoId: String): Boolean = false
}
