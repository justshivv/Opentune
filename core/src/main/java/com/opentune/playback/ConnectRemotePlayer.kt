package com.opentune.playback

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.opentune.data.connect.CTrack
import com.opentune.data.connect.Connect
import com.opentune.data.connect.Device

/**
 * The player behind the media notification while another of the person's
 * devices plays (Connect): that device's song, play state and position, and
 * the notification's buttons (and headphones', and the lock screen's) acting
 * there. The phone's own player is left alone underneath.
 */
internal class ConnectRemotePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    /** The device playing; null shows nothing. */
    var device: Device? = null
        set(value) {
            field = value
            invalidateState()
        }

    override fun getState(): State {
        val d = device
        val s = d?.state
        val base = State.Builder().setAvailableCommands(COMMANDS)
        if (d == null || s == null) return base.setPlaybackState(Player.STATE_IDLE).build()
        val name = d.name.ifBlank { com.opentune.i18n.Tr.t("another device") }
        val current = s.track ?: CTrack("", com.opentune.i18n.Tr.t("Something of its own"))
        // What comes next there, so Next is offered; Previous always is.
        val items = (listOf(current) + s.queue).mapIndexed { i, t -> item(t, i, if (i == 0) com.opentune.i18n.Tr.f("Playing on {0}", name) else t.artist) }
        return base
            .setPlaylist(items)
            .setCurrentMediaItemIndex(0)
            .setPlayWhenReady(s.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(Player.STATE_READY)
            .setContentPositionMs { d.positionAt(System.currentTimeMillis()) }
            .build()
    }

    private fun item(t: CTrack, i: Int, artist: String): MediaItemData {
        val meta = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(artist)
            .setAlbumTitle(t.album)
            .setArtworkUri(t.thumb?.let(Uri::parse))
            .build()
        return MediaItemData.Builder("${t.id}#$i")
            .setMediaItem(MediaItem.Builder().setMediaId(t.id.ifBlank { "connect" }).setMediaMetadata(meta).build())
            .setDurationUs(t.lengthMs.takeIf { it > 0 }?.times(1000) ?: C.TIME_UNSET)
            .setIsSeekable(true)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady != (device?.state?.playing == true)) Connect.togglePlayPause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> Connect.skipNext()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> Connect.skipPrevious()
            else -> Connect.seekTo(positionMs.coerceAtLeast(0))
        }
        return Futures.immediateVoidFuture()
    }

    // Nothing of its own to let go of; the session around it is released by the service.
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    companion object {
        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_RELEASE,
        ).build()
    }
}
