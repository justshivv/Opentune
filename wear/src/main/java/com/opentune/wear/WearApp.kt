package com.opentune.wear

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.opentune.data.Core
import com.opentune.data.Http
import com.opentune.data.MusicRepository
import com.opentune.data.subsonic.Subsonic
import okio.Path.Companion.toOkioPath

class WearApp : Application(), SingletonImageLoader.Factory {
    /** Covers on the shared HTTP client, with less memory and disk than the phone gets. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { Http.client }))
                add(coil3.map.Mapper<String, String> { data, _ -> if (Subsonic.isCoverRef(data)) Subsonic.resolveCover(data) else null })
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.1).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(20L * 1024 * 1024)
                    .build()
            }
            .build()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        WatchDownloads.rules()
        Core.init(this, phone = false, form = "watch")
        WatchDownloads.init(this)
        // The playback notification marks OpenTune on the watch face while music plays.
        com.opentune.playback.PlaybackHooks.notificationProvider = { OngoingPlayback(it) }
        com.opentune.playback.PlaybackHooks.sharedPlayer = { WatchNow.player(it) }
        com.opentune.playback.PlaybackHooks.steadyPosition = true
        com.opentune.playback.PlaybackHooks.keepRemote = true
        MusicRepository.init(this)
    }
}
