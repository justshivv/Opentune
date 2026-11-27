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

    /** [length] ("3:45", "1:02:03") in ms; 0 when there's none. */
    val lengthMs: Long get() = length?.split(':')?.map { it.toLongOrNull() ?: return 0 }?.fold(0L) { a, b -> a * 60 + b }?.times(1000) ?: 0

    /**
     * With its length filled in from the player ([ms]) where the song came without one (a radio,
     * autoplay or a list that doesn't say): a watch draws the song's progress from it.
     */
    fun withLength(ms: Long): CTrack {
        if (lengthMs > 0 || ms <= 0) return this
        val s = ms / 1000
        val text = if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            else String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
        return copy(length = text)
    }

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
        /** Smart shuffle (similar songs mixed in) is what [shuffle] is; only the app does it. */
        val smart: Boolean = false,
        /** Smart shuffle can be turned on there (a queue from a list). */
        val canSmart: Boolean = false,
        val repeat: String = "off",
        /** Similar music keeps playing after the queue there; null from a device that doesn't say. */
        val autoplay: Boolean? = null,
        /**
         * When this device took the music (started it, changed the song, or
         * was handed it), on its own clock. The others show whichever took it
         * last; pausing doesn't count.
         */
        val since: Long? = null,
        /** How it sounds there, in that device's words ("Lossless", "High Quality"). */
        val quality: String? = null,
        /**
         * Meant to play, stalled or not; [playing] is whether it moves. Missing
         * from devices too old to say: they're taken at [playing].
         */
        val wants: Boolean? = null,
    ) : ConnectMsg

    /** Asks device [to] to do [action]: play, pause, next, previous, seek, volume, shuffle, repeat, autoplay, playTrack, addToQueue, jump or remove. */
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
        /** For repeat: "off", "all" or "one"; for shuffle, "smart" for smart shuffle. */
        val mode: String? = null,
        /** For move: the place in Up Next the entry at [pos] goes to. */
        val index: Int? = null,
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

    /**
     * A device turning Connect off or closing. [next] is set when it joins
     * another group of the person's devices: the secret of that group, so the
     * devices here follow it and no device is left behind by pairing.
     */
    @Serializable @SerialName("leave")
    data class Leave(override val from: String, override val seq: Long, override val at: Long, val next: String? = null) : ConnectMsg

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
            is Command -> m.tracks
            else -> emptyList()
        }

        private fun trimmed(m: ConnectMsg, max: Int): ConnectMsg = when {
            m is State && m.queue.size > max -> m.copy(queue = m.queue.take(max))
            // A transfer keeps the track it starts on.
            m is Transfer && m.queue.size > max -> m.copy(queue = m.queue.drop(m.index).take(maxOf(max, 1)), index = 0)
            m is Command && m.tracks.size > max -> m.copy(tracks = m.tracks.take(maxOf(max, 1)))
            else -> m
        }
    }
}
