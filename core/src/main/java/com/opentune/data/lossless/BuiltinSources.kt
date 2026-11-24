package com.opentune.data.lossless

import com.opentune.data.model.Song

/**
 * JioSaavn behind the [StreamSource] contract: the built-in 320 kbps catalogue.
 * The lookup itself is unchanged (see [JioSaavnSource]): the strict catalogue
 * identity decides the match, and the stream is verified before it is handed out;
 * a remembered match (see SaavnMatches) skips only the search, never the checks.
 */
object JioSaavnStreamSource : StreamSource {
    override val id = "jiosaavn"
    override val tier = SourceTier.HIGH_BITRATE
    override val capabilities = SourceCapabilities(fastPath = true, keepsMatches = true, searchCost = 1)

    /** The name this source's renditions carry; the one place it is written. */
    val provider = "JioSaavn"

    override fun eligible(): Boolean = ExternalStreams.allowed(false)

    override suspend fun find(song: Song, durationMs: Long,
                              report: (String) -> Unit, onArtists: (String) -> Unit): SourceHit? =
        JioSaavnSource.resolve(song, durationMs, report = report, onArtists = onArtists)?.let { url ->
            SourceHit(url = url, provider = provider, lossless = false, kbps = 320)
        }
}

/**
 * The add-on catalogues behind the [StreamSource] contract: the user-configured
 * lossless services (BitChord, Monochrome, ...) already resolve generically through
 * [LosslessSource] and the add-on resolver; this is their one registry face.
 * [find] is the best verified file; [findAll] is the full search the pipeline runs,
 * with its late answers and per-add-on settling.
 */
object AddonStreamSource : StreamSource {
    override val id = "addons"
    override val tier = SourceTier.LOSSLESS
    override val capabilities = SourceCapabilities(fastPath = false, keepsMatches = false, searchCost = 3)

    override fun eligible(): Boolean = ExternalStreams.allowed(true)

    override suspend fun find(song: Song, durationMs: Long,
                              report: (String) -> Unit, onArtists: (String) -> Unit): SourceHit? =
        LosslessSource.resolve(song, durationMs, report = report)?.toHit()

    /** Every verified match, best first; see [LosslessSource.resolveAll]. */
    suspend fun findAll(song: Song, durationMs: Long, preferHiRes: Boolean = false,
                        bestAvailable: Boolean = false, report: (String) -> Unit = {},
                        late: (SourceHit) -> Unit = {}, skip: Set<String> = emptySet(),
                        settled: (addon: String, erred: Boolean) -> Unit = { _, _ -> }): List<SourceHit> =
        LosslessSource.resolveAll(song, durationMs, preferHiRes = preferHiRes, bestAvailable = bestAvailable,
            report = report, late = { late(it.toHit()) }, skip = skip, settled = settled).map { it.toHit() }

    private fun LosslessSource.Track.toHit() = SourceHit(
        url = url, provider = provider, lossless = true, bits = bits, sampleRate = sampleRate,
        addonId = addonId, mimeType = mimeType, trackId = trackId.ifEmpty { null },
        tier = tier.ifEmpty { null }, codec = codec,
    )
}
