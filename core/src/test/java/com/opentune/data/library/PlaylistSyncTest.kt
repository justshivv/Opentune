package com.opentune.data.library

import com.opentune.data.RemotePlaylist
import com.opentune.data.innertube.Innertube
import com.opentune.data.model.PlaylistPrivacy
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.library.PlaylistEdit.Kind
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSyncTest {
    private fun queue(vararg edits: PlaylistEdit, busy: Long? = null) =
        edits.fold(emptyList<PlaylistEdit>()) { q, e -> SyncQueue.add(q, e, busy) }

    private fun add(vararg ids: String, playlist: String = "p") = PlaylistEdit(Kind.ADD, playlist, "PLx", videoIds = ids.toList())
    private fun remove(id: String, playlist: String = "p", set: String? = null) =
        PlaylistEdit(Kind.REMOVE, playlist, "PLx", videoIds = listOf(id), setVideoId = set)

    @Test fun editsKeepTheirOrderAndEachGetsItsOwnNumber() {
        val q = queue(add("a"), PlaylistEdit(Kind.RENAME, "p", "PLx", name = "New"), add("b"))
        assertEquals(listOf(Kind.ADD, Kind.RENAME, Kind.ADD), q.map { it.kind })
        assertEquals(listOf(1L, 2L, 3L), q.map { it.seq })
    }

    @Test fun nothingElseIsQueuedWhileThePlaylistIsStillBeingMade() {
        val q = queue(
            PlaylistEdit(Kind.CREATE, "p"),
            add("a"),
            remove("b"),
            PlaylistEdit(Kind.RENAME, "p", name = "New"),
            PlaylistEdit(Kind.CREATE, "p"),
            add("c", playlist = "other"),
        )
        assertEquals(listOf(Kind.CREATE to "p", Kind.ADD to "other"), q.map { it.kind to it.playlist })
    }

    @Test fun deletingDropsWhatWasWaitingAndAPlaylistNeverMadeNeedsNoDelete() {
        val linked = queue(add("a"), add("x", playlist = "other"), PlaylistEdit(Kind.DELETE, "p", "PLx"))
        assertEquals(listOf(Kind.ADD to "other", Kind.DELETE to "p"), linked.map { it.kind to it.playlist })

        val unmade = queue(PlaylistEdit(Kind.CREATE, "p"), PlaylistEdit(Kind.DELETE, "p"))
        assertTrue(unmade.isEmpty())
    }

    @Test fun aNewNameOrPrivacyReplacesTheOneWaiting() {
        val q = queue(
            PlaylistEdit(Kind.RENAME, "p", "PLx", name = "One"),
            PlaylistEdit(Kind.PRIVACY, "p", "PLx", privacy = PlaylistPrivacy.PUBLIC),
            PlaylistEdit(Kind.RENAME, "p", "PLx", name = "Two"),
            PlaylistEdit(Kind.PRIVACY, "p", "PLx", privacy = PlaylistPrivacy.UNLISTED),
        )
        assertEquals(listOf("Two"), q.filter { it.kind == Kind.RENAME }.map { it.name })
        assertEquals(listOf(PlaylistPrivacy.UNLISTED), q.filter { it.kind == Kind.PRIVACY }.map { it.privacy })
    }

    @Test fun aRenameAlreadyOnItsWayIsNotReplaced() {
        val first = queue(PlaylistEdit(Kind.RENAME, "p", "PLx", name = "One"))
        val q = SyncQueue.add(first, PlaylistEdit(Kind.RENAME, "p", "PLx", name = "Two"), busy = first.single().seq)
        assertEquals(listOf("One", "Two"), q.map { it.name })
    }

    @Test fun removingASongNotSentYetTakesItOutOfItsAdd() {
        val q = queue(add("a", "b"), remove("a"))
        assertEquals(listOf(listOf("b")), q.map { it.videoIds })

        // The only song in the add: the add goes, and so does the removal.
        assertTrue(queue(add("a"), remove("a")).isEmpty())

        // A song already sent has to be removed on YouTube Music.
        val sent = queue(add("a"))
        val after = SyncQueue.add(sent, remove("a"), busy = sent.single().seq)
        assertEquals(listOf(Kind.ADD, Kind.REMOVE), after.map { it.kind })
    }

    @Test fun anAddWithNothingToSendIsNotQueued() {
        assertTrue(queue(add()).isEmpty())
    }

    @Test fun aRefusedEditIsTriedAgainAFewTimesThenDropped() {
        var q = queue(add("a"), add("b"))
        val seq = q.first().seq
        repeat(SyncQueue.MAX_REFUSALS - 1) { q = SyncQueue.refused(q, seq) }
        assertEquals(SyncQueue.MAX_REFUSALS - 1, q.first().refusals)
        q = SyncQueue.refused(q, seq)
        assertEquals(listOf(listOf("b")), q.map { it.videoIds })
        assertEquals(emptyList<PlaylistEdit>(), SyncQueue.done(q, q.single().seq))
    }

    @Test fun aRefusedCreateIsNotTriedAgain() {
        val q = queue(PlaylistEdit(Kind.CREATE, "p"))
        assertTrue(SyncQueue.refused(q, q.single().seq).isEmpty())
    }

    @Test fun beingOfflineIsNotARefusal() {
        assertTrue(SyncQueue.isTransient(IOException("no route")))
        assertTrue(SyncQueue.isTransient(SocketTimeoutException()))
        assertTrue(SyncQueue.isTransient(Innertube.NotSignedInException()))
        assertFalse(SyncQueue.isTransient(IllegalStateException("YouTube Music refused the edit (STATUS_FAILED)")))
    }

    @Test fun theQueueSurvivesBeingSavedAndRead() {
        val q = queue(PlaylistEdit(Kind.CREATE, "p"), PlaylistEdit(Kind.PRIVACY, "q", "PLy", privacy = PlaylistPrivacy.PUBLIC), remove("v", "r", "set"))
        val text = Json.encodeToString(ListSerializer(PlaylistEdit.serializer()), q)
        assertEquals(q, Json.decodeFromString(ListSerializer(PlaylistEdit.serializer()), text))
    }

    // ---- Pulling changes made on YouTube Music ----------------------------

    private fun ref(id: String, set: String? = null) = SongRef(id, "Song $id", "Artist", setVideoId = set)
    private fun song(id: String, set: String?) = Song(id, "Song $id", "Artist", null, setVideoId = set)
    private fun local(vararg songs: SongRef) =
        LocalPlaylist("p", "Mine", songs.toList(), createdAt = 5, remoteId = "PLx", privacy = PlaylistPrivacy.PRIVATE)

    @Test fun pullingFollowsAddsAndRemovalsMadeOnYouTubeMusic() {
        val here = local(ref("a", "s1"), ref("b", "s2"), ref("local:file"), ref("c"))
        val there = RemotePlaylist("Renamed", PlaylistPrivacy.PUBLIC, listOf(song("c", "s3"), song("a", "s1"), song("d", "s4")))
        val merged = PlaylistMerge.pull(here, there)
        // b was there and isn't now; the file and c stay; d is new, at the end.
        assertEquals(listOf("a", "local:file", "c", "d"), merged.songs.map { it.videoId })
        assertEquals(listOf("s1", null, "s3", "s4"), merged.songs.map { it.setVideoId })
        assertEquals("Renamed", merged.name)
        assertEquals(PlaylistPrivacy.PUBLIC, merged.privacy)
        assertEquals("PLx", merged.remoteId)
    }

    @Test fun pullingWidensAStoredCreditThatNamesOnlyTheFirstPerformer() {
        val here = local(SongRef("one-kiss", "One Kiss", "Calvin Harris", null, setVideoId = "s1"))
        val there = RemotePlaylist("Mine", PlaylistPrivacy.PRIVATE,
            listOf(Song("one-kiss", "One Kiss", "Calvin Harris, Dua Lipa", null, setVideoId = "s1")))
        assertEquals("Calvin Harris, Dua Lipa", PlaylistMerge.pull(here, there).songs.single().artist)
    }

    @Test fun anEmptyAnswerDoesNotEmptyThePlaylist() {
        val here = local(ref("a", "s1"), ref("b", "s2"))
        assertEquals(here, PlaylistMerge.pull(here, RemotePlaylist("", null, emptyList())))
    }

    @Test fun nothingNewChangesNothing() {
        val here = local(ref("a", "s1"))
        assertEquals(here, PlaylistMerge.pull(here, RemotePlaylist("Mine", PlaylistPrivacy.PRIVATE, listOf(song("a", "s1")))))
    }

    // ---- Which playlists to list -------------------------------------------

    private fun card(browseId: String, title: String = browseId) = ShelfItem(title, "", null, null, browseId)

    @Test fun aLinkedPlaylistIsListedOnce() {
        val account = listOf(card("VLPLx"), card("VLPLother"))
        val here = listOf(local(), LocalPlaylist("q", "Phone only"))
        assertEquals(listOf("VLPLother"), PlaylistLinks.unlinked(account, here).map { it.browseId })
    }

    @Test fun thePickerOffersOnlyPlaylistsTheAccountCanChange() {
        val account = listOf(
            card("VLLM", "Liked Music"),
            card("VLRDCLAK5uy_mix", "A mix"),
            card("VLPLx", "Linked here"),
            card("VLPLown", "Made on YouTube Music"),
            card("VLPLsaved", "Someone else's"),
            card("VLPLunread", "Not opened yet"),
            card("MPREb_album", "An album"),
        )
        val owned = mapOf("VLPLown" to true, "VLPLsaved" to false)
        val picked = PlaylistLinks.pickable(account, owned, listOf(local()))
        assertEquals(listOf("PLown", "PLunread"), picked.map { it.playlistId })
    }

    // ---- Saved playlists from before linking existed -----------------------

    @Test fun aPlaylistSavedBeforeLinkingStillLoads() {
        val old = """{"id":"p","name":"Road trip","songs":[{"videoId":"v","title":"T","artist":"A"}],"createdAt":5}"""
        val p = Json.decodeFromString(LocalPlaylist.serializer(), old)
        assertNull(p.remoteId)
        assertNull(p.privacy)
        assertNull(p.songs.single().setVideoId)
        assertFalse(p.synced)
    }

    @Test fun aLinkedPlaylistKeepsItsLinkAndEntryIds() {
        val p = local(ref("a", "s1"))
        assertTrue(p.synced)
        assertEquals(p, Json.decodeFromString(LocalPlaylist.serializer(), Json.encodeToString(LocalPlaylist.serializer(), p)))
        // One that isn't linked is written just as before.
        val plain = Json.encodeToString(LocalPlaylist.serializer(), LocalPlaylist("p", "Mine", listOf(ref("a")), createdAt = 5))
        assertFalse(plain.contains("remoteId") || plain.contains("privacy") || plain.contains("setVideoId"))
    }

    @Test fun aSongFromAnotherPlaylistDoesNotBringItsEntryId() {
        assertNull(SongRef.of(song("a", "elsewhere")).setVideoId)
    }

    @Test fun aLinkMadeInAnotherAccountIsNotThisOnes() {
        val mine = LocalPlaylist("p1", "Runs", remoteId = "PLa", privacy = PlaylistPrivacy.PRIVATE, account = "me@example.com")
        assertFalse(PlaylistMerge.forOtherAccount(mine, "me@example.com"))
        assertFalse("email case aside", PlaylistMerge.forOtherAccount(mine, "Me@Example.com"))
        assertTrue(PlaylistMerge.forOtherAccount(mine, "someone@example.com"))
        assertFalse("no account recorded", PlaylistMerge.forOtherAccount(mine.copy(account = null), "someone@example.com"))
        assertFalse("not linked at all", PlaylistMerge.forOtherAccount(LocalPlaylist("p2", "Phone only"), "someone@example.com"))
    }
}
