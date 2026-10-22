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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Prefer a cached source or a quick JioSaavn match; never wait for FLAC at startup. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StartupSelection(
    private val song: Song,
    private val original: Uri,
    private val maximum: () -> Boolean = { AppSettings.effectiveAudioQuality == AudioQuality.MAX },
    /** Whether lossless is wanted at all, at any quality setting (an add-on can give it). */
    private val losslessWanted: () -> Boolean = { ExternalStreams.allowed(true) },
    private val lookupBudgetMs: Long = 900L,
    private val findJioSaavn: suspend (Song, Long) -> ExternalStreams.Rendition? = ExternalStreams::findJioSaavn,
    private val cached: (String) -> ExternalStreams.Rendition? = ExternalStreams::cached,
) {
    private val mutex = Mutex()
    @Volatile var selected: Uri? = null
        private set
    suspend fun resolve(spec: DataSpec): DataSpec = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (selected == null) {
            // A lossless file found ahead of time starts the song whenever lossless is on, not only at Max.
            val ready = if (maximum() || losslessWanted()) cached(song.videoId)?.takeIf { it.mimeType == null && it.lossless } else null
            val rendition = ready ?: if (maximum()) try {
                withTimeoutOrNull(lookupBudgetMs) { findJioSaavn(song, song.durationMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } else null
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



