package com.opentune.ui.lossless

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.data.lossless.*
import kotlinx.coroutines.*
import java.io.File

/** Provider-supplied short previews, separate from verified full-quality FLAC. */
@UnstableApi
internal class LosslessPreview(private val context: Context, private val scope: CoroutineScope,
    private val pauseMain: () -> Unit, private val onError: (String) -> Unit) {
    var activeKey by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    private var job: Job? = null
    private var player: ExoPlayer? = null
    private var file: File? = null

    fun toggle(track: LosslessTrack) {
        if (activeKey == track.key) { stop(); return }
        stop()
        val url = track.previewUrl ?: return
        val pkg = ProviderPackages.get(track.provider) ?: return
        activeKey = track.key
        loading = true
        pauseMain()
        job = scope.launch {
            val clip = File(context.cacheDir, "lossless-preview-${java.util.UUID.randomUUID()}.audio")
            try {
                withContext(Dispatchers.IO) {
                    val response = withTimeout(30_000) { ProviderNetwork(pkg.hosts).request("GET", url, "", emptyMap(), clip, remainingBytes = 8_388_608) }
                    if (!response.optBoolean("ok")) throw LosslessFailure("Preview unavailable from this provider.")
                }
                ensureActive()
                file = clip
                player = ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                    setHandleAudioBecomingNoisy(true)
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) loading = false
                            if (state == Player.STATE_ENDED) stop()
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            onError("Preview could not play. You can still download the full FLAC."); stop()
                        }
                    })
                    setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(clip)))
                    prepare(); play()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { onError("Preview unavailable. You can still download the full FLAC."); stop() }
            finally { if (file != clip) clip.delete() }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        player?.release(); player = null
        file?.delete(); file = null
        activeKey = null; loading = false
    }
}
