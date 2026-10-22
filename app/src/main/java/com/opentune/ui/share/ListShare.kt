package com.opentune.ui.share

import android.content.Context
import android.content.Intent
import com.opentune.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sharing an album or playlist: a link for YouTube Music's own, the songs themselves for one made in the app. */
object ListShare {
    /**
     * Where [browseId] opens on YouTube Music, and in OpenTune for anyone who
     * has it: playlists as playlist?list=, albums and the rest as browse/.
     */
    fun linkFor(browseId: String): String = when {
        browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("OLAK") || browseId.startsWith("RD") ->
            playlistLink(browseId)
        else -> "https://music.youtube.com/browse/$browseId"
    }

    /** A YouTube Music playlist's address, from its id with or without the `VL` it's browsed under. */
    fun playlistLink(playlistId: String): String = "https://music.youtube.com/playlist?list=${playlistId.removePrefix("VL")}"

    fun shareList(context: Context, title: String, browseId: String, covers: List<String?> = emptyList(), subtitle: String? = null) =
        send(context, "$title\n${linkFor(browseId)}", title, covers, subtitle)

    /** A playlist on YouTube Music, by its id: anyone it's shared with can open it there and save it. */
    fun sharePlaylist(context: Context, title: String, playlistId: String, covers: List<String?> = emptyList(), subtitle: String? = null) =
        send(context, "$title\n${playlistLink(playlistId)}", title, covers, subtitle)

    /**
     * A playlist made in the app has no address of its own, so it goes as
     * its songs, each with its link, up to [MAX_SONGS] of them.
     */
    internal fun localText(name: String, songs: List<Song>): String = buildString {
        append(name).append(" · ").append(songs.size).append(if (songs.size == 1) " song" else " songs").append('\n')
        songs.take(MAX_SONGS).forEachIndexed { i, s ->
            append('\n').append(i + 1).append(". ").append(s.title)
            if (s.artist.isNotBlank()) append(" – ").append(s.artist)
            append("\nhttps://music.youtube.com/watch?v=").append(s.videoId)
        }
        if (songs.size > MAX_SONGS) append("\n\n…and ").append(songs.size - MAX_SONGS).append(" more")
        append("\n\nShared from OpenTune")
    }

    fun shareLocal(context: Context, name: String, songs: List<Song>) =
        send(context, localText(name, songs), name, songs.map { it.thumbnailUrl }, countLine(songs.size))

    /**
     * The pictures for a list's card: for a playlist, up to four of its songs'
     * covers when it has that many different ones; otherwise (and for an
     * album, whose songs share one) the list's own picture.
     */
    fun coversFor(thumbnailUrl: String?, songs: List<Song>, album: Boolean = false): List<String?> {
        val tracks = songs.mapNotNull { it.thumbnailUrl }.distinct()
        return when {
            !album && tracks.size >= 4 -> tracks.take(4)
            thumbnailUrl != null -> listOf(thumbnailUrl)
            else -> tracks.take(1)
        }
    }

    /** "12 songs", for a card's second line. */
    fun countLine(count: Int): String = if (count == 1) "1 song" else "$count songs"

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /**
     * Shares [text]; with [covers], a card of the list goes with it
     * ([PlaylistCard]), and the text alone if the card can't be made.
     */
    private fun send(context: Context, text: String, title: String, covers: List<String?> = emptyList(), subtitle: String? = null) {
        val urls = covers.filterNotNull().distinct().take(4)
        if (urls.isEmpty()) {
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title)
            runCatching { context.startActivity(Intent.createChooser(intent, "Share $title").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        scope.launch {
            val card = runCatching {
                val images = urls.mapNotNull { ShareCard.loadImage(context, it, 540) }
                withContext(Dispatchers.Default) { PlaylistCard.draw(images, title, subtitle) }
            }.getOrNull()
            ShareCard.sendImage(context, card, "playlist-${title.hashCode().toUInt()}.png", text, title)
        }
    }

    private const val MAX_SONGS = 50
}
