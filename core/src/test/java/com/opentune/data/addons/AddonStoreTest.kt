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
    @Test fun builtInMonochromeNeedsNoUrlAndKeepsItsSwitchAcrossRestartAndCatalogSync() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("audio_addons", 0).edit().clear().commit()
        AddonStore.init(context)
        AddonStore.setMonochromeEnabled(true)
        AddonStore.setMonochromeEnabled(true)
        val source = AddonStore.addons.value.single()
        assertEquals(MonochromeModule.BASE, source.base)
        assertTrue(AddonStore.usable(source))
        AddonStore.setMonochromeEnabled(false)
        AddonStore.init(context)
        assertFalse(AddonStore.addons.value.single().enabled)
        val managed = source.copy(key = "admin:mono", admin = true, enabled = true, ownerOff = true)
        AddonStore.syncCommunity(listOf(managed))
        AddonStore.setMonochromeEnabled(true)
        assertEquals(managed.key, AddonStore.addons.value.single().key)
        assertFalse(AddonStore.usable(AddonStore.addons.value.single()))
        AddonStore.syncCommunity(listOf(managed.copy(ownerOff = false)))
        AddonStore.setMonochromeEnabled(true)
        assertTrue(AddonStore.usable(AddonStore.addons.value.single()))
    }

    @Test fun aPersonalServerUsingTheSameManifestIdCannotTakeOverTheBuiltInSwitch() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("audio_addons", 0).edit().clear().commit()
        AddonStore.init(context)
        AddonStore.add("https://other.example/", MonochromeModule.preset)
        AddonStore.setMonochromeEnabled(true)
        AddonStore.setMonochromeEnabled(false)
        assertTrue(AddonStore.addons.value.first().enabled)
        assertFalse(AddonStore.addons.value.single(MonochromeModule::matches).enabled)
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
