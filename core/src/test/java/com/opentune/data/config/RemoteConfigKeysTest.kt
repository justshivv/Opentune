package com.opentune.data.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which signing key a configuration is taken under: the pinned one, one this build trusts, or
 * on first use the one the server offers. The live fixture is the dashboard's own configuration
 * (revision 50, with a banner), signed with the key the app ships.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RemoteConfigKeysTest {
    private fun read(name: String) = JSONObject(javaClass.getResourceAsStream("/config/$name")!!.bufferedReader().use { it.readText() })
    private val live = read("dashboard-live.json")
    private val liveEnvelope = live.getJSONObject("envelope").toString()
    private val liveNow = live.getLong("now")
    private val other = read("signed.json")
    private val otherKey = other.getString("publicKey")

    @Test fun theLiveDashboardIsSignedWithTheBundledKey() {
        assertTrue(live.getString("publicKey") in RemoteConfig.BUNDLED_KEYS)
    }

    @Test fun aStalePinIsReplacedByTheBundledKey() {
        // Pinned to another key, with a revision count far past the dashboard's: the banner still arrives.
        val (key, config) = RemoteConfig.accept(liveEnvelope, pinned = otherKey, revision = 999, offered = { null }, now = liveNow)!!
        assertEquals(RemoteConfig.BUNDLED_KEYS.first(), key)
        assertEquals(50L, config.revision)
        assertEquals("Join Discord", config.banners.single().action)
    }

    @Test fun aBundledPinStillRefusesAnOlderRevision() {
        assertNull(RemoteConfig.accept(liveEnvelope, pinned = RemoteConfig.BUNDLED_KEYS.first(), revision = 51, offered = { null }, now = liveNow))
    }

    @Test fun aPinnedKeyIsNeverSwappedForOneTheServerOffers() {
        // Signed by a key this build doesn't know: taken on first use only.
        val envelope = other.getJSONObject("envelope").toString(); val now = other.getLong("now")
        assertNull(RemoteConfig.accept(envelope, pinned = RemoteConfig.BUNDLED_KEYS.first(), revision = 0, offered = { otherKey }, now = now))
        assertEquals(otherKey, RemoteConfig.accept(envelope, pinned = null, revision = 0, offered = { otherKey }, now = now)!!.first)
    }
}
