package com.opentune.data.lossless

import com.opentune.data.model.Song

/** The quality family a [StreamSource] answers in: a verified lossless file, or the best lossy stream below it. */
enum class SourceTier { LOSSLESS, HIGH_BITRATE }

/**
 * What a [StreamSource] costs and can do, so planners (the queue's preload, song start)
 * can weigh sources without knowing their names: a [fastPath] source answers quickly
 * enough to be asked while a song starts; a [keepsMatches] one remembers its matches
 * across launches, so asking it again is cheap; [searchCost] is the relative weight of
 * one full search (1 = a couple of requests, 3 = a fan-out across services).
 */
data class SourceCapabilities(val fastPath: Boolean, val keepsMatches: Boolean, val searchCost: Int)

/**
 * One verified stream a [StreamSource] found for a song: everything [ExternalStreams]
 * needs to pin it as a rendition, and nothing about how it was found. A source returns
 * only streams it has itself verified (the recording's identity, and the stream's own
 * first bytes where the source can read them), so a hit is playable as-is.
 */
data class SourceHit(
    val url: String,
    val provider: String,
    val lossless: Boolean,
    val bits: Int? = null,
    val sampleRate: Int? = null,
    val kbps: Int? = null,
    val addonId: String? = null,
    val mimeType: String? = null,
    val trackId: String? = null,
    val tier: String? = null,
    val codec: String? = null,
)

/**
 * A place songs are looked up outside YouTube: the add-on catalogues, JioSaavn, and
 * whatever joins them later. Playback, preload and downloads speak to sources only
 * through this contract and [SourceRegistry], never to a source by name, so a new
 * source is one new implementation and one registry line, and the layers above it
 * (which songs to ready, how much of them, what plays first) stay as they are.
 *
 * A source owns its matching and its verification: [find] answers a stream already
 * checked against the song (title, artists, album and duration, and the stream's own
 * bytes where the source can read them), or null when it has no matching recording.
 * Whether its misses are remembered, and for how long, is the source's own business.
 */
interface StreamSource {
    /** Stable id, unique in [SourceRegistry]; used in rendition keys and logs. */
    val id: String

    /** The quality family this source answers in. */
    val tier: SourceTier

    /** What asking this source costs and what it can do; see [SourceCapabilities]. */
    val capabilities: SourceCapabilities

    /** Whether the current settings and network allow this source at all right now. */
    fun eligible(): Boolean

    /**
     * This source's verified stream for [song], or null when it has none. [report]
     * receives the source's own line for the pipeline report ("Name: verified ..."),
     * and [onArtists] the fuller artist credit when the source's catalogue bills one.
     */
    suspend fun find(song: Song, durationMs: Long, report: (String) -> Unit = {},
                     onArtists: (String) -> Unit = {}): SourceHit?
}
