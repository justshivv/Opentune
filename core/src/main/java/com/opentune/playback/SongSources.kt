package com.opentune.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.opentune.data.Http
import com.opentune.data.innertube.StreamResolver
import com.opentune.data.lossless.ExternalStreams
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * Where songs are read from: an `opentune://track/<id>` item through the
 * song cache and, on a miss, [resolveTrack]; files, content:// and direct
 * http (a song on your own server) straight from the platform source.
 *
 * [PlaybackService]'s player reads through these, and so do short-lived
 * players elsewhere (the music behind Wrapped), so they play songs exactly
 * as the main player does and share its cache.
 */
@OptIn(UnstableApi::class)
internal class SongSources(context: Context) {
    // The same OkHttp client Innertube and the stream resolver use — a
    // stream URL is bound to the connection context of the request that
    // minted it, so a separate HTTP stack here risks a 403 on playback.
    // No fixed User-Agent: [resolveTrack] sends the minting client's own.
    private val refusing = RefusalReportingDataSource.Factory(OkHttpDataSource.Factory(Http.client))
    // An add-on's file that answers HTTP 429 is waited on and asked again, and one that
    // refuses its link gets a new link from the add-on, rather than the song falling back.
    private val http = DataSource.Factory {
        RenewingDataSource(refusing.createDataSource(), renew = ExternalStreams::refreshLinkFor, patient = ExternalStreams::isAddonLink)
    }
    private val resolving = ResolvingDataSource.Factory(http, ::resolveTrack)

    val cached: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(AudioCache.get(context))
        .setUpstreamDataSourceFactory(resolving)
        .setCacheKeyFactory { spec -> cacheKeyOf(spec.uri) ?: spec.key ?: spec.uri.toString() }
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // Files, content:// and direct http (a song on your own server) go straight
    // to the platform source, its http side on the app's one OkHttp client.
    val direct: DataSource.Factory = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(Http.client))

    val routing: DataSource.Factory = SchemeRoutingDataSource.Factory(streams = cached, local = direct)
}

/** Swaps an `opentune://track/<id>` spec for the stream URL it stands for, with the headers that URL needs. */
@OptIn(UnstableApi::class)
internal fun resolveTrack(dataSpec: DataSpec): DataSpec {
    val videoId = videoIdOf(dataSpec.uri) ?: return dataSpec
    val url = try {
        runBlocking {
            val losslessKey = externalStreamKeyOf(dataSpec.uri)
            when {
                losslessKey != null -> ExternalStreams.resolve(videoId, losslessKey)
                isUpgradedUri(dataSpec.uri) -> StreamResolver.resolveUpgrade(videoId)
                else -> StreamResolver.resolve(videoId)
            }
        }
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e.message ?: "Couldn't resolve a stream for $videoId", e)
    }
    // googlevideo expects the media request to look like the client that
    // minted the URL; these are also the headers StreamResolver probed with.
    return dataSpec.withUri(url.toUri())
        .withAdditionalHeaders(if (externalStreamKeyOf(dataSpec.uri) != null) emptyMap() else StreamResolver.mediaHeadersFor(url))
}

/**
 * Tells [StreamResolver] when googlevideo refuses a URL it handed out, so
 * the cached URL is dropped and the next attempt resolves a new one.
 */
@OptIn(UnstableApi::class)
private class RefusalReportingDataSource(
    private val upstream: HttpDataSource,
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long = try {
        upstream.open(dataSpec)
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        StreamResolver.onPlaybackRefused(dataSpec.uri.toString(), e.responseCode)
        throw e
    }

    class Factory(private val upstream: HttpDataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RefusalReportingDataSource(upstream.createDataSource())
    }
}

/**
 * Sends streamed tracks through the cache and resolver, and anything else
 * (local `content://` files) straight to the platform data source, so local
 * files neither fill the cache nor go looking for a YouTube stream.
 */
@OptIn(UnstableApi::class)
internal class SchemeRoutingDataSource(
    private val streams: DataSource,
    private val local: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        streams.addTransferListener(transferListener)
        local.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (videoIdOf(dataSpec.uri) != null) streams else local
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun close() {
        active?.close()
        active = null
    }

    class Factory(
        private val streams: DataSource.Factory,
        private val local: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SchemeRoutingDataSource(streams.createDataSource(), local.createDataSource())
    }
}
