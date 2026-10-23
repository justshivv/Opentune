package com.opentune.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.InterruptedIOException

/**
 * Opens [upstream], patiently. When the server is busy (HTTP 429) and asks for a short
 * wait, it's waited out and the same address asked again, a couple of times, as BitChord
 * does with its add-ons. When the server turns the link down otherwise (another HTTP
 * error, a dropped connection), [renew] is asked for a new link to the same file, which
 * is opened instead, once. [renew] gets the address that failed and answers null when it
 * has nothing better; only addresses [patient] accepts are waited on.
 */
@UnstableApi
internal class RenewingDataSource(
    private val upstream: DataSource,
    private val renew: (failed: String) -> String?,
    private val patient: (url: String) -> Boolean = { true },
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        var waits = 0
        while (true) {
            try {
                return upstream.open(dataSpec)
            } catch (e: HttpDataSource.HttpDataSourceException) {
                // A load being cancelled isn't the link's fault.
                if (e is HttpDataSource.CleartextNotPermittedException || e.cause is InterruptedIOException) throw e
                val url = dataSpec.uri.toString()
                val wait = (e as? HttpDataSource.InvalidResponseCodeException)?.takeIf { it.responseCode == 429 }?.let { busy ->
                    busy.headerFields.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
                        ?.trim()?.toLongOrNull()?.times(1_000) ?: (BUSY_WAIT_MS shl waits)
                }
                if (wait != null) {
                    if (waits >= BUSY_TRIES || wait > BUSY_WAIT_CAP_MS || !patient(url)) throw e
                    runCatching { upstream.close() }
                    try { Thread.sleep(wait) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw e }
                    waits++
                    continue
                }
                val fresh = renew(url) ?: throw e
                runCatching { upstream.close() }
                return upstream.open(dataSpec.withUri(Uri.parse(fresh)))
            }
        }
    }

    private companion object {
        const val BUSY_TRIES = 2
        const val BUSY_WAIT_MS = 1_500L
        const val BUSY_WAIT_CAP_MS = 8_000L
    }
}
