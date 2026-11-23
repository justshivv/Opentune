package com.opentune.data.lyrics

/**
 * What a glance at the lyrics shows at one moment: the line being sung and the one after
 * it, for the places outside the player that show a line or two (widgets, floating lyrics).
 *
 * [current] is null before the first line and through an instrumental break, so a line
 * isn't left lit through a long stretch with no singing. [untilMs] is the song time at
 * which this changes, or [Long.MAX_VALUE] when nothing comes after.
 */
data class LyricMoment(val current: LyricLine?, val next: LyricLine?, val untilMs: Long)

/**
 * The [LyricMoment] at [positionMs] (song time, the lyrics offset already added). A gap of
 * [breakMs] or more between one line's last word and the next line counts as a break, as
 * the player's breathing dots do ([instrumentalGaps]).
 */
fun List<LyricLine>.momentAt(positionMs: Long, breakMs: Long = 5_000L): LyricMoment {
    if (isEmpty()) return LyricMoment(null, null, Long.MAX_VALUE)
    val i = activeIndex(positionMs)
    if (i < 0) return LyricMoment(null, first(), first().startMs)
    val line = this[i]
    val next = getOrNull(i + 1)
    val sungUntil = line.sungUntilMs()
    val inBreak = next != null && next.startMs - sungUntil >= breakMs
    return when {
        inBreak && positionMs >= sungUntil -> LyricMoment(null, next, next!!.startMs)
        inBreak -> LyricMoment(line, next, sungUntil)
        else -> LyricMoment(line, next, next?.startMs ?: Long.MAX_VALUE)
    }
}
