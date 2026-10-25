package com.opentune.data.addons

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddonRefreshTest {
    @Before fun reset() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("audio_addons", 0).edit().clear().commit()
        AddonStore.init(context)
    }

    @Test fun refreshesPersonalAndAdminWithoutChangingOrderOrDisabledSources() = runBlocking {
        AddonStore.add("https://mine.example/", AddonManifest("mine", "Mine", "1"))
        val personal = AddonStore.addons.value.single()
        val admin = AudioAddon("admin:one", "https://admin.example/", AddonManifest("a", "Owner name", "1"), admin = true)
        val off = admin.copy(key = "admin:off", ownerOff = true)
        val disabled = admin.copy(key = "admin:disabled", enabled = false)
        AddonStore.syncCommunity(listOf(admin, off, disabled))
        AddonStore.enable(disabled.key, false)
        AddonStore.move(admin.key, -1)
        val before = AddonStore.addons.value
        val called = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        assertTrue(AddonRefresh.refreshInstalled(before, force = true) { addon, fresh ->
            assertTrue(fresh)
            called += addon.key
            addon.manifest.copy(name = "Provider name", version = "2")
        })
        val after = AddonStore.addons.value
        assertEquals(setOf(personal.key, admin.key), called)
        assertEquals(before.map { it.key to it.enabled }, after.map { it.key to it.enabled })
        assertEquals("Owner name", after.first().manifest.name)
        assertEquals("2", after.first().manifest.version)
    }

    @Test fun offlineAndStaleResponsesCannotEraseWorkingManifests() = runBlocking {
        val admin = AudioAddon("admin:one", "https://old.example/", AddonManifest("a", "Owner", "1"), admin = true)
        AddonStore.syncCommunity(listOf(admin))
        assertFalse(AddonRefresh.refreshInstalled(listOf(admin)) { _, _ -> error("offline") })
        assertEquals(admin, AddonStore.addons.value.single())
        AddonRefresh.refreshInstalled(listOf(admin)) { _, _ ->
            AddonStore.syncCommunity(listOf(admin.copy(base = "https://new.example/")))
            admin.manifest.copy(version = "stale")
        }
        assertEquals("1", AddonStore.addons.value.single().manifest.version)
    }

    @Test fun manualRefreshBypassesManifestCacheButAutomaticRefreshUsesItsTtl() = runBlocking {
        var requests = 0
        var now = 0L
        val client = AddonClient("https://source.example/", fetch = {
            requests++
            """{"id":"test","name":"Test","version":"$requests","resources":["search","stream"]}"""
        }, now = { now })
        assertEquals("1", client.manifest().version)
        assertEquals("1", client.manifest().version)
        assertEquals("2", client.manifest(fresh = true).version)
        now = 600_001L
        assertEquals("3", client.manifest().version)
    }
}
