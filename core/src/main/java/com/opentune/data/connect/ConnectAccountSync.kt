package com.opentune.data.connect

import com.opentune.data.MusicRepository
import com.opentune.data.innertube.Innertube
import com.opentune.data.model.PlaylistPrivacy
import com.opentune.data.model.ShelfItem

/**
 * Keeps the group's secret in the person's YouTube account, so every device
 * signed in to it joins by itself: in the description of a private, empty
 * playlist named [TITLE]. The web player does the same (accountSync.ts).
 */
object ConnectAccountSync {
    const val TITLE = "NepTune Connect"

    /** The playlist's name before the rename; playlists already made keep it, and still count. */
    private const val LEGACY_TITLE = "OpenTune Connect"
    private const val PREFIX = "opentune-connect:v1:"
    private val SECRET = Regex("""opentune-connect:v1:([A-Za-z0-9_-]{43})""")

    /** The description that carries [group]. */
    fun description(group: ConnectGroup) =
        "Lets your NepTune devices find each other. Keep it private; deleting it only stops them joining by themselves.\n$PREFIX${group.encoded}"

    /** The secret in any text from the playlist's page (its JSON as it came), or null. */
    fun secretIn(text: String): ConnectGroup? = SECRET.find(text)?.groupValues?.get(1)?.let(ConnectGroup::parse)

    /** Whether [item] is the playlist kept for Connect, which NepTune never shows. */
    fun isConnectPlaylist(item: ShelfItem): Boolean = item.title == TITLE || item.title == LEGACY_TITLE

    /** The playlist among the account's own, by its title. */
    fun pick(playlists: List<ShelfItem>): ShelfItem? = playlists.firstOrNull { isConnectPlaylist(it) && it.browseId != null }

    /** The group kept in the account, or null when there's none yet. */
    suspend fun read(): ConnectGroup? {
        val playlist = pick(MusicRepository.libraryPlaylists(withConnect = true)) ?: return null
        val browseId = playlist.browseId!!.let { if (it.startsWith("VL")) it else "VL$it" }
        return secretIn(Innertube.browse(browseId).toString())
    }

    /** Puts [group] in the account: the playlist made if it isn't there, its description changed if it is. */
    suspend fun write(group: ConnectGroup) {
        val playlist = pick(MusicRepository.libraryPlaylists(withConnect = true))
        if (playlist == null) Innertube.createPlaylist(TITLE, PlaylistPrivacy.PRIVATE, description(group))
        else Innertube.setPlaylistDescription(playlist.browseId!!, description(group))
    }
}
