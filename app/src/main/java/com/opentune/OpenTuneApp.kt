package com.opentune

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
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(200)
            .build()

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        History.init(this)
        LoudnessStore.init(this)
        UpgradedTracks.init(this)
        QueueStore.init(this)
        LibraryStore.init(this)
        Downloads.init(this)
        AccountStore.init(this)
        InnerTubeXResolver.init(this)
        // Last session's Home, shown at once and refreshed in the background.
        MusicRepository.init(this)?.let { ScreenCache.put("home", it, at = 0L) }
    }
}
