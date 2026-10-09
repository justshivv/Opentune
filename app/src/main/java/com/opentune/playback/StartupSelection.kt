package com.opentune.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.opentune.data.lossless.ExternalStreams
import com.opentune.data.model.Song
import com.opentune.data.model.durationMillis
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

/** One choice per media source, made before the cache opens. Seeks must keep the same bytes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StartupSelection(
    private val song: Song,
    private val original: Uri,
    private val maximum: () -> Boolean = { AppSettings.effectiveAudioQuality == AudioQuality.MAX },
    private val find: suspend (Song, Long) -> ExternalStreams.Rendition? = { item, duration ->
        ExternalStreams.find(item, duration, beforePlayback = true)
    },
) {
    private val mutex = Mutex()
    @Volatile var selected: Uri? = null
        private set
    @Volatile var checkedMaximum: Boolean = false
        private set

    suspend fun resolve(spec: DataSpec): DataSpec = mutex.withLock {
        if (selected == null) {
            checkedMaximum = maximum()
            val rendition = try {
                if (checkedMaximum) find(song, song.durationMillis()) else null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            selected = rendition?.let { externalStreamUri(song.videoId, it.key) } ?: original
        }
        spec.withUri(selected!!)
    }
}

internal fun startupToken(uri: Uri): String? = if (videoIdOf(uri) != null) uri.getQueryParameter("startup") else null
internal fun withoutStartup(uri: Uri): Uri = uri.buildUpon().clearQuery().apply {
    uri.queryParameterNames.filterNot { it == "startup" }.forEach { name ->
        uri.getQueryParameters(name).forEach { appendQueryParameter(name, it) }
    }
}.build()
internal fun youtubeFallbackUri(id: String, upgraded: Boolean = false): Uri = streamUri(id, upgraded).buildUpon().appendQueryParameter("fallback", "youtube").build()
