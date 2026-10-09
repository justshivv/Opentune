package com.opentune.cast

import android.os.SystemClock
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A DLNA (UPnP) media renderer: most smart TVs, many network speakers and
 * players like Kodi or VLC. It's told what to play with SOAP calls to its
 * AVTransport service, and asked once a second how far it's got.
 */
class Dlna(
    override val name: String,
    private val transportUrl: String,
    private val renderingUrl: String?,
) : Receiver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(RemoteStatus())
    override val status: StateFlow<RemoteStatus> = _status
    @Volatile private var loadedId: String? = null
    @Volatile private var played = false
    /** Until when the renderer's reports are ignored, while it takes in a command. */
    @Volatile private var quietUntil = 0L

    /** The phone's address on the renderer's network. */
    val localAddress: InetAddress? by lazy {
        runCatching {
            val uri = URI(transportUrl)
            DatagramSocket().use { it.connect(InetAddress.getByName(uri.host), if (uri.port > 0) uri.port else 80); it.localAddress }
        }.getOrNull()
    }

    init {
        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                if (loadedId != null) runCatching { poll() }
            }
        }
    }

    override suspend fun load(media: CastMedia, positionMs: Long, play: Boolean) = withContext(Dispatchers.IO) {
        loadedId = media.id
        played = false
        quietUntil = SystemClock.elapsedRealtime() + QUIET_MS
        _status.value = RemoteStatus(RemoteState.LOADING, positionMs, SystemClock.elapsedRealtime(), _status.value.volume, media.id)
        runCatching { transport("Stop") }
        try {
            transport("SetAVTransportURI", "<CurrentURI>${xml(media.url)}</CurrentURI><CurrentURIMetaData>${xml(didl(media))}</CurrentURIMetaData>")
            transport("Play", "<Speed>1</Speed>")
            if (positionMs > SEEK_MIN_MS) {
                // Most renderers only take a seek once they've started.
                delay(1_500)
                runCatching { transport("Seek", "<Unit>REL_TIME</Unit><Target>${clock(positionMs)}</Target>") }
            }
            if (!play) transport("Pause")
        } catch (e: Exception) {
            Log.w(TAG, "$name wouldn't play ${media.title}", e)
            _status.value = _status.value.copy(state = RemoteState.ERROR, at = SystemClock.elapsedRealtime())
        }
    }

    override suspend fun play() = command("Play", "<Speed>1</Speed>")
    override suspend fun pause() = command("Pause")
    override suspend fun seek(positionMs: Long) = command("Seek", "<Unit>REL_TIME</Unit><Target>${clock(positionMs)}</Target>")

    override suspend fun setVolume(level: Float) {
        val url = renderingUrl ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                soap(url, RENDERING, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${(level.coerceIn(0f, 1f) * 100).toInt()}</DesiredVolume>")
                _status.value = _status.value.copy(volume = level)
            }
        }
    }

    override fun close() {
        scope.launch {
            runCatching { transport("Stop") }
            scope.cancel()
        }
    }

    private suspend fun command(action: String, args: String = "") = withContext(Dispatchers.IO) {
        quietUntil = SystemClock.elapsedRealtime() + QUIET_MS
        runCatching { transport(action, args) }.onFailure { Log.w(TAG, "$action on $name failed", it) }
        Unit
    }

    private fun poll() {
        val info = transport("GetTransportInfo")
        val position = transport("GetPositionInfo")
        if (SystemClock.elapsedRealtime() < quietUntil) return
        val at = SystemClock.elapsedRealtime()
        val ms = parseClock(tag(position, "RelTime")) ?: _status.value.positionMs
        val state = when (tag(info, "CurrentTransportState")) {
            "PLAYING" -> RemoteState.PLAYING.also { played = true }
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> RemoteState.PAUSED
            "TRANSITIONING" -> RemoteState.BUFFERING
            // Stopped after playing is the end of the song; before, it's still taking it in.
            "STOPPED", "NO_MEDIA_PRESENT" -> if (played) RemoteState.FINISHED else RemoteState.BUFFERING
            else -> _status.value.state
        }
        val volume = renderingUrl?.let { url ->
            runCatching { tag(soap(url, RENDERING, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentVolume")?.toFloatOrNull()?.div(100f) }.getOrNull()
        } ?: _status.value.volume
        _status.value = RemoteStatus(state, ms, at, volume, loadedId)
    }

    private fun transport(action: String, args: String = ""): String =
        soap(transportUrl, TRANSPORT, action, "<InstanceID>0</InstanceID>$args")

    private fun soap(url: String, service: String, action: String, args: String): String {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:$action xmlns:u="$service">$args</u:$action></s:Body></s:Envelope>"""
        val request = Request.Builder()
            .url(url)
            .header("SOAPACTION", "\"$service#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        Http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { "$action: HTTP ${response.code}" }
            return text
        }
    }

    companion object {
        private const val TAG = "Dlna"
        private const val TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RENDERING = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val POLL_MS = 1_000L
        private const val QUIET_MS = 2_500L
        private const val SEEK_MIN_MS = 3_000L

        /** "0:03:25" for 205 s, the way UPnP writes times. */
        fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        }

        fun parseClock(text: String?): Long? {
            val parts = text?.substringBefore('.')?.split(':')?.mapNotNull { it.trim().toLongOrNull() } ?: return null
            if (parts.size != 3) return null
            return ((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1000
        }

        /** The text of the first <[name]> element, ignoring any namespace prefix. */
        fun tag(xml: String, name: String): String? =
            Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

        fun xml(text: String): String = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

        /** The DIDL-Lite description renderers show while a song plays. */
        fun didl(media: CastMedia): String {
            val info = "http-get:*:${media.contentType}:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
                """<item id="0" parentID="-1" restricted="1">""" +
                "<dc:title>${xml(media.title)}</dc:title>" +
                "<dc:creator>${xml(media.artist)}</dc:creator>" +
                "<upnp:artist>${xml(media.artist)}</upnp:artist>" +
                (media.album?.let { "<upnp:album>${xml(it)}</upnp:album>" } ?: "") +
                (media.imageUrl?.let { "<upnp:albumArtURI>${xml(it)}</upnp:albumArtURI>" } ?: "") +
                "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
                "<res protocolInfo=\"${xml(info)}\"${if (media.durationMs > 0) " duration=\"${clock(media.durationMs)}.000\"" else ""}>${xml(media.url)}</res>" +
                "</item></DIDL-Lite>"
        }
    }
}

/** A renderer found on the network, from its description: its name and where its services listen. */
data class DlnaDevice(val name: String, val transportUrl: String, val renderingUrl: String?, val id: String)

internal object DlnaDescription {
    /** The LOCATION of a reply to an SSDP search, if it's one. */
    fun location(reply: String): String? =
        reply.lineSequence().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }?.substringAfter(':')?.trim()

    /** Reads a renderer's device description, fetched from [location]. Null if it has no AVTransport. */
    fun parse(xml: String, location: String): DlnaDevice? {
        val name = Dlna.tag(xml, "friendlyName")?.let(::unescape) ?: return null
        val base = Dlna.tag(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        var transport: String? = null
        var rendering: String? = null
        Regex("<(?:\\w+:)?service>(.*?)</(?:\\w+:)?service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            val service = m.groupValues[1]
            val type = Dlna.tag(service, "serviceType").orEmpty()
            val control = Dlna.tag(service, "controlURL") ?: return@forEach
            val url = runCatching { URI(base).resolve(control.trim()).toString() }.getOrNull() ?: return@forEach
            when {
                "AVTransport" in type && transport == null -> transport = url
                "RenderingControl" in type && rendering == null -> rendering = url
            }
        }
        val id = Dlna.tag(xml, "UDN") ?: location
        return transport?.let { DlnaDevice(name, it, rendering, id) }
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
