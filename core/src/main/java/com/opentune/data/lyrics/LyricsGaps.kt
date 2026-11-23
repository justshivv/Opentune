package com.opentune.data.lyrics

import com.opentune.data.sponsorblock.SponsorBlock

/**
 * Lyrics for a music video with stretches that aren't the song: a scene with
 * no music in the middle, a talking intro, credits after. Lyrics are timed to
 * the song as released, which has none of them, so from the first stretch on
 * they ran ahead of the video by its length, whether it was skipped or not.
 * SponsorBlock marks those stretches ("music_offtopic"): the lyrics are looked
 * up for the song without them, and their times moved past each one.
 */
object LyricsGaps {
    const val CATEGORY = "music_offtopic"

    /** A stretch of the video with no song, from [startMs] to [endMs] on the video's clock. */
    data class Gap(val startMs: Long, val endMs: Long) {
        val lengthMs: Long get() = endMs - startMs
    }

    /** The stretches of [segments] that aren't the song, for a video [durationMs] long, in order. */
    fun of(segments: List<SponsorBlock.Segment>, durationMs: Long): List<Gap> =
        segments.filter { it.category == CATEGORY && it.endMs > it.startMs && SponsorBlock.fits(it, durationMs) }
            .map { Gap(it.startMs, if (durationMs > 0) minOf(it.endMs, durationMs) else it.endMs) }
            .filter { it.lengthMs > 0 }
            .sortedBy { it.startMs }
            .fold(mutableListOf<Gap>()) { out, g ->
                // Overlapping marks (two people marked the same credits) count once.
                val last = out.lastOrNull()
                if (last != null && g.startMs <= last.endMs) out[out.lastIndex] = Gap(last.startMs, maxOf(last.endMs, g.endMs)) else out += g
                out
            }

    /** How long the song itself is in a video [durationMs] long with [gaps]. */
    fun songMs(durationMs: Long, gaps: List<Gap>): Long =
        if (durationMs <= 0) durationMs else (durationMs - gaps.sumOf { it.lengthMs }).coerceAtLeast(0)

    /**
     * [ms] on the song's own clock, as the video's: each gap at or before it pushes it
     * on by its length. An [end] right where a gap starts stays before it, so a line
     * sung up to the gap isn't shown through it.
     */
    fun toVideo(ms: Long, gaps: List<Gap>, end: Boolean = false): Long {
        var at = ms
        for (g in gaps) if (at > g.startMs || (!end && at == g.startMs)) at += g.lengthMs else break
        return at
    }

    /** [lyrics] timed to the song, moved onto the clock of the video with [gaps]. */
    fun onto(lyrics: Lyrics, gaps: List<Gap>): Lyrics {
        if (gaps.isEmpty() || lyrics !is Lyrics.Synced) return lyrics
        fun t(ms: Long) = toVideo(ms, gaps)
        fun e(ms: Long) = toVideo(ms, gaps, end = true)
        return lyrics.copy(
            lines = lyrics.lines.map { line ->
                line.copy(
                    startMs = t(line.startMs),
                    endMs = e(line.endMs),
                    words = line.words.map { it.copy(startMs = t(it.startMs), endMs = e(it.endMs)) },
                )
            },
            parts = lyrics.parts.map { it.copy(startMs = t(it.startMs), endMs = e(it.endMs)) },
            recordingMs = if (lyrics.recordingMs > 0) lyrics.recordingMs + gaps.sumOf { it.lengthMs } else 0,
        )
    }
}
