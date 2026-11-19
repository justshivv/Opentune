package com.opentune.desktop

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the Library puts the account's library and this computer's together, and how Back finds a page as it was. */
class LibraryDataTest {
    private fun song(id: String) = Song(id.padEnd(11, '0'), "Song $id", "Artist", null)
    private fun card(title: String, browseId: String?) = ShelfItem(title, "Playlist", null, null, browseId)

    @Test fun theAccountsPlaylistsLeaveOutLikedMusicConnectAndRepeats() {
        val shown = AccountLibrary.shownPlaylists(
            listOf(
                card("Liked Music", "VLLM"),
                card("Road trip", "VLPLroad"),
                card("OpenTune Connect", "VLPLconnect"),
                card("Road trip", "PLroad"),
                card("Liked Music", "LM"),
                card("New playlist", null),
                card("Episodes for later", "VLSE"),
            ),
        )
        assertEquals(listOf("Road trip", "Episodes for later"), shown.map { it.title })
        assertTrue(AccountLibrary.isHidden(card("Liked Music", "VLLM")))
        assertFalse(AccountLibrary.isHidden(card("Liked", "VLPLliked")))
    }

    @Test fun likedSongsAreTheAccountsThenThoseLikedOnlyHere() {
        val a = song("a"); val b = song("b"); val c = song("c"); val d = song("d")
        // Signed out (no account likes): this computer's, as they are.
        assertEquals(listOf(c, a), mergeLiked(emptyList(), listOf(c, a)))
        // Signed in: the account's in its order, then the ones only liked here; each once.
        assertEquals(listOf(a, b, c, d), mergeLiked(listOf(a, b), listOf(c, a, d)))
        // A like shows first at once; an unlike goes.
        assertEquals(listOf(c, a, b), AccountLibrary.applyLike(listOf(a, b), c, true))
        assertEquals(listOf(b, a), AccountLibrary.applyLike(listOf(a, b), b, true))
        assertEquals(listOf(b), AccountLibrary.applyLike(listOf(a, b), a, false))
    }

    @Test fun theLibrarysSongsAreEachKeptOnce() {
        val a = song("a"); val b = song("b"); val c = song("c")
        assertEquals(listOf(a, b, c), librarySongs(listOf(a), listOf(b, a), listOf(c, b, a)))
    }

    @Test fun playlistsAreListedHereFirstThenTheAccounts() {
        Library.useFile(java.nio.file.Files.createTempDirectory("librarydata").resolve("desktop.json").toFile())
        val id = Library.createPlaylist("Gym")
        val list = libraryPlaylists(Library.playlists.value, listOf(card("Liked Music", "VLLM"), card("Road trip", "VLPLroad"), card("Road trip", "VLPLroad")))
        assertEquals(listOf("Gym", "Road trip"), list.map { it.title })
        assertEquals(PlaylistKey(id), list[0].key)
        assertEquals(AccountPlaylistKey("PLroad"), list[1].key)
    }

    @Test fun likedSongsFollowTheAccountAndThisComputer() {
        Library.useFile(java.nio.file.Files.createTempDirectory("librarydata").resolve("desktop.json").toFile())
        val here = song("here")
        Library.setLiked(here, true)
        AccountLibrary.preload(AccountLibrary.State(signedIn = true, loaded = true, liked = listOf(song("acct"))))
        runBlocking { kotlinx.coroutines.withTimeout(2_000) { while (LikedSongs.all.value.size != 2) kotlinx.coroutines.delay(10) } }
        assertEquals(listOf(song("acct").videoId, here.videoId), LikedSongs.all.value.map { it.videoId })
        assertTrue(here.videoId in LikedSongs.ids.value)
        // Signed out again: only this computer's.
        AccountLibrary.preload(AccountLibrary.State())
        runBlocking { kotlinx.coroutines.withTimeout(2_000) { while (LikedSongs.all.value.size != 1) kotlinx.coroutines.delay(10) } }
        assertEquals(listOf(here.videoId), LikedSongs.all.value.map { it.videoId })
        Library.setLiked(here, false)
    }

    /** A cover's glyph is the icon's own outline, in its 24-unit viewport, drawn without a cached picture. */
    @Test fun aCoversGlyphIsTheIconsPath() {
        val heart = Icons.Rounded.Favorite
        val bounds = glyphPath(heart).getBounds()
        assertFalse(bounds.isEmpty)
        assertTrue(bounds.left >= 0f && bounds.top >= 0f && bounds.right <= heart.viewportWidth && bounds.bottom <= heart.viewportHeight)
        assertTrue(bounds.width > heart.viewportWidth * 0.6f)
    }

    @Test fun theStackGoesBackTheWayItCameAndDropsWhatsGone() {
        val dropped = mutableListOf<Int>()
        val stack = PageStack("home", limit = 3).also { it.onDrop = { k -> dropped += k } }
        assertFalse(stack.go("home"))
        assertTrue(stack.go("library"))
        val library = stack.current
        stack.go("playlist")
        assertTrue(stack.isBack(stack.current, library))
        assertTrue(stack.back())
        assertEquals(library, stack.current)
        // The playlist was gone back from: what it kept goes.
        assertEquals(listOf(2), dropped)
        stack.go("a"); stack.go("b"); stack.go("c")
        // Past the limit, the oldest goes too.
        assertEquals(listOf("library", "a", "b"), stack.history)
        assertEquals(listOf(2, 0), dropped)
        while (stack.back()) Unit
        assertEquals("library", stack.page)
        assertFalse(stack.canGoBack)
    }

    /** What a page keeps (a tab, a list of things read, how far it's scrolled) is there again after Back. */
    @Test fun backShowsThePageAsItWasLeft() {
        var stack: PageStack<String>? = null
        var tab: MutableState<Int>? = null
        var kept: MutableState<List<Pages.Shelf>?>? = null
        var scroll: LazyListState? = null
        val scene = ImageComposeScene(400, 600, Density(1f)) {
            val (s, holder) = rememberPageStack("library")
            stack = s
            holder.SaveableStateProvider(s.current.key) {
                if (s.page == "library") {
                    tab = rememberSaveable { mutableStateOf(0) }
                    kept = rememberSaveable { mutableStateOf<List<Pages.Shelf>?>(null) }
                    val list = rememberLazyListState()
                    scroll = list
                    LazyColumn(state = list) { items(200) { Box(Modifier.height(40.dp)) { Text("Row $it") } } }
                } else {
                    Text("A playlist")
                }
            }
        }
        try {
            scene.render()
            tab!!.value = 3
            kept!!.value = listOf(Pages.Shelf("Saved", listOf(card("Road trip", "VLPLroad"))))
            runBlocking { scroll!!.scrollToItem(57) }
            scene.render()
            stack!!.go("playlist")
            scene.render()
            stack!!.back()
            scene.render()
            assertEquals(3, tab!!.value)
            assertEquals("Saved", kept!!.value?.single()?.title)
            assertEquals(57, scroll!!.firstVisibleItemIndex)
            // Opened afresh (not by Back), it starts anew.
            stack!!.go("playlist")
            scene.render()
            stack!!.go("library")
            scene.render()
            assertEquals(0, tab!!.value)
            assertEquals(0, scroll!!.firstVisibleItemIndex)
        } finally {
            scene.close()
        }
    }
}
