package com.opentune.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.InterruptedIOException

/**
 * Opens [upstream], and when the server turns the link down (an HTTP error, a dropped
 * connection) asks [renew] for a new link to the same file and opens that instead, once.
 * [renew] gets the address that failed and answers null when it has nothing better.
 */
@UnstableApi
internal class RenewingDataSource(
    private val upstream: DataSource,
    private val renew: (failed: String) -> String?,
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long = try {
        upstream.open(dataSpec)
    } catch (e: HttpDataSource.HttpDataSourceException) {
        // A load being cancelled isn't the link's fault.
        if (e is HttpDataSource.CleartextNotPermittedException || e.cause is InterruptedIOException) throw e
        val fresh = renew(dataSpec.uri.toString()) ?: throw e
        runCatching { upstream.close() }
        upstream.open(dataSpec.withUri(Uri.parse(fresh)))
    }
}
