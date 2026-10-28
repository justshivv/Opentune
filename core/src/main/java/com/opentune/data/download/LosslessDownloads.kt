package com.opentune.data.download

import com.opentune.data.addons.AddonResolver
import com.opentune.data.addons.AddonStore
import com.opentune.data.addons.AddonStream
import com.opentune.data.lossless.ExternalStreams

/** A playback lookup is reusable, but its streaming permission does not imply download permission. */
internal object LosslessDownloads {
    fun shouldTry(maxKbps: Int, requested: Boolean, preferred: ExternalStreams.Rendition?): Boolean =
        maxKbps == Int.MAX_VALUE || requested || preferred?.lossless == true

    fun direct(rendition: ExternalStreams.Rendition?,
        permitted: (String) -> Boolean = { key ->
            AddonStore.addons.value.firstOrNull { it.key == key }?.let {
                it.manifest.downloads && AddonStore.usable(it) && !AddonResolver.paused(key)
            } == true
        }, urlOf: (ExternalStreams.Rendition) -> String = ExternalStreams::urlOf): AddonStream? {
        val r = rendition ?: return null
        val addon = r.addonId ?: return null
        val codec = r.codec ?: return null
        if (!r.lossless || r.mimeType != null || !permitted(addon)) return null
        return AddonStream(urlOf(r), codec, null, r.bits, r.sampleRate, r.kbps, addon,
            r.provider, r.trackId.orEmpty(), r.tier.orEmpty()).takeIf { it.lossless && it.extension != null }
    }
}
