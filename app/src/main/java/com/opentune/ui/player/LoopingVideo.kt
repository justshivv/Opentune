package com.opentune.ui.player

import android.graphics.Matrix
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * A silent clip on a loop, filling its box and cropped to it: Apple's
 * animated covers (HLS) and Spotify's Canvas (MP4). It fades in on its first
 * frame, so until then whatever is under it shows; it plays only while
 * [playing] and the app is in front, and tries [fallbackUrl] once if [url]
 * won't play. Its own player takes no audio focus and has no sound track.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun LoopingVideo(
    url: String,
    playing: Boolean,
    modifier: Modifier = Modifier,
    fallbackUrl: String? = null,
    /** The largest picture worth fetching, in pixels each way (see MotionQuality). */
    maxPx: Int = 1080,
    /** The most bits a second worth spending on a silent loop. */
    maxBitrate: Int = 4_000_000,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var drawn by remember(url) { mutableStateOf(false) }
    var size by remember(url) { mutableStateOf(VideoSize.UNKNOWN) }
    val fade by animateFloatAsState(if (drawn) 1f else 0f, tween(700), label = "loopingVideo")
    val player = remember(url) {
        ExoPlayer.Builder(context)
            // Kept on disk, so each turn of the loop plays from the phone rather than downloading again.
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(LoopCache.dataSource(context)))
            .build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            // The sharpest version that fits the screen and the bitrate cap, straight away. Left to adapt,
            // the player opened on Apple's smallest (360 px) and a short loop seldom gave it time to step up.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setMaxVideoSize(maxPx, maxPx)
                .setMaxVideoBitrate(maxBitrate)
                .setForceHighestSupportedBitrate(true)
                .build()
            var retried = false
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { drawn = true }
                override fun onVideoSizeChanged(videoSize: VideoSize) { size = videoSize }
                override fun onPlayerError(error: PlaybackException) {
                    if (retried || fallbackUrl == null) return
                    retried = true
                    setMediaItem(MediaItem.fromUri(fallbackUrl))
                    prepare()
                }
            })
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }
    LaunchedEffect(player, playing) { player.playWhenReady = playing && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    DisposableEffect(player, lifecycle) {
        val watch = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.playWhenReady = playing
                Lifecycle.Event.ON_STOP -> player.playWhenReady = false
                else -> Unit
            }
        }
        lifecycle.addObserver(watch)
        onDispose {
            lifecycle.removeObserver(watch)
            player.release()
        }
    }
    AndroidView(
        factory = { c ->
            TextureView(c).also { view ->
                player.setVideoTextureView(view)
                view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> crop(v as TextureView, size) }
            }
        },
        update = { view -> crop(view, size) },
        modifier = modifier.graphicsLayer { alpha = fade },
    )
}

/** Scales the picture to cover the view, cropping what spills over, as ContentScale.Crop does for images. */
private fun crop(view: TextureView, video: VideoSize) {
    val w = view.width.toFloat()
    val h = view.height.toFloat()
    val vw = video.width * video.pixelWidthHeightRatio
    val vh = video.height.toFloat()
    if (w <= 0f || h <= 0f || vw <= 0f || vh <= 0f) return
    // The view first stretches the picture to its own size; this undoes that and covers instead.
    val scale = maxOf(w / vw, h / vh)
    view.setTransform(Matrix().apply { setScale(vw * scale / w, vh * scale / h, w / 2f, h / 2f) })
}

/**
 * Animated covers and Canvas clips kept on disk, apart from the music's own
 * cache: a clip loops for the whole song, and a song comes round again.
 */
@OptIn(UnstableApi::class)
internal object LoopCache {
    private const val MAX_BYTES = 150L * 1024 * 1024
    @Volatile private var cache: androidx.media3.datasource.cache.SimpleCache? = null

    fun dataSource(context: android.content.Context): androidx.media3.datasource.DataSource.Factory {
        val app = context.applicationContext
        val c = cache ?: synchronized(this) {
            cache ?: androidx.media3.datasource.cache.SimpleCache(
                java.io.File(app.cacheDir, "video-loops"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                androidx.media3.database.StandaloneDatabaseProvider(app),
            ).also { cache = it }
        }
        return androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(app))
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
