package com.opentune.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.media3.common.MediaItem
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.download.Downloads
import com.opentune.data.innertube.StreamResolver
import com.opentune.data.local.LocalMusic
import com.opentune.data.radio.Radio
import com.opentune.data.subsonic.Subsonic
import com.opentune.playback.toSong
import com.opentune.playback.trackUri
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/** A device music can be cast to. */
data class CastTarget(
    val id: String,
    val name: String,
    val kind: Kind,
    val host: String,
    val port: Int = 0,
    val model: String? = null,
    val dlna: DlnaDevice? = null,
) {
    enum class Kind { CHROMECAST, DLNA }
}

/**
 * Casting: finding Chromecasts and DLNA renderers on the Wi-Fi, connecting
 * to one, and turning queue items into something it can play. The phone's
 * player keeps running, silently, as the queue's clock; see
 * [CastMirror][com.opentune.playback.CastMirror], which keeps the device in
 * step with it.
 */
object Cast {
    private const val TAG = "Cast"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _devices = MutableStateFlow<List<CastTarget>>(emptyList())
    val devices: StateFlow<List<CastTarget>> = _devices

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** The device being connected to or cast to, and whether it's connected yet. */
    data class Session(val target: CastTarget, val receiver: Receiver?)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Things to tell the person: a device that couldn't be reached, a song it couldn't play. */
    val messages: SharedFlow<String> = _messages

    private var scanJob: Job? = null
    private var connectJob: Job? = null

    /** Looks for devices for a while, adding them to [devices] as they answer. */
    fun scan(context: Context) {
        if (scanJob?.isActive == true) return
        val app = context.applicationContext
        scanJob = scope.launch {
            _scanning.value = true
            try {
                val chromecasts = launch { findChromecasts(app) }
                val renderers = launch(Dispatchers.IO) { findRenderers(app) }
                delay(SCAN_MS)
                chromecasts.cancel()
                renderers.cancel()
            } finally {
                _scanning.value = false
            }
        }
    }

