package com.opentune.data.lossless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRegistryTest {
    @Test
    fun idsAreUniqueAndEverySourceIsFindable() {
        val ids = SourceRegistry.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        SourceRegistry.all.forEach { assertSame(it, SourceRegistry.byId(it.id)) }
    }

    @Test
    fun losslessIsPreferredBeforeHighBitrate() {
        val tiers = SourceRegistry.all.map { it.tier }
        assertEquals(SourceTier.LOSSLESS, tiers.first())
        assertTrue(tiers.indexOf(SourceTier.LOSSLESS) < tiers.indexOf(SourceTier.HIGH_BITRATE))
    }

    @Test
    fun tiersPartitionTheRegistry() {
        val byTier = SourceTier.entries.flatMap { SourceRegistry.ofTier(it) }
        assertEquals(SourceRegistry.all.toSet(), byTier.toSet())
    }

    @Test
    fun capabilitiesMatchTheSourcesWays() {
        val saavn = SourceRegistry.byId("jiosaavn")!!
        assertTrue(saavn.capabilities.fastPath)
        assertTrue(saavn.capabilities.keepsMatches)
        val addons = SourceRegistry.byId("addons")!!
        assertTrue(addons.capabilities.searchCost > saavn.capabilities.searchCost)
    }
}
