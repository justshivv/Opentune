package com.opentune.data.config

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RemoteConfigTest {
    private fun fixture() = JSONObject(javaClass.getResourceAsStream("/config/signed.json")!!.bufferedReader().use { it.readText() })
    @Test fun acceptsRealNodeSignatureAndPreservesOrderedContentAndOwnerSwitches() {
        val f = fixture()
        val config = RemoteConfigProtocol.verify(f.getJSONObject("envelope").toString(), f.getString("publicKey"), now = f.getLong("now"))
        assertEquals(1L, config.revision)
        assertEquals("recommendations", config.sections.first().id)
        assertEquals("Your next favorite", config.title("quick-picks", "Quick picks"))
        assertEquals(setOf("android", "tv", "web"), config.banners.single().platforms)
        assertEquals("VLPLabcdefghijk", config.playlists.single().browseId)
        assertFalse(config.sources!!.single().enabled)
        assertEquals(8000L, config.timeoutMs)
    }
    @Test fun rejectsTamperExpiredDocumentsAndRevisionDowngrades() {
        val f = fixture(); val envelope = f.getJSONObject("envelope"); val key = f.getString("publicKey"); val now = f.getLong("now")
        assertThrows(Exception::class.java) { RemoteConfigProtocol.verify(envelope.toString(), key, 2, now) }
        assertThrows(Exception::class.java) { RemoteConfigProtocol.verify(envelope.toString(), key, now = now + 8 * 86_400_000L) }
        envelope.put("payload", envelope.getString("payload") + "x")
        assertThrows(Exception::class.java) { RemoteConfigProtocol.verify(envelope.toString(), key, now = now) }
    }
    @Test fun defaultsDoNotReplacePersonalCatalogOrTrustPolicy() {
        assertNull(AppConfig().sources)
        assertEquals(RemoteConfigProtocol.sectionIds, AppConfig().sections.map { it.id })
    }
}