    fun connect(context: Context, target: CastTarget) {
        val app = context.applicationContext
        disconnect()
        _session.value = Session(target, null)
        connectJob = scope.launch {
            val receiver = try {
                withContext(Dispatchers.IO) {
                    CastServer.start(app)
                    when (target.kind) {
                        CastTarget.Kind.CHROMECAST -> Chromecast(target.name, target.host, target.port).also { it.connect() }
                        CastTarget.Kind.DLNA -> target.dlna!!.let { Dlna(it.name, it.transportUrl, it.renderingUrl) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "couldn't connect to ${target.name}", e)
                _messages.tryEmit("Couldn't connect to ${target.name}")
                if (_session.value?.target == target) _session.value = null
                return@launch
            }
            if (_session.value?.target == target) _session.value = Session(target, receiver) else receiver.close()
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        _session.value?.receiver?.close()
        _session.value = null
        CastServer.stop()
    }

    /** Called when the device drops the connection on its own. */
    internal fun lost(receiver: Receiver) {
        if (_session.value?.receiver !== receiver) return
        _messages.tryEmit("Lost the connection to ${receiver.name}")
        disconnect()
    }

    internal fun say(message: String) {
        _messages.tryEmit(message)
    }

    // ---- What to send ---------------------------------------------------------

    private val streams = ConcurrentHashMap<String, Pair<String, String>>()

    /**
     * [item] as the device [receiver] should get it, or null if it can't be
     * cast. Most songs go through [CastServer]; a radio station is handed
     * over as it is.
     */
    suspend fun mediaFor(context: Context, item: MediaItem, receiver: Receiver): CastMedia? = withContext(Dispatchers.IO) {
        val id = item.mediaId
        val song = item.toSong()
        val host = when (receiver) {
            is Chromecast -> receiver.localAddress
            is Dlna -> receiver.localAddress
            else -> null
        } ?: return@withContext null
        val dlna = receiver is Dlna
        val image = song.thumbnailUrl?.takeIf { it.startsWith("http") }
            ?.let { Subsonic.resolveCover(it) ?: it }
        val duration = item.mediaMetadata.durationMs ?: 0L
        fun media(url: String, type: String, live: Boolean = false) =
            CastMedia(id, url, type, song.title, song.artist, song.albumName, image, duration, live)

        if (Radio.isRadio(id)) {
            val url = Radio.streamUrl(id) ?: return@withContext null
            val hls = Radio.station(id)?.hls == true
            return@withContext media(url, if (hls) "application/x-mpegurl" else "audio/mpeg", live = true)
        }
        if (LocalMusic.isLocal(id)) {
            val uri = trackUri(id)
            val type = context.contentResolver.getType(uri) ?: "audio/mpeg"
            return@withContext media(CastServer.publish(CastServer.Source.Content(uri, type), host), type)
        }
        if (Subsonic.isSubsonic(id)) {
            val url = Subsonic.streamUrl(id) ?: return@withContext null
            val type = runCatching {
                Http.client.newCall(Request.Builder().url(url).head().build()).execute().use { it.header("Content-Type") }
            }.getOrNull()?.substringBefore(';')?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
            return@withContext media(CastServer.publish(CastServer.Source.Web(type) { url to emptyMap() }, host), type)
        }
        // A download plays from the phone, if the device can take its format.
        Downloads.fileFor(id)?.let { file ->
            val type = typeOf(file.extension)
            if (type != null && !(dlna && type == "audio/webm")) {
                return@withContext media(CastServer.publish(CastServer.Source.OnPhone(file, type), host), type)
            }
        }
        // A YouTube song: AAC in MP4, which every device plays, through the phone.
        val type = runCatching { youtube(id, fresh = false).second }.getOrElse {
            Log.w(TAG, "no stream for $id", it)
            return@withContext null
        }
        media(CastServer.publish(CastServer.Source.Web(type) { fresh -> youtube(id, fresh).first.let { it to StreamResolver.mediaHeadersFor(it) } }, host), type)
    }

    /** A YouTube song's stream address and type, preferring AAC; held until it stops working. */
    private suspend fun youtube(id: String, fresh: Boolean): Pair<String, String> {
        if (!fresh) streams[id]?.let { return it }
        val found = runCatching { StreamResolver.resolveForDownload(id, maxKbps = 320, requireM4a = true).url to "audio/mp4" }
            .getOrElse { StreamResolver.resolve(id) to "audio/webm" }
        streams[id] = found
        return found
    }

    private fun typeOf(ext: String): String? = when (ext.lowercase()) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "webm", "opus" -> "audio/webm"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        else -> null
    }

    // ---- Finding devices --------------------------------------------------------

    private fun add(target: CastTarget) {
        _devices.update { list -> (list.filterNot { it.id == target.id } + target).sortedBy { it.name.lowercase() } }
    }

    /** Chromecasts announce themselves over mDNS as `_googlecast._tcp`. */
    private suspend fun findChromecasts(context: Context) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val resolving = Mutex()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                scope.launch { resolving.withLock { resolve(nsd, info)?.let(::chromecastFrom)?.let(::add) } }
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Log.w(TAG, "mDNS search failed: $errorCode")
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        runCatching { nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { return }
        try {
            delay(SCAN_MS)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(4_000) {
        suspendCancellableCoroutine { cont ->
            val ok = runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) { if (cont.isActive) cont.resume(serviceInfo) }
                })
            }.isSuccess
            if (!ok && cont.isActive) cont.resume(null)
        }
    }

    @Suppress("DEPRECATION")
    private fun chromecastFrom(info: NsdServiceInfo): CastTarget? {
        val host = info.host?.hostAddress ?: return null
        fun txt(key: String) = info.attributes[key]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        val name = txt("fn") ?: info.serviceName
        return CastTarget(txt("id") ?: info.serviceName, name, CastTarget.Kind.CHROMECAST, host, info.port.takeIf { it > 0 } ?: 8009, txt("md"))
    }

    /** DLNA renderers answer an SSDP search with where their description is. */
    private suspend fun findRenderers(context: Context) {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("opentune-cast")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val seen = HashSet<String>()
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = 500
                val search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                val buffer = ByteArray(4096)
                val end = System.currentTimeMillis() + SCAN_MS
                var sent = 0
                while (System.currentTimeMillis() < end) {
                    if (sent < 3) {
                        runCatching { socket.send(DatagramPacket(search, search.size, group, 1900)) }
                        sent++
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    val reply = runCatching { socket.receive(packet); String(packet.data, 0, packet.length) }.getOrNull() ?: continue
                    val location = DlnaDescription.location(reply) ?: continue
                    if (!seen.add(location)) continue
                    scope.launch(Dispatchers.IO) { describe(location)?.let(::add) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP search failed", e)
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun describe(location: String): CastTarget? = runCatching {
        val xml = Http.client.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string().orEmpty() }
        val device = DlnaDescription.parse(xml, location) ?: return null
        CastTarget(device.id, device.name, CastTarget.Kind.DLNA, java.net.URI(location).host, dlna = device)
    }.getOrNull()

    private const val SCAN_MS = 8_000L
}
