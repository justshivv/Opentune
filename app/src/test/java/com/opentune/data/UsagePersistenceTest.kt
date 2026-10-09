package com.opentune.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UsagePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("usage-test", Context.MODE_PRIVATE)
    private val now = java.time.Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()

    @Test fun updatesAndForegroundCallsKeepIdentityAndDoNotInventPlays() {
        prefs.edit().clear().commit()
        val first = Usage.persistDaily(prefs, now, "0.4.2", true)!!
        val second = Usage.persistDaily(prefs, now, "0.4.2", false)!!
        val upgrade = Usage.persistDaily(prefs, now, "0.4.3", false)!!
        assertEquals(first.first, second.first)
        assertEquals(first.first, upgrade.first)
        assertEquals(1, prefs.getInt("total:20261009|0.4.2", -1))
        assertEquals(0, prefs.getInt("total:20261009|0.4.3", -1))
        assertEquals(2, upgrade.second.size)
    }

    @Test fun clearingDataCreatesNewInstallationAndOldPendingReportsExpire() {
        prefs.edit().clear().commit()
        val first = Usage.persistDaily(prefs, now, "0.4.2", true)!!
        val later = Usage.persistDaily(prefs, now + 31L * 86400000, "0.4.2", false)!!
        assertEquals(first.first, later.first)
        assertEquals(1, later.second.size)
        assertFalse(prefs.contains("total:20261009|0.4.2"))
        prefs.edit().clear().commit()
        val reinstall = Usage.persistDaily(prefs, now, "0.4.2", false)!!
        assertNotEquals(first.first, reinstall.first)
    }
}
