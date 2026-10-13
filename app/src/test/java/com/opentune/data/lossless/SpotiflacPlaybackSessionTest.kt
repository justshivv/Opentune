package com.opentune.data.lossless

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SpotiflacPlaybackSessionTest {
    @Test fun communityQualityAndDirectUrlContractWorksAcrossSignedTrackRequests() = runBlocking {
        SpotiflacSession.init(RuntimeEnvironment.getApplication())
        SpotiflacSession.clear()
        assertTrue(SpotiflacSession.save(
            """{"session_id":"test-session","session_secret":"test-secret","expires_at":"2099-01-01T00:00:00Z"}""",
            LosslessRegistry.DEFAULT_VERIFY_BASE))
        val session = SpotiflacSession.current()
        val received = CompletableFuture<List<String>>()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            Thread {
                try {
                    val tracks = mutableListOf<String>()
                    repeat(2) {
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                            val requestLine = reader.readLine()
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = reader.readLine() ?: error("Missing request headers")
                                if (line.isEmpty()) break
                                headers[line.substringBefore(':').lowercase(Locale.ROOT)] = line.substringAfter(':').trim()
                            }
                            // These test track payloads are ASCII; read the complete transmitted body.
                            val body = CharArray(headers.getValue("content-length").toInt())
                            var offset = 0
                            while (offset < body.size) {
                                val count = reader.read(body, offset, body.size - offset)
                                check(count > 0)
                                offset += count
                            }
                            val bytes = String(body).toByteArray(Charsets.UTF_8)
                            val payload = JSONObject(String(body))
                            val track = payload.getString("id")
                            val quality = if (track == "123") "16" else "24"
                            val valid = requestLine == "POST /api/dl HTTP/1.1" && accepts(headers, bytes) &&
                                payload.optString("quality") == quality
                            tracks += track
                            // Real community response shape, from backend/tidal_community.go.
                            val response = if (valid) JSONObject().put("quality", quality)
                                .put("url", "https://cdn.example/$track.flac").put("lyric", "").toString()
                                else "{}"
                            val status = if (valid) "200 OK" else "401 Unauthorized"
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n$response".toByteArray())
                                flush()
                            }
                        }
                    }
                    received.complete(tracks)
                } catch (e: Throwable) { received.completeExceptionally(e) }
            }.apply { isDaemon = true; start() }
            try {
                for (track in listOf("123", "456")) {
                    assertEquals("https://cdn.example/$track.flac", withTimeout(5_000) {
                        SpotiflacGated.tidalUrl("http://127.0.0.1:${server.localPort}/api/dl", track, preferHiRes = track == "456")
                    })
                    assertEquals(session, SpotiflacSession.current())
                    assertTrue(SpotiflacSession.connected.value)
                    assertFalse(SpotiflacSession.needsVerification.value)
                }
                assertEquals(listOf("123", "456"), received.get(5, TimeUnit.SECONDS))
            } finally { SpotiflacSession.clear() }
        }
    }

    /** Test server follows the community contract, independently of the app's signer. */
    private fun accepts(headers: Map<String, String>, body: ByteArray): Boolean {
        val time = headers.getValue("x-sig-timestamp")
        val window = Instant.parse(time).epochSecond / 300
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
        val rolling = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec("test-secret".toByteArray(), "HmacSHA256"))
        }.doFinal("$window:test-session".toByteArray())
        val input = listOf("SPOTIFLAC-HMAC-V1", "POST", "/api/dl", "", hash, time,
            headers.getValue("x-sig-nonce"), "test-session", "4.8.5", "android").joinToString("\n")
        val signature = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(rolling, "HmacSHA256")) }
            .doFinal(input.toByteArray())
        return headers["x-sig-session"] == "test-session" && headers["x-sig-body-sha256"] == hash &&
            headers["x-sig-app-version"] == "4.8.5" && headers["x-sig-platform"] == "android" &&
            headers["x-sig-signature"] == Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }
}
