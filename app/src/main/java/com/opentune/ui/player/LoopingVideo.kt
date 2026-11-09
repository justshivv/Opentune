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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
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
/**
 * One player per clip, shared by every place that shows it at once, and kept a
 * moment after the last one goes. The player's cover and the cover flying to
 * and from the lyrics and queue show the same clip: each starting its own
 * player showed the still cover for a second while the new one loaded and
 * faded in. Only one view can show a player's picture, so the one most
 * recently put on screen has it, and it goes back to the one before when that
 * one leaves.
 */
@OptIn(UnstableApi::class)
internal object SharedLoops {
    class Entry(val key: String, val player: ExoPlayer) {
        var users = 0
        /** The clip's picture has been drawn at least once: a view showing it again needs no fade. */
        val drawn = mutableStateOf(false)
        val size = mutableStateOf(VideoSize.UNKNOWN)
        val views = ArrayList<TextureView>()
        var linger: Runnable? = null
    }

    private val entries = HashMap<String, Entry>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private const val LINGER_MS = 2_500L

    fun acquire(context: android.content.Context, url: String, fallbackUrl: String?, maxPx: Int, maxBitrate: Int): Entry {
        val key = "$url|$maxPx|$maxBitrate"
        entries[key]?.let { e ->
            e.linger?.let(main::removeCallbacks)
            e.linger = null
            e.users++
            return e
        }
        val player = ExoPlayer.Builder(context.applicationContext)
            .setRenderersFactory(
                // Some hardware decoders refuse these tall canvas files at
                // start-up though they claim to support them: fall back to
                // another decoder instead of showing nothing.
                androidx.media3.exoplayer.DefaultRenderersFactory(context.applicationContext)
                    .setEnableDecoderFallback(true)
            )
            // Kept on disk, so each turn of the loop plays from the phone rather than downloading again.
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(LoopCache.dataSource(context)))
            .build()
        val entry = Entry(key, player)
        player.apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            // The sharpest version that fits the screen and the bitrate cap, straight away. Left to adapt,
            // the player opened on Apple's smallest (360 px) and a short loop seldom gave it time to step up.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                // Left to itself the player also caps the picture at what fits the screen: a square
                // clip on a 1080 px wide phone never went past 1080, whatever quality was chosen.
                .clearViewportSizeConstraints()
                .setMaxVideoSize(maxPx, maxPx)
                .setMaxVideoBitrate(maxBitrate)
                .setForceHighestSupportedBitrate(true)
                .build()
            var retried = false
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { entry.drawn.value = true }
                override fun onVideoSizeChanged(videoSize: VideoSize) { entry.size.value = videoSize }
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
        entry.users = 1
        entries[key] = entry
        return entry
    }

    fun release(entry: Entry) {
        entry.users--
        if (entry.users > 0) return
        val r = Runnable {
            if (entry.users > 0) return@Runnable
            entries.remove(entry.key)
            entry.player.release()
        }
        entry.linger = r
        main.postDelayed(r, LINGER_MS)
    }

    fun show(entry: Entry, view: TextureView) {
        entry.views.remove(view)
        entry.views.add(view)
        entry.player.setVideoTextureView(view)
    }

    fun hide(entry: Entry, view: TextureView) {
        val wasShowing = entry.views.lastOrNull() === view
        entry.views.remove(view)
        if (!wasShowing) return
        entry.player.clearVideoTextureView(view)
        entry.views.lastOrNull()?.let { entry.player.setVideoTextureView(it) }
    }

    /** Whether any place on screen wants [entry] playing. */
    private val wanted = HashMap<Entry, Int>()

    fun want(entry: Entry, on: Boolean) {
        val n = (wanted[entry] ?: 0) + if (on) 1 else -1
        if (n <= 0) wanted.remove(entry) else wanted[entry] = n
        entry.player.playWhenReady = n > 0
    }
}

