package com.opentune.data.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A TV running OpenTune on the same network, as it announced itself. [tvId] ties it to a pairing. */
data class FoundTv(val tvId: String?, val name: String, val host: String, val port: Int)

/** The phone's end of mDNS: the TVs that answer. Started when the remote's picker opens, stopped when it closes. */
class TvDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _found = MutableStateFlow<List<FoundTv>>(emptyList())
    val found: StateFlow<List<FoundTv>> = _found.asStateFlow()
    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { synchronized(this@TvDiscovery) { listener = null } }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { enqueue(info) }
            override fun onServiceLost(info: NsdServiceInfo) { _found.value = _found.value.filterNot { it.name == info.serviceName } }
        }
        listener = l
        runCatching { nsd.discoverServices(RemoteProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { listener = null }
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        pending.clear()
        resolving = false
        _found.value = emptyList()
    }

    @Synchronized private fun enqueue(info: NsdServiceInfo) { pending.addLast(info); next() }

    // Android resolves one service at a time; the rest wait their turn.
    @Synchronized
    private fun next() {
        if (resolving) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { done() }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                // Only the home network: an announcement can say anything.
                if (host != null && HomeNetwork.accepts(host) && serviceInfo.port in 1024..65535) {
                    val tvId = serviceInfo.attributes?.get("tv")?.toString(Charsets.UTF_8)
                    val tv = FoundTv(tvId, serviceInfo.serviceName, host, serviceInfo.port)
                    _found.value = _found.value.filterNot { it.name == tv.name } + tv
                }
                done()
            }
        })
    }

    @Synchronized private fun done() { resolving = false; next() }
}

/** The TV's end of mDNS: announces it, so a paired phone finds it without typing an address. */
class TvAdvertiser(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun start(name: String, port: Int, tvId: String) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = RemoteProtocol.SERVICE_TYPE.trimEnd('.')
            this.port = port
            setAttribute("tv", tvId)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { listener = null }
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }
}
