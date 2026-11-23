package com.opentune.data.innertube

import com.opentune.data.lyrics.LyricsGaps
import com.opentune.data.model.Song
import com.opentune.data.model.durationMillis
import com.opentune.data.sponsorblock.SponsorBlock
import java.text.Normalizer
import kotlin.math.abs

/**
 * A music video for a song YouTube Music has only as an art track. Its picture
 * plays over the song's own audio, so the song keeps its sound (lossless where
 * it has it), with its video's stretches that aren't the song ([gaps], on the
 * video's clock) left out of the picture, so the two run in step.
 */
data class VideoLink(val videoId: String, val videoMs: Long, val gaps: List<LyricsGaps.Gap>, val songMs: Long) {
    /**
     * The stretches of the video the picture plays, start to end on the video's
     * clock, in order. Credits at the end stay: the picture runs on past the song
     * and is cut where it ends, so the song's last note is never cut instead.
     */
    fun pieces(): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var from = 0L
        for (g in gaps) {
            if (g.endMs >= videoMs - END_SLACK_MS) break
            if (g.startMs > from) out += from to g.startMs
            from = maxOf(from, g.endMs)
        }
        if (videoMs > from) out += from to videoMs
        return out
    }

    /** How long the picture runs: the video without the stretches [pieces] leaves out. */
    fun pictureMs(): Long = pieces().sumOf { it.second - it.first }

    private companion object {
        /** A gap ending this close to the video's end is its end. */
        const val END_SLACK_MS = 1_000L
    }
}

object VideoLinks {
    /**
     * How far the video without its gaps may run from the song's own length. Both
     * lengths can come rounded to the second, and a video's last frame often
     * outlasts the song's last note by a moment.
     */
    const val TOLERANCE_MS = 3_000L

    /** Words in a video's title that say it's something other than the song's own music video. */
    private val NOT_THE_VIDEO = setOf(
        // "Official Audio" is a still of the cover, like the art track itself.
        "audio", "lyric", "lyrics", "cover", "live", "remix", "karaoke", "instrumental", "slowed", "sped", "reverb",
        "8d", "nightcore", "acoustic", "visualizer", "visualiser", "reaction", "tutorial", "edit",
    )

    /** How many search results are looked at for the song's video. */
    const val CANDIDATES = 3

    /** Whether [video] is [song]'s music video, by its title and artist. */
    fun sameSong(song: Song, video: Song): Boolean {
        if (video.videoId == song.videoId) return false
        val title = words(song.title)
        if (title.isBlank()) return false
        // A lyric video, a cover, a live take: not the song's own music video, though as long.
        val other = words(video.title).split(' ').toSet()
        if (NOT_THE_VIDEO.any { it in other && it !in title.split(' ') }) return false
        val videoTitle = words(stripped(video.title))
        if (videoTitle != title && !videoTitle.endsWith(" $title") && !videoTitle.startsWith("$title ")) return false
        val artist = words(song.artist.split(',', '&').first())
        return artist.isBlank() || words(video.artist).contains(artist) || words(video.title).contains(artist)
    }

    /**
     * [videoId]'s link to a song [songMs] long, or null when the video without its
     * stretches that aren't the song is another length: another cut, a live take, an
     * extended version. [videoMs] is the video's length, and [segments] what
     * SponsorBlock marks in it; a segment's own record of the length wins.
     */
    fun check(songMs: Long, videoId: String, videoMs: Long, segments: List<SponsorBlock.Segment>): VideoLink? {
        if (songMs <= 0) return null
        val exact = segments.firstOrNull { it.videoDurationMs > 0 }?.videoDurationMs ?: videoMs
        if (exact <= 0) return null
        val gaps = LyricsGaps.of(segments, exact)
        if (abs(LyricsGaps.songMs(exact, gaps) - songMs) > TOLERANCE_MS) return null
        return VideoLink(videoId, exact, gaps, songMs)
    }

    /** [video]'s length in ms from its row, 0 when it doesn't say. */
    fun lengthOf(video: Song): Long = video.durationMillis()

    /** The title without what a video adds to it: "(Official Music Video)", "[4K]" and the like. */
    private fun stripped(title: String): String = title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), " ")

    /** Lower case, accents and punctuation gone, single spaces. */
    private fun words(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
}
