// The phone library's book of artist credits, as the shared listening history uses it
// (see Downloads.kt in this folder).
package com.opentune.data.library

/** The desktop keeps no such book: a play's own credit is the one recorded. */
object LibraryStore {
    @Suppress("UNUSED_PARAMETER")
    fun artistLine(videoId: String): String? = null

    @Suppress("UNUSED_PARAMETER")
    fun learnArtistLines(lines: Map<String, String>) = Unit
}
