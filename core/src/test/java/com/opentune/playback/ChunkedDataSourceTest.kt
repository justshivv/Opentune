package com.opentune.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChunkedDataSourceTest {
    /** A server holding [file]; [ranges] false answers every request with the rest of the file and no Content-Range. */
    private class Server(val file: ByteArray, val ranges: Boolean = true) : DataSource {
        val asked = mutableListOf<Pair<Long, Long>>()
        private var at = 0
        private var stop = 0
        private var headers = emptyMap<String, List<String>>()
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            at = dataSpec.position.toInt()
            stop = if (ranges && dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(file.size, at + dataSpec.length.toInt()) else file.size
            asked += dataSpec.position to dataSpec.length
            headers = if (ranges) mapOf("Content-Range" to listOf("bytes $at-${stop - 1}/${file.size}")) else emptyMap()
            return (stop - at).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at >= stop) return C.RESULT_END_OF_INPUT
            val n = minOf(length, stop - at)
            System.arraycopy(file, at, buffer, offset, n)
            at += n
            return n
        }
        override fun getUri(): Uri = Uri.parse("https://cdn.example/song.flac")
        override fun getResponseHeaders() = headers
        override fun close() = Unit
    }

    private val file = ByteArray(10_000) { (it % 251).toByte() }

    private fun readAll(source: DataSource, spec: DataSpec, limit: Int = Int.MAX_VALUE): ByteArray {
        val length = source.open(spec)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(700)
        while (out.size() < limit) {
            val n = source.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        source.close()
        if (limit == Int.MAX_VALUE) assertEquals(length, out.size().toLong())
        return out.toByteArray()
    }

    @Test fun theFileComesInPiecesEachAskedForOnlyWhenReached() {
        val server = Server(file)
        val pieces = ChunkedDataSource(server, chunkBytes = 3_000) { true }
        assertArrayEquals(file, readAll(pieces, DataSpec(Uri.parse("opentune://track/x"))))
        assertEquals(listOf(0L to 3_000L, 3_000L to 3_000L, 6_000L to 3_000L, 9_000L to 1_000L), server.asked)
    }

    @Test fun stoppingPartwayAsksForNothingPastThePieceBeingRead() {
        val server = Server(file)
        val pieces = ChunkedDataSource(server, chunkBytes = 3_000) { true }
        readAll(pieces, DataSpec(Uri.parse("opentune://track/x")), limit = 4_000)
        assertEquals(listOf(0L to 3_000L, 3_000L to 3_000L), server.asked)
    }

    @Test fun aSeekStartsFromThereAndAKnownLengthIsKept() {
        val server = Server(file)
        val pieces = ChunkedDataSource(server, chunkBytes = 3_000) { true }
        val got = readAll(pieces, DataSpec.Builder().setUri("opentune://track/x").setPosition(5_000).setLength(4_500).build())
        assertArrayEquals(file.copyOfRange(5_000, 9_500), got)
        assertEquals(listOf(5_000L to 3_000L, 8_000L to 1_500L), server.asked)
    }

    @Test fun aServerThatIgnoresRangesIsReadStraightThrough() {
        val server = Server(file, ranges = false)
        val pieces = ChunkedDataSource(server, chunkBytes = 3_000) { true }
        assertArrayEquals(file, readAll(pieces, DataSpec(Uri.parse("opentune://track/x"))))
        assertEquals(1, server.asked.size)
    }

    @Test fun requestsNotChosenGoStraightThrough() {
        val server = Server(file)
        val pieces = ChunkedDataSource(server, chunkBytes = 3_000) { false }
        assertArrayEquals(file, readAll(pieces, DataSpec(Uri.parse("opentune://track/x"))))
        assertEquals(listOf(0L to C.LENGTH_UNSET.toLong()), server.asked)
    }
}
