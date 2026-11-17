package com.opentune.data.connect

import com.opentune.data.model.Song
import com.opentune.data.isYouTubeId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A song as Connect passes it between devices: enough for another one to play it. */
@Serializable
data class CTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val thumb: String? = null,
    val length: String? = null,
    val album: String? = null,
) {
    fun toSong() = Song(videoId = id, title = title, artist = artist, thumbnailUrl = thumb, durationText = length, albumName = album)

    companion object {
        /** The track for [song], or null for one only this device can play (its own files, a private server). */
        fun of(song: Song): CTrack? =
            if (isYouTubeId(song.videoId)) CTrack(song.videoId, song.title, song.artist, song.thumbnailUrl, song.durationText, song.albumName) else null
    }
}

/**
 * Everything one of a person's devices says to the others. Sealed with the
 * group's key; relays never see these. [from] is the sender's device id,
 * [seq] rises with each message it sends, [at] is its clock in ms.
 */
@Serializable
sealed interface ConnectMsg {
    val from: String
    val seq: Long
    val at: Long

    /** A device that is here, every 30 seconds and when something about it changes. */
    @Serializable @SerialName("presence")
    data class Presence(override val from: String, override val seq: Long, override val at: Long, val name: String, val form: String, val active: Boolean = false) : ConnectMsg

    /** What the device playing is playing. [pos] is the position in ms at [at]; [queue] is what comes after [track]. */
    @Serializable @SerialName("state")
    data class State(
        override val from: String,
        override val seq: Long,
        override val at: Long,
        val track: CTrack? = null,
        /** Playing something the others can't (a file on this device). */
        val private: Boolean = false,
        val playing: Boolean = false,
        val pos: Long = 0,
        val queue: List<CTrack> = emptyList(),
        val volume: Float = 1f,
        val shuffle: Boolean = false,
        val repeat: String = "off",
    ) : ConnectMsg

    /** Asks device [to] to do [action]: play, pause, next, previous, seek, volume, playTrack or addToQueue. */
    @Serializable @SerialName("command")
    data class Command(
        override val from: String,
        override val seq: Long,
        override val at: Long,
        val to: String,
        val id: String,
        val action: String,
        val pos: Long = 0,
        val value: Float = 0f,
        val track: CTrack? = null,
        val tracks: List<CTrack> = emptyList(),
    ) : ConnectMsg

    /** The answer to a [Command] or [Transfer] with the same [id]. */
    @Serializable @SerialName("ack")
    data class Ack(override val from: String, override val seq: Long, override val at: Long, val to: String, val id: String, val ok: Boolean = true, val reason: String? = null) : ConnectMsg

    /** Tells device [to] to take over playback: [queue] from [index], at [pos] ms as of [at]. */
    @Serializable @SerialName("transfer")
    data class Transfer(
        override val from: String,
        override val seq: Long,
        override val at: Long,
        val to: String,
        val id: String,
        val queue: List<CTrack> = emptyList(),
        val index: Int = 0,
        val pos: Long = 0,
        val playing: Boolean = true,
    ) : ConnectMsg

    /** A device turning Connect off or closing. */
    @Serializable @SerialName("leave")
    data class Leave(override val from: String, override val seq: Long, override val at: Long) : ConnectMsg

    /** Clock check: [c] is the asker's clock. */
    @Serializable @SerialName("ping")
    data class Ping(override val from: String, override val seq: Long, override val at: Long, val to: String, val c: Long) : ConnectMsg

    /** The answer to a [Ping]: [h] is the answering device's clock. */
    @Serializable @SerialName("pong")
    data class Pong(override val from: String, override val seq: Long, override val at: Long, val to: String, val c: Long, val h: Long) : ConnectMsg

    companion object {
        const val MAX_QUEUE = 100
        const val MAX_SEALED = 48_000
        private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        fun encode(msg: ConnectMsg): String = json.encodeToString(serializer(), msg)
        fun decode(text: String): ConnectMsg? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        /** [msg] sealed for [group], its queue cut from the end until it fits a relay's limit. */
        fun fit(msg: ConnectMsg, group: ConnectGroup, limit: Int = MAX_SEALED): String {
            var m = trimmed(msg, MAX_QUEUE)
            var sealed = group.seal(encode(m))
            while (sealed.length > limit) {
                val size = queueOf(m).size
                if (size == 0) break
                m = trimmed(m, size * 3 / 4)
                sealed = group.seal(encode(m))
            }
            return sealed
        }

        private fun queueOf(m: ConnectMsg) = when (m) {
            is State -> m.queue
            is Transfer -> m.queue
            else -> emptyList()
        }

        private fun trimmed(m: ConnectMsg, max: Int): ConnectMsg = when {
            m is State && m.queue.size > max -> m.copy(queue = m.queue.take(max))
            // A transfer keeps the track it starts on.
            m is Transfer && m.queue.size > max -> m.copy(queue = m.queue.drop(m.index).take(maxOf(max, 1)), index = 0)
            else -> m
        }
    }
}
