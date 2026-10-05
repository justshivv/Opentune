package com.opentune.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.opentune.MainActivity
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.innertube.PlayerClient
import com.opentune.data.innertube.StreamResolver
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Foreground media session hosting playback, the queue, and autoplay.
 *
 * Queue entries carry a [trackUri], not a stream URL. The data source below
 * resolves it through [StreamResolver] when ExoPlayer opens the item, which
 * happens on ExoPlayer's loader thread, so blocking there is fine.
 *
 * Autoplay and error recovery live here rather than in the UI so they keep
 * working when the activity is gone and only the notification is left.
 */
// The data source plumbing (ResolvingDataSource, OkHttpDataSource, DataSpec
// headers) is all marked unstable by Media3; there is no stable equivalent.
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var radioJob: Job? = null
    private var radioSeed: String? = null

    /** Seeds whose radio added nothing new; asking again would only repeat that. */
    private val exhaustedSeeds = mutableSetOf<String>()

    /** The item a failed load was last retried for. See [recover]. */
    private var retriedMediaId: String? = null

    override fun onCreate() {
        super.onCreate()

        // The same OkHttp client Innertube and the stream resolver use — a
        // stream URL is bound to the connection context of the request that
        // minted it, so a separate HTTP stack here risks a 403 on playback.
        // No fixed User-Agent: [resolveTrack] sends the minting client's own.
        val httpFactory = OkHttpDataSource.Factory(Http.client)
        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, RefusalReportingDataSource.Factory(httpFactory)),
            ::resolveTrack,
        )

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(playerListener)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaSession ?: return
        if (!session.player.playWhenReady || session.player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun resolveTrack(dataSpec: DataSpec): DataSpec {
        val videoId = videoIdOf(dataSpec.uri) ?: return dataSpec
        val url = try {
            runBlocking { StreamResolver.resolve(videoId) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Couldn't resolve a stream for $videoId", e)
        }
        // googlevideo expects the media request to look like the client that
        // minted the URL; these are also the headers StreamResolver probed with.
        return dataSpec.withUri(url.toUri())
            .withAdditionalHeaders(PlayerClient.forStreamUrl(url).mediaHeaders())
    }

    /**
     * Items that arrive from a controller may have lost their URI on the way
     * (Media3 only carries it across a binder in some cases), so rebuild it
     * from the media id, which always survives.
     */
    private val sessionCallback = object : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { it.buildUpon().setUri(trackUri(it.mediaId)).build() }.toMutableList(),
        )
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            extendQueueIfNeeded()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            extendQueueIfNeeded()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retriedMediaId = null
        }

        override fun onPlayerError(error: PlaybackException) {
            recover(error)
        }
    }

    /**
     * Append radio for the last queued track once playback nears the end, so
     * the music doesn't stop. Starting a single song goes through here too:
     * a one-track queue is already "near the end".
     */
    private fun extendQueueIfNeeded() {
        val player = mediaSession?.player ?: return
        if (!Autoplay.shouldExtend(player.currentMediaItemIndex, player.mediaItemCount)) return
        val seed = player.getMediaItemAt(player.mediaItemCount - 1).mediaId
        if (seed in exhaustedSeeds) return
        if (radioJob?.isActive == true && radioSeed == seed) return

        // A request for an older seed is answering a queue that no longer exists.
        radioJob?.cancel()
        radioSeed = seed
        radioJob = scope.launch {
            val radio = try {
                withContext(Dispatchers.IO) {
                    InnertubeParser.parseWatchQueue(Innertube.next(seed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Radio for $seed failed", e)
                return@launch
            }
            // The queue may have been replaced while the request was out; radio
            // for a track that's no longer last doesn't belong at the end.
            val count = player.mediaItemCount
            if (count == 0 || player.getMediaItemAt(count - 1).mediaId != seed) return@launch

            val queued = (0 until count).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
            val additions = Autoplay.newTracks(queued, radio)
            if (additions.isEmpty()) {
                exhaustedSeeds += seed
                return@launch
            }
            player.addMediaItems(additions.map { it.toMediaItem() })
        }
    }

    /**
     * Retry a failed item once, then move on.
     *
     * The common failure is a cached stream URL that googlevideo has stopped
     * honouring; [RefusalReportingDataSource] has already evicted it by the
     * time this runs, so preparing again resolves a fresh one. A track the
     * resolver has ruled unplayable is skipped without the retry.
     */
    private fun recover(error: PlaybackException) {
        val player = mediaSession?.player ?: return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val permanent = generateSequence<Throwable>(error) { it.cause }
            .any { it is StreamResolver.PermanentlyUnplayableException }

        if (!permanent && retriedMediaId != mediaId) {
            Log.w(TAG, "Retrying $mediaId after ${error.errorCodeName}")
            retriedMediaId = mediaId
            player.prepare()
            return
        }
        if (player.hasNextMediaItem()) {
            Log.w(TAG, "Skipping $mediaId after ${error.errorCodeName}")
            player.seekToNextMediaItem()
            player.prepare()
        }
    }

    /**
     * Tells [StreamResolver] when googlevideo refuses a URL it handed out, so
     * the cached URL is dropped and the next attempt resolves a new one.
     */
    private class RefusalReportingDataSource(
        private val upstream: HttpDataSource,
    ) : DataSource by upstream {
        override fun open(dataSpec: DataSpec): Long = try {
            upstream.open(dataSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            StreamResolver.onPlaybackRefused(dataSpec.uri.toString(), e.responseCode)
            throw e
        }

        class Factory(private val upstream: HttpDataSource.Factory) : DataSource.Factory {
            override fun createDataSource(): DataSource =
                RefusalReportingDataSource(upstream.createDataSource())
        }
    }

    private companion object {
        const val TAG = "PlaybackService"
    }
}
