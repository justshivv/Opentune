package com.opentune.data.lossless

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LosslessHttpTest {
    @Test fun rangeProbePreservesFullSizeWithoutReadingFullFile() = runBlocking {
        server { socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 206 Partial Content\r\nContent-Length: 64\r\nContent-Range: bytes 0-63/9142812\r\nConnection: close\r\n\r\n".toByteArray())
                write(ByteArray(64).apply { "ftyp".toByteArray().copyInto(this, 4) })
                flush()
            }
        }.use { server ->
            val data = withTimeout(3_000) { LosslessHttp.read(request(server), 64, prefixOnly = true) }
            assertEquals(64, data.bytes.size)
            assertEquals(9_142_812L, data.totalBytes)
        }
    }

    @Test fun rejectsOversizedCatalogResponse() = runBlocking {
        server { socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 2048\r\nConnection: close\r\n\r\n".toByteArray())
                write(ByteArray(2048)); flush()
            }
        }.use { server ->
            try {
                LosslessHttp.read(request(server), 1024)
                fail("Oversized response must fail")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("too large"))
            }
        }
    }

    @Test fun cancellingLookupClosesStalledResponseSocket() = runBlocking {
        val started = CountDownLatch(1)
        val closed = CompletableFuture<Boolean>()
        server { socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\na".toByteArray()); flush()
            }
            started.countDown()
            closed.complete(try { socket.getInputStream().read() == -1 } catch (_: IOException) { true })
        }.use { server ->
            val lookup = async(Dispatchers.IO) { LosslessHttp.read(request(server), 1000) }
            assertTrue("Response did not start", started.await(3, TimeUnit.SECONDS))
            lookup.cancelAndJoin()
            assertTrue("Cancellation left the socket open", closed.get(3, TimeUnit.SECONDS))
        }
    }

    private fun request(server: ServerSocket) = Request.Builder().url("http://127.0.0.1:${server.localPort}/audio").build()

    private fun server(respond: (Socket) -> Unit): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        Thread {
            server.accept().use { socket ->
                socket.soTimeout = 4_000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                respond(socket)
            }
        }.apply { isDaemon = true; start() }
        return server
    }
}
