package com.opentune.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Reads a stream as a run of range requests of [chunkBytes] each, the next one asked for only
 * when the player has read to the end of the one before. A single open-ended request lets the
 * server send the whole file as fast as the connection allows, so a song skipped halfway had
 * often come down whole already; this way at most a piece past what the player wanted is
 * fetched. Requests [chunked] turns down, short ones, and servers that ignore the range (no
 * Content-Range in the answer) are read straight through as before.
 */
@UnstableApi
internal class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long = CHUNK_BYTES,
    private val chunked: (DataSpec) -> Boolean,
) : DataSource {
    private var spec: DataSpec? = null
    /** The next byte to read, from the start of the file. */
    private var next = 0L
    /** Where the request ends, from the start of the file. */
    private var end = 0L
    /** Bytes still to read from the piece that's open. */
    private var left = 0L
    private var straight = false
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val unset = C.LENGTH_UNSET.toLong()
        if (!chunked(dataSpec) || (dataSpec.length != unset && dataSpec.length <= chunkBytes)) {
            straight = true
            opened = true
            return upstream.open(dataSpec)
        }
        straight = false
        spec = dataSpec
        next = dataSpec.position
        end = if (dataSpec.length == unset) Long.MAX_VALUE else dataSpec.position + dataSpec.length
        val got = openPiece()
        val total = totalLength(upstream.responseHeaders)
        if (total == null) {
            // The server sent the rest of the file regardless (or didn't say how long it is):
            // carry on reading that one answer.
            straight = true
            return got
        }
        if (end == Long.MAX_VALUE || end > total) end = total
        return end - dataSpec.position
    }

    private fun openPiece(): Long {
        val s = checkNotNull(spec)
        val want = minOf(chunkBytes, end - next)
        val got = upstream.open(s.subrange(next - s.position, want))
        opened = true
        left = if (got == C.LENGTH_UNSET.toLong()) want else minOf(got, want)
        return got
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (straight) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (next >= end) return C.RESULT_END_OF_INPUT
        if (left <= 0L) nextPiece() ?: return C.RESULT_END_OF_INPUT
        var n = upstream.read(buffer, offset, minOf(length.toLong(), left).toInt())
        if (n == C.RESULT_END_OF_INPUT) {
            // The piece came up short; the rest is asked for from where it stopped.
            if (next >= end) return C.RESULT_END_OF_INPUT
            nextPiece() ?: return C.RESULT_END_OF_INPUT
            n = upstream.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (n == C.RESULT_END_OF_INPUT) return n
        }
        next += n
        left -= n
        return n
    }

    /** Opens the piece from [next]; null when the server has nothing more to give. */
    private fun nextPiece(): Unit? {
        upstream.close()
        opened = false
        val got = openPiece()
        return if (got == 0L || left <= 0L) null else Unit
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null
        straight = false
        if (opened) {
            opened = false
            upstream.close()
        }
    }

    companion object {
        /** Two megabytes: seconds of a hi-res FLAC, a good part of a minute of a CD-quality one. */
        const val CHUNK_BYTES = 2L * 1024 * 1024

        /** The file's whole length from a "bytes a-b/total" Content-Range, if the answer has one. */
        internal fun totalLength(headers: Map<String, List<String>>): Long? {
            val value = headers.entries.firstOrNull { it.key?.equals("Content-Range", ignoreCase = true) == true }?.value?.firstOrNull() ?: return null
            return value.substringAfterLast('/', "").trim().toLongOrNull()
        }
    }
}
