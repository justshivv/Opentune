package com.opentune.data.lossless

import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogMetadataTest {
    private val song = Song("abcdefghijk", "Uyi Amma", "Amit Trivedi", null)

    @Test fun knownDurationDoesNotFetchMetadata() = runBlocking {
        assertEquals(253_000L, CatalogMetadata.duration(song, 253_000) { error("Unexpected metadata request") })
    }

    @Test fun missingDurationUsesOnlyTheExactVideoInTheWatchQueue() = runBlocking {
        val queue = listOf(song.copy(videoId = "other", durationText = "3:00"), song.copy(durationText = "4:13"))
        assertEquals(253_000L, CatalogMetadata.duration(song, 0) { queue })
        assertEquals(0L, CatalogMetadata.duration(song, 0) { queue.take(1) })
    }

    @Test fun unavailableMetadataFallsBackButCancellationPropagates() = runBlocking {
        assertEquals(0L, CatalogMetadata.duration(song, 0) { throw java.io.IOException() })
        try {
            CatalogMetadata.duration(song, 0) { throw CancellationException() }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
    }
}
