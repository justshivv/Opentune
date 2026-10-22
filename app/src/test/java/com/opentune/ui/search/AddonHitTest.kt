package com.opentune.ui.search

import com.opentune.data.addons.AddonManifest
import com.opentune.data.addons.AddonTrack
import com.opentune.data.addons.AudioAddon
import org.junit.Assert.assertEquals
import org.junit.Test

class AddonHitTest {
    private fun hit(addon: String, id: String, title: String, artist: String, ms: Long): AddonHit {
        val m = AddonManifest(addon, addon, "1")
        return AddonHit(AddonTrack(id, title, artist, null, ms, null, null, "flac"), AudioAddon(addon, "https://$addon.example/", m), m)
    }

    @Test fun theSameRecordingFromSeveralAddOnsShowsOnce() {
        val merged = AddonHit.merged(listOf(
            hit("a", "1", "52 Bars", "Karan Aujla, Ikky", 214_000),
            hit("b", "9", "52 bars", "Karan Aujla", 214_900),
            hit("a", "2", "Wavy", "Karan Aujla", 161_000),
            hit("c", "7", "52 Bars", "Karan Aujla, Ikky", 214_000),
        ))
        assertEquals(listOf("52 Bars", "Wavy"), merged.map { it.track.title })
        assertEquals(listOf("b", "c"), merged[0].alternates.map { it.addon.key })
    }

    @Test fun anotherVersionStaysApart() {
        val merged = AddonHit.merged(listOf(
            hit("a", "1", "Boyfriend", "Karan Aujla", 161_000),
            hit("b", "2", "Boyfriend", "Karan Aujla", 175_000),
        ))
        assertEquals(2, merged.size)
    }
}
