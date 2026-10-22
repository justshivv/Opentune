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
import com.opentune.data.MusicRepository
import com.opentune.data.subsonic.Subsonic
import com.opentune.ui.ScreenCache
import okio.Path.Companion.toOkioPath

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
                add(coil3.map.Mapper<String, String> { data, _ -> com.opentune.data.library.LibraryArtwork.local(data) })
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

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this is kept for Export diagnostics.
        com.opentune.data.CrashLog.install(this)
        // The home-screen widget follows the shared player service.
        com.opentune.data.AppHooks.onPlayerChanged = { context, player -> com.opentune.widget.NowPlayingWidget.refresh(context, player) }
        com.opentune.data.AppHooks.onPlayerStopped = { context -> com.opentune.widget.NowPlayingWidget.stopped(context) }
        com.opentune.data.Core.init(this)
        // Last session's Home, shown at once and refreshed in the background.
        MusicRepository.init(this)?.let { ScreenCache.put("home", it, at = 0L) }
        // After history, library, downloads and account: they tell an existing user from a new one.
        com.opentune.data.Welcome.settle(this)
    }
}
