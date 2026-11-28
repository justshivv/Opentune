package com.opentune.overlay

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.opentune.MainActivity
import com.opentune.data.DebugLog
import com.opentune.data.settings.AppSettings
import com.opentune.playback.AudioLevels
import com.opentune.playback.MixState
import com.opentune.playback.PlaybackService
import com.opentune.playback.toSong
import com.opentune.ui.Links
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * Bound overlay service for OpenTune's Dynamic Notch Mini-Player.
 *
 * Connects directly to [PlaybackService] via Media3 [SessionToken] / [MediaController] (never
 * `NotificationListenerService`), mirrors real-time track metadata and playback progress into
 * [NotchOverlayManager], and stays alive only while OpenTune is in the background with active
 * media and the user's "Floating Notch Player" setting enabled.
 */
class NotchOverlayService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controller: MediaController? = null
    private var connectJob: Job? = null
    private var progressJob: Job? = null
    private var settingsJob: Job? = null
    private var audioLevelsHeld = false

    private lateinit var overlayManager: NotchOverlayManager

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                )
            ) {
                syncFromPlayer(player)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            controller?.let(::syncFromPlayer)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            controller?.let(::syncFromPlayer)
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlayManager = NotchOverlayManager(
            serviceContext = this,
            actions = NotchOverlayActions(
                onToggleExpanded = {
                    val next = !overlayManager.state.value.expanded
                    overlayManager.setExpanded(next)
                    controller?.let(::syncFromPlayer)
                },
                onCollapse = {
                    overlayManager.setExpanded(false)
                },
                onDismiss = {
                    dismissOverlayForSession()
                },
                onOpenPlayer = {
                    overlayManager.setExpanded(false)
                    openFullPlayer()
                },
                onTogglePlayPause = {
                    togglePlayPause()
                },
                onPrevious = {
                    skipPrevious()
                },
                onNext = {
                    skipNext()
                },
                onSeekTo = { positionMs ->
                    seekTo(positionMs)
                },
            ),
        )

        observeSettingsAndMixState()
        connectToPlaybackService()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!shouldRunNow(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayManager.onConfigurationChanged(newConfig)
        updateAudioLevelsHold()
    }

    override fun onDestroy() {
        progressJob?.cancel()
        connectJob?.cancel()
        settingsJob?.cancel()
        releaseAudioLevels()
        overlayManager.hide(immediate = true)
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        scope.cancel()
        super.onDestroy()
    }

    private fun observeSettingsAndMixState() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            launch {
                AppSettings.ui.collect { ui ->
                    if (!ui.floatingNotchPlayer || !Settings.canDrawOverlays(this@NotchOverlayService)) {
                        releaseAudioLevels()
                        overlayManager.hide(immediate = false) {
                            sync(applicationContext)
                        }
                    }
                }
            }
            launch {
                MixState.showing.collect {
                    controller?.let(::syncFromPlayer)
                }
            }
        }
    }

    private fun connectToPlaybackService() {
        connectJob?.cancel()
        connectJob = scope.launch {
            val token = SessionToken(
                this@NotchOverlayService,
                ComponentName(this@NotchOverlayService, PlaybackService::class.java),
            )
            val built = try {
                MediaController.Builder(this@NotchOverlayService, token).buildAsync().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "Unable to connect NotchOverlayService to PlaybackService", e)
                unbindFromCoordinator(applicationContext)
                return@launch
            }
            controller = built
            built.addListener(playerListener)
            syncFromPlayer(built)
            startProgressTicker()
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val player = controller
                if (player != null && overlayManager.isShowing) {
                    val state = overlayManager.state.value
                    val mixing = MixState.showing.value?.takeIf { it.index in 0 until player.mediaItemCount }
                    val pos = (mixing?.positionMs?.invoke() ?: player.currentPosition).coerceAtLeast(0L)
                    val dur = (mixing?.durationMs?.invoke()?.takeIf { it > 0 } ?: player.duration.takeIf { it > 0 })
                        ?: state.durationMs
                    if (pos != state.positionMs || dur != state.durationMs) {
                        overlayManager.updateState(state.copy(positionMs = pos, durationMs = dur))
                    }
                    val intervalMs = when {
                        state.expanded && state.isPlaying -> 250L
                        state.expanded -> 600L
                        state.isPlaying -> 1_000L
                        else -> 1_500L
                    }
                    delay(intervalMs)
                } else {
                    delay(500L)
                }
            }
        }
    }

    private fun syncFromPlayer(player: Player) {
        val mixing = MixState.showing.value?.takeIf { it.index in 0 until player.mediaItemCount }
        val activeItem = mixing?.item ?: player.currentMediaItem
        val song = activeItem?.toSong()
        val hasMedia = activeItem != null &&
            player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_IDLE

        if (!hasMedia) {
            releaseAudioLevels()
            overlayManager.hide(immediate = false) {
                onNoActiveMedia(applicationContext)
            }
            return
        }

        val previousState = overlayManager.state.value
        val mediaId = activeItem?.mediaId
        val durationMs = (mixing?.durationMs?.invoke()?.takeIf { it > 0 } ?: player.duration.takeIf { it > 0 })
            ?: previousState.durationMs.takeIf { previousState.mediaId == mediaId }
            ?: 0L
        val positionMs = (mixing?.positionMs?.invoke() ?: player.currentPosition).coerceAtLeast(0L)
        val isPlaying = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        val isBuffering = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING

        val nextState = previousState.copy(
            mediaId = mediaId,
            title = song?.title.orEmpty().ifBlank { activeItem?.mediaMetadata?.title?.toString().orEmpty() },
            artist = song?.artist.orEmpty().ifBlank { activeItem?.mediaMetadata?.artist?.toString().orEmpty() },
            artworkUrl = song?.thumbnailUrl ?: activeItem?.mediaMetadata?.artworkUri?.toString(),
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            positionMs = positionMs,
            durationMs = durationMs,
            hasPrevious = player.hasPreviousMediaItem() || positionMs > 0L,
            hasNext = player.hasNextMediaItem(),
        )

        overlayManager.updateState(nextState)
        if (shouldRunNow(this) && nextState.hasActiveMedia) {
            overlayManager.show()
        } else {
            overlayManager.hide()
        }
        updateAudioLevelsHold()
    }

    private fun togglePlayPause() {
        val player = controller ?: return
        if (player.playWhenReady && (player.playerError == null || !AppSettings.isOnline())) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
        }
    }

    private fun skipNext() {
        val player = controller ?: return
        MixState.showing.value?.let {
            player.seekTo(it.index, 0L)
            player.seekToNext()
            return
        }
        player.seekToNext()
    }

    private fun skipPrevious() {
        val player = controller ?: return
        MixState.showing.value?.let {
            player.seekTo(it.index, 0L)
            return
        }
        player.seekToPrevious()
    }

    private fun seekTo(positionMs: Long) {
        val player = controller ?: return
        val clamped = positionMs.coerceAtLeast(0L)
        MixState.showing.value?.let {
            player.seekTo(it.index, clamped)
            return
        }
        player.seekTo(clamped)
        overlayManager.updateState(overlayManager.state.value.copy(positionMs = clamped))
    }

    private fun openFullPlayer() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Links.ACTION_OPEN_PLAYER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        runCatching { startActivity(intent) }
            .onFailure { error -> DebugLog.w(TAG, "Unable to launch MainActivity from notch overlay", error) }
    }

    private fun dismissOverlayForSession() {
        releaseAudioLevels()
        overlayManager.hide(immediate = false) {
            markDismissedForSession(applicationContext)
        }
    }

    private fun updateAudioLevelsHold() {
        val wantLevels = overlayManager.isShowing && overlayManager.state.value.isPlaying
        if (wantLevels && !audioLevelsHeld) {
            AudioLevels.hold()
            audioLevelsHeld = true
        } else if (!wantLevels && audioLevelsHeld) {
            releaseAudioLevels()
        }
    }

    private fun releaseAudioLevels() {
        if (audioLevelsHeld) {
            AudioLevels.release()
            audioLevelsHeld = false
        }
    }

    inner class LocalBinder : Binder() {
        fun service(): NotchOverlayService = this@NotchOverlayService
    }

    companion object {
        private const val TAG = "NotchOverlayService"

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var appInForeground: Boolean = false

        @Volatile
        private var hasActiveMedia: Boolean = false

        @Volatile
        private var dismissedForSession: Boolean = false

        @Volatile
        private var lastMediaId: String? = null

        private var bound: Boolean = false

        private val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

            override fun onServiceDisconnected(name: ComponentName?) {
                bound = false
            }
        }

        /** Updates foreground visibility from [com.opentune.OpenTuneApp]'s Activity lifecycle callbacks. */
        fun setAppForeground(context: Context, foreground: Boolean) {
            appInForeground = foreground
            if (foreground) {
                // Reset session dismissal whenever the user returns to OpenTune.
                dismissedForSession = false
            }
            sync(context)
        }

        /** Called from [com.opentune.data.AppHooks.onPlayerChanged] whenever PlaybackService updates. */
        fun onPlaybackChanged(context: Context, player: Player?) {
            val currentItem = player?.currentMediaItem
            val active = currentItem != null &&
                (player.mediaItemCount > 0) &&
                player.playbackState != Player.STATE_IDLE
            val newMediaId = currentItem?.mediaId
            if (active && newMediaId != null && newMediaId != lastMediaId) {
                lastMediaId = newMediaId
                dismissedForSession = false
            }
            hasActiveMedia = active
            sync(context)
        }

        /** Called from [com.opentune.data.AppHooks.onPlayerStopped] when PlaybackService destroys. */
        fun onPlaybackStopped(context: Context) {
            hasActiveMedia = false
            lastMediaId = null
            dismissedForSession = false
            sync(context)
        }

        /** Evaluates whether the notch overlay service should be bound or cleanly stopped. */
        fun sync(context: Context) {
            val appContext = context.applicationContext
            if (Looper.myLooper() == Looper.getMainLooper()) {
                syncOnMain(appContext)
            } else {
                mainHandler.post { syncOnMain(appContext) }
            }
        }

        private fun onNoActiveMedia(context: Context) {
            hasActiveMedia = false
            sync(context)
        }

        private fun markDismissedForSession(context: Context) {
            dismissedForSession = true
            sync(context)
        }

        private fun unbindFromCoordinator(context: Context) {
            val appContext = context.applicationContext
            mainHandler.post {
                if (bound) {
                    runCatching { appContext.unbindService(serviceConnection) }
                    bound = false
                }
            }
        }

        private fun shouldRunNow(context: Context): Boolean {
            val enabled = AppSettings.ui.value.floatingNotchPlayer
            return enabled &&
                !appInForeground &&
                hasActiveMedia &&
                !dismissedForSession &&
                Settings.canDrawOverlays(context)
        }

        private fun syncOnMain(appContext: Context) {
            val shouldBind = shouldRunNow(appContext)
            if (shouldBind && !bound) {
                val intent = Intent(appContext, NotchOverlayService::class.java)
                val ok = runCatching {
                    appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                }.onFailure { error ->
                    DebugLog.w(TAG, "Failed to bind NotchOverlayService", error)
                }.getOrDefault(false)
                bound = ok
            } else if (!shouldBind && bound) {
                runCatching { appContext.unbindService(serviceConnection) }
                    .onFailure { error -> DebugLog.w(TAG, "Failed to unbind NotchOverlayService", error) }
                bound = false
            }
        }
    }
}
