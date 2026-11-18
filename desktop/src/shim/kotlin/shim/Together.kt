// Listen together on the desktop: the phone's room, run against the desktop's player. The
// room types are the phone's own (Together.kt in core), word for word, so the shared room
// protocol (RoomSession and the rest, compiled as they are) speaks to phones and the web
// player alike. Voice notes and talk, which need the phone's microphone and codecs, are
// left out: they show in the chat as a line to hear on a phone.
package com.opentune.data.together

import android.content.Context
import androidx.core.content.edit
import com.opentune.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object Together {
    const val KIND = 21420
    val RELAYS = listOf(
        "wss://relay.primal.net",
        "wss://relay.snort.social",
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://nostr.mom",
        "wss://relay.nostr.net",
        "wss://nostr-pub.wellorder.net",
        "wss://offchain.pub",
    )

    enum class Phase { Connecting, FindingHost, Waiting, Live, LostHost, Ended }

    data class ChatLine(
        val name: String,
        val text: String,
        val mine: Boolean = false,
        val system: Boolean = false,
        val at: Long = System.currentTimeMillis(),
        val fromId: String? = null,
        /** A voice note or a piece of talk, instead of text. */
        val voice: VoiceClip? = null,
    )

    /** A voice clip as it arrives: AAC frames with the codec's setup, see [VoiceFrames]. */
    class VoiceClip(val id: String, val cfg: ByteArray?, val frames: List<ByteArray>, val ms: Long, val complete: Boolean, val live: Boolean)

    data class Room(
        val code: RoomCode,
        val hosting: Boolean,
        val myName: String,
        val phase: Phase = Phase.Connecting,
        val relays: Int = 0,
        val members: List<Member> = emptyList(),
        val hostName: String? = null,
        val open: Boolean = false,
        val chat: List<ChatLine> = emptyList(),
        val track: Track? = null,
        val hostPrivate: Boolean = false,
        /** A guest who paused for themselves; the room plays on without them. */
        val holding: Boolean = false,
        val endedReason: String? = null,
        /** This phone's id in [members]. */
        val myId: String? = null,
        /** For the host: people who knocked, waiting to be let in or turned away. */
        val requests: List<Member> = emptyList(),
        /** For the host: knocking needs approval; off lets anyone with the code straight in. */
        val askToJoin: Boolean = true,
        /** Member ids of the people talking right now (others; this phone's own talk is [Together.speaking]). */
        val talking: List<String> = emptyList(),
    ) {
        val following get() = !hosting && phase == Phase.Live
    }

    /** A playback control pressed on a guest's phone. */
    sealed interface Control {
        data object PlayPause : Control
        data object Next : Control
        data object Previous : Control
        data class Seek(val positionMs: Long) : Control
    }

    private val _room = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = _room.asStateFlow()

    /** Short messages for the window to show, like "Only the host can skip". */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    // One at a time, as the phone's main thread runs a room.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var player: RoomPlayer? = null
    private var session: RoomSession? = null
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("together", Context.MODE_PRIVATE)
        _picksPlayNow.value = prefs?.getBoolean("picks_play_now", false) ?: false
    }

    internal fun attach(player: RoomPlayer) { this.player = player }

    /** Whether people who knock wait for approval in rooms this computer hosts. */
    val askToJoin: Boolean get() = prefs?.getBoolean("ask_to_join", true) ?: true

    fun approve(id: String) = session?.approve(id)
    fun reject(id: String) = session?.reject(id)
    fun remove(id: String) = session?.remove(id)
    fun setAskToJoin(on: Boolean) {
        prefs?.edit { putBoolean("ask_to_join", on) }
        session?.setAskToJoin(on)
    }

    private val _picksPlayNow = MutableStateFlow(false)
    /** As a guest, a song picked starts for everyone (when the host allows it) rather than joining the queue. */
    val picksPlayNow: StateFlow<Boolean> = _picksPlayNow.asStateFlow()

    fun setPicksPlayNow(on: Boolean) {
        _picksPlayNow.value = on
        prefs?.edit { putBoolean("picks_play_now", on) }
    }

    /** The name last used in a room, to fill in next time. */
    var savedName: String
        get() = prefs?.getString("name", null).orEmpty()
        private set(value) { prefs?.edit { putString("name", value) } }

    fun create(name: String): RoomCode {
        leave()
        val code = RoomCode.generate()
        start(code, name, hosting = true)
        return code
    }

    fun join(code: RoomCode, name: String) {
        if (session?.code == code && _room.value?.phase != Phase.Ended) return
        leave()
        start(code, name, hosting = false)
    }

    private fun start(code: RoomCode, name: String, hosting: Boolean) {
        val clean = name.trim().take(30).ifBlank { "Someone" }
        savedName = clean
        _room.value = Room(code, hosting, clean)
        val p = player ?: return
        val filter = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(KIND))))
            put("#t", JsonArray(listOf(JsonPrimitive(code.tag))))
            put("since", JsonPrimitive(System.currentTimeMillis() / 1000 - 10))
        }
        session = RoomSession(code, clean, hosting, p, RelayPool(RELAYS, filter), _room, ::notice, main, askToJoin).also { it.start() }
    }

    fun leave() {
        session?.stop(saying = true)
        session = null
        _room.value = null
    }

    fun setOpen(open: Boolean) = session?.setOpen(open)

    fun say(text: String) = session?.say(text)

    /** Adds [song] to the room's queue: straight in for the host, as a request for a guest. */
    fun suggest(song: Song): Boolean = session?.suggest(song, now = _picksPlayNow.value) ?: false

    /** For a guest following a room, sends [control] to the host instead of acting on it here. */
    fun intercept(control: Control): Boolean = session?.intercept(control) ?: false

    private fun notice(text: String) {
        _notices.tryEmit(text)
    }
}
