package com.opentune.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The phone as a remote for OpenTune on a TV. A phone and a TV that have been paired once
 * (see [RemoteInvite]) talk over the home network, each message sealed with the key made at
 * pairing; this file is what they say to each other.
 */
object RemoteProtocol {
    /** Bumped when a message changes in a way an older app can't read. */
    const val VERSION = 1

    /** The mDNS service type a TV announces itself as, so a paired phone can find it. */
    const val SERVICE_TYPE = "_opentune-tv._tcp."

    /** Tried first, so a phone can reach a TV where mDNS is blocked; another is used if it's taken. */
    const val DEFAULT_PORT = 47731
}

/** A button on the remote. */
@Serializable
enum class RemoteKey { UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, PLAY_PAUSE, NEXT, PREVIOUS, FORWARD, REWIND, VOLUME_UP, VOLUME_DOWN, MUTE }

@Serializable
sealed interface RemoteMessage {
    // ---- From the phone ----

    /** First thing a paired phone says; [name] is how it's shown on the TV. */
    @Serializable @SerialName("hello") data class Hello(val name: String, val version: Int = RemoteProtocol.VERSION) : RemoteMessage

    /** Sent every few seconds, so each side notices when the other has gone. */
    @Serializable @SerialName("ping") data class Ping(val at: Long = 0) : RemoteMessage

    /** The cursor moves by this many pixels of the phone's trackpad; the TV scales it to its own screen. */
    @Serializable @SerialName("move") data class Move(val dx: Float, val dy: Float) : RemoteMessage

    /** A click where the cursor is. */
    @Serializable @SerialName("click") data class Click(val double: Boolean = false) : RemoteMessage

    /** The button held down ([down]) or let go, for dragging. */
    @Serializable @SerialName("press") data class Press(val down: Boolean) : RemoteMessage

    /**
     * Scrolling under the cursor: how far two fingers moved on the trackpad, in dp. The content
     * follows the fingers, as on a phone, so fingers moving down ([dy] positive) bring the earlier
     * part of the page into view. A phone that scrolls the other way sends the opposite sign.
     */
    @Serializable @SerialName("scroll") data class Scroll(val dx: Float, val dy: Float) : RemoteMessage

    /** A remote button; [repeat] when it is being held. */
    @Serializable @SerialName("key") data class Key(val key: RemoteKey, val repeat: Boolean = false) : RemoteMessage

    /** Typed on the phone: goes into Search on the TV. */
    @Serializable @SerialName("text") data class Text(val text: String) : RemoteMessage

    /** Show or hide the cursor (shown when the trackpad opens, hidden when the buttons do). */
    @Serializable @SerialName("cursor") data class Cursor(val shown: Boolean) : RemoteMessage

    // ---- From the TV ----

    /** The TV's answer to a connection that holds the right key. */
    @Serializable @SerialName("welcome") data class Welcome(val tv: String, val version: Int = RemoteProtocol.VERSION) : RemoteMessage

    /** What is playing, for the phone's remote to show. [volume] is 0 to 100. */
    @Serializable @SerialName("now")
    data class Now(
        val title: String? = null,
        val artist: String? = null,
        val artworkUrl: String? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val volume: Int = 0,
    ) : RemoteMessage

    // ---- Pairing, only ever sent under a one-time invite's token ----

    @Serializable @SerialName("pair") data class PairRequest(val name: String) : RemoteMessage

    /** The key this phone will use from now on, and the id that says which one it is. */
    @Serializable @SerialName("granted") data class PairGranted(val id: String, val key: String, val tvId: String) : RemoteMessage
}

internal object RemoteCodec {
    private val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true }

    fun encode(message: RemoteMessage): String = json.encodeToString(RemoteMessage.serializer(), message)

    /** The message, or null when it's one this version of the app doesn't know (a newer app's). */
    fun decode(text: String): RemoteMessage? = try {
        json.decodeFromString(RemoteMessage.serializer(), text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
