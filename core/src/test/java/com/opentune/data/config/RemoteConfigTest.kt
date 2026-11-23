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
    @Test fun optionalLinksAreKeptOnlyWhenSetAndOnTheirOwnSite() {
        val url = { raw: String -> com.opentune.data.addons.AddonProtocol.url(raw).toString() }
        val host = { raw: String -> com.opentune.data.addons.AddonProtocol.url(raw).host }
        val links = JSONObject(
            """{"website":"https://opentune.example.org/","x":"https://x.com/opentune","instagram":"https://www.instagram.com/opentune/",
               "telegram":"https://t.me.evil.example/","reddit":"","youtube":"http://youtube.com/@opentune","donate":"https://127.0.0.1/"}""",
        )
        val extra = RemoteConfigProtocol.optional(links, url, host)
        assertEquals("https://opentune.example.org/", extra["website"])
        assertEquals("https://x.com/opentune", extra["x"])
        assertEquals("https://www.instagram.com/opentune/", extra["instagram"])
        // Off its own site, empty, not HTTPS, or not public: left out, and the rest still read.
        assertNull(extra["telegram"]); assertNull(extra["reddit"]); assertNull(extra["youtube"]); assertNull(extra["donate"])
        val config = AppConfig(extra = extra)
        assertEquals(listOf("x", "instagram"), config.socials.map { it.first })
        assertEquals("https://github.com/justshivv/Opentune/releases/latest", config.repo("releases/latest"))
        // A configuration from before these links has none.
        assertTrue(RemoteConfigProtocol.optional(JSONObject("""{"github":"https://github.com/a/b"}"""), url, host).isEmpty())
    }

    @Test fun defaultsDoNotReplacePersonalCatalogOrTrustPolicy() {
        assertNull(AppConfig().sources)
        assertEquals(RemoteConfigProtocol.sectionIds, AppConfig().sections.map { it.id })
    }
}
