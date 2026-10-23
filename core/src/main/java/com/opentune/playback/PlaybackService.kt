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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import com.opentune.data.radio.Radio
import com.opentune.data.podcasts.Podcasts
import com.opentune.data.sponsorblock.SponsorBlock
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
import com.opentune.data.DebugLog as Log
import com.opentune.data.isYouTubeId
import com.opentune.data.subsonic.Subsonic
import com.opentune.data.listenbrainz.ListenBrainz
import com.opentune.data.download.Downloads
import com.opentune.data.lastfm.LastFm
import com.opentune.data.library.LibraryStore
import com.opentune.data.library.SongRef
import com.opentune.data.NerdStats
import com.opentune.data.history.History
import com.opentune.data.local.LocalMusic
import com.opentune.data.innertube.StreamResolver
import com.opentune.data.model.QueueTier
import com.opentune.data.model.Song
import com.opentune.data.reco.Recommendations
import com.opentune.data.settings.AppSettings
import com.opentune.playback.dsp.DspAudioProcessor
import com.opentune.playback.dsp.DspParams
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
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
    /** The same effects, for the bridge player that carries a song across a quality swap. */
    private val bridgeDsp = DspAudioProcessor()
    private var audioManager: AudioManager? = null
    private val startupSelections = ConcurrentHashMap<String, StartupSelection>()

    private fun selectedUri(uri: Uri): Uri = startupToken(uri)?.let { startupSelections[it]?.selected } ?: uri

    override fun onCreate() {
        super.onCreate()
        val context: Context = this

        val songSources = SongSources(context)
        val cached = songSources.cached
        val direct = songSources.direct
        val sources = DefaultMediaSourceFactory(songSources.routing)
        val qualitySources = object : MediaSource.Factory by sources {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val input = mediaItem.localConfiguration?.uri ?: return sources.createMediaSource(mediaItem)
                if (videoIdOf(input) == null) return sources.createMediaSource(mediaItem)
                val addonKey = externalStreamKeyOf(input)
                val addon = addonKey?.let { ExternalStreams.get(mediaItem.mediaId, it) }?.takeIf { it.addonId != null }
                if (addon != null) {
                    val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(com.opentune.data.addons.AddonHttp.mediaClient)
                    val resolving = androidx.media3.datasource.ResolvingDataSource.Factory(
                        androidx.media3.datasource.DefaultDataSource.Factory(context, http)
                    ) { spec ->
                        // Re-check enablement/output before each manifest or segment request.
                        val resolved = ExternalStreams.resolve(mediaItem.mediaId, addon.key)
                        if (externalStreamKeyOf(spec.uri) != null) spec.withUri(android.net.Uri.parse(resolved)) else spec
                    }
                    // Kept in the song cache under the add-on stream's own address (not the
                    // short-lived URL it resolves to), so an upgrade can be fetched ahead
                    // by a silent player and then played from what's already here.
                    val data = androidx.media3.datasource.cache.CacheDataSource.Factory()
                        .setCache(AudioCache.get(context))
                        .setUpstreamDataSourceFactory(resolving)
                        .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    return DefaultMediaSourceFactory(data).createMediaSource(mediaItem.buildUpon().setMimeType(addon.mimeType).build())
                }
                val uri = withoutStartup(input)
                val id = videoIdOf(uri) ?: return sources.createMediaSource(mediaItem)
                if (!isYouTubeId(id) || Podcasts.isEpisode(id) || externalStreamKeyOf(uri) != null ||
                    uri.getQueryParameter("fallback") == "youtube") return sources.createMediaSource(mediaItem)
                val token = java.util.UUID.randomUUID().toString()
                val tagged = uri.buildUpon().appendQueryParameter("startup", token).build()
                val selection = StartupSelection(mediaItem.toSong(), uri)
                // Selection wraps the cache, so external audio never enters a YouTube cache entry.
                val choosing = ResolvingDataSource.Factory(cached) { spec ->
                    startupSelections[token] = selection
                    val resolved = runBlocking { selection.resolve(spec) }
                    scope.launch { updateExternalStats(); applyLoudness() }
                    resolved
                }
                return DefaultMediaSourceFactory(SchemeRoutingDataSource.Factory(choosing, direct))
                    .createMediaSource(mediaItem.buildUpon().setUri(tagged).build())
            }
        }

        val floatOutput = AppSettings.playback.value.floatOutput
        // The main player and the bridge decode the same way: the bridge used to
        // take the phone's default FLAC decoder, which some phones can't play
        // add-on FLAC with, so the swap to it failed and the song stayed on YouTube.
        fun renderersFor(effects: DspAudioProcessor) = object : DefaultRenderersFactory(context) {
            init {
                setEnableDecoderFallback(true)
                setMediaCodecSelector { mime, secure, tunneling ->
                    val selector = if (mime == "audio/flac") androidx.media3.exoplayer.mediacodec.MediaCodecSelector.PREFER_SOFTWARE
                        else androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                    selector.getDecoderInfos(mime, secure, tunneling).filterNot {
                        mime == "audio/flac" && LosslessAudioPolicy.unsafeFlacDecoder(it.name)
                    }
                }
            }
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = com.opentune.playback.dsp.PrecisionAudioSink(
                // The effects run in the precision sink, on float samples, not in Media3's chain.
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(floatOutput)
                    .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain())
                    .build(),
                effects,
                floatWanted = { floatOutput },
            )
        }
        val renderers = renderersFor(dsp)

        // Start as soon as a little audio is in: the defaults wait for 2.5 s,
        // which is video-sized caution. A stream starts after half a second buffered
        // and a local file after a quarter second.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(30_000, FAR_BUFFER_MS, 500, 2_000)
            .setBufferDurationsMsForLocalPlayback(5_000, FAR_BUFFER_MS, 250, 500)
            // The byte ceiling decides, not the time one: a whole song at CD
            // quality, a couple of minutes at 24-bit/96 kHz, so a fetched track is
            // fetched once and seeks within it are instant. 8 MB held only about
            // 15 s of hi-res FLAC, and any dip in the connection stalled it.
            .setTargetBufferBytes(FAR_BUFFER_BYTES)
            // However heavy the stream, at least the minimum time ahead is held.
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(BACK_BUFFER_MS, true)
            .build()

        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(qualitySources)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // On a watch, music waits for Bluetooth headphones rather than playing from
            // its speaker, and Wear OS offers to connect some. Other devices are unaffected.
            .setSuppressPlaybackOnUnsuitableOutput(true)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioMimeTypes("audio/flac", "audio/alac", "audio/raw").build()
        // A silent, never-playing copy of the player for upgrades: it loads the
        // better stream from where the song is, through the same sources and
        // so into the same cache, and the swap then reads what it fetched.
        auditionPlayer = {
            ExoPlayer.Builder(context, renderersFor(bridgeDsp))
                .setMediaSourceFactory(qualitySources)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMsForStreaming((AUDITION_AHEAD_MS + 10_000).toInt(), (AUDITION_AHEAD_MS + 20_000).toInt(), 500, 1_000)
                        .build(),
                )
                .build()
                .apply { volume = 0f; playWhenReady = false }
        }
        player.addListener(playerListener)
        player.addAnalyticsListener(PipelineTelemetry)

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
            }.distinctUntilChanged().collect { dsp.params = it; bridgeDsp.params = it }
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
                    val uri = item?.localConfiguration?.uri?.let(::selectedUri)
                    if (item != null && uri != null) {
                        val rendition = externalStreamKeyOf(uri)?.let { ExternalStreams.get(item.mediaId, it) }
                        if (rendition != null && !ExternalStreams.allowed(rendition.lossless)) {
                            swapToUpgrade(player, item.mediaId, youtubeFallbackUri(item.mediaId))
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
        scope.launch {
            com.opentune.data.addons.AddonStore.revision.collect { revision ->
                // Searched again at once, the misses forgotten, but quietly: not as if asked from the menu.
                if (revision > 0) scheduleUpgrade(force = true, asked = false)
            }
        }
        scope.launch { PlaybackRequests.upgrade.collect { scheduleUpgrade(force = true) } }
        scope.launch { guardQuality() }
        // A stream picked in Search's Add-ons tab that came in after its song had started.
        scope.launch {
            PlaybackRequests.picked.collect { id ->
                val player = mediaSession?.player as? ExoPlayer ?: return@collect
                if (player.currentMediaItem?.mediaId != id) return@collect
                val rendition = ExternalStreams.cached(id)?.takeIf { it.picked } ?: return@collect
                val uri = player.currentMediaItem?.localConfiguration?.uri ?: return@collect
                if (externalStreamKeyOf(selectedUri(uri)) == rendition.key) return@collect
                upgradeJob?.cancel()
                swapToUpgrade(player, id, externalStreamUri(id, rendition.key))
            }
        }
        // A higher-quality FLAC from an add-on that answered after the search had picked one:
        // once any switch already under way is done, the song moves onto it the same seamless way.
        scope.launch {
            ExternalStreams.improved.collect { id ->
                val player = mediaSession?.player as? ExoPlayer ?: return@collect
                if (player.currentMediaItem?.mediaId != id) return@collect
                upgradeJob?.join()
                if (player.currentMediaItem?.mediaId != id) return@collect
                val better = ExternalStreams.cached(id)?.takeIf { it.lossless && ExternalStreams.allowed(true) } ?: return@collect
                val playing = player.currentMediaItem?.localConfiguration?.uri ?: return@collect
                val existing = externalStreamKeyOf(selectedUri(playing))?.let { ExternalStreams.get(id, it) }
                if (existing?.key == better.key) return@collect
                if (existing?.lossless == true && com.opentune.data.addons.AddonResolver.qualityScore(better.bits, better.sampleRate) <=
                    com.opentune.data.addons.AddonResolver.qualityScore(existing.bits, existing.sampleRate)) return@collect
                if (player.duration > 0 && player.duration - player.currentPosition < UPGRADE_MIN_REMAINING_MS) return@collect
                if (swapToUpgrade(player, id, externalStreamUri(id, better.key))) updateExternalStats()
            }
        }

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
            com.opentune.data.AppHooks.openApp(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setSessionActivity(sessionActivity)
            .build()
        // Earlier versions posted the song as a Live Update on its own channel. That's
        // gone; clear the channel so it doesn't linger in the phone's settings.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getSystemService(android.app.NotificationManager::class.java)?.run {
                cancel(7_160)
                deleteNotificationChannel("live_update")
            }
        }
        // Likes changed anywhere (the app, the widget) show on the heart at once.
        scope.launch { LibraryStore.liked.collect { refreshButtons() } }
        scope.launch { skipDisliked(player) }

        restoreQueue(player)
        scope.launch { keepQueueSaved(player) }
    }

    /**
     * A song disliked anywhere (the app, the notification, a car) stops being
     * heard: if it's playing it's skipped, and the copies of it still to come
     * leave the queue. Songs already played stay, so going back still works.
     */
    private suspend fun skipDisliked(player: ExoPlayer) {
        var before: Set<String>? = null
        LibraryStore.disliked.collect { now ->
            val fresh = before?.let { now - it }.orEmpty()
            before = now
            if (fresh.isEmpty()) return@collect
            // A guest's queue mirrors the host's: it asks the host to skip, and changes nothing here.
            if (com.opentune.data.together.Together.room.value?.following == true) {
                if (player.currentMediaItem?.mediaId in fresh) com.opentune.data.together.Together.intercept(com.opentune.data.together.Together.Control.Next)
                return@collect
            }
            val current = player.currentMediaItemIndex
            for (i in player.mediaItemCount - 1 downTo current + 1) {
                if (player.getMediaItemAt(i).mediaId in fresh) player.removeMediaItem(i)
            }
            if (player.currentMediaItem?.mediaId in fresh && !com.opentune.data.together.Together.intercept(com.opentune.data.together.Together.Control.Next)) {
                if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause()
            }
        }
    }

    /**
     * Puts back the queue from last time, paused where it was left and not
     * prepared: nothing is fetched until play is pressed.
     */
    private fun restoreQueue(player: ExoPlayer) {
        if (player.mediaItemCount > 0) return
        val saved = QueueStore.load() ?: return
        val items = saved.songs.mapIndexed { i, ref ->
            ref.toSong().copy(queueTier = saved.tiers?.getOrNull(i) ?: QueueTier.CONTEXT).toMediaItem()
        }
        player.setMediaItems(items, saved.index.coerceIn(items.indices), saved.positionMs.coerceAtLeast(0))
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeat
    }

    /**
     * With the whole queue on repeat, songs queued by hand play once: when a
     * song of the list comes round, the ones already played leave.
     */
    private fun dropPlayedHandQueued() {
        val player = mediaSession?.player ?: return
        if (player.repeatMode != Player.REPEAT_MODE_ALL || player.shuffleModeEnabled) return
        val tiers = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).queueTier }
        QueueTimeline.playedUser(tiers, player.currentMediaItemIndex).asReversed().forEach(player::removeMediaItem)
    }

    /** Saves the queue every few seconds while it changes or plays. */
    private suspend fun keepQueueSaved(player: ExoPlayer) {
        var last: QueueStore.Saved? = null
        while (true) {
            delay(QUEUE_SAVE_MS)
            if (player.mediaItemCount == 0) continue
            val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            val tiers = items.map { it.queueTier }
            val saved = QueueStore.Saved(
                items.map { SongRef.of(it.toSong()) }, player.currentMediaItemIndex, player.currentPosition,
                tiers = tiers.takeIf { t -> t.any { it != QueueTier.CONTEXT } },
                shuffle = player.shuffleModeEnabled,
                repeat = player.repeatMode,
            )
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
        com.opentune.data.AppHooks.onPlayerStopped(this)
        crossfade?.release()
        crossfade = null
        castMirror?.detach(resume = false)
        castMirror = null
        closeEffectSession()
        finishListen()
        // The listen just finished, and anything else not yet saved, before the process goes.
        com.opentune.data.Core.flush()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        runCatching { unregisterReceiver(volumeReceiver) }
        audioManager?.let { BitPerfectUsb.apply(it, wanted = false, sampleRate = outputRate, float = false) }
        scope.cancel()
        startupSelections.clear()
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
        com.opentune.data.AppHooks.onPlayerChanged(this, mediaSession?.player)
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
                // Songs added after the current one started (radio, Play next) get readied too.
                prefetchAhead()
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
            // The same song put onto its better stream: the swap that did it is
            // still running, waiting to bring the volume back up. A new upgrade
            // check here used to cancel it and leave every song after silent.
            val swap = mediaItem != null && mediaItem.mediaId == swappingTo
            // A quality switch says what's playing once the new stream really is (see swapToUpgrade).
            if (!swap) updateExternalStats()
            applyLoudness()
            if (!swap) scheduleUpgrade()
            if (!swap) prefetchAhead()
            // After this callback, not inside it: the queue changes under it otherwise.
            if (!swap) scope.launch(Dispatchers.Main) { dropPlayedHandQueued() }
            // Nothing but a blend or a swap turns the main player down; outside
            // one, a new song always starts at full level.
            (mediaSession?.player as? ExoPlayer)?.let { p ->
                if (!swap && !swapRamping && crossfade?.blending != true && p.volume < 1f) {
                    Log.w(TAG, "Player volume was left at ${p.volume}; back to full")
                    p.volume = 1f
                }
            }
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
            watchStall(playbackState)
            if (playbackState == Player.STATE_READY) {
                updateExternalStats()
                // The figure arrives with the stream; on a first play it's
                // only known once the track has resolved.
                applyLoudness()
                if (mediaSession?.player?.currentMediaItem?.localConfiguration?.uri?.let(::startupToken) != null && upgradeJob?.isActive != true) scheduleUpgrade()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val format = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) } ?: return
            val item = mediaSession?.player?.currentMediaItem
            val rendition = item?.localConfiguration?.uri?.let(::externalStreamKeyOf)?.let { ExternalStreams.get(item.mediaId, it) }
            if (rendition?.addonId != null && format.sampleMimeType != null &&
                format.sampleMimeType !in setOf("audio/flac", "audio/alac", "audio/raw")) {
                scope.launch { recover(PlaybackException("Add-on returned a lossy track for a lossless request", null,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)) }
                return
            }
            val rate = format.sampleRate.takeIf { it > 0 } ?: return
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
            val item = mediaSession?.player?.currentMediaItem
            val external = item?.localConfiguration?.uri?.let(::externalStreamKeyOf) != null
            if (external) Log.e(TAG, "External playback failed: ${error.errorCodeName}")
            else Log.e(TAG, "Playback error on ${item?.mediaId}: ${error.errorCodeName}", error)
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
        PhoneEqualizer.session = session
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
        val rawUri = player.currentMediaItem?.localConfiguration?.uri
        val pendingSelection = rawUri?.let(::startupToken)?.let { startupSelections[it]?.selected == null } == true
        val lossless = rawUri?.let(::selectedUri)?.let(::externalStreamKeyOf) != null
        // YouTube's gain belongs to its own master, not the external FLAC.
        val db = if (lossless) null else id?.let { StreamResolver.loudnessDbFor(it) ?: Downloads.loudnessFor(it) }
        // A track playing from the cache never resolved, so its figure may not
        // be known yet. Look it up once, a moment in, then apply it.
        loudnessRetry?.cancel()
        if (!lossless && !pendingSelection && db == null && id != null && isYouTubeId(id) && AppSettings.playback.value.loudnessNormalization) {
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
     * As each song starts, looks for a better stream (a verified FLAC from the
     * add-ons, JioSaavn, or [StreamResolver.findUpgrade]) and swaps to it in
     * place once the song is sounding with a little buffered, so the search
     * runs alongside the start rather than after it. Also run from the
     * player's menu.
     */
    private fun scheduleUpgrade(
        force: Boolean = false,
        /** Asked for from the menu: "upgrading" shows and the outcome is told. A forced search after the add-ons change isn't. */
        asked: Boolean = force,
    ) {
        upgradeJob?.cancel()
        NerdStats.onUpgrade(null)
        if (!force && !AppSettings.playback.value.qualityUpgrade && !ExternalStreams.allowed()) return
        val player = mediaSession?.player as? ExoPlayer ?: return
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        val id = videoIdOf(uri) ?: return
        fun alreadyExternal(): Boolean {
            val key = externalStreamKeyOf(selectedUri(uri)) ?: return false
            // Only a stream known to be lossless: one no longer on record used to count too,
            // and its song was never upgraded.
            return ExternalStreams.get(id, key)?.lossless == true
        }
        if (alreadyExternal() && !force) return
        // "Upgrading" shows only when asked for from the menu, or while a switch is under way
        // (see swapToUpgrade); a search in the background, finding nothing or not, says nothing.
        upgradeAsked = asked
        if (asked) NerdStats.onUpgrade(id)
        // Asked for from the menu: say how it went, whatever the outcome.
        fun tell(message: String) { if (asked) PlaybackRequests.report(message) }
        fun swapped(ok: Boolean) = tell(
            if (ok) "Switched to ${ExternalStreams.selectionDetail(id) ?: NerdStats.externalSource.value ?: "a better stream"}"
            else "The better stream didn't load in time, so this one keeps playing",
        )
        /**
         * Before a swap: the song is playing with some of it buffered, so the
         * second stream's fetch doesn't hold up the first one's start. Paused,
         * or slow to start, it goes ahead after a while anyway.
         */
        suspend fun started() {
            if (asked) return
            kotlinx.coroutines.withTimeoutOrNull(UPGRADE_START_WAIT_MS) {
                while (!(player.isPlaying && player.currentPosition >= UPGRADE_SETTLE_MS && player.totalBufferedDuration >= UPGRADE_BUFFERED_MS)) delay(50)
            }
        }
        upgradeJob = scope.launch {
            val startup = startupToken(uri)?.let { startupSelections[it] }
            if (startup != null && startup.selected == null) {
                if (kotlinx.coroutines.withTimeoutOrNull(30_000) {
                    while (startup.selected == null) delay(100)
                    true
                } != true) return@launch
            }
            if (alreadyExternal() && !force) return@launch
            val duration = player.duration.takeIf { it > 0 } ?: item.toSong().durationMillis()
            // The FLAC search (every add-on at once) and JioSaavn go out together, and
            // whichever answers first is acted on. JioSaavn usually does and is played
            // while the FLAC is still being verified; a verified FLAC then takes over,
            // so a lossy stream found first never keeps a lossless one from playing,
            // and a FLAC found first doesn't wait for JioSaavn at all.
            val flac = if (Podcasts.isEpisode(id)) null else async {
                ExternalStreams.find(item.toSong(), duration, force = force,
                    beforePlayback = AppSettings.effectiveAudioQuality == com.opentune.data.settings.AudioQuality.MAX,
                    losslessOnly = true)
            }
            // A slow startup lookup can finish here without delaying audible playback.
            val saavnSearch = if (!Podcasts.isEpisode(id) && externalStreamKeyOf(selectedUri(uri)) == null)
                async { ExternalStreams.findJioSaavn(item.toSong(), duration) } else null
            val flacFirst = flac != null && saavnSearch != null && kotlinx.coroutines.selects.select {
                flac.onAwait { it != null }
                saavnSearch.onAwait { false }
            }
            val saavn = if (flacFirst) { saavnSearch?.cancel(); null } else saavnSearch?.await()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (player.currentMediaItem?.localConfiguration?.uri != uri) { flac?.cancel(); return@launch }
            // JioSaavn plays unless a FLAC is already here to play instead; a FLAC search that
            // finished with nothing is exactly when JioSaavn is wanted.
            val flacReady = flac != null && flac.isCompleted && flac.getCompleted() != null
            if (saavn != null && !saavn.lossless && ExternalStreams.allowed(false) && !flacReady) {
                started()
                val left = player.duration - player.currentPosition
                if (player.duration > 0 && left < UPGRADE_MIN_REMAINING_MS) { flac?.cancel(); tell("Too close to the end of the song to switch"); return@launch }
                if (player.currentMediaItem?.localConfiguration?.uri == uri) swapToUpgrade(player, id, externalStreamUri(id, saavn.key))
            }
            var lossless = flac?.await()
            // Came back empty only because add-ons erred (an HTTP 404 or 502, no answer):
            // asked again a little later in the song rather than given up on until the
            // next one, the way only a manual Upgrade quality used to.
            var retries = 0
            while (lossless == null && flac != null && retries < LOSSLESS_RETRIES && ExternalStreams.missWasTransient(id)) {
                retries++
                delay(LOSSLESS_RETRY_MS)
                if (player.currentMediaItem?.mediaId != id) return@launch
                if (player.duration > 0 && player.duration - player.currentPosition < UPGRADE_MIN_REMAINING_MS + LOSSLESS_RETRY_MS) break
                lossless = ExternalStreams.find(item.toSong(), duration, beforePlayback = AppSettings.effectiveAudioQuality == com.opentune.data.settings.AudioQuality.MAX,
                    losslessOnly = true)
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            // Still this song, maybe now on the JioSaavn stream switched to above.
            if (player.currentMediaItem?.mediaId != id) return@launch
            val playing = player.currentMediaItem?.localConfiguration?.uri ?: return@launch
            updateExternalStats()
            if (lossless != null && ExternalStreams.allowed(lossless.lossless)) {
                val existing = externalStreamKeyOf(selectedUri(playing))?.let { ExternalStreams.get(id, it) }
                if (existing?.lossless == true && com.opentune.data.addons.AddonResolver.qualityScore(lossless.bits, lossless.sampleRate) <=
                    com.opentune.data.addons.AddonResolver.qualityScore(existing.bits, existing.sampleRate)) { tell("Already playing the best quality there is for this song"); return@launch }
                started()
                if (player.currentMediaItem?.mediaId != id) return@launch
                val left = player.duration - player.currentPosition
                if (player.duration > 0 && left < UPGRADE_MIN_REMAINING_MS) { tell("Too close to the end of the song to switch"); return@launch }
                val candidates = ExternalStreams.alternatives(id).ifEmpty { listOf(lossless) }
                val ok = swapToBestAnswering(player, id, candidates, existing)
                if (!ok && player.currentMediaItem?.mediaId == id) {
                    ExternalStreams.swapFailed(id, swapNotes.map { (provider, why) -> "$provider: $why" })
                    updateExternalStats()
                }
                swapped(ok)
                return@launch
            }
            if (externalStreamKeyOf(selectedUri(playing)) != null) { tell("Already playing the best quality there is for this song"); return@launch }
            if (isUpgradedUri(playing)) { tell("Already playing the best quality there is for this song"); return@launch }
            if (!force && !AppSettings.playback.value.qualityUpgrade) return@launch
            val found = withContext(Dispatchers.IO) { runCatching { StreamResolver.findUpgrade(id, force) }.getOrDefault(false) }
            if (player.currentMediaItem?.mediaId != id) return@launch
            if (!found) { tell("No better stream found; this is the best quality there is for this song"); return@launch }
            started()
            if (player.currentMediaItem?.mediaId != id) return@launch
            val left = player.duration - player.currentPosition
            if (player.duration > 0 && left < UPGRADE_MIN_REMAINING_MS) { tell("Too close to the end of the song to switch"); return@launch }
            swapped(swapToUpgrade(player, id))
        }
        val currentJob = upgradeJob
        currentJob?.invokeOnCompletion { if (upgradeJob === currentJob) NerdStats.onUpgrade(null) }
    }

    /**
     * The safety net under the upgrade. Every few seconds while a song plays, with nothing
     * already at work on it: if a better stream is known for it than the one playing, or its
     * FLAC search never ran or came back empty only because servers erred, the upgrade runs
     * again, a few times a song at most. Skipping fast, a search cut short, a switch that
     * didn't load or a fallback all used to leave a song on a lower stream to its end, with
     * a verified FLAC on record.
     */
    private suspend fun guardQuality() {
        var song: String? = null
        var tries = 0
        var lastAt = 0L
        while (true) {
            delay(GUARD_EVERY_MS)
            val player = mediaSession?.player as? ExoPlayer ?: continue
            val item = player.currentMediaItem ?: continue
            val id = item.mediaId
            val now = SystemClock.elapsedRealtime()
            if (id != song) { song = id; tries = 0; lastAt = now }
            if (!player.isPlaying || upgradeJob?.isActive == true || swappingTo != null || swapRamping || crossfade?.blending == true) continue
            if (!isYouTubeId(id) || Podcasts.isEpisode(id)) continue
            val raw = item.localConfiguration?.uri ?: continue
            if (raw.scheme == "file") continue
            if (startupToken(raw) != null && startupToken(raw)?.let { startupSelections[it]?.selected } == null) continue
            if (player.currentPosition < GUARD_SETTLE_MS) continue
            if (player.duration > 0 && player.duration - player.currentPosition < UPGRADE_MIN_REMAINING_MS + GUARD_EVERY_MS) continue
            if (tries >= GUARD_TRIES || now - lastAt < GUARD_GAP_MS) continue
            val uri = selectedUri(raw)
            val playing = externalStreamKeyOf(uri)?.let { ExternalStreams.get(id, it) }
            fun score(r: ExternalStreams.Rendition) = com.opentune.data.addons.AddonResolver.qualityScore(r.bits, r.sampleRate)
            val better = ExternalStreams.alternatives(id).filter { playing?.lossless != true || score(it) > score(playing) }
            val wanted = when {
                ExternalStreams.allowed(true) && playing?.lossless == true -> better.isNotEmpty()
                ExternalStreams.allowed(true) -> better.isNotEmpty() || ExternalStreams.missWasTransient(id) || !ExternalStreams.losslessSearched(id)
                ExternalStreams.allowed(false) -> playing == null && !isUpgradedUri(uri) && ExternalStreams.saavnFor(id) != null
                else -> false
            }
            if (!wanted) continue
            tries++
            lastAt = now
            Log.d(TAG, "Quality check for $id: ${if (better.isNotEmpty()) "a better verified stream is known" else "its search came to nothing"}; upgrading again (try $tries)")
            if (playing?.lossless == true) {
                upgradeJob = scope.launch {
                    if (swapToBestAnswering(player, id, better, playing)) updateExternalStats()
                }
            } else scheduleUpgrade()
        }
    }

    /**
     * Finds the better sources for the next [PREFETCH_AHEAD] songs while this
     * one plays: JioSaavn first, a quick answer that lets the song start on it
     * at once, then the lossless one, so each starts on the best found (or
     * swaps to it as soon as it's sounding) instead of waiting for a lookup
     * after it's begun. Only what's on for this network; local, server and
     * downloaded songs are left alone.
     */
    private fun prefetchAhead() {
        val lossless = ExternalStreams.allowed(true)
        val saavn = ExternalStreams.allowed(false)
        if (!lossless && !saavn) return
        val player = mediaSession?.player as? ExoPlayer ?: return
        val from = player.currentMediaItemIndex
        val ahead = (1..PREFETCH_AHEAD).mapNotNull { i -> (from + i).takeIf { it in 0 until player.mediaItemCount } }
            .map { player.getMediaItemAt(it) }
            .filter { item ->
                val id = item.mediaId
                val uri = item.localConfiguration?.uri
                isYouTubeId(id) && !Podcasts.isEpisode(id) && uri?.scheme != "file"
            }
        val ids = ahead.map { it.mediaId }.distinct()
        // A song still ahead keeps the lookup it has: each skip used to cancel them all and ask
        // every add-on again, so skipping a few in a row readied none, and the add-ons, asked
        // the same over and over, began answering with errors.
        // The song just skipped to keeps its own too: its upgrade waits on that same search.
        val current = player.currentMediaItem?.mediaId
        aheadJobs.keys.filterNot { it in ids || it == current }.forEach { aheadJobs.remove(it)?.cancel() }
        val fresh = ahead.filter { aheadJobs[it.mediaId]?.isActive != true }.distinctBy { it.mediaId }
        if (fresh.isEmpty()) return
        fresh.forEach { item ->
            aheadJobs[item.mediaId] = scope.launch(Dispatchers.IO) {
                // Skipping through: a song passed by within a moment is never looked up.
                delay(PREFETCH_SETTLE_MS)
                aheadSlots.withPermit { readyAhead(item.toSong(), saavn, lossless) }
            }
        }
    }

    /** The lookups for the songs ahead, by video id; a couple at a time, see [aheadSlots]. */
    private val aheadJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val aheadSlots = kotlinx.coroutines.sync.Semaphore(PREFETCH_AT_ONCE)

    /**
     * Gets [song] ready to start in its best quality: JioSaavn first, quick to
     * answer, then the verified lossless files. The start of the one it'll
     * play is read now, which also wakes its server; if it doesn't answer,
     * the next verified file is chosen instead, before the song ever starts.
     */
    private suspend fun readyAhead(song: com.opentune.data.model.Song, saavn: Boolean, lossless: Boolean) {
        suspend fun attempt(block: suspend () -> Unit) = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        var jioSaavn: ExternalStreams.Rendition? = null
        if (saavn) attempt { jioSaavn = ExternalStreams.findJioSaavn(song, song.durationMillis())?.takeIf { !it.lossless } }
        if (!lossless) return
        attempt { ExternalStreams.find(song, song.durationMillis(), beforePlayback = true, losslessOnly = true) }
        val files = ExternalStreams.alternatives(song.videoId)
        for (file in files) {
            val answers = kotlinx.coroutines.withTimeoutOrNull(PING_LIMIT_MS) {
                runCatching { com.opentune.data.addons.AddonHttp.bytes(file.url, PING_BYTES, true).isNotEmpty() }.getOrDefault(false)
            } == true
            if (answers) {
                if (ExternalStreams.cached(song.videoId)?.key != file.key) ExternalStreams.remember(song.videoId, file)
                return
            }
            Log.w(TAG, "${file.provider}'s file for ${song.videoId} didn't answer ahead of time; readying the next one")
        }
        // No lossless file answers: it starts on JioSaavn, and only on YouTube's stream when JioSaavn had nothing.
        val fallback = jioSaavn
        if (files.isNotEmpty() && fallback != null) ExternalStreams.remember(song.videoId, fallback)
    }

    /**
     * Moves [id] onto the best of [candidates] (verified files of the same
     * recording) that actually serves its audio. All of them are pinged at
     * once; the song moves onto the best that has answered, and again onto a
     * better one that answers after, so a source that verified but won't
     * hand over its stream never keeps another from playing. A file whose
     * stream doesn't load is passed over for the next. True if it moved at all.
     */
    private suspend fun swapToBestAnswering(
        player: ExoPlayer,
        id: String,
        candidates: List<ExternalStreams.Rendition>,
        current: ExternalStreams.Rendition?,
    ): Boolean {
        // A verified file whose stream didn't load in time often does a little later, once its
        // server has it ready: tried again a few times in the song rather than left till the next.
        swapNotes.clear()
        repeat(SWAP_ROUNDS) { round ->
            if (round > 0) {
                delay(SWAP_ROUND_GAP_MS)
                if (player.currentMediaItem?.mediaId != id) return false
                if (player.duration > 0 && player.duration - player.currentPosition < UPGRADE_MIN_REMAINING_MS) return false
            }
            if (swapRound(player, id, candidates, current)) return true
        }
        return false
    }

    private suspend fun swapRound(
        player: ExoPlayer,
        id: String,
        candidates: List<ExternalStreams.Rendition>,
        current: ExternalStreams.Rendition?,
    ): Boolean = kotlinx.coroutines.coroutineScope {
        fun score(r: ExternalStreams.Rendition) = com.opentune.data.addons.AddonResolver.qualityScore(r.bits, r.sampleRate)
        var playing = if (current?.lossless == true) score(current) else -1L
        val upgrades = candidates.filter { score(it) > playing || current?.lossless != true }
        if (upgrades.isEmpty()) return@coroutineScope false
        val answers = kotlinx.coroutines.channels.Channel<ExternalStreams.Rendition?>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val pings = upgrades.map { r ->
            launch(Dispatchers.IO) {
                val alive = kotlinx.coroutines.withTimeoutOrNull(PING_LIMIT_MS) {
                    runCatching { com.opentune.data.addons.AddonHttp.bytes(r.url, PING_BYTES, true).isNotEmpty() }.getOrDefault(false)
                } == true
                if (!alive) swapNotes[r.provider] = "its server didn't answer"
                answers.send(r.takeIf { alive })
            }
        }
        var waiting = pings.size
        var moved = false
        val pending = mutableListOf<ExternalStreams.Rendition>()
        fun take(r: ExternalStreams.Rendition?) { waiting--; if (r != null) pending += r }
        try {
            while (true) {
                if (pending.isEmpty()) {
                    if (waiting == 0) break
                    take(answers.receive())
                }
                // Whatever else has answered meanwhile is weighed too: the best of them goes first.
                while (true) {
                    val more = answers.tryReceive()
                    if (!more.isSuccess) break
                    take(more.getOrNull())
                }
                val best = pending.maxByOrNull(::score) ?: continue
                pending.remove(best)
                if (moved && score(best) <= playing) { pending.clear(); continue }
                if (player.currentMediaItem?.mediaId != id) break
                if (player.duration > 0 && player.duration - player.currentPosition < UPGRADE_MIN_REMAINING_MS) break
                if (swapToUpgrade(player, id, externalStreamUri(id, best.key))) {
                    moved = true
                    playing = score(best)
                    updateExternalStats()
                    pending.removeAll { score(it) <= playing }
                } else {
                    swapNotes[best.provider] = loadFailure ?: "its stream didn't load"
                    Log.w(TAG, "${best.provider}'s stream for $id didn't load (${swapNotes[best.provider]}); trying the next verified source")
                }
            }
        } finally {
            pings.forEach { it.cancel() }
        }
        moved
    }

    /** Why each verified source a switch tried didn't take, by provider, for the pipeline report. */
    private val swapNotes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Why the last stream [prebuffer] or the bridge tried didn't load. */
    private var loadFailure: String? = null

    /** What went wrong on [p], which was asked to load from [from]: an error, or how little arrived in time. */
    private fun whyNotLoaded(p: ExoPlayer, from: Long): String {
        val error = p.playerError
        if (error != null) {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return "its server answered HTTP ${cause.responseCode}"
                cause = cause.cause
            }
            return "playback error (${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})"
        }
        if (p.playbackState == Player.STATE_ENDED) return "its stream ended as soon as it started"
        val got = (p.bufferedPosition - from).coerceAtLeast(0L) / 1000
        return "only ${got}s of audio arrived in ${AUDITION_TIMEOUT_MS / 1000}s"
    }

    /** Builds the silent player [prebuffer] loads an upgrade with; set in [onCreate]. */
    private var auditionPlayer: (() -> ExoPlayer)? = null

    /**
     * Loads [item] on a silent player from a little ahead of where [main] is,
     * until enough past [main]'s position is fetched to cover the swap, so the
     * swapped-in stream plays from the cache rather than the network. False
     * when the stream fails, ends, or doesn't fill in time: then there's no swap.
     */
    private suspend fun prebuffer(main: ExoPlayer, item: MediaItem): Boolean {
        val make = auditionPlayer ?: return true
        val audition = make()
        loadFailure = null
        val from = main.currentPosition + AUDITION_LEAD_MS
        return try {
            audition.setMediaItem(item)
            audition.seekTo(from)
            audition.prepare()
            (kotlinx.coroutines.withTimeoutOrNull(AUDITION_TIMEOUT_MS) {
                while (true) {
                    if (audition.playerError != null || audition.playbackState == Player.STATE_ENDED) return@withTimeoutOrNull false
                    if (main.currentMediaItem?.mediaId != item.mediaId) return@withTimeoutOrNull false
                    val covered = audition.bufferedPosition >= main.currentPosition + AUDITION_AHEAD_MS ||
                        (audition.duration > 0 && audition.bufferedPosition >= audition.duration - 500)
                    if (audition.playbackState == Player.STATE_READY && covered) return@withTimeoutOrNull true
                    delay(200)
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: false).also { if (!it && main.currentMediaItem?.mediaId == item.mediaId) loadFailure = whyNotLoaded(audition, from) }
        } finally {
            audition.release()
            // Let the cache's lock on what it wrote go before the main player reads it.
            delay(250)
        }
    }

    /** Moves [player]'s volume to [to] over [ms], in small steps. */
    private suspend fun rampVolume(player: ExoPlayer, to: Float, ms: Long) {
        val from = player.volume
        val steps = 8
        for (i in 1..steps) {
            player.volume = from + (to - from) * i / steps
            delay(ms / steps)
        }
    }

    /**
     * Swaps the playing song onto its better stream without a stall: the new
     * stream is fetched ahead by [prebuffer], the volume dips for a moment to
     * hide the seam, and the song carries on from where it was. Returns
     * whether it swapped.
     */
    private suspend fun swapToUpgrade(player: ExoPlayer, id: String, uri: android.net.Uri = youtubeFallbackUri(id, upgraded = true)): Boolean {
        NerdStats.onUpgrade(id)
        return try {
            switchStream(player, id, uri)
        } finally {
            if (!upgradeAsked) NerdStats.onUpgrade(null)
        }
    }

    /** Whether the running upgrade was asked for from the menu, so "upgrading" stays up through all of it. */
    private var upgradeAsked = false

    /** What [uri] plays, for the log: the external source's label, or YouTube's better stream. */
    private fun streamName(id: String, uri: android.net.Uri): String =
        externalStreamKeyOf(uri)?.let { ExternalStreams.get(id, it)?.label } ?: "YouTube's better stream"

    private suspend fun switchStream(player: ExoPlayer, id: String, uri: android.net.Uri): Boolean {
        Log.d(TAG, "Switching $id to ${streamName(id, uri)}${if (player.isPlaying) "" else " (not playing yet, so without the bridge)"}")
        val index = player.currentMediaItemIndex
        val item = player.getMediaItemAt(index)
        item.localConfiguration?.uri?.let(::selectedUri)?.let { beforeSwap = id to it }
        val next = item.buildUpon().setUri(uri).setMimeType(externalStreamKeyOf(uri)?.let { ExternalStreams.get(id, it)?.mimeType }).build()
        // Playing out loud: a second player carries the song across the switch, so nothing is heard to stop.
        if (player.isPlaying && !BitPerfectUsb.active) bridgedSwap(player, index, id, next)?.let { return it }
        if (!prebuffer(player, next)) {
            Log.w(TAG, "The better stream for $id didn't load in time; staying on this one")
            return false
        }
        // The queue may have moved on, or a blend begun, while it loaded.
        if (player.currentMediaItemIndex != index || player.currentMediaItem?.mediaId != id || crossfade?.blending == true) return false
        val smooth = player.isPlaying && !BitPerfectUsb.active
        // Outside a blend (ruled out above) the main player plays at full level.
        val level = 1f
        swapRamping = smooth
        try {
            if (smooth) rampVolume(player, 0f, SWAP_DIP_MS)
            val position = player.currentPosition
            // Through the switch the song is silent but carries on; the lyrics carry on with it.
            if (smooth) {
                val at = SystemClock.elapsedRealtime()
                PlaybackRequests.soundingPosition = { position + (SystemClock.elapsedRealtime() - at) }
            }
            swappingTo = id
            // If the player updates the item in place there's no transition to clear this.
            scope.launch { delay(2_000); if (swappingTo == id) swappingTo = null }
            player.replaceMediaItem(index, next)
            applyLoudness()
            if (player.currentMediaItemIndex != index || player.currentPosition < position - 1_000) player.seekTo(index, position)
            if (smooth) {
                // Back up once the new stream is sounding.
                kotlinx.coroutines.withTimeoutOrNull(3_000) { while (player.playbackState != Player.STATE_READY || !player.isPlaying) delay(30) }
                PlaybackRequests.soundingPosition = null
                rampVolume(player, level, SWAP_RISE_MS)
            }
            // Only now does the label say the better quality: it's what's heard.
            updateExternalStats()
            Log.d(TAG, "Swapped $id to ${streamName(id, uri)} at ${position}ms")
            return true
        } finally {
            PlaybackRequests.soundingPosition = null
            swapRamping = false
            // Cancelled or failed between the dip and the rise: never leave the music silent.
            if (smooth && crossfade?.blending != true && player.volume < level) player.volume = level
        }
    }

    /** True while a swap has the volume dipped, so nothing else puts it back mid-ramp. */
    private var swapRamping = false

    /**
     * The swap with nothing to hear: a second player (the "bridge") loads the
     * better stream ahead, starts it silently in step with the song, and takes
     * the sound over in a short crossfade. The main player changes streams
     * while silent, catches up with the bridge, and takes the sound back the
     * same way. Its rebuffering after the change happens while the bridge is
     * the one sounding, so no gap or dip reaches the ears.
     *
     * Null when the bridge couldn't be set up (no factory, or the stream
     * wouldn't load): the caller falls back to the plain swap. Otherwise
     * whether it swapped.
     */
    private suspend fun bridgedSwap(player: ExoPlayer, index: Int, id: String, next: MediaItem): Boolean? {
        val make = auditionPlayer ?: return null
        val bridge = make()
        swapRamping = true
        var handedOver = false
        try {
            if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) bridge.setAudioSessionId(player.audioSessionId)
            bridge.playbackParameters = player.playbackParameters
            bridge.setMediaItem(next)
            val from = player.currentPosition + AUDITION_LEAD_MS
            bridge.seekTo(from)
            bridge.prepare()
            val loaded = kotlinx.coroutines.withTimeoutOrNull(AUDITION_TIMEOUT_MS) {
                while (true) {
                    if (bridge.playerError != null || bridge.playbackState == Player.STATE_ENDED) return@withTimeoutOrNull false
                    if (player.currentMediaItem?.mediaId != id) return@withTimeoutOrNull false
                    val covered = bridge.bufferedPosition >= player.currentPosition + AUDITION_AHEAD_MS ||
                        (bridge.duration > 0 && bridge.bufferedPosition >= bridge.duration - 500)
                    if (bridge.playbackState == Player.STATE_READY && covered) return@withTimeoutOrNull true
                    delay(200)
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: false
            if (!loaded) {
                if (player.currentMediaItem?.mediaId == id) loadFailure = whyNotLoaded(bridge, from)
                Log.w(TAG, "The bridge couldn't load the better stream for $id ($loadFailure); trying the plain swap")
                return null
            }
            // The queue may have moved on or a blend begun while it loaded.
            if (player.currentMediaItemIndex != index || player.currentMediaItem?.mediaId != id || crossfade?.blending == true) return false
            // Paused while it loaded: the plain swap needs no bridge.
            if (!player.isPlaying) return null

            // 1. The bridge plays, silent, in step with the song as it is heard now.
            bridge.volume = 0f
            bridge.playWhenReady = true
            if (!lineUp(follower = bridge, leader = player)) {
                Log.w(TAG, "Couldn't line the better stream for $id up with the song; trying the plain swap")
                return null
            }
            // 2. The sound moves onto it. From here the bridge is what's heard, so it's
            // also where the lyrics and seek bar read the song's position.
            PlaybackRequests.soundingPosition = { bridge.currentPosition }
            crossfadeVolumes(from = player, to = bridge, BRIDGE_FADE_MS)
            handedOver = true

            // 3. Silent now, the main player changes streams and catches up with the bridge.
            swappingTo = id
            scope.launch { delay(4_000); if (swappingTo == id) swappingTo = null }
            player.replaceMediaItem(index, next)
            applyLoudness()
            player.seekTo(index, bridge.currentPosition + BRIDGE_SEEK_LEAD_MS)
            val ready = kotlinx.coroutines.withTimeoutOrNull(BRIDGE_CATCH_UP_MS) {
                while (player.playbackState != Player.STATE_READY || !player.isPlaying) delay(20)
                true
            } == true
            // Lined up within a few milliseconds, or the two would be heard as an echo.
            val aligned = ready && (lineUp(follower = player, leader = bridge) || lineUp(follower = player, leader = bridge))

            // 4. The sound comes back to the main player, now on the better stream: blended
            // when the two play the same moment, otherwise one out before the other comes
            // in, so two copies of the song are never heard at once out of step.
            if (aligned) crossfadeVolumes(from = bridge, to = player, BRIDGE_FADE_MS)
            else {
                Log.w(TAG, "Main player for $id didn't line up with the bridge; handing back without a blend")
                rampVolume(bridge, 0f, BRIDGE_CUT_MS)
                rampVolume(player, 1f, BRIDGE_CUT_MS)
            }
            PlaybackRequests.soundingPosition = null
            handedOver = false
            updateExternalStats()
            Log.d(TAG, "Swapped $id to ${next.localConfiguration?.uri?.let { streamName(id, it) }} at ${player.currentPosition}ms, bridged")
            return true
        } finally {
            PlaybackRequests.soundingPosition = null
            swapRamping = false
            bridge.release()
            // However it ended, the main player sounds at full level again.
            if (crossfade?.blending != true && player.volume < 1f) player.volume = 1f
            if (handedOver) Log.w(TAG, "Bridge for $id ended early; the main player took the sound back")
        }
    }

    /**
     * Seeks [follower] (playing) until it plays where [leader] does, within
     * [BRIDGE_ALIGN_MS]. Each seek lands a little ahead, by an amount learned
     * from the last try, to cover the time the seek itself takes to sound.
     */
    private suspend fun lineUp(follower: ExoPlayer, leader: ExoPlayer): Boolean {
        var lead = BRIDGE_SEEK_LEAD_MS
        repeat(BRIDGE_ALIGN_TRIES) {
            if (!settledPlaying(follower)) return false
            val drift = follower.currentPosition - leader.currentPosition
            if (kotlin.math.abs(drift) <= BRIDGE_ALIGN_MS) return true
            lead = (lead - drift).coerceIn(0L, 2_000L)
            follower.seekTo(leader.currentPosition + lead)
        }
        return settledPlaying(follower) && kotlin.math.abs(follower.currentPosition - leader.currentPosition) <= BRIDGE_ALIGN_MS * 2
    }

    /** Waits for [p] to be playing from a buffer, then a moment more for its position to settle. */
    private suspend fun settledPlaying(p: ExoPlayer): Boolean {
        val ok = kotlinx.coroutines.withTimeoutOrNull(3_000) {
            while (p.playbackState != Player.STATE_READY || !p.isPlaying) delay(15)
            true
        } == true
        if (ok) delay(BRIDGE_SETTLE_MS)
        return ok
    }

    /** Equal-power fade from [from] to [to] over [ms]: the level stays even through it. */
    private suspend fun crossfadeVolumes(from: ExoPlayer, to: ExoPlayer, ms: Long) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val p = ((SystemClock.elapsedRealtime() - start).toFloat() / ms).coerceIn(0f, 1f)
            from.volume = kotlin.math.cos(p * Math.PI / 2).toFloat()
            to.volume = kotlin.math.sin(p * Math.PI / 2).toFloat()
            if (p >= 1f) break
            delay(10)
        }
    }

    private fun updateExternalStats() {
        val item = mediaSession?.player?.currentMediaItem
        val uri = item?.localConfiguration?.uri?.let(::selectedUri)
        val key = uri?.let(::externalStreamKeyOf)
        val rendition = if (item != null && key != null) ExternalStreams.get(item.mediaId, key) else null
        NerdStats.onExternalSource(rendition?.label)
        val downloaded = item?.let { com.opentune.data.download.Downloads.entryNow(it.mediaId) }
            ?.takeIf { uri?.scheme == "file" && it.path == uri.path }
        val provider = when {
            item == null -> "No track selected"
            rendition != null -> rendition.provider
            uri?.let(::startupToken) != null -> "Loading audio…"
            LocalMusic.isLocal(item.mediaId) -> "Local file"
            Radio.isRadio(item.mediaId) -> "Internet radio"
            Subsonic.isSubsonic(item.mediaId) -> "Your music server"
            uri?.scheme == "file" -> downloaded?.source?.let { "$it · downloaded" } ?: "Downloaded file"
            else -> StreamResolver.engineFor(item.mediaId, uri?.let(::isUpgradedUri) == true)?.let { "YouTube · $it" } ?: "YouTube · cached stream or resolving"
        }
        NerdStats.onPlaybackSource(NerdStats.Source(provider, rendition?.bits ?: downloaded?.bits, rendition?.sampleRate ?: downloaded?.sampleRate,
            rendition?.kbps ?: if (rendition == null && item != null && uri?.let(::videoIdOf) != null)
                StreamResolver.bitrateFor(item.mediaId, isUpgradedUri(uri)) else null,
            selectionDetail = if (item != null && uri?.let(::videoIdOf) != null && uri.let(::startupToken) == null)
                ExternalStreams.selectionDetail(item.mediaId) else null, mediaId = item?.mediaId))
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
        // Maximum also starts immediately; warm its normal stream while quality
        // searches run independently, so next/previous do not repeat resolution.
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

        val songs = indices.map { player.getMediaItemAt(it).toSong() }.filter { it.videoId in ids }.distinctBy { it.videoId }
        val previousJob = warmJob
        warmJob = scope.launch(Dispatchers.IO) {
            previousJob?.join()
            // Prepare the preferred source independently of YouTube URL warm-up.
            if (AppSettings.effectiveAudioQuality == com.opentune.data.settings.AudioQuality.MAX) launch {
                for (song in songs) kotlinx.coroutines.withTimeoutOrNull(3_000) {
                    ExternalStreams.findJioSaavn(song, song.durationMillis())
                }
            }
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
                com.opentune.data.Pills.done("Skipped ${SponsorBlock.CATEGORIES[segment.category]?.one ?: "a segment"}", this@PlaybackService)
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
            // The last song leads; a couple of earlier ones in the queue, by other
            // artists, are asked for too, so what comes next follows the whole
            // queue rather than only its last song.
            val engine = AppSettings.playback.value.recommender
            val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() }
            val others = if (Subsonic.isSubsonic(seed)) emptyList() else Autoplay.seeds(queue) { s ->
                isYouTubeId(s.videoId) && !Podcasts.isEpisode(s.videoId)
            }.drop(1)
            val extra = others.map { s ->
                async {
                    try {
                        Recommendations.after(s, engine).songs
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Radio for ${s.videoId} failed", e)
                        emptyList()
                    }
                }
            }
            var lead: List<Song>? = null
            for ((attempt, wait) in RADIO_RETRY_MS.withIndex()) {
                if (wait > 0) delay(wait)
                lead = try {
                    if (Subsonic.isSubsonic(seed)) {
                        Subsonic.randomSongs(RADIO_FROM_SERVER)
                    } else {
                        // YouTube Music, Spotify or JioSaavn, as chosen; the songs always play from YouTube Music.
                        Recommendations.after(seedItem.toSong(), engine).songs
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Radio for $seed failed (try ${attempt + 1})", e)
                    null
                }
                if (!lead.isNullOrEmpty()) break
            }
            if (lead.isNullOrEmpty()) {
                extra.forEach { it.cancel() }
                return@launch
            }
            val radio = Autoplay.blend(listOf(lead) + extra.awaitAll(), queue)
            // The queue may have been replaced while the request was out; radio
            // for a track that's no longer last doesn't belong at the end.
            val count = player.mediaItemCount
            if (count == 0 || player.getMediaItemAt(count - 1).mediaId != seed) return@launch

            val queued = (0 until count).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
            // Disliked and hidden songs and artists are never queued on their own.
            val liked = LibraryStore.hidden.value.songs(radio.filterNot { LibraryStore.isDisliked(it.videoId) })
            // Songs already heard this session are skipped when asked, unless that leaves nothing to play.
            val additions = Autoplay.newTracks(if (AppSettings.playback.value.noRepeatInSession) queued + sessionIds else queued, liked)
                .ifEmpty { Autoplay.newTracks(queued, liked) }
            if (additions.isEmpty()) {
                exhaustedSeeds += seed
                return@launch
            }
            val ended = player.playbackState == Player.STATE_ENDED
            player.addMediaItems(additions.map { it.copy(queueTier = QueueTier.AUTOPLAY).toMediaItem() })
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
    private var stallJob: Job? = null

    /**
     * A better stream that stops to buffer mid-song and doesn't come back
     * within [STALL_FALLBACK_MS] goes back to the stream the song started on,
     * from where it stopped, the way a failed one does: a hi-res file over a
     * weak connection shouldn't leave the music standing still.
     */
    private fun watchStall(state: Int) {
        stallJob?.cancel()
        if (state != Player.STATE_BUFFERING) return
        val player = mediaSession?.player as? ExoPlayer ?: return
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        if (!isUpgradedUri(uri) && externalStreamKeyOf(uri) == null) return
        // Only mid-song: the start and a seek buffer by design.
        if (player.currentPosition < STALL_MIN_POSITION_MS) return
        val at = player.currentPosition
        stallJob = scope.launch {
            delay(STALL_FALLBACK_MS)
            if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return@launch
            if (player.currentMediaItem?.localConfiguration?.uri != uri || kotlin.math.abs(player.currentPosition - at) > 1_000) return@launch
            Log.w(TAG, "Better stream for ${item.mediaId} stalled for ${STALL_FALLBACK_MS}ms at ${at}ms; back to the one before it")
            StreamResolver.dropUpgrade(item.mediaId)
            val stalled = selectedUri(uri)
            externalStreamKeyOf(stalled)?.let { ExternalStreams.failed(item.mediaId, it) }
            val index = player.currentMediaItemIndex
            swappingTo = item.mediaId
            player.replaceMediaItem(index, item.buildUpon().setUri(fallbackUri(item.mediaId, stalled)).setMimeType(null).build())
            updateExternalStats()
            applyLoudness()
            player.seekTo(index, at)
            player.prepare()
            PlaybackRequests.report("The better stream kept stopping, so this song went back to the one before it")
            upgradeAgainSoon(item.mediaId)
        }
    }

    /** The stream the song last switched from, to go back to if the new one fails. */
    private var beforeSwap: Pair<String, android.net.Uri>? = null

    /**
     * Where [mediaId] goes when its better stream [failed] or stalled: back to the stream it
     * played before the switch (a lower FLAC, or JioSaavn), else JioSaavn while it has the
     * song, and YouTube's own only when there's neither. It went straight to YouTube when
     * JioSaavn's link was more than a few minutes old.
     */
    private fun fallbackUri(mediaId: String, failed: android.net.Uri?): android.net.Uri {
        beforeSwap?.takeIf { it.first == mediaId }?.second?.takeIf { it != failed }
            ?.let(::externalStreamKeyOf)?.takeIf { ExternalStreams.get(mediaId, it) != null }
            ?.let { return externalStreamUri(mediaId, it) }
        ExternalStreams.saavnFor(mediaId)?.let { return externalStreamUri(mediaId, it.key) }
        return youtubeFallbackUri(mediaId)
    }

    /**
     * After a fallback, the upgrade runs again once the song is sounding: the file that
     * failed is off the list, so the next verified one is tried, rather than the song
     * staying on the fallback to its end.
     */
    private fun upgradeAgainSoon(mediaId: String) {
        scope.launch {
            delay(FALLBACK_RETRY_MS)
            val player = mediaSession?.player ?: return@launch
            if (player.currentMediaItem?.mediaId == mediaId && upgradeJob?.isActive != true) scheduleUpgrade()
        }
    }

    private fun recover(error: PlaybackException) {
        val player = mediaSession?.player as? ExoPlayer ?: return
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        val uri = item.localConfiguration?.uri?.let(::selectedUri)
        if (uri?.scheme == "file" && error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND &&
            com.opentune.data.isYouTubeId(mediaId) && com.opentune.data.download.Downloads.entryNow(mediaId) == null) {
            // The user removed an offline copy after it was queued. Stream it
            // without recreating the download they deliberately removed.
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            player.replaceMediaItem(index, item.buildUpon().setUri(streamUri(mediaId, upgraded = false)).setMimeType(null).build())
            player.seekTo(index, position)
            player.prepare()
            return
        }
        if (uri?.scheme == "file" &&
            error.errorCode in setOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) &&
            com.opentune.data.download.Downloads.entryNow(mediaId)?.path == uri.path) {
            // Old versions could mark a bad ranged response as complete. Repair it
            // and let playback use the network instead of retrying the same file.
            com.opentune.data.download.Downloads.markBroken(mediaId)
            com.opentune.data.download.Downloads.enqueue(this, item.toSong())
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            player.replaceMediaItem(index, item.buildUpon().setUri(streamUri(mediaId, upgraded = false)).setMimeType(null).build())
            player.seekTo(index, position)
            player.prepare()
            return
        }
        // A downloaded song that couldn't stream (no internet, or the connection dropped)
        // plays on from its file.
        val file = if (uri?.scheme != "file") com.opentune.data.download.Downloads.fileFor(mediaId) else null
        if (file != null && (error.errorCode in IO_ERRORS || !AppSettings.isOnline())) {
            Log.w(TAG, "Stream for $mediaId failed (${error.errorCodeName}); playing its download")
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            player.replaceMediaItem(index, item.buildUpon().setUri(android.net.Uri.fromFile(file)).setMimeType(null).build())
            updateExternalStats()
            applyLoudness()
            player.seekTo(index, position)
            player.prepare()
            return
        }
        if (uri != null && (isUpgradedUri(uri) || externalStreamKeyOf(uri) != null)) {
            Log.w(TAG, "Upgraded stream for $mediaId failed (${error.errorCodeName}); back to the one before it")
            StreamResolver.dropUpgrade(mediaId)
            externalStreamKeyOf(uri)?.let { ExternalStreams.failed(mediaId, it) }
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            swappingTo = mediaId
            player.replaceMediaItem(index, item.buildUpon().setUri(fallbackUri(mediaId, uri)).setMimeType(null).build())
            updateExternalStats()
            applyLoudness()
            player.seekTo(index, position)
            player.prepare()
            upgradeAgainSoon(mediaId)
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
        /** A swap waits for the song to have played this much, with [UPGRADE_BUFFERED_MS] ahead of it... */
        const val UPGRADE_SETTLE_MS = 300L
        const val UPGRADE_BUFFERED_MS = 4_000L
        /** ...or for this long at most, when it's paused or slow to start. */
        const val UPGRADE_START_WAIT_MS = 6_000L
        /** How many songs ahead the lossless source is looked up. */
        const val PREFETCH_AHEAD = 5
        /** How often [guardQuality] looks, how far into a song it starts, how long between its tries, and how many a song gets. */
        const val GUARD_EVERY_MS = 4_000L
        const val GUARD_SETTLE_MS = 8_000L
        const val GUARD_GAP_MS = 20_000L
        const val GUARD_TRIES = 4
        /** After a fallback, how long the song sounds before the upgrade runs again. */
        const val FALLBACK_RETRY_MS = 3_000L
        /** Songs ahead looked up at once; the nearest go first. */
        const val PREFETCH_AT_ONCE = 2
        /** How long the queue has to rest on a song before the ones after it are looked up. */
        const val PREFETCH_SETTLE_MS = 1_500L
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
        /** A FLAC search that failed only on server errors is asked again this many times, this far apart. */
        const val LOSSLESS_RETRIES = 3
        const val LOSSLESS_RETRY_MS = 12_000L
        /** A source's file is pinged with a read this size... */
        const val PING_BYTES = 65_536
        /** ...and counted as not answering after this long. */
        const val PING_LIMIT_MS = 20_000L
        /** An upgrade is fetched this far past the playing position before it's swapped in. */
        const val AUDITION_AHEAD_MS = 6_000L
        /** It starts loading a little ahead, for the time the loading itself takes. */
        const val AUDITION_LEAD_MS = 1_500L
        const val AUDITION_TIMEOUT_MS = 40_000L
        /** Network and input errors (Media3's 2000s): what a lost connection fails with. */
        val IO_ERRORS = 2000..2999
        /** Tries at switching to the verified files, this far apart, before giving up for the song. */
        const val SWAP_ROUNDS = 3
        const val SWAP_ROUND_GAP_MS = 10_000L
        /** The volume dip around a swap: quickly down, gently back. */
        /** Each crossfade between the main player and the bridge. */
        const val BRIDGE_FADE_MS = 160L
        /** Each half of a hand-back without a blend: one player out, then the other in. */
        const val BRIDGE_CUT_MS = 70L
        /** Two players this close play the same moment: their mix over a fade sounds as one. */
        const val BRIDGE_ALIGN_MS = 20L
        const val BRIDGE_ALIGN_TRIES = 5
        /** A first guess at how long a seek takes to sound, landed ahead by. */
        const val BRIDGE_SEEK_LEAD_MS = 120L
        const val BRIDGE_SETTLE_MS = 120L
        /** How long the bridge carries the song while the main player loads the new stream. */
        const val BRIDGE_CATCH_UP_MS = 8_000L
        const val SWAP_DIP_MS = 120L
        const val SWAP_RISE_MS = 260L
        /** Stuck buffering this long mid-song, a better stream gives way to the regular one. */
        const val STALL_FALLBACK_MS = 6_000L
        const val STALL_MIN_POSITION_MS = 3_000L
        const val FAR_BUFFER_MS = 15 * 60 * 1000
        const val FAR_BUFFER_BYTES = 48 * 1024 * 1024
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


