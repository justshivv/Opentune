package com.opentune.data.library

import com.opentune.data.model.Song
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class UndoRemoveTest {
    private fun song(id: String) = Song(id, "Song $id", "Artist", null)

    @After fun clear() = LibraryStore.importJson(Json.parseToJsonElement("{}"))

    @Test fun undoPutsTheSongBackWhereItWas() {
        LibraryStore.importJson(Json.parseToJsonElement("{}"))
        val id = LibraryStore.createPlaylist("Mine")
        LibraryStore.addAllToPlaylist(id, listOf(song("a"), song("b"), song("c")))
        val removed = LibraryStore.removeUndoably(id, 1)!!
        assertEquals(listOf("a", "c"), LibraryStore.playlist(id)!!.songs.map { it.videoId })
        LibraryStore.undoRemove(removed)
        assertEquals(listOf("a", "b", "c"), LibraryStore.playlist(id)!!.songs.map { it.videoId })
    }

    @Test fun theLastSongComesBackLast() {
        LibraryStore.importJson(Json.parseToJsonElement("{}"))
        val id = LibraryStore.createPlaylist("Mine")
        LibraryStore.addAllToPlaylist(id, listOf(song("a"), song("b")))
        val removed = LibraryStore.removeUndoably(id, 1)!!
        LibraryStore.removeUndoably(id, 0)
        LibraryStore.undoRemove(removed)
        assertEquals(listOf("b"), LibraryStore.playlist(id)!!.songs.map { it.videoId })
    }
}
