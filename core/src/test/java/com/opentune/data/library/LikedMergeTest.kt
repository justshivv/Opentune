package com.opentune.data.library

import com.opentune.data.model.LikeStatus
import com.opentune.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LikedMergeTest {
    private fun song(id: String) = Song(id, "Song $id", "Artist", null)
    private fun local(vararg ids: String) = ids.map { SongRef.of(song(it)) }

    @Test fun firstSignInUploadsOnlyMissingLikesAndShowsOneMergedList() = runBlocking {
        val writes = mutableListOf<Pair<String, LikeStatus>>()
        val result = mergeLikedSongs(local("offline", "shared", "local:file"),
            listOf(song("remote"), song("shared"), song("remote")), null, emptySet()) { id, status ->
            writes += id to status; true
        }
        assertEquals(listOf("offline" to LikeStatus.LIKE), writes)
        assertEquals(listOf("remote", "offline", "shared", "local:file"), result.songs.map { it.videoId })
        assertEquals(setOf("remote", "offline", "shared"), result.agreed)
    }

    @Test fun failedUploadStaysLocalAndIsRetriedWithoutRepeatingSuccessfulWrites() = runBlocking {
        val first = mergeLikedSongs(local("a", "b"), emptyList(), null, emptySet()) { id, _ -> id == "a" }
        assertEquals(setOf("a"), first.agreed)
        assertEquals(2, first.songs.size)
        val writes = mutableListOf<String>()
        val second = mergeLikedSongs(first.songs, listOf(song("a")), first.agreed, emptySet()) { id, _ -> writes += id; true }
        assertEquals(listOf("b"), writes)
        assertEquals(setOf("a", "b"), second.agreed)
    }

    @Test fun secondSyncDoesNotWriteAgainWhenBothSidesAlreadyMatch() = runBlocking {
        val result = mergeLikedSongs(local("a"), listOf(song("a")), setOf("a"), emptySet()) { _, _ ->
            fail("Already-liked songs must not be uploaded again"); false
        }
        assertEquals(setOf("a"), result.agreed)
    }

    @Test fun pendingUnlikeIsRetriedAndRemoteUnlikeIsAppliedLocally() = runBlocking {
        val first = mergeLikedSongs(local("removedThere"), listOf(song("removedHere")),
            setOf("removedHere", "removedThere"), emptySet()) { _, _ -> false }
        assertTrue(first.songs.isEmpty())
        assertEquals(setOf("removedHere"), first.agreed)
        val writes = mutableListOf<Pair<String, LikeStatus>>()
        val second = mergeLikedSongs(first.songs, listOf(song("removedHere")), first.agreed, emptySet()) { id, status ->
            writes += id to status; true
        }
        assertEquals(listOf("removedHere" to LikeStatus.INDIFFERENT), writes)
        assertTrue(second.agreed.isEmpty())
    }

    @Test fun dislikesAndLocalFilesAreNotSentToYouTube() = runBlocking {
        val result = mergeLikedSongs(local("local:file"), listOf(song("disliked")), null, setOf("disliked")) { _, _ ->
            fail("No upload expected"); false
        }
        assertEquals(listOf("local:file"), result.songs.map { it.videoId })
        assertTrue(result.agreed.isEmpty())
    }

    @Test fun heartTapsDuringSyncArePreserved() {
        val merged = keepLikedEdits(local("a", "b"), local("new", "b"), local("remote", "a", "b"))
        assertEquals(listOf("new", "remote", "b"), merged.map { it.videoId })
    }
}
