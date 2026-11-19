package com.opentune.data.addons

import org.junit.Assert.assertEquals
import org.junit.Test

class AddonRefreshScheduleTest {
    @Test fun theAddonListIsCheckedOnceADay() {
        val now = 1_760_000_000_000L
        assertEquals(true, AddonRefresh.due(0L, now))
        assertEquals(false, AddonRefresh.due(now - 23 * 60 * 60_000L, now))
        assertEquals(true, AddonRefresh.due(now - AddonRefresh.INTERVAL, now))
        // A failed check is recorded as due again an hour later.
        assertEquals(false, AddonRefresh.due(now - AddonRefresh.INTERVAL + AddonRefresh.RETRY, now + AddonRefresh.RETRY - 1))
        assertEquals(true, AddonRefresh.due(now - AddonRefresh.INTERVAL + AddonRefresh.RETRY, now + AddonRefresh.RETRY))
        // A clock set back doesn't stop checks for good.
        assertEquals(true, AddonRefresh.due(now + 60_000, now))
    }
}
