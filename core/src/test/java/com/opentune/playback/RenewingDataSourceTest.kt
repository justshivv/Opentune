package com.opentune.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RenewingDataSourceTest {
    /** Answers each open with the next of [codes] (200 opens), noting the address asked. */
    private class Scripted(vararg codes: Int) : DataSource {
        val codes = codes.toMutableList()
        val opened = mutableListOf<String>()
        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec.uri.toString()
            val code = codes.removeAt(0)
            if (code != 200) throw HttpDataSource.InvalidResponseCodeException(code, null, null,
                if (code == 429) mapOf("Retry-After" to listOf("0")) else emptyMap(), dataSpec, ByteArray(0))
            return 10
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun getUri(): Uri? = null
        override fun close() {}
        override fun addTransferListener(transferListener: TransferListener) {}
    }
    private val spec = DataSpec(Uri.parse("https://files.example/a.flac"))

    @Test fun aBusyFileServerIsWaitedOutAndAskedAgainForTheSameFile() {
        val upstream = Scripted(429, 429, 200)
        assertEquals(10, RenewingDataSource(upstream, renew = { null }).open(spec))
        assertEquals(List(3) { "https://files.example/a.flac" }, upstream.opened)
    }

    @Test fun aRefusedLinkIsSwappedForANewOneFromItsSource() {
        val upstream = Scripted(403, 200)
        assertEquals(10, RenewingDataSource(upstream, renew = { "https://files.example/b.flac" }).open(spec))
        assertEquals(listOf("https://files.example/a.flac", "https://files.example/b.flac"), upstream.opened)
    }

    @Test fun anythingButAnAddOnFileIsNotHeldUpByA429() {
        val upstream = Scripted(429, 200)
        try { RenewingDataSource(upstream, renew = { null }, patient = { false }).open(spec); fail() }
        catch (e: HttpDataSource.InvalidResponseCodeException) { assertEquals(429, e.responseCode) }
        assertEquals(1, upstream.opened.size)
    }
}
