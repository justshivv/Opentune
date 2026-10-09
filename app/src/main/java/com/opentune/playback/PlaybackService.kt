package com.opentune.playback

import com.opentune.data.lossless.ExternalStreams
import com.opentune.data.model.durationMillis
import kotlinx.coroutines.ensureActive

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
import androidx.media3.session.LibraryResult
import com.opentune.widget.NowPlayingWidget
import com.opentune.data.radio.Radio
import com.opentune.data.podcasts.Podcasts
import com.opentune.data.sponsorblock.SponsorBlock
import android.widget.Toast
import android.os.Bundle
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionError
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.guava.future
import com.opentune.MainActivity
import com.opentune.data.DebugLog as Log
import com.opentune.data.isYouTubeId
import com.opentune.data.subsonic.Subsonic
import com.opentune.data.listenbrainz.ListenBrainz
import com.opentune.data.Http
import com.opentune.data.download.Downloads
import com.opentune.data.lastfm.LastFm
import com.opentune.data.library.LibraryStore
import com.opentune.data.library.SongRef
import com.opentune.data.NerdStats
import com.opentune.data.history.History
import com.opentune.data.local.LocalMusic
import com.opentune.data.innertube.StreamResolver
import com.opentune.data.model.Song
import com.opentune.data.reco.Recommendations
import com.opentune.data.settings.AppSettings
import com.opentune.playback.dsp.DspAudioProcessor
import com.opentune.playback.dsp.DspParams
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.media3.common.Tracks
import android.content.IntentFilter
import android.content.BroadcastReceiver
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
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null

    /** What Android Auto and other media browsers can browse; see [CarLibrary]. */
    private val car by lazy { CarLibrary(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var castMirror: CastMirror? = null
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
        // Files, content:// and direct http (a song on your own server) go straight
        // to the platform source, its http side on the app's one OkHttp client.
        val direct = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(Http.client))
        val routing = SchemeRoutingDataSource.Factory(streams = cached, local = direct)
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
            combine(AppSettings.equalizer, AppSettings.sound, AppSettings.playback, BitPerfectUsb.status, volumeTick) { eq, sound, pb, bp, _ ->
                // Bit-perfect means nothing but the volume touches the samples.
                if (bp is BitPerfectUsb.Status.Active) {
                    DspParams(outputGainDb = audioManager?.let(BitPerfectUsb::softwareGainDb) ?: 0f)
                } else {
                    DspParams(eq, sound.bassBoost, pb.spatialAudio, pb.clarity, eightDPeriod = sound.eightD.periodSeconds, spacePeriod = sound.space.periodSeconds)
                }
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
            AppSettings.playback.map { listOf(it.losslessStreaming, it.losslessUnmeteredOnly, it.losslessHiRes, it.jioSaavnQuality, it.qobuzRelayUrl, it.wifiQuality, it.mobileQuality) }
                .distinctUntilChanged().collect {
                    val item = player.currentMediaItem
                    val uri = item?.localConfiguration?.uri
                    if (item != null && uri != null) {
                        val rendition = externalStreamKeyOf(uri)?.let { ExternalStreams.get(item.mediaId, it) }
                        if (rendition != null && !ExternalStreams.allowed(rendition.lossless)) {
                            swapToUpgrade(player, item.mediaId, streamUri(item.mediaId, upgraded = false))
                        }
                    }
                    scheduleUpgrade()
                }
        }
        scope.launch {
            AppSettings.playback.map { Triple(it.loudnessNormalization, it.normalizeOnSpeaker, it.volumeLevel) }.distinctUntilChanged().collect { applyLoudness() }
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
        scope.launch {
            AppSettings.playback.map { it.systemEffects }.distinctUntilChanged().collect { openEffectSession(player.audioSessionId) }
        }
        crossfade = Crossfade(context, scope, sources, player).also { it.start() }
        // Casting: the device follows this player while one is connected.
        castMirror = CastMirror(context, player, scope)
        scope.launch {
            com.opentune.cast.Cast.session.map { it?.receiver }.distinctUntilChanged().collect { r ->
                if (r != null) castMirror?.attach(r) else castMirror?.detach()
            }
        }
        scope.launch { PlaybackRequests.upgrade.collect { scheduleUpgrade(force = true) } }

        audioManager = getSystemService(AudioManager::class.java)?.also { am ->
            am.registerAudioDeviceCallback(deviceCallback, null)
        }
        scope.launch {
            AppSettings.playback.map { it.preferUsbDac }.distinctUntilChanged().collect { applyPreferredDevice() }
        }
        scope.launch {
            AppSettings.playback.map { it.bitPerfectUsb to it.floatOutput }.distinctUntilChanged().collect { applyBitPerfect() }
        }
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), VOLUME_RECEIVER_FLAGS)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setSessionActivity(sessionActivity)
            .build()
        // Likes changed anywhere (the app, the widget) show on the heart at once.
        scope.launch { LibraryStore.liked.collect { refreshButtons() } }

        restoreQueue(player)
        scope.launch { keepQueueSaved(player) }
    }

    /**
     * Puts back the queue from last time, paused where it was left and not
     * prepared: nothing is fetched until play is pressed.
     */
    private fun restoreQueue(player: ExoPlayer) {
        if (player.mediaItemCount > 0) return
        val saved = QueueStore.load() ?: return
        val items = saved.songs.map { it.toSong().toMediaItem() }
        player.setMediaItems(items, saved.index.coerceIn(items.indices), saved.positionMs.coerceAtLeast(0))
    }

    /** Saves the queue every few seconds while it changes or plays. */
    private suspend fun keepQueueSaved(player: ExoPlayer) {
        var last: QueueStore.Saved? = null
        while (true) {
            delay(QUEUE_SAVE_MS)
            if (player.mediaItemCount == 0) continue
            val songs = (0 until player.mediaItemCount).map { SongRef.of(player.getMediaItemAt(it).toSong()) }
            val saved = QueueStore.Saved(songs, player.currentMediaItemIndex, player.currentPosition)
            if (saved == last) continue
            last = saved
            withContext(Dispatchers.IO) { QueueStore.save(saved) }
        }
    }

    /**
     * Pauses at the time the sleep timer names, or at the end of the current
     * track (ExoPlayer's own pause-at-end, so it lands on the boundary).
     */
    private suspend fun runSleepTimer(player: ExoPlayer) {
        SleepTimer.state.collectLatest { state ->
            player.pauseAtEndOfMediaItems = state is SleepTimer.State.EndOfTrack
            try {
                when (state) {
                    is SleepTimer.State.At -> {
                        // Winding down: the volume eases away over the last few minutes.
                        val fadeMs = SleepTimer.fadeMsFor(state.endsAtMs - state.startedAtMs)
                        while (true) {
                            val left = state.endsAtMs - System.currentTimeMillis()
                            if (left <= 0) break
                            if (AppSettings.playback.value.sleepWindDown && left < fadeMs) {
                                val f = 1f - left.toFloat() / fadeMs
                                com.opentune.playback.dsp.DspAudioProcessor.masterGain = SleepTimer.gainAt(f)
                                SleepTimer.setWindDown(f)
                                delay(200)
                            } else {
                                delay(minOf(left - fadeMs, 1_000L).coerceAtLeast(50))
                            }
                        }
                        player.pause()
                        SleepTimer.cancel()
                    }
                    SleepTimer.State.EndOfTrack -> {
                        // The last seconds of the song fade out, then pauseAtEndOfMediaItems pauses.
                        val stopped = scope.launch { playWhenReadyFlow(player).first { !it } }
                        while (stopped.isActive) {
                            val left = player.duration - player.currentPosition
                            if (AppSettings.playback.value.sleepWindDown && player.duration > 0 && left in 0 until END_FADE_MS) {
                                val f = 1f - left.toFloat() / END_FADE_MS
                                com.opentune.playback.dsp.DspAudioProcessor.masterGain = SleepTimer.gainAt(f)
                                SleepTimer.setWindDown(f)
                            }
                            delay(200)
                        }
                        SleepTimer.cancel()
                    }
                    SleepTimer.State.Off -> Unit
                }
            } finally {
                com.opentune.playback.dsp.DspAudioProcessor.masterGain = 1f
                SleepTimer.setWindDown(0f)
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
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
        (mediaSession?.player as? ExoPlayer)?.takeIf { it.mediaItemCount > 0 }?.let { p ->
            QueueStore.save(QueueStore.Saved((0 until p.mediaItemCount).map { SongRef.of(p.getMediaItemAt(it).toSong()) }, p.currentMediaItemIndex, p.currentPosition))
        }
        NowPlayingWidget.stopped(this)
        crossfade?.release()
        crossfade = null
        castMirror?.detach(resume = false)
        castMirror = null
        closeEffectSession()
        finishListen()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        runCatching { unregisterReceiver(volumeReceiver) }
        audioManager?.let { BitPerfectUsb.apply(it, wanted = false, sampleRate = outputRate, float = false) }
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
                val losslessKey = externalStreamKeyOf(dataSpec.uri)
                when {
                    losslessKey != null -> ExternalStreams.resolve(videoId, losslessKey)
                    isUpgradedUri(dataSpec.uri) -> StreamResolver.resolveUpgrade(videoId)
                    else -> StreamResolver.resolve(videoId)
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Couldn't resolve a stream for $videoId", e)
        }
        // googlevideo expects the media request to look like the client that
        // minted the URL; these are also the headers StreamResolver probed with.
        return dataSpec.withUri(url.toUri())
            .withAdditionalHeaders(if (externalStreamKeyOf(dataSpec.uri) != null) emptyMap() else StreamResolver.mediaHeadersFor(url))
    }

    /**
     * The session's callback, for the app's own controller and for media
     * browsers like Android Auto.
     *
     * Items that arrive from a controller may have lost their URI on the way
     * (Media3 only carries it across a binder in some cases), so it's rebuilt
     * from the media id, which always survives. A browser's items carry the
     * list they were shown in; [CarLibrary.resolve] queues that list.
     */
    private val sessionCallback = object : MediaLibrarySession.Callback {
        /** Every controller (notification, Android Auto, Bluetooth) also gets Like and Shuffle. */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(LIKE_COMMAND)
                .add(SHUFFLE_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(mediaButtons())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player
            when (customCommand.customAction) {
                ACTION_LIKE -> player.currentMediaItem?.toSong()?.let { song ->
                    LibraryStore.setLiked(song, !LibraryStore.isLiked(song.videoId))
                }
                ACTION_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            refreshButtons()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * "Resume" from the system's media controls or a Bluetooth play button
         * when the app isn't running: the queue saved last time, where it was.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = QueueStore.load()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            val items = saved.songs.map { it.toSong().toMediaItem() }
            if (items.isEmpty()) return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, saved.index.coerceIn(items.indices), saved.positionMs.coerceAtLeast(0)),
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map(car::toQueueItem).toMutableList(),
        )

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            car.resolve(mediaItems, startIndex, startPositionMs)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(car.root(), LibraryParams.Builder().setExtras(car.rootExtras()).build()),
        )

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            car.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                LibraryResult.ofItemList(car.children(parentId, browser).page(page, pageSize), params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't list $parentId for ${browser.packageName}", e)
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = runCatching { car.search(query, browser).also { searchResults[query] = it }.size }
                .onFailure { Log.w(TAG, "Search failed for ${browser.packageName}", it) }
                .getOrDefault(0)
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val items = searchResults[query] ?: runCatching { car.search(query, browser) }.getOrDefault(emptyList())
            LibraryResult.ofItemList(items.page(page, pageSize), params)
        }
    }

    /** Like (heart) and Shuffle, as the notification and Android Auto show them now. */
    private fun mediaButtons(): List<CommandButton> {
        val player = mediaSession?.player
        val liked = player?.currentMediaItem?.mediaId?.let(LibraryStore::isLiked) == true
        val shuffle = player?.shuffleModeEnabled == true
        return listOf(
            CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(if (liked) "Remove from Liked" else "Like")
                .setSessionCommand(LIKE_COMMAND)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(if (shuffle) "Shuffle off" else "Shuffle on")
                .setSessionCommand(SHUFFLE_COMMAND)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    private fun refreshButtons() {
        mediaSession?.setMediaButtonPreferences(mediaButtons())
        NowPlayingWidget.refresh(this, mediaSession?.player)
    }

    /** The last browser search's results, between onSearch and onGetSearchResult. */
    private val searchResults = object : LinkedHashMap<String, List<MediaItem>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MediaItem>>?) = size > 4
    }

    private fun List<MediaItem>.page(page: Int, pageSize: Int): List<MediaItem> =
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) this else drop(page * pageSize).take(pageSize)

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                for (i in 0 until player.mediaItemCount) sessionIds += player.getMediaItemAt(i).mediaId
            }
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                )
            ) {
                refreshButtons()
            }
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    // Asked again as playback starts and ends, in case an earlier ask came to nothing.
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) {
                extendQueueIfNeeded()
                warmNeighbours()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateExternalStats()
            applyLoudness()
            scheduleUpgrade()
            watchSegments(mediaItem)
            // A podcast episode picks up where it was left.
            mediaItem?.mediaId?.let(Podcasts::resumeAt)?.let { at ->
                mediaSession?.player?.takeIf { it.currentPosition < RESUME_SLACK_MS }?.seekTo(at)
            }
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
            mediaSession?.player?.let { watchPlayed(it, isPlaying) }
            if (!isPlaying) mediaSession?.player?.let(::saveEpisodePosition)
            if (isPlaying && startRequestedAt > 0) {
                NerdStats.onStartup(SystemClock.elapsedRealtime() - startRequestedAt)
                startRequestedAt = 0
            }
            if (isPlaying) {
                sendNowPlaying()
                if (listenSince < 0) listenSince = SystemClock.elapsedRealtime()
            } else {
                pauseListen()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                updateExternalStats()
                // The figure arrives with the stream; on a first play it's
                // only known once the track has resolved.
                applyLoudness()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val rate = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) }
                ?.sampleRate?.takeIf { it > 0 } ?: return
            if (rate != outputRate) {
                outputRate = rate
                if (AppSettings.playback.value.bitPerfectUsb) applyBitPerfect()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            openEffectSession(audioSessionId)
            applyLoudness()
        }

        /** A radio station names the song on air (ICY); shown as the station's second line. */
        override fun onMetadata(metadata: androidx.media3.common.Metadata) {
            val player = mediaSession?.player ?: return
            val item = player.currentMediaItem ?: return
            if (!Radio.isRadio(item.mediaId)) return
            val onAir = (0 until metadata.length()).firstNotNullOfOrNull { i ->
                (metadata[i] as? androidx.media3.extractor.metadata.icy.IcyInfo)?.title?.trim()?.takeIf { it.isNotEmpty() }
            } ?: return
            if (item.mediaMetadata.artist?.toString() == onAir) return
            // Same URI, so ExoPlayer updates the item in place without rebuffering.
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtist(onAir).build()).build(),
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error on ${mediaSession?.player?.currentMediaItem?.mediaId}: ${error.errorCodeName}", error)
            recover(error)
        }
    }

    // ---- Listening history ---------------------------------------------------

    private var listenSong: Song? = null
    private var listenedMs = 0L
    private var listenSince = -1L
    private var listenRecord: Long? = null

    private fun startListen(song: Song?, playing: Boolean) {
        // A radio station isn't a song: it stays out of history and scrobbles.
        listenSong = song?.takeUnless { Radio.isRadio(it.videoId) }
        listenedMs = 0
        listenSince = if (playing) SystemClock.elapsedRealtime() else -1
        listenRecord = null
        listenStartedAt = System.currentTimeMillis()
        scrobbled = false
        nowPlayingSent = false
        if (playing) sendNowPlaying()
    }

    /** Wall-clock start of the current listen, for the scrobble's timestamp. */
    private var listenStartedAt = 0L
    private var scrobbled = false
    private var nowPlayingSent = false

    private fun sendNowPlaying() {
        val song = listenSong ?: return
        if (nowPlayingSent) return
        nowPlayingSent = true
        val duration = mediaSession?.player?.duration?.takeIf { it > 0 } ?: 0L
        LastFm.nowPlaying(song, duration)
        ListenBrainz.playingNow(song, duration)
        if (Subsonic.isSubsonic(song.videoId)) scope.launch { Subsonic.scrobble(song.videoId, System.currentTimeMillis(), submission = false) }
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

    /** Keeps a podcast episode's place, so it resumes there. */
    private fun saveEpisodePosition(player: Player) {
        val id = player.currentMediaItem?.mediaId ?: return
        if (!Podcasts.isEpisode(id)) return
        Podcasts.savePosition(id, player.currentPosition, player.duration.takeIf { it > 0 } ?: 0L)
    }

    /** A track counts as played after 30 seconds, or half its length if shorter. */
    private suspend fun trackListening(player: Player) {
        var ticks = 0
        while (scope.isActive) {
            delay(5_000)
            if (player.isPlaying && ++ticks % 3 == 0) saveEpisodePosition(player)
            val song = listenSong ?: continue
            val heard = listenedSoFar()
            val known = player.duration.takeIf { it > 0 }
            // Last.fm's rule: a song over 30 s, heard for half its length or 4 minutes.
            if (!scrobbled && known != null && known > 30_000 && heard >= minOf(known / 2, 240_000L)) {
                scrobbled = true
                LastFm.scrobble(song, listenStartedAt, known)
                ListenBrainz.listened(song, listenStartedAt, known)
                if (Subsonic.isSubsonic(song.videoId)) scope.launch { Subsonic.scrobble(song.videoId, listenStartedAt, submission = true) }
            }
            if (listenRecord != null) continue
            val duration = known ?: Long.MAX_VALUE
            val threshold = minOf(30_000L, duration / 2)
            if (heard >= threshold) {
                listenRecord = History.record(song, heard)
                com.opentune.data.Usage.played()
            }
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
        if (!AppSettings.playback.value.systemEffects || BitPerfectUsb.active) return closeEffectSession()
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
        val lossless = player.currentMediaItem?.localConfiguration?.uri?.let(::externalStreamKeyOf) != null
        // YouTube's gain belongs to its own master, not the external FLAC.
        val db = if (lossless) null else id?.let { StreamResolver.loudnessDbFor(it) ?: Downloads.loudnessFor(it) }
        // A track playing from the cache never resolved, so its figure may not
        // be known yet. Look it up once, a moment in, then apply it.
        loudnessRetry?.cancel()
        if (!lossless && db == null && id != null && isYouTubeId(id) && AppSettings.playback.value.loudnessNormalization) {
            loudnessRetry = scope.launch {
                delay(LOUDNESS_RETRY_MS)
                withContext(Dispatchers.IO) { runCatching { StreamResolver.resolve(id) } }
                if (player.currentMediaItem?.mediaId == id && StreamResolver.loudnessDbFor(id) != null) applyLoudness()
            }
        }
        // On the phone's own speaker, levelling only takes the loud masters
        // down, and a small speaker needs every dB; there it waits for headphones.
        val pb = AppSettings.playback.value
        val speakerHold = pb.loudnessNormalization && !pb.normalizeOnSpeaker && onPhoneSpeaker()
        NerdStats.onLoudnessOffOnSpeaker(speakerHold)
        val on = pb.loudnessNormalization && db != null && !BitPerfectUsb.active
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
                val gainMb = (loudnessGainDb(db!!, pb.volumeLevel, speakerHold) * 100).roundToInt().coerceIn(MIN_LOUDNESS_GAIN_MB, MAX_LOUDNESS_GAIN_MB)
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
        if (!force && !AppSettings.playback.value.qualityUpgrade && !ExternalStreams.allowed()) return
        val player = mediaSession?.player as? ExoPlayer ?: return
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        val id = videoIdOf(uri) ?: return
        if (externalStreamKeyOf(uri) != null) return
        upgradeJob = scope.launch {
            if (!force) delay(UPGRADE_DELAY_MS)
            val duration = player.duration.takeIf { it > 0 } ?: item.toSong().durationMillis()
            val lossless = if (Podcasts.isEpisode(id)) null else ExternalStreams.find(item.toSong(), duration)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (player.currentMediaItem?.localConfiguration?.uri != uri) return@launch
            if (lossless != null && ExternalStreams.allowed(lossless.lossless)) {
                val left = player.duration - player.currentPosition
                if (player.duration > 0 && left < UPGRADE_MIN_REMAINING_MS) return@launch
                swapToUpgrade(player, id, externalStreamUri(id, lossless.key))
                return@launch
            }
            if (isUpgradedUri(uri) || (!force && !AppSettings.playback.value.qualityUpgrade)) return@launch
            val found = withContext(Dispatchers.IO) { runCatching { StreamResolver.findUpgrade(id, force) }.getOrDefault(false) }
            if (!found || player.currentMediaItem?.mediaId != id) return@launch
            val left = player.duration - player.currentPosition
            if (player.duration > 0 && left < UPGRADE_MIN_REMAINING_MS) return@launch
            swapToUpgrade(player, id)
        }
    }

    private fun swapToUpgrade(player: ExoPlayer, id: String, uri: android.net.Uri = streamUri(id, upgraded = true)) {
        val index = player.currentMediaItemIndex
        val item = player.getMediaItemAt(index)
        val position = player.currentPosition
        swappingTo = id
        // If the player updates the item in place there's no transition to clear this.
        scope.launch { delay(2_000); if (swappingTo == id) swappingTo = null }
        player.replaceMediaItem(index, item.buildUpon().setUri(uri).build())
        updateExternalStats()
        applyLoudness()
        if (player.currentMediaItemIndex != index || player.currentPosition < position - 1_000) player.seekTo(index, position)
        Log.d(TAG, "Swapped $id to its upgraded stream at ${position}ms")
    }

    private fun updateExternalStats() {
        val item = mediaSession?.player?.currentMediaItem
        val key = item?.localConfiguration?.uri?.let(::externalStreamKeyOf)
        val rendition = if (item != null && key != null) ExternalStreams.get(item.mediaId, key) else null
        NerdStats.onExternalSource(rendition?.label)
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
            .filter { isYouTubeId(it) && now - (warmedAt[it] ?: 0L) > WARM_TTL_MS }
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

    // ---- SponsorBlock ------------------------------------------------------------

    private var segmentsJob: Job? = null

    /**
     * Looks up [item]'s SponsorBlock segments and, while it plays, jumps
     * over each one the first time playback enters it. Seeking back into a
     * segment afterwards plays it: that was asked for.
     */
    private fun watchSegments(item: MediaItem?) {
        segmentsJob?.cancel()
        val id = item?.mediaId ?: return
        val pb = AppSettings.playback.value
        if (!pb.sponsorBlock || !isYouTubeId(id)) return
        segmentsJob = scope.launch {
            val segments = SponsorBlock.segments(id, pb.sponsorBlockCategories)
            if (segments.isEmpty()) return@launch
            val player = mediaSession?.player ?: return@launch
            val skipped = HashSet<String>()
            while (player.currentMediaItem?.mediaId == id && skipped.size < segments.size) {
                delay(if (player.isPlaying) SEGMENT_POLL_MS else SEGMENT_IDLE_POLL_MS)
                if (!player.isPlaying) continue
                val at = player.currentPosition
                val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                val segment = segments.firstOrNull {
                    it.uuid !in skipped && at >= it.startMs && at < it.endMs - SEGMENT_END_SLACK_MS && SponsorBlock.fits(it, duration)
                } ?: continue
                skipped += segment.uuid
                Log.i(TAG, "SponsorBlock: skipping ${segment.category} ${segment.startMs}-${segment.endMs} ms")
                if (duration > 0 && segment.endMs >= duration - SEGMENT_END_SLACK_MS) {
                    // The segment runs to the end: that's the song over.
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(duration)
                } else {
                    player.seekTo(segment.endMs)
                }
                Toast.makeText(this@PlaybackService, "Skipped ${SponsorBlock.CATEGORIES[segment.category]?.one ?: "a segment"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- Output device ---------------------------------------------------------

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            applyPreferredDevice()
            // The callback reports every device already there as it's
            // registered; only one that turns up afterwards is a connection.
            if (!devicesListed) {
                devicesListed = true
                return
            }
            if (addedDevices.orEmpty().any { it.isSink && it.type in LISTENING_DEVICES }) resumeOnConnect()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = applyPreferredDevice()
    }
    private var devicesListed = false

    /**
     * Headphones or a Bluetooth device came on: play the paused queue, if
     * the setting is on and there's something to play. Audio routes to the
     * new device the moment it appears, so this doesn't start on the speaker.
     */
    private fun resumeOnConnect() {
        val player = mediaSession?.player ?: return
        if (!AppSettings.playback.value.resumeOnConnect || player.mediaItemCount == 0 || player.playWhenReady) return
        Log.i(TAG, "Output device connected; resuming")
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    /** Bumped when the volume keys move, so the bit-perfect software volume follows. */
    private val volumeTick = MutableStateFlow(0)

    /** Set while paused by "Pause at zero volume", so raising it again resumes. */
    private var pausedAtZero = false

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            volumeTick.value++
            if (intent?.getIntExtra(EXTRA_VOLUME_STREAM, AudioManager.STREAM_MUSIC) == AudioManager.STREAM_MUSIC) onMusicVolume()
        }
    }

    private fun onMusicVolume() {
        val player = mediaSession?.player ?: return
        val volume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return
        if (!AppSettings.playback.value.pauseAtZeroVolume) {
            pausedAtZero = false
            return
        }
        if (volume == 0 && player.playWhenReady) {
            pausedAtZero = true
            player.pause()
        } else if (volume > 0 && pausedAtZero) {
            pausedAtZero = false
            player.play()
        }
    }

    /** The decoder's output rate for the current track, for the bit-perfect mixer. */
    private var outputRate = 48_000

    private fun applyBitPerfect() {
        val am = audioManager ?: return
        val pb = AppSettings.playback.value
        BitPerfectUsb.apply(am, pb.bitPerfectUsb, outputRate, pb.floatOutput)
        applyLoudness()
        mediaSession?.player?.let { openEffectSession((it as ExoPlayer).audioSessionId) }
    }

    /**
     * Whether sound is going to the phone's own speaker: no headphones,
     * Bluetooth audio, USB, HDMI, dock or line output is connected. Android
     * routes media to any of those as soon as it appears.
     */
    private fun onPhoneSpeaker(): Boolean {
        val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
        return outputs.none { it.isSink && it.type in EXTERNAL_OUTPUTS }
    }

    /** Route to a USB DAC when one is plugged in and the setting asks for it. */
    private fun applyPreferredDevice() {
        applyBitPerfect()
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
        val seedItem = player.getMediaItemAt(player.mediaItemCount - 1)
        val seed = seedItem.mediaId
        // Radio is a YouTube feature; a local file has none. A song on your own
        // server carries on with more of the server's songs, picked at random.
        if (LocalMusic.isLocal(seed) || Radio.isRadio(seed)) return
        if (seed in exhaustedSeeds) return
        if (radioJob?.isActive == true && radioSeed == seed) return

        // A request for an older seed is answering a queue that no longer exists.
        radioJob?.cancel()
        radioSeed = seed
        radioJob = scope.launch {
            // A song started on its own asks for radio while its own stream is
            // still loading, which is when a request is most likely to fail; a
            // failure must not leave the song to end in silence, so ask again.
            var radio: List<Song>? = null
            for ((attempt, wait) in RADIO_RETRY_MS.withIndex()) {
                if (wait > 0) delay(wait)
                radio = try {
                    if (Subsonic.isSubsonic(seed)) {
                        Subsonic.randomSongs(RADIO_FROM_SERVER)
                    } else {
                        // YouTube Music, Spotify or JioSaavn, as chosen; the songs always play from YouTube Music.
                        Recommendations.after(seedItem.toSong(), AppSettings.playback.value.recommender).songs
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Radio for $seed failed (try ${attempt + 1})", e)
                    null
                }
                if (!radio.isNullOrEmpty()) break
            }
            if (radio.isNullOrEmpty()) return@launch
            // The queue may have been replaced while the request was out; radio
            // for a track that's no longer last doesn't belong at the end.
            val count = player.mediaItemCount
            if (count == 0 || player.getMediaItemAt(count - 1).mediaId != seed) return@launch

            val queued = (0 until count).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
            val liked = radio.filterNot { LibraryStore.isDisliked(it.videoId) }
            // Songs already heard this session are skipped when asked, unless that leaves nothing to play.
            val additions = Autoplay.newTracks(if (AppSettings.playback.value.noRepeatInSession) queued + sessionIds else queued, liked)
                .ifEmpty { Autoplay.newTracks(queued, liked) }
            if (additions.isEmpty()) {
                exhaustedSeeds += seed
                return@launch
            }
            val ended = player.playbackState == Player.STATE_ENDED
            player.addMediaItems(additions.map { it.toMediaItem() })
            // The song finished before the next ones came; carry on into them.
            if (ended) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    /**
     * What to do when the current track fails:
     *  - an upgraded stream goes back to the stream the track started on, at
     *    the same place, rather than costing the track;
     *  - anything that may pass is retried once;
     *  - then the track is skipped, but only up to [MAX_FAILED_IN_ROW] tracks
     *    in a row. Past that, every stream is failing for the same reason
     *    (network, a YouTube change), and skipping on would only run through
     *    the queue and autoplay without a sound. Playback stops on the error
     *    instead, so it's shown, and play tries again.
     */
    private fun recover(error: PlaybackException) {
        val player = mediaSession?.player as? ExoPlayer ?: return
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        val uri = item.localConfiguration?.uri
        if (uri != null && (isUpgradedUri(uri) || externalStreamKeyOf(uri) != null)) {
            Log.w(TAG, "Upgraded stream for $mediaId failed (${error.errorCodeName}); back to the one it started on")
            StreamResolver.dropUpgrade(mediaId)
            if (externalStreamKeyOf(uri) != null) ExternalStreams.failed(mediaId)
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            swappingTo = mediaId
            player.replaceMediaItem(index, item.buildUpon().setUri(streamUri(mediaId, upgraded = false)).build())
            updateExternalStats()
            applyLoudness()
            player.seekTo(index, position)
            player.prepare()
            return
        }
        val permanent = generateSequence<Throwable>(error) { it.cause }
            .any { it is StreamResolver.PermanentlyUnplayableException }

        if (!permanent && retriedMediaId != mediaId) {
            Log.w(TAG, "Retrying $mediaId after ${error.errorCodeName}")
            retriedMediaId = mediaId
            player.prepare()
            return
        }
        failedInRow++
        if (failedInRow >= MAX_FAILED_IN_ROW) {
            Log.w(TAG, "$failedInRow tracks in a row failed (last: $mediaId, ${error.errorCodeName}); stopping")
            failedInRow = 0
            return
        }
        if (player.hasNextMediaItem()) {
            Log.w(TAG, "Skipping $mediaId after ${error.errorCodeName}")
            player.seekToNextMediaItem()
            player.prepare()
        }
    }

    /** Tracks in a row that failed and were skipped; see [recover]. */
    private var failedInRow = 0
    private var playedCheck: Job? = null

    /**
     * A track counts as working once it has really played for a while, not
     * when it first reports ready: a stream refused a few seconds in is
     * ready first and fails after. Until then its retry stays spent and
     * the failures in a row stand.
     */
    private fun watchPlayed(player: Player, playing: Boolean) {
        playedCheck?.cancel()
        if (!playing) return
        val id = player.currentMediaItem?.mediaId ?: return
        playedCheck = scope.launch {
            delay(PLAYED_OK_MS)
            if (player.isPlaying && player.currentMediaItem?.mediaId == id) {
                failedInRow = 0
                retriedMediaId = null
            }
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
        /**
         * The volume broadcast comes from the system, so the receiver is
         * exported; a spoofed one only makes the gain re-read the real volume.
         */
        val VOLUME_RECEIVER_FLAGS = if (android.os.Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        const val QUEUE_SAVE_MS = 5_000L
        const val UPGRADE_DELAY_MS = 8_000L
        /** Songs a server queue carries on with when it runs out. */
        const val RADIO_FROM_SERVER = 25
        /** With "end of song", how long the song fades out before it stops. */
        const val END_FADE_MS = 15_000L
        /** Waits before each ask for radio: at once, then twice more if it fails. */
        val RADIO_RETRY_MS = longArrayOf(0L, 3_000L, 10_000L)
        /** An episode only jumps to its saved place if it hasn't got going yet. */
        const val RESUME_SLACK_MS = 5_000L
        const val SEGMENT_POLL_MS = 250L
        const val SEGMENT_IDLE_POLL_MS = 1_000L
        const val SEGMENT_END_SLACK_MS = 500L
        /** The stream a VOLUME_CHANGED_ACTION is about (a hidden AudioManager extra). */
        const val EXTRA_VOLUME_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        /**
         * Outputs a person listens on privately, as opposed to the phone's
         * speaker. The BLE types are plain ints that older Android never reports.
         */
        @android.annotation.SuppressLint("InlinedApi")
        val LISTENING_DEVICES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        /** Outputs that take media away from the phone's speaker. */
        val EXTERNAL_OUTPUTS = LISTENING_DEVICES + setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_DOCK,
        )
        const val ACTION_LIKE = "com.opentune.LIKE"
        const val ACTION_SHUFFLE = "com.opentune.SHUFFLE"
        val LIKE_COMMAND = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
        val SHUFFLE_COMMAND = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)
        /** Failed tracks in a row before playback stops on the error. */
        const val MAX_FAILED_IN_ROW = 3
        /** Playing this long means the track's stream really works. */
        const val PLAYED_OK_MS = 10_000L
        const val UPGRADE_MIN_REMAINING_MS = 20_000L
        const val FAR_BUFFER_MS = 15 * 60 * 1000
        const val FAR_BUFFER_BYTES = 8 * 1024 * 1024
        const val BACK_BUFFER_MS = 30_000
        const val MIN_LOUDNESS_GAIN_MB = -1500
        const val MAX_LOUDNESS_GAIN_MB = 900

        /** How much of the next track ExoPlayer buffers ahead of time. */
        const val PRELOAD_US = 10_000_000L

        /** Tracks ahead of the current one whose stream URLs are resolved early. */
        const val WARM_AHEAD = 2

        /** A little under StreamResolver's 20-minute URL lifetime. */
        const val WARM_TTL_MS = 15 * 60 * 1000L
    }
}

/**
 * The gain, in dB, for a song YouTube measured at [loudnessDb] against its
 * reference: brought to the reference, moved by [level]'s offset, and lifted
 * no more than its cap. With [speakerHold] (the phone speaker, normalization
 * held off there) it's never negative: a song is only lifted, never cut.
 */
internal fun loudnessGainDb(loudnessDb: Double, level: com.opentune.data.settings.VolumeLevel, speakerHold: Boolean): Double {
    val target = (-loudnessDb + level.offsetDb).coerceAtMost(level.maxGainDb.toDouble())
    return if (speakerHold) target.coerceAtLeast(0.0) else target
}
