package com.opentune.cast

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CastTest {
    @Test fun castMessagesRoundTrip() {
        val payload = """{"type":"PING","note":"héllo"}"""
        val bytes = CastFrames.encode("sender-0", "receiver-0", "urn:x-cast:com.google.cast.tp.heartbeat", payload)
        // Field 1 (version) is a zero varint, then field 2 is a length-delimited string.
        assertEquals(0x08, bytes[0].toInt())
        assertEquals(0x12, bytes[2].toInt())
        val frame = CastFrames.decode(bytes)
        assertEquals("sender-0", frame.source)
        assertEquals("receiver-0", frame.destination)
        assertEquals("urn:x-cast:com.google.cast.tp.heartbeat", frame.namespace)
        assertEquals(payload, frame.payload)
        // A payload past 127 bytes takes a two-byte length.
        val long = "x".repeat(300)
        assertEquals(long, CastFrames.decode(CastFrames.encode("a", "b", "c", long)).payload)
    }

    @Test fun readsByteRanges() {
        assertNull(Ranges.parse(null, 100))
        assertEquals(10L to 99L, Ranges.parse("bytes=10-", 100))
        assertEquals(10L to 19L, Ranges.parse("bytes=10-19", 100))
        assertEquals(90L to 99L, Ranges.parse("bytes=-10", 100))
        assertEquals(0L to 99L, Ranges.parse("bytes=0-500", 100))
        assertNull(Ranges.parse("bytes=200-", 100))
    }

    @Test fun upnpTimesAndTags() {
        assertEquals("0:03:25", Dlna.clock(205_000))
        assertEquals("1:00:00", Dlna.clock(3_600_000))
        assertEquals(205_000L, Dlna.parseClock("0:03:25"))
        assertEquals(205_000L, Dlna.parseClock("00:03:25.500"))
        assertNull(Dlna.parseClock("NOT_IMPLEMENTED"))
        assertEquals("PLAYING", Dlna.tag("<s:Body><u:R><CurrentTransportState>PLAYING</CurrentTransportState></u:R></s:Body>", "CurrentTransportState"))
        val didl = Dlna.didl(CastMedia("id", "http://1.2.3.4:5/s/a.m4a", "audio/mp4", "Tom & Jerry", "A <B>", null, null, 200_000))
        assertTrue("Tom &amp; Jerry" in didl)
        assertTrue("A &lt;B&gt;" in didl)
        assertTrue("http-get:*:audio/mp4:" in didl)
        assertTrue("duration=\"0:03:20.000\"" in didl)
    }

    @Test fun readsRendererDescriptions() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.20:1400/xml/device.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://192.168.1.20:1400/xml/device.xml", DlnaDescription.location(reply))
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <friendlyName>Living Room TV &amp; Sound</friendlyName><UDN>uuid:1234</UDN>
              <serviceList>
                <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/cm</controlURL></service>
                <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/MediaRenderer/AVTransport/Control</controlURL></service>
                <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>rc</controlURL></service>
              </serviceList>
            </device></root>
        """.trimIndent()
        val device = DlnaDescription.parse(xml, "http://192.168.1.20:1400/xml/device.xml")
        assertNotNull(device)
        assertEquals("Living Room TV & Sound", device!!.name)
        assertEquals("http://192.168.1.20:1400/MediaRenderer/AVTransport/Control", device.transportUrl)
        assertEquals("http://192.168.1.20:1400/xml/rc", device.renderingUrl)
        assertEquals("uuid:1234", device.id)
        assertNull(DlnaDescription.parse("<root><device><friendlyName>Printer</friendlyName></device></root>", "http://x/"))
    }

    @Test fun servesFilesWithRanges() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        CastServer.start(context)
        try {
            val file = File.createTempFile("song", ".m4a").apply { writeBytes(ByteArray(1000) { (it % 251).toByte() }); deleteOnExit() }
            val url = CastServer.publish(CastServer.Source.OnPhone(file, "audio/mp4"), InetAddress.getLoopbackAddress())
            assertTrue(url.endsWith(".m4a"))
            val http = OkHttpClient()
            http.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("audio/mp4", it.header("Content-Type"))
                assertEquals("Streaming", it.header("transferMode.dlna.org"))
                assertEquals(1000, it.body!!.bytes().size)
            }
            http.newCall(Request.Builder().url(url).header("Range", "bytes=100-199").build()).execute().use {
                assertEquals(206, it.code)
                assertEquals("bytes 100-199/1000", it.header("Content-Range"))
                val bytes = it.body!!.bytes()
                assertEquals(100, bytes.size)
                assertEquals((100 % 251).toByte(), bytes[0])
            }
            http.newCall(Request.Builder().url(url.replaceAfterLast('/', "nothing.m4a")).build()).execute().use { assertEquals(404, it.code) }
        } finally {
            CastServer.stop()
        }
    }

    /** A pretend renderer: answers SOAP calls and records which ones came. */
    @Test fun drivesARenderer() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use { sock ->
                        val input = sock.getInputStream().bufferedReader()
                        var length = 0
                        var action = ""
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length", true)) length = line.substringAfter(':').trim().toInt()
                            if (line.startsWith("SOAPACTION", true)) action = line.substringAfter('#').trim('"', ' ')
                        }
                        val body = CharArray(length).also { var read = 0; while (read < length) { val n = input.read(it, read, length - read); if (n < 0) break; read += n } }
                        calls += action + if (action == "SetAVTransportURI") ":" + Regex("<CurrentURI>(.*?)</CurrentURI>").find(String(body))?.groupValues?.get(1) else ""
                        val answer = when (action) {
                            "GetTransportInfo" -> "<CurrentTransportState>PLAYING</CurrentTransportState>"
                            "GetPositionInfo" -> "<RelTime>0:00:42</RelTime>"
                            "GetVolume" -> "<CurrentVolume>30</CurrentVolume>"
                            else -> ""
                        }
                        val xml = "<s:Envelope><s:Body><u:${action}Response>$answer</u:${action}Response></s:Body></s:Envelope>"
                        sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${xml.length}\r\nConnection: close\r\n\r\n$xml".toByteArray())
                    }
                }
            }
        }
        val base = "http://127.0.0.1:${server.localPort}"
        val tv = Dlna("TV", "$base/avt", "$base/rc")
        try {
            runBlocking {
                tv.load(CastMedia("song", "http://phone/s/a.m4a", "audio/mp4", "T", "A", null, null, 0), 0, play = true)
                tv.setVolume(0.5f)
            }
            assertEquals(listOf("Stop", "SetAVTransportURI:http://phone/s/a.m4a", "Play", "SetVolume"), calls.toList().take(4))
            // Its reports come in once it's past the quiet spell after a command.
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3))
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline && tv.status.value.state != RemoteState.PLAYING) Thread.sleep(100)
            assertEquals(RemoteState.PLAYING, tv.status.value.state)
            assertEquals(42_000L, tv.status.value.positionMs)
            assertEquals(0.3f, tv.status.value.volume!!, 0.001f)
            assertEquals("song", tv.status.value.mediaId)
        } finally {
            tv.close()
            server.close()
        }
    }

    /** A pretend Chromecast: launches the media app, loads, plays and reports, as the real one does. */
    @Test fun drivesAChromecast() {
        val server = ServerSocket(0)
        val seen = Collections.synchronizedList(mutableListOf<String>())
        thread(isDaemon = true) {
            val sock = server.accept()
            val input = java.io.DataInputStream(sock.getInputStream())
            val output = java.io.DataOutputStream(sock.getOutputStream())
            fun reply(to: CastFrames.Frame, ns: String, json: org.json.JSONObject) {
                val bytes = CastFrames.encode(to.destination, to.source, ns, json.toString())
                synchronized(output) { output.writeInt(bytes.size); output.write(bytes); output.flush() }
            }
            val app = org.json.JSONObject().put("appId", "CC1AD845").put("transportId", "web-5").put("sessionId", "s-1")
            var position = 12.0
            runCatching {
                while (true) {
                    val bytes = ByteArray(input.readInt()).also(input::readFully)
                    val frame = CastFrames.decode(bytes)
                    val json = org.json.JSONObject(frame.payload!!)
                    val type = json.getString("type")
                    seen += "${frame.destination}:$type"
                    val id = json.optInt("requestId")
                    when (type) {
                        "LAUNCH", "GET_STATUS" -> if (frame.namespace.endsWith("receiver")) reply(frame, frame.namespace, org.json.JSONObject().put("type", "RECEIVER_STATUS").put("requestId", id)
                            .put("status", org.json.JSONObject().put("applications", org.json.JSONArray().put(app)).put("volume", org.json.JSONObject().put("level", 0.4))))
                        else reply(frame, frame.namespace, mediaStatus(id, "PLAYING", position))
                        "LOAD" -> {
                            position = json.getDouble("currentTime")
                            reply(frame, frame.namespace, mediaStatus(id, "PLAYING", position))
                        }
                        "PAUSE" -> reply(frame, frame.namespace, mediaStatus(id, "PAUSED", position))
                    }
                }
            }
        }
        val cast = Chromecast("Kitchen", "127.0.0.1", server.localPort, plain = true)
        try {
            runBlocking {
                cast.connect()
                cast.load(CastMedia("song", "http://phone/s/a.m4a", "audio/mp4", "T", "A", null, "http://img/x.jpg", 200_000), 30_000, play = true)
            }
            assertEquals(RemoteState.PLAYING, cast.status.value.state)
            assertEquals(30_000L, cast.status.value.positionMs)
            assertEquals("song", cast.status.value.mediaId)
            runBlocking { cast.pause() }
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && cast.status.value.state != RemoteState.PAUSED) Thread.sleep(50)
            assertEquals(RemoteState.PAUSED, cast.status.value.state)
            val order = seen.toList()
            assertEquals("receiver-0:CONNECT", order[0])
            assertTrue(order.indexOf("receiver-0:LAUNCH") < order.indexOf("web-5:CONNECT"))
            assertTrue(order.indexOf("web-5:CONNECT") < order.indexOf("web-5:LOAD"))
            assertTrue("web-5:PAUSE" in order)
        } finally {
            cast.close()
            server.close()
        }
    }

    private fun mediaStatus(requestId: Int, state: String, position: Double) = org.json.JSONObject()
        .put("type", "MEDIA_STATUS")
        .put("requestId", requestId)
        .put("status", org.json.JSONArray().put(org.json.JSONObject().put("mediaSessionId", 7).put("playerState", state).put("currentTime", position)))
}
