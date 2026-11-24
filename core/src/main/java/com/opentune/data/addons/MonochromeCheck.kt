package com.opentune.data.addons

import com.opentune.data.lossless.LosslessHeader

/** Checks one playable file through the same service, HTTP client and FLAC reader as playback. */
object MonochromeCheck {
    suspend fun search(source: AudioAddon, query: String): List<AddonTrack> =
        AddonClient(source.base).search(query, source.manifest, "LOSSLESS").take(5)

    suspend fun verify(source: AudioAddon, track: AddonTrack): String? {
        val resolved = AddonClient.forBase(source.base).stream(track.id, source.manifest, "LOSSLESS", fresh = true)
        if (resolved.codec != "flac" || resolved.transport != null) return null
        val header = LosslessHeader.read(resolved.url, AddonHttp::bytes) as? LosslessHeader.Flac ?: return null
        val info = header.info
        if (!LosslessHeader.sameRecording(track.durationMs, track.durationMs, info.durationMs)) return null
        return "${info.bits}-bit FLAC · ${info.sampleRate} Hz"
    }
}
