package com.opentune.data.lossless

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LosslessLookupTest {
    private val flac = LosslessSource.Track("https://cdn.example/full", "SpotiFLAC", 16, 44_100)

    @Test fun verifiedRouteIsNotBlockedByAStalledPublicProvider() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val found = withTimeout(2_000) {
            LosslessSource.searchSources(false, providers = listOf(
                { try { awaitCancellation() } finally { cancelled.complete(Unit) } },
                { record -> record(flac) },
            ))
        }
        assertEquals(flac, found)
        assertTrue(cancelled.isCompleted)
    }

    @Test fun maximumKeepsVerifiedCandidateWhenAnotherRouteUsesTheBudget() = runBlocking {
        val highest = flac.copy(bits = 24, sampleRate = 96_000)
        val result = LosslessSource.searchSources(true, timeoutMs = 250, providers = listOf(
            { record -> record(flac); awaitCancellation() },
            { record -> record(highest) },
            { throw java.io.IOException("Mapping service unavailable") },
        ))
        assertEquals(highest, result)
    }

    @Test fun cancellingPlaybackCancelsEveryLookup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val task = async {
            LosslessSource.searchSources(true, providers = listOf({
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }))
        }
        started.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(cancelled.isCompleted)
    }

    @Test fun catalogLookupSurvivesMappingOutagesAndKeepsKnownLinkedIds() = runBlocking {
        assertEquals("123", TidalCatalog.withLinkedFallback(CompletableDeferred<String?>(value = null)) { "123" })
        assertEquals("456", TidalCatalog.withLinkedFallback(CompletableDeferred("456")) { error("Unnecessary search") })
        val linked = CompletableDeferred<String?>()
        assertEquals("789", TidalCatalog.withLinkedFallback(linked) { linked.complete("789"); null })
    }

    @Test fun directTidalCatalogRequiresTheSameRecordingAndAValidId() {
        val identity = CatalogIdentity("Blinding Lights", "The Weeknd", "After Hours", 200_000)
        fun response(title: String = "Blinding Lights", artist: String = "The Weeknd", duration: Int = 200,
            id: String = "123456", version: String = ""): JSONObject = JSONObject().put("data", JSONObject().put("items",
                org.json.JSONArray().put(JSONObject().put("id", id).put("title", title).put("version", version)
                    .put("artist", JSONObject().put("name", artist)).put("album", JSONObject().put("title", "After Hours"))
                    .put("duration", duration))))
        assertEquals("123456", TidalCatalog.match(response(), identity))
        assertNull(TidalCatalog.match(response(artist = "Cover Band"), identity))
        assertNull(TidalCatalog.match(response(duration = 30), identity))
        assertNull(TidalCatalog.match(response(version = "Live"), identity))
        assertNull(TidalCatalog.match(response(id = "../123"), identity))
        assertNull(TidalCatalog.match(JSONObject("{}"), identity))
    }

    @Test fun unavailableCatalogIsNotReportedAsAConfirmedMissingRecording() = runBlocking {
        val identity = CatalogIdentity("Blinding Lights", "The Weeknd", "After Hours", 200_000)
        val endpoints = listOf("https://first.example", "https://second.example")
        val empty = JSONObject("""{"data":{"items":[]}}""")
        assertNull(TidalCatalog.findIn(identity, endpoints) { empty })
        try {
            TidalCatalog.findIn(identity, endpoints) { url ->
                if (url.contains("first.example")) throw LosslessHttp.HttpFailure(503)
                empty
            }
            fail("An outage must leave the search inconclusive")
        } catch (_: TidalCatalog.Unavailable) { }
        try {
            TidalCatalog.findIn(identity, endpoints) { JSONObject("""{"error":"unavailable"}""") }
            fail("An error payload is not a successful empty catalog")
        } catch (_: TidalCatalog.Unavailable) { }
        assertEquals("123", TidalCatalog.withLinkedFallback(CompletableDeferred<String?>().apply { complete("123") }) {
            throw TidalCatalog.Unavailable()
        })
        val lateLinked = CompletableDeferred<String?>()
        assertEquals("456", TidalCatalog.withLinkedFallback(lateLinked) {
            lateLinked.complete("456")
            throw TidalCatalog.Unavailable()
        })
    }
}
