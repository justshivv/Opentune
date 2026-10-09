package com.opentune

import com.opentune.data.AppLog
import com.opentune.data.subsonic.Subsonic
import com.opentune.data.listenbrainz.ListenBrainz
import com.opentune.data.covers.AlbumCovers
import com.opentune.data.autoeq.AutoEq
import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.opentune.data.Http
import okio.Path.Companion.toOkioPath
import com.opentune.data.account.AccountStore
import com.opentune.data.download.Downloads
import com.opentune.data.LoudnessStore
import com.opentune.data.MusicRepository
import com.opentune.data.history.History
import com.opentune.ui.ScreenCache
import com.opentune.data.innertube.InnerTubeXResolver
import com.opentune.data.innertube.UpgradedTracks
import com.opentune.data.lastfm.LastFm
import com.opentune.data.library.LibraryStore
import com.opentune.data.settings.AppSettings
import com.opentune.playback.QueueStore

class OpenTuneApp : Application(), SingletonImageLoader.Factory {
    /**
     * Artwork on the app's shared HTTP client (warm connections to Google's
     * image hosts), a fifth of memory for decoded images and 100 MB on disk,
     * with a short cross-fade as each one lands.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { Http.client }))
                // A cover on your own server is stored without credentials; sign it here.
                add(coil3.map.Mapper<String, String> { data, _ -> if (Subsonic.isCoverRef(data)) Subsonic.resolveCover(data) else null })
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(200)
            .build()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Media3's warnings and errors (a failed load, a decoder that won't
        // start) into the app's own log as well as logcat, for "Copy log".
        androidx.media3.common.util.Log.setLogger(object : androidx.media3.common.util.Log.Logger {
            private val system = androidx.media3.common.util.Log.Logger.DEFAULT
            override fun d(tag: String, message: String, throwable: Throwable?) = system.d(tag, message, throwable)
            override fun i(tag: String, message: String, throwable: Throwable?) = system.i(tag, message, throwable)
            override fun w(tag: String, message: String, throwable: Throwable?) {
                AppLog.add('W', tag, message, throwable)
                system.w(tag, message, throwable)
            }
            override fun e(tag: String, message: String, throwable: Throwable?) {
                AppLog.add('E', tag, message, throwable)
                system.e(tag, message, throwable)
            }
        })
        AppSettings.init(this)
        com.opentune.data.lossless.SpotiflacSession.init(this)
        History.init(this)
        LoudnessStore.init(this)
        UpgradedTracks.init(this)
        QueueStore.init(this)
        com.opentune.data.together.Together.init(this)
        LastFm.init(this)
        Subsonic.init(this)
        ListenBrainz.init(this)
        com.opentune.data.radio.Radio.init(this)
        com.opentune.data.releases.NewReleases.init(this)
        com.opentune.data.UpdateCheck.schedule(this)
        com.opentune.data.Usage.init(this)
        // Spotify sign-in is gone; drop any tokens an earlier version kept.
        deleteSharedPreferences("spotify")
        com.opentune.data.podcasts.Podcasts.init(this)
        AlbumCovers.init(this)
        AutoEq.init(this)
        LibraryStore.init(this)
        Downloads.init(this)
        AccountStore.init(this)
        InnerTubeXResolver.init(this)
        // Last session's Home, shown at once and refreshed in the background.
        MusicRepository.init(this)?.let { ScreenCache.put("home", it, at = 0L) }
    }
}
