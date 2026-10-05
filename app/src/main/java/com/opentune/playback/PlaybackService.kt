package com.opentune.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.SystemClock
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
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.opentune.MainActivity
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.download.Downloads
import com.opentune.data.NerdStats
import com.opentune.data.history.History
import com.opentune.data.local.LocalMusic
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.innertube.StreamResolver
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.playback.dsp.DspAudioProcessor
import com.opentune.playback.dsp.DspParams
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import android.media.audiofx.AudioEffect
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Foreground media session hosting playback, the queue, autoplay, the audio
 * chain and listening history.
 *
 * Data path: an `opentune://track/<id>` item is looked up in [AudioCache]
 * first, by video id; only on a miss is it resolved through [StreamResolver]
 * and fetched. Local files (`content://`) skip both.
 *
 * Audio path: decoder → [DspAudioProcessor] (EQ, tone, balance, widening,
 * levelling, limiter) → ExoPlayer's silence skipping and speed/pitch → output.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var radioJob: Job? = null
    private var radioSeed: String? = null

    /** Seeds whose radio added nothing new; asking again would only repeat that. */
    private val exhaustedSeeds = mutableSetOf<String>()

    /** Every track queued since the service started, for "don't repeat songs". */
    private val sessionIds = mutableSetOf<String>()

    /** The item a failed load was last retried for. See [recover]. */
    private var retriedMediaId: String? = null

    private var soundEffects: SoundEffects? = null
    private val dsp = DspAudioProcessor()
    private var audioManager: AudioManager? = null

    override fun onCreate() {
        super.onCreate()
        val context: Context = this

        // The same OkHttp client Innertube and the stream resolver use — a
        // stream URL is bound to the connection context of the request that
        // minted it, so a separate HTTP stack here risks a 403 on playback.
        // No fixed User-Agent: [resolveTrack] sends the minting client's own.
        val http = RefusalReportingDataSource.Factory(OkHttpDataSource.Factory(Http.client))
        val resolving = ResolvingDataSource.Factory(http, ::resolveTrack)
        val cached = CacheDataSource.Factory()
            .setCache(AudioCache.get(context))
            .setUpstreamDataSourceFactory(resolving)
            .setCacheKeyFactory { spec -> cacheKeyOf(spec.uri) ?: spec.key ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val routing = SchemeRoutingDataSource.Factory(streams = cached, local = DefaultDataSource.Factory(context))
        val sources = DefaultMediaSourceFactory(routing)

        val floatOutput = AppSettings.playback.value.floatOutput
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(floatOutput)
                .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(dsp))
                .build()
        }

        // Start as soon as a little audio is in: the defaults wait for 2.5 s,
        // which is video-sized caution. A stream starts after half a second buffered
        // and a local file after a quarter second.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(20_000, FAR_BUFFER_MS, 500, 2_000)
            .setBufferDurationsMsForLocalPlayback(5_000, FAR_BUFFER_MS, 250, 500)
            // The byte ceiling decides, not the time one: about a whole song
            // of Opus, so a fetched track is fetched once and seeks within it
            // are instant. Media3's audio default is around 40 seconds.
            .setTargetBufferBytes(FAR_BUFFER_BYTES)
            .setBackBuffer(BACK_BUFFER_MS, true)
            .build()

        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sources)
            .setLoadControl(loadControl)
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

        val effects = SoundEffects(player)
        soundEffects = effects
        scope.launch { AppSettings.sound.collect(effects::apply) }
        scope.launch {
            combine(AppSettings.equalizer, AppSettings.sound, AppSettings.playback) { eq, sound, pb ->
                DspParams(eq, sound.bassBoost, pb.spatialAudio)
            }.distinctUntilChanged().collect { dsp.params = it }
        }
        scope.launch {
            AppSettings.playback.map { it.skipSilence }.distinctUntilChanged().collect { player.skipSilenceEnabled = it }
        }
        // Turning autoplay back on near the end of the queue should fetch now,
        // not at the next track change.
        scope.launch {
            AppSettings.playback.map { it.autoplay }.distinctUntilChanged().collect { extendQueueIfNeeded() }
        }
        scope.launch { trackListening(player) }
        scope.launch {
            AppSettings.playback.map { it.loudnessNormalization }.distinctUntilChanged().collect { applyLoudness() }
        }
        scope.launch {
            AppSettings.playback.map { it.preloadUpcoming }.distinctUntilChanged().collect { on ->
                // ExoPlayer prepares and buffers the next item in play order
                // while this one plays, so it starts from memory.
                player.preloadConfiguration =
                    if (on) ExoPlayer.PreloadConfiguration(PRELOAD_US) else ExoPlayer.PreloadConfiguration.DEFAULT
                if (on) warmNeighbours()
            }
        }

        scope.launch { runSleepTimer(player) }
        crossfade = Crossfade(context, scope, sources, player).also { it.start() }
        scope.launch { PlaybackRequests.upgrade.collect { scheduleUpgrade(force = true) } }

        audioManager = getSystemService(AudioManager::class.java)?.also { am ->
            am.registerAudioDeviceCallback(deviceCallback, null)
        }
        scope.launch {
            AppSettings.playback.map { it.preferUsbDac }.distinctUntilChanged().collect { applyPreferredDevice() }
        }

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

    /**
     * Pauses at the time the sleep timer names, or at the end of the current
     * track (ExoPlayer's own pause-at-end, so it lands on the boundary).
     */
    private suspend fun runSleepTimer(player: ExoPlayer) {
        SleepTimer.state.collectLatest { state ->
            player.pauseAtEndOfMediaItems = state is SleepTimer.State.EndOfTrack
            when (state) {
                is SleepTimer.State.At -> {
                    delay((state.endsAtMs - System.currentTimeMillis()).coerceAtLeast(0))
                    player.pause()
                    SleepTimer.cancel()
                }
                SleepTimer.State.EndOfTrack -> {
                    // pauseAtEndOfMediaItems pauses; clear the timer once it has.
                    playWhenReadyFlow(player).first { !it }
                    SleepTimer.cancel()
                }
                SleepTimer.State.Off -> Unit
            }
        }
    }

    /** Emits playWhenReady, now and on every change. */
    private fun playWhenReadyFlow(player: Player) = callbackFlow {
        trySend(player.playWhenReady)
        val l = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { trySend(playWhenReady) }
        }
        player.addListener(l)
        awaitClose { player.removeListener(l) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (AppSettings.playback.value.stopOnTaskRemoved) {
            player.pause()
            player.stop()
            stopSelf()
            return
        }
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        crossfade?.release()
        crossfade = null
        closeEffectSession()
        finishListen()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        scope.cancel()
        soundEffects?.release()
        soundEffects = null
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
            runBlocking {
                if (isUpgradedUri(dataSpec.uri)) StreamResolver.resolveUpgrade(videoId) else StreamResolver.resolve(videoId)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Couldn't resolve a stream for $videoId", e)
        }
        // googlevideo expects the media request to look like the client that
        // minted the URL; these are also the headers StreamResolver probed with.
        return dataSpec.withUri(url.toUri())
            .withAdditionalHeaders(StreamResolver.mediaHeadersFor(url))
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
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                for (i in 0 until player.mediaItemCount) sessionIds += player.getMediaItemAt(i).mediaId
            }
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) {
                extendQueueIfNeeded()
                warmNeighbours()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applyLoudness()
            scheduleUpgrade()
            // The same song on a better stream is not a new listen.
            if (mediaItem != null && mediaItem.mediaId == swappingTo) {
                swappingTo = null
                return
            }
            finishListen()
            startListen(mediaItem?.toSong(), mediaSession?.player?.isPlaying == true)
            // Only time starts meant to sound now, not a skip made while paused.
            startRequestedAt = if (mediaSession?.player?.playWhenReady == true) SystemClock.elapsedRealtime() else 0L
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && startRequestedAt > 0) {
                NerdStats.onStartup(SystemClock.elapsedRealtime() - startRequestedAt)
                startRequestedAt = 0
            }
            if (isPlaying) {
                if (listenSince < 0) listenSince = SystemClock.elapsedRealtime()
            } else {
                pauseListen()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                retriedMediaId = null
                // The figure arrives with the stream; on a first play it's
                // only known once the track has resolved.
                applyLoudness()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            openEffectSession(audioSessionId)
            applyLoudness()
        }

        override fun onPlayerError(error: PlaybackException) {
            recover(error)
        }
    }

    // ---- Listening history ---------------------------------------------------

    private var listenSong: Song? = null
    private var listenedMs = 0L
    private var listenSince = -1L
    private var listenRecord: Long? = null

    private fun startListen(song: Song?, playing: Boolean) {
        listenSong = song
        listenedMs = 0
        listenSince = if (playing) SystemClock.elapsedRealtime() else -1
        listenRecord = null
    }

    private fun pauseListen() {
        if (listenSince >= 0) listenedMs += SystemClock.elapsedRealtime() - listenSince
        listenSince = -1
    }

    private fun listenedSoFar(): Long =
        listenedMs + if (listenSince >= 0) SystemClock.elapsedRealtime() - listenSince else 0

    private fun finishListen() {
        listenRecord?.let { History.updateListened(it, listenedSoFar()) }
        listenRecord = null
    }

    /** A track counts as played after 30 seconds, or half its length if shorter. */
    private suspend fun trackListening(player: Player) {
        while (scope.isActive) {
            delay(5_000)
            val song = listenSong ?: continue
            if (listenRecord != null) continue
            val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            val threshold = minOf(30_000L, duration / 2)
            val heard = listenedSoFar()
            if (heard >= threshold) listenRecord = History.record(song, heard)
        }
    }

    // ---- Loudness ----------------------------------------------------------------

    private var crossfade: Crossfade? = null

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessSession = C.AUDIO_SESSION_ID_UNSET

    /**
     * Loudness normalization the way YouTube does it: one fixed gain per
     * track, from YouTube's own measurement of that track ([StreamResolver.loudnessDbFor]),
     * applied with the platform's LoudnessEnhancer. Loud masters come down to
     * the reference and quiet ones come up a little (at most +3 dB), with no
     * pumping and nothing touching the peaks in between. Without a figure, or
     * with the setting off, the gain is zero and the effect is off.
     */
    /**
     * Tells the system a music session is open, so the phone's own sound
     * effects (Dolby Atmos, Samsung's SoundAlive and Adapt Sound, the system
     * equalizer and similar) attach to this app the way they do to the stock
     * players. Without this many phones leave OpenTune unprocessed and it
     * sounds flatter and quieter next to other apps.
     */
    private var effectSession = C.AUDIO_SESSION_ID_UNSET

    private fun openEffectSession(session: Int) {
        if (session == C.AUDIO_SESSION_ID_UNSET || session == effectSession) return
        closeEffectSession()
        effectSession = session
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }

    private fun closeEffectSession() {
        if (effectSession == C.AUDIO_SESSION_ID_UNSET) return
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, effectSession)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName),
        )
        effectSession = C.AUDIO_SESSION_ID_UNSET
    }

    private var loudnessRetry: Job? = null

    private fun applyLoudness() {
        val player = mediaSession?.player as? ExoPlayer ?: return
        val session = player.audioSessionId
        if (session == C.AUDIO_SESSION_ID_UNSET) return
        openEffectSession(session)
        val id = player.currentMediaItem?.mediaId
        val db = id?.let { StreamResolver.loudnessDbFor(it) ?: Downloads.loudnessFor(it) }
        // A track playing from the cache never resolved, so its figure may not
        // be known yet. Look it up once, a moment in, then apply it.
        loudnessRetry?.cancel()
        if (db == null && id != null && !LocalMusic.isLocal(id) && AppSettings.playback.value.loudnessNormalization) {
            loudnessRetry = scope.launch {
                delay(LOUDNESS_RETRY_MS)
                withContext(Dispatchers.IO) { runCatching { StreamResolver.resolve(id) } }
                if (player.currentMediaItem?.mediaId == id && StreamResolver.loudnessDbFor(id) != null) applyLoudness()
            }
        }
        val on = AppSettings.playback.value.loudnessNormalization && db != null
        val enhancer = loudnessEnhancer?.takeIf { loudnessSession == session }
            ?: runCatching { LoudnessEnhancer(session) }
                .onFailure { Log.w(TAG, "LoudnessEnhancer unavailable", it) }
                .getOrNull()
                ?.also {
                    loudnessEnhancer?.release()
                    loudnessEnhancer = it
                    loudnessSession = session
                }
            ?: return
        runCatching {
            if (on) {
                val gainMb = (-db!! * 100).roundToInt().coerceIn(MIN_LOUDNESS_GAIN_MB, MAX_LOUDNESS_GAIN_MB)
                enhancer.setTargetGain(gainMb)
                enhancer.enabled = true
                NerdStats.onLoudnessGain(gainMb / 100f)
            } else {
                enhancer.setTargetGain(0)
                enhancer.enabled = false
                NerdStats.onLoudnessGain(null)
            }
        }.onFailure { Log.w(TAG, "Couldn't apply loudness gain", it) }
    }

    // ---- Quality upgrade -----------------------------------------------------------

    private var upgradeJob: Job? = null

    /** The song being swapped onto its better stream; its transition isn't a new listen. */
    private var swappingTo: String? = null

    /**
     * A few seconds into each song, off the start path, asks
     * [StreamResolver.findUpgrade] whether a clearly better stream exists,
     * and swaps to it in place if so. Also run from the player's menu.
     */
    private fun scheduleUpgrade(force: Boolean = false) {
        upgradeJob?.cancel()
        if (!force && !AppSettings.playback.value.qualityUpgrade) return
        val player = mediaSession?.player as? ExoPlayer ?: return
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        val id = videoIdOf(uri) ?: return
        if (isUpgradedUri(uri)) return
        upgradeJob = scope.launch {
            if (!force) delay(UPGRADE_DELAY_MS)
            val found = withContext(Dispatchers.IO) { runCatching { StreamResolver.findUpgrade(id, force) }.getOrDefault(false) }
            if (!found || player.currentMediaItem?.mediaId != id) return@launch
            val left = player.duration - player.currentPosition
            if (player.duration > 0 && left < UPGRADE_MIN_REMAINING_MS) return@launch
            swapToUpgrade(player, id)
        }
    }

    private fun swapToUpgrade(player: ExoPlayer, id: String) {
        val index = player.currentMediaItemIndex
        val item = player.getMediaItemAt(index)
        val position = player.currentPosition
        swappingTo = id
        // If the player updates the item in place there's no transition to clear this.
        scope.launch { delay(2_000); if (swappingTo == id) swappingTo = null }
        player.replaceMediaItem(index, item.buildUpon().setUri(streamUri(id, upgraded = true)).build())
        if (player.currentMediaItemIndex != index || player.currentPosition < position - 1_000) player.seekTo(index, position)
        Log.d(TAG, "Swapped $id to its upgraded stream at ${position}ms")
    }

    // ---- Latency -----------------------------------------------------------------

    /** When the current item was asked for, to time how long it took to sound. */
    private var startRequestedAt = 0L

    private var warmJob: Job? = null

    /** Video ids whose stream URL was warmed, and when; StreamResolver holds them 20 minutes. */
    private val warmedAt = ConcurrentHashMap<String, Long>()

    /**
     * Resolve stream URLs for the tracks either side of this one in play
     * order (the next two and the previous one) before they're asked for.
     * Finding a working stream is the slow part of starting a track, a few
     * YouTube requests; with the URL already in [StreamResolver]'s cache a
     * skip or a jump back only has to fetch audio. ExoPlayer's own preload
     * then buffers the very next one.
     */
    private fun warmNeighbours() {
        if (!AppSettings.playback.value.preloadUpcoming) return
        val player = mediaSession?.player ?: return
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        val current = player.currentMediaItemIndex
        val previous = timeline.getPreviousWindowIndex(current, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        val indices = player.upcomingPlayOrder().drop(1).take(WARM_AHEAD) +
            listOfNotNull(previous.takeIf { it != C.INDEX_UNSET })
        val now = SystemClock.elapsedRealtime()
        val ids = indices.map { player.getMediaItemAt(it).mediaId }
            .filter { !LocalMusic.isLocal(it) && now - (warmedAt[it] ?: 0L) > WARM_TTL_MS }
            .distinct()
        if (ids.isEmpty()) return
        ids.forEach { warmedAt[it] = now }
        if (warmedAt.size > 64) warmedAt.entries.removeAll { now - it.value > WARM_TTL_MS }

        val previousJob = warmJob
        warmJob = scope.launch(Dispatchers.IO) {
            previousJob?.join()
            // One at a time: these compete with the playing track for bandwidth.
            for (id in ids) {
                runCatching { StreamResolver.resolve(id) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Warm-up for $id failed: ${e.message}")
                        warmedAt.remove(id)
                    }
            }
        }
    }

    // ---- Output device ---------------------------------------------------------

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = applyPreferredDevice()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = applyPreferredDevice()
    }

    /** Route to a USB DAC when one is plugged in and the setting asks for it. */
    private fun applyPreferredDevice() {
        val player = mediaSession?.player as? ExoPlayer ?: return
        val usb = if (AppSettings.playback.value.preferUsbDac) {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        } else {
            null
        }
        player.setPreferredAudioDevice(usb)
    }

    // ---- Autoplay ----------------------------------------------------------------

    /**
     * Append radio for the last queued track once playback nears the end, so
     * the music doesn't stop. Starting a single song goes through here too:
     * a one-track queue is already "near the end". With repeat on the queue
     * never ends, so there is nothing to extend.
     */
    private fun extendQueueIfNeeded() {
        val player = mediaSession?.player ?: return
        if (!AppSettings.playback.value.autoplay || player.repeatMode != Player.REPEAT_MODE_OFF) return
        val remaining = player.upcomingPlayOrder().size - 1
        if (!Autoplay.shouldExtend(remaining, player.mediaItemCount)) return
        val seed = player.getMediaItemAt(player.mediaItemCount - 1).mediaId
        // Radio is a YouTube feature; a local file has none.
        if (LocalMusic.isLocal(seed)) return
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
            if (AppSettings.playback.value.noRepeatInSession) queued += sessionIds
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

    /**
     * Sends streamed tracks through the cache and resolver, and anything else
     * (local `content://` files) straight to the platform data source, so local
     * files neither fill the cache nor go looking for a YouTube stream.
     */
    private class SchemeRoutingDataSource(
        private val streams: DataSource,
        private val local: DataSource,
    ) : DataSource {
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            streams.addTransferListener(transferListener)
            local.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val target = if (videoIdOf(dataSpec.uri) != null) streams else local
            active = target
            return target.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

        override fun close() {
            active?.close()
            active = null
        }

        class Factory(
            private val streams: DataSource.Factory,
            private val local: DataSource.Factory,
        ) : DataSource.Factory {
            override fun createDataSource(): DataSource =
                SchemeRoutingDataSource(streams.createDataSource(), local.createDataSource())
        }
    }

    private companion object {
        const val TAG = "PlaybackService"

        /** Bounds on the loudness gain, in millibels: down 15 dB, up 3 dB at most. */
        const val LOUDNESS_RETRY_MS = 6_000L
        const val UPGRADE_DELAY_MS = 8_000L
        const val UPGRADE_MIN_REMAINING_MS = 20_000L
        const val FAR_BUFFER_MS = 15 * 60 * 1000
        const val FAR_BUFFER_BYTES = 8 * 1024 * 1024
        const val BACK_BUFFER_MS = 30_000
        const val MIN_LOUDNESS_GAIN_MB = -1500
        const val MAX_LOUDNESS_GAIN_MB = 300

        /** How much of the next track ExoPlayer buffers ahead of time. */
        const val PRELOAD_US = 10_000_000L

        /** Tracks ahead of the current one whose stream URLs are resolved early. */
        const val WARM_AHEAD = 2

        /** A little under StreamResolver's 20-minute URL lifetime. */
        const val WARM_TTL_MS = 15 * 60 * 1000L
    }
}
