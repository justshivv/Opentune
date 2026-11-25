package com.opentune.ui.remote

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.data.remote.FoundTv
import com.opentune.data.remote.PairedTv
import com.opentune.data.remote.PairedTvs
import com.opentune.data.remote.PrefsStorage
import com.opentune.data.remote.RemoteClient
import com.opentune.data.remote.RemoteInvite
import com.opentune.data.remote.RemoteMessage
import com.opentune.data.remote.TvDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A TV's pair-a-phone code read somewhere other than the remote's own scanner (Link a device's
 * camera, or the phone's camera app), waiting for the remote's page to pair with it.
 */
object RemoteCodes {
    val pending = MutableStateFlow<String?>(null)
}

/**
 * The phone's side of the TV remote: the TVs it has paired with, the ones it can see on this
 * network, and the connection to one of them while the remote is open.
 */
class TvRemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val tvs = PairedTvs(PrefsStorage(app, "remote_tvs"))
    private val discovery = TvDiscovery(app)
    // Held to the phone's Wi-Fi, so mobile data or a VPN doesn't carry what's meant for the TV.
    private val client = RemoteClient(com.opentune.data.remote.LanNetworks.sockets(app)) { phoneName(app) }

    val paired: StateFlow<List<PairedTv>> = tvs.tvs
    val found: StateFlow<List<FoundTv>> = discovery.found
    val state: StateFlow<RemoteClient.State> = client.state
    val now: StateFlow<RemoteMessage.Now?> = client.now

    /** The keyboard for the TV's text field with the focus: opens and closes as the TV says. */
    val keyboard = RemoteKeyboard(client::send)

    init {
        viewModelScope.launch { client.field.collect(keyboard::onField) }
    }

    private val _pairing = MutableStateFlow<Pairing>(Pairing.Idle)
    val pairing: StateFlow<Pairing> = _pairing.asStateFlow()

    sealed interface Pairing {
        data object Idle : Pairing
        data object Working : Pairing
        data class Failed(val reason: String) : Pairing
    }

    /** Looks for TVs on the network; call when the picker is on screen, and [stopLooking] when it isn't. */
    fun look() = discovery.start()
    fun stopLooking() = discovery.stop()

    /** Connects to [tv], by the address it announced on this network if it has, else the one last used. */
    fun connect(tv: PairedTv) {
        viewModelScope.launch {
            // Discovery may still be finding it; give it a moment before falling back to the last address.
            var seen = found.value.firstOrNull { it.tvId == tv.tvId }
            if (seen == null && tv.host == null) { delay(DISCOVERY_WAIT_MS); seen = found.value.firstOrNull { it.tvId == tv.tvId } }
            val where = listOfNotNull(
                seen?.let { it.host to it.port },
                tv.host?.let { h -> tv.port?.let { p -> h to p } },
            ).distinct()
            client.connect(tv, where) { host, port -> tvs.remember(tv.tvId, host, port) }
        }
    }

    fun disconnect() = client.disconnect()

    fun send(message: RemoteMessage) = client.send(message)

    fun forget(tv: PairedTv) {
        if ((state.value as? RemoteClient.State.Connected)?.tv == tv.name) client.disconnect()
        tvs.remove(tv.tvId)
    }

    /**
     * Pairs with the TV that showed [scanned], and connects to it. False if it isn't a TV's
     * pairing code at all; a code that is one but can't be used says why in [pairing].
     */
    fun pair(scanned: String): Boolean {
        val invite = when (val read = RemoteInvite.read(scanned)) {
            is RemoteInvite.Read.Invite -> read.invite
            RemoteInvite.Read.NotAnInvite -> return false
            RemoteInvite.Read.Damaged -> { _pairing.value = Pairing.Failed("That code didn't scan whole. Hold the phone steady and scan it again."); return true }
            is RemoteInvite.Read.OffNetwork -> {
                _pairing.value = Pairing.Failed(com.opentune.i18n.Tr.f("The TV's address in that code ({0}) isn't on your home network. Connect the TV to your Wi-Fi and make a new code.", read.host))
                return true
            }
        }
        _pairing.value = Pairing.Working
        viewModelScope.launch {
            // Where this same TV has announced itself here too, in case the code's address doesn't answer.
            val also = found.value.filter { it.tvId == invite.tvId }.map { it.host to it.port }
            val result = withContext(Dispatchers.IO) { runCatching { client.pair(invite, also) } }
            result.onSuccess { tv -> tvs.add(tv); _pairing.value = Pairing.Idle; connect(tv) }
                .onFailure { _pairing.value = Pairing.Failed(it.message ?: "Couldn't pair with the TV.") }
        }
        return true
    }

    fun dismissPairing() { _pairing.value = Pairing.Idle }

    override fun onCleared() {
        client.close()
        discovery.stop()
        super.onCleared()
    }

    private companion object {
        const val DISCOVERY_WAIT_MS = 2_500L

        fun phoneName(app: Application): String =
            Settings.Global.getString(app.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
    }
}