/**
 * A silent clip on a loop, filling its box and cropped to it: Apple's
 * animated covers (HLS) and Spotify's Canvas (MP4). The first time a clip is
 * shown it fades in on its first frame, so until then whatever is under it
 * shows; shown again while its player is still around (the cover flying to
 * the lyrics, say), it's there at once. It plays only while [playing] and the
 * app is in front, and tries [fallbackUrl] once if [url] won't play. Its
 * player takes no audio focus and has no sound track.
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
    /** Told of the view the picture is drawn in (null once it's gone), for a frost made from its frames. */
    onView: ((TextureView?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val entry = remember(url, maxPx, maxBitrate) { SharedLoops.acquire(context, url, fallbackUrl, maxPx, maxBitrate) }
    val drawn by entry.drawn
    val size by entry.size
    // Black bands some clips carry in the picture itself (a wide video padded to a tall
    // frame): found from a few frames, then cropped away with the rest.
    var bars by remember(url) { mutableStateOf(Bars.NONE) }
    var view by remember { mutableStateOf<TextureView?>(null) }
    // Already drawn elsewhere: no fade, the picture is simply there.
    val alreadyDrawn = remember(entry) { entry.drawn.value }
    val fade by animateFloatAsState(if (drawn) 1f else 0f, if (alreadyDrawn) tween(0) else tween(700), label = "loopingVideo")
    val lifecycleState by lifecycle.currentStateAsState()
    val active = playing && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(entry, active) {
        if (active) SharedLoops.want(entry, true)
        onDispose { if (active) SharedLoops.want(entry, false) }
    }
    LaunchedEffect(url, drawn) {
        if (!drawn) return@LaunchedEffect
        // A band has to be there in every look, so a dark scene isn't taken for one.
        var found: Bars? = null
        for (wait in listOf(400L, 1_200L, 2_000L)) {
            kotlinx.coroutines.delay(wait)
            val frame = view?.takeIf { it.isAvailable }?.getBitmap(48, 96) ?: continue
            val seen = Bars.of(frame)
            frame.recycle()
            found = found?.let { it.min(seen) } ?: seen
            bars = found
        }
    }
    DisposableEffect(entry) {
        onDispose {
            view?.let { SharedLoops.hide(entry, it) }
            onView?.invoke(null)
            SharedLoops.release(entry)
        }
    }
    AndroidView(
        factory = { c ->
            TextureView(c).also { v ->
                SharedLoops.show(entry, v)
                v.addOnLayoutChangeListener { l, _, _, _, _, _, _, _, _ -> crop(l as TextureView, entry.size.value, bars) }
                view = v
                onView?.invoke(v)
            }
        },
        update = { v -> crop(v, size, bars) },
        modifier = modifier.graphicsLayer { alpha = fade },
    )
}

/**
 * Scales the picture to cover the view, cropping what spills over, as
 * ContentScale.Crop does for images; the [bars] in the picture are left
 * outside the view too.
 */
private fun crop(view: TextureView, video: VideoSize, bars: Bars) {
    val w = view.width.toFloat()
    val h = view.height.toFloat()
    val vw = video.width * video.pixelWidthHeightRatio
    val vh = video.height.toFloat()
    if (w <= 0f || h <= 0f || vw <= 0f || vh <= 0f) return
    // What's left of the picture once its bands are taken off.
    val cw = vw * (1f - bars.left - bars.right)
    val ch = vh * (1f - bars.top - bars.bottom)
    // The view first stretches the picture to its own size; this undoes that and covers instead.
    val scale = maxOf(w / cw, h / ch)
    // Centred on the picture, not on the frame round it, when the bands aren't even.
    val dx = (bars.left - bars.right) / 2f * vw * scale
    val dy = (bars.top - bars.bottom) / 2f * vh * scale
    view.setTransform(Matrix().apply {
        setScale(vw * scale / w, vh * scale / h, w / 2f, h / 2f)
        postTranslate(-dx, -dy)
    })
}

/** Shares of the picture taken by black bands on each side. */
internal data class Bars(val top: Float, val bottom: Float, val left: Float, val right: Float) {
    fun min(o: Bars) = Bars(minOf(top, o.top), minOf(bottom, o.bottom), minOf(left, o.left), minOf(right, o.right))

    companion object {
        val NONE = Bars(0f, 0f, 0f, 0f)
        /** The most taken off a side: past this it's a dark picture, not a band. */
        private const val MOST = 0.3f

        /** The bands in one frame: rows (and columns) at the edges with nothing but near-black in them. */
        fun of(frame: android.graphics.Bitmap): Bars {
            val w = frame.width
            val h = frame.height
            val px = IntArray(w * h).also { frame.getPixels(it, 0, w, 0, 0, w, h) }
            fun dark(c: Int): Boolean {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                return r < 24 && g < 24 && b < 24
            }
            fun rowDark(y: Int) = (0 until w).count { dark(px[y * w + it]) } >= w * 0.97f
            fun colDark(x: Int) = (0 until h).count { dark(px[it * w + x]) } >= h * 0.97f
            fun run(n: Int, darkAt: (Int) -> Boolean): Int { var i = 0; while (i < n && darkAt(i)) i++; return i }
            fun share(count: Int, n: Int) = (count.toFloat() / n).let { if (it > MOST || count >= n) 0f else it }
            return Bars(
                top = share(run(h) { rowDark(it) }, h),
                bottom = share(run(h) { rowDark(h - 1 - it) }, h),
                left = share(run(w) { colDark(it) }, w),
                right = share(run(w) { colDark(w - 1 - it) }, w),
            )
        }
    }
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
