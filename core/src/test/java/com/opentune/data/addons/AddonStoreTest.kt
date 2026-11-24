package com.opentune.data.addons

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddonStoreTest {
    @Test fun savedMonochromeSourcesAreDroppedOnLoadAndPersonalServersStay() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val origin = FirstPartyAddonAuth.ORIGIN
        fun entry(key: String, base: String, id: String, enabled: Boolean = true) = org.json.JSONObject().put("key", key).put("base", base)
            .put("enabled", enabled).put("admin", false).put("ownerOff", false)
            .put("manifest", org.json.JSONObject().put("id", id).put("name", key).put("version", "1"))
        // As the built-in module, the "Add Monochrome" shortcut and the official root alias saved them.
        val saved = org.json.JSONArray()
            .put(entry("builtin:monochrome", "$origin/monochrome/", "org.opentune.monochrome"))
            .put(entry("mine", "https://mine.example/", "mine"))
            .put(entry("shortcut", "$origin/monochrome/", "org.opentune.monochrome", enabled = false))
            .put(entry("worker", "${FirstPartyAddonAuth.WORKER_ORIGIN}/monochrome/", "org.opentune.monochrome"))
            .put(entry("root", "$origin/", "org.opentune.monochrome"))
            .put(entry("archive", "$origin/archive/", "org.opentune.archive"))
            .put(entry("personal", "https://other.example/", "org.opentune.monochrome"))
        context.getSharedPreferences("audio_addons", 0).edit().clear().putString("items", saved.toString()).commit()
        AddonStore.init(context)
        assertEquals(listOf("mine", "archive", "personal"), AddonStore.addons.value.map { it.key })
        // Rewritten, so the next launch starts clean too.
        assertFalse(context.getSharedPreferences("audio_addons", 0).getString("items", "")!!.contains("/monochrome/"))
        AddonStore.init(context)
        assertEquals(listOf("mine", "archive", "personal"), AddonStore.addons.value.map { it.key })
        // A published list still naming it, or its address pasted in, doesn't bring it back.
        AddonStore.syncCommunity(listOf(AudioAddon("admin:monochrome", "$origin/monochrome/", AddonManifest("admin:monochrome", "Monochrome", ""), admin = true)))
        assertEquals(listOf("mine", "archive", "personal"), AddonStore.addons.value.map { it.key })
        assertThrows(IllegalArgumentException::class.java) { AddonStore.add("$origin/monochrome/manifest.json", AddonManifest("org.opentune.monochrome", "Monochrome", "1")) }
        assertEquals(3, AddonStore.addons.value.size)
    }

    @Test fun preservesPriorityAndEnablementAcrossRestartAndDoesNotExposePathAsName() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AddonStore.init(context)
        AddonStore.add("https://one.example/private/manifest.json", AddonManifest("first", "First", "1"))
        AddonStore.add("https://two.example/", AddonManifest("second", "Second", "1", downloads = false, outputRequired = true))
        val second = AddonStore.addons.value.last()
        AddonStore.move(second.key, -1); AddonStore.enable(second.key, false)
        AddonStore.init(context)
        assertEquals("second", AddonStore.addons.value.first().manifest.id)
        assertFalse(AddonStore.addons.value.first().enabled)
        assertFalse(AddonStore.addons.value.first().manifest.downloads)
        assertFalse(AddonStore.usable(AddonStore.addons.value.first()))
        AddonStore.enable(second.key, true)
        AddonStore.outputAllowed = { false }; assertFalse(AddonStore.usable(AddonStore.addons.value.first()))
        AddonStore.outputAllowed = { true }; assertTrue(AddonStore.usable(AddonStore.addons.value.first()))
        AddonStore.remove(second.key); assertEquals(1, AddonStore.addons.value.size)
    }

    @Test fun communityRefreshPreservesPersonalSourcesAndListenerChoices() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("audio_addons",0).edit().clear().commit()
        AddonStore.init(context)
        AddonStore.add("https://mine.example/", AddonManifest("mine", "Mine", "1"))
        val admin = AudioAddon("admin:one", "https://community.example/", AddonManifest("remote", "Community source", "1"), admin = true)
        AddonStore.syncCommunity(listOf(admin)); AddonStore.enable(admin.key, false); AddonStore.move(admin.key, -1)
        AddonStore.syncCommunity(listOf(admin.copy(base = "https://updated.example/")))
        assertEquals(admin.key, AddonStore.addons.value.first().key)
        assertFalse(AddonStore.addons.value.first().enabled)
        AddonStore.refreshed(admin.key, AddonManifest("remote", "Underlying provider", "2"))
        assertEquals("Community source", AddonStore.addons.value.first().manifest.name)
        AddonStore.syncCommunity(emptyList())
        assertEquals("Mine", AddonStore.addons.value.single().manifest.name)
    }

    @Test fun aSourceTheOwnerTurnedOffIsNeverUsableAndComesBackAsTheListenerLeftIt() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("audio_addons",0).edit().clear().commit()
        AddonStore.init(context)
        AddonStore.outputAllowed = { true }
        val admin = AudioAddon("admin:one", "https://community.example/", AddonManifest("remote", "Community source", "1"), admin = true)
        AddonStore.syncCommunity(listOf(admin.copy(ownerOff = true)))
        assertFalse(AddonStore.usable(AddonStore.addons.value.single()))
        AddonStore.init(context)
        assertTrue(AddonStore.addons.value.single().ownerOff)
        AddonStore.syncCommunity(listOf(admin))
        assertTrue(AddonStore.usable(AddonStore.addons.value.single()))
    }
}
