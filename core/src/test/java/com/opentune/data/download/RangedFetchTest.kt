package com.opentune.data.download

import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangedFetchTest {
    private val data = Random(7).nextBytes(5 * 1024 * 1024 + 12_345)
    private val ranges = Collections.synchronizedList(mutableListOf<LongRange>())
    private val dropped = AtomicBoolean(false)

    /** A server that answers Range requests from [data]; the second range is cut short once, as a dropped connection would be. */
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val (a, b) = request.header("Range")!!.removePrefix("bytes=").split('-').map { it.toLong() }
        val end = minOf(b, data.size - 1L)
        ranges += a..end
        val body = data.copyOfRange(a.toInt(), end.toInt() + 1)
        val cut = a == RangedFetch.CHUNK && dropped.compareAndSet(false, true)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
            .header("Content-Range", "bytes $a-$end/${data.size}")
            .body((if (cut) body.copyOf(body.size / 2) else body).toResponseBody())
            .build()
    }.build()

    @Test fun fetchesTheFileInPlayerSizedRanges() = runBlocking {
        val part = File.createTempFile("ranged", ".part")
        var last = 0f
        RangedFetch.fetch(client, "https://media.invalid/audio?clen=${data.size}", emptyMap(), part) { last = it }
        assertArrayEquals(data, part.readBytes())
        // No range larger than the player's, and the cut one was asked for again.
        assertTrue(ranges.all { it.last - it.first + 1 <= RangedFetch.CHUNK })
        assertTrue(ranges.count { it.first == RangedFetch.CHUNK } >= 2)
        assertTrue(last > 0.9f && last <= 1f)
        part.delete()
        Unit
    }

    @Test fun findsTheSizeWhenTheUrlDoesntSay() = runBlocking {
        dropped.set(true)
        val part = File.createTempFile("ranged", ".part")
        RangedFetch.fetch(client, "https://media.invalid/audio", emptyMap(), part) {}
        assertEquals(data.size.toLong(), part.length())
        assertArrayEquals(data, part.readBytes())
        part.delete()
        Unit
    }
    @Test fun aServerWithoutRangesSendsTheWholeFile() = runBlocking {
        // Answers every request, Range or not, with the whole file and a 200, as many add-ons' hosts do.
        val whole = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "audio/flac")
                .body(data.toResponseBody()).build()
        }.build()
        val part = File.createTempFile("ranged", ".part")
        var last = 0f
        RangedFetch.fetch(whole, "https://media.invalid/file.flac", emptyMap(), part) { last = it }
        assertArrayEquals(data, part.readBytes())
        assertTrue(last > 0.9f)
        part.delete()
        Unit
    }

    @Test fun rejectsWrongRangeEvenWhenResponseHasTheExpectedLength() = runBlocking {
        rejectsResponse("bytes 1-16/16", ByteArray(16), "audio/mp4")
    }

    @Test fun rejectsOversizedBodiesBeforeWritingPastTheRange() = runBlocking {
        rejectsResponse("bytes 0-15/16", ByteArray(32), "audio/mp4")
    }

    @Test fun rejectsAnErrorPageReturnedAsPartialContent() = runBlocking {
        rejectsResponse("bytes 0-15/16", ByteArray(16), "text/html")
    }

    private suspend fun rejectsResponse(range: String, bytes: ByteArray, type: String) {
        val bad = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Partial")
                .header("Content-Range", range).header("Content-Type", type)
                .body(bytes.toResponseBody()).build()
        }.build()
        val part = File.createTempFile("wrong-range", ".part")
        try {
            val failure = runCatching { RangedFetch.fetch(bad, "https://media.invalid/audio?clen=16", emptyMap(), part) {} }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(16L, part.length())
        } finally { part.delete() }
    }

}
