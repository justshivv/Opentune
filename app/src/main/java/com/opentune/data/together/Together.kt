package com.opentune.data.together

import android.content.Context
import androidx.core.content.edit
import com.opentune.data.model.Song
import com.opentune.playback.PlayerConnection
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
import kotlinx.serialization.json.put

/**
 * Listening together: one phone hosts a room and the others play what it
 * plays, in step. Rooms run over public Nostr relays. Each message is
 * encrypted with a key that comes from the room code, signed with a key
 * made fresh for the session, and sent as an ephemeral event, which relays
 * pass to whoever is listening and don't keep.
 *
 * Every phone fetches its own audio; only "what, where, playing or not"
 * travels. Guests follow the host's state, correcting when they drift more
 * than a second, and can add songs to the host's queue. When the host lets
 * them, they can also play, pause, seek and skip for everyone.
 */
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

    enum class Phase { Connecting, FindingHost, Live, LostHost, Ended }

    data class ChatLine(val name: String, val text: String, val mine: Boolean = false, val system: Boolean = false)

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

    /** Short messages for the UI to show, like "Only the host can skip". */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val main by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var player: RoomPlayer? = null
    private var session: RoomSession? = null
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("together", Context.MODE_PRIVATE)
    }

    fun attach(connection: PlayerConnection) {
        player = ConnectionPlayer(connection)
    }

    /** Leaves any room and lets go of the player, when the screen that owns it goes away. */
    fun detach() {
        leave()
        player = null
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
            put("since", System.currentTimeMillis() / 1000 - 10)
        }
        session = RoomSession(code, clean, hosting, p, RelayPool(RELAYS, filter), _room, ::notice, main).also { it.start() }
    }

    fun leave() {
        session?.stop(saying = true)
        session = null
        _room.value = null
    }

    fun setOpen(open: Boolean) = session?.setOpen(open)

    fun say(text: String) = session?.say(text)

    /** Adds [song] to the room's queue: straight in for the host, as a request for a guest. */
    fun suggest(song: Song): Boolean = session?.suggest(song) ?: false

    /**
     * For a guest following a room, sends [control] to the host instead of
     * acting on it here. True when the room took it, so the caller does
     * nothing locally.
     */
    fun intercept(control: Control): Boolean = session?.intercept(control) ?: false

    private fun notice(text: String) {
        _notices.tryEmit(text)
    }


}
