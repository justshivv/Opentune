package com.opentune.data.together

import com.opentune.data.isYouTubeId
import com.opentune.data.model.Song
import com.opentune.data.radio.Radio
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A song as a room passes it around: enough for another phone to play it. */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val thumb: String? = null,
    val length: String? = null,
    val album: String? = null,
    /** For a radio station, the station itself, since its stream isn't looked up by id. */
    val station: Radio.Station? = null,
) {
    fun toSong() = Song(videoId = id, title = title, artist = artist, thumbnailUrl = thumb, durationText = length, albumName = album)

    companion object {
        /** The track for [song], or null for one only this phone can play (its own files, a private server). */
        fun of(song: Song): Track? = when {
            isYouTubeId(song.videoId) -> Track(song.videoId, song.title, song.artist, song.thumbnailUrl, song.durationText, song.albumName)
            Radio.isRadio(song.videoId) -> Radio.station(song.videoId)?.let { Track(song.videoId, song.title, song.artist, song.thumbnailUrl, station = it) }
            else -> null
        }
    }
}

@Serializable
data class Member(val id: String, val name: String, val host: Boolean = false)

/** Everything said in a room. Sent encrypted; relays never see these. */
@Serializable
sealed interface Msg {
    /** A guest arriving, and asking for the room's state. */
    @Serializable @SerialName("hello") data class Hello(val name: String) : Msg

    /** A guest still here. */
    @Serializable @SerialName("here") data class Here(val name: String) : Msg

    @Serializable @SerialName("bye") data object Bye : Msg

    /** The host's playback, sent on every change and every few seconds. */
    @Serializable @SerialName("state")
    data class State(
        val seq: Long,
        val host: String,
        val track: Track? = null,
        /** The host is playing something guests can't (a file on their phone). */
        val private: Boolean = false,
        val playing: Boolean = false,
        /** Position in ms at host time [at]. */
        val pos: Long = 0,
        val at: Long = 0,
        val next: List<Track> = emptyList(),
        val members: List<Member> = emptyList(),
        /** Guests may control playback, not only add songs. */
        val open: Boolean = false,
    ) : Msg

    /** The host closed the room. */
    @Serializable @SerialName("end") data object End : Msg

    /** Clock check: [c] is the asker's clock. */
    @Serializable @SerialName("ping") data class Ping(val c: Long) : Msg

    /** The host's answer: [h] is the host's clock when it answered. */
    @Serializable @SerialName("pong") data class Pong(val c: Long, val h: Long, val to: String) : Msg

    /** A guest asking the host to do something: play, pause, seek, next, previous or add. */
    @Serializable @SerialName("ask") data class Ask(val action: String, val pos: Long = 0, val track: Track? = null) : Msg

    @Serializable @SerialName("chat") data class Chat(val text: String) : Msg

    companion object {
        val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true; encodeDefaults = false }
        fun encode(m: Msg) = json.encodeToString(serializer(), m)
        fun decode(s: String): Msg? = runCatching { json.decodeFromString(serializer(), s) }.getOrNull()
    }
}

/** How far a guest should be into the song right now, given the host's last [state] and the clock gap. */
internal fun targetPosition(state: Msg.State, localNow: Long, offsetMs: Long): Long =
    if (!state.playing) state.pos else (state.pos + (localNow + offsetMs - state.at)).coerceAtLeast(0)

/**
 * The host clock's lead over ours from one ping: it answered at [hostAt],
 * which we take to be halfway through the round trip.
 */
internal fun clockOffset(sentAt: Long, hostAt: Long, receivedAt: Long): Long = hostAt - (sentAt + (receivedAt - sentAt) / 2)
