package com.opentune.data

import android.content.Context
import androidx.core.content.edit
import com.opentune.data.radio.ApproxLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.time.LocalDate
import java.util.Locale

/**
 * A festival greeting on Home, for the country the listener is in. The
 * country is taken once from their approximate location, when they allow it,
 * and kept; the festivals and their dates (which move each year for Diwali,
 * Holi, Eid and the like) come from Google's public holiday calendar for that
 * country, fetched at most once a week.
 */
object Festivals {
    data class Festival(val name: String, val date: LocalDate) {
        val key get() = "$date:$name"
    }

    private const val PREFS = "festivals"
    private const val K_COUNTRY = "country"
    private const val K_ASKED = "asked"
    private const val K_DISMISSED = "dismissed"
    private const val K_CACHE = "cache"
    private const val K_CACHE_FOR = "cacheFor"
    private const val K_CACHE_AT = "cacheAt"
    private const val REFRESH_MS = 7 * 24 * 60 * 60 * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether the one-time question about location has been answered, either way. */
    fun asked(context: Context): Boolean = prefs(context).getBoolean(K_ASKED, false)

    fun country(context: Context): String? = prefs(context).getString(K_COUNTRY, null)

    /**
     * Answers the question: with permission, the country is read from where the phone is
     * now and kept. Without it, no banner.
     */
    suspend fun answered(context: Context, allowed: Boolean) {
        prefs(context).edit { putBoolean(K_ASKED, true) }
        if (!allowed) return
        val location = ApproxLocation.get(context) ?: return
        val code = ApproxLocation.place(context, location.latitude, location.longitude)?.countryCode?.uppercase(Locale.ROOT) ?: return
        prefs(context).edit { putString(K_COUNTRY, code) }
    }

    /** Today's festival where the listener is, unless they closed its banner. */
    suspend fun today(context: Context, today: LocalDate = LocalDate.now()): Festival? {
        val country = country(context) ?: return null
        val dismissed = prefs(context).getStringSet(K_DISMISSED, emptySet()).orEmpty()
        return calendar(context, country).firstOrNull { it.date == today && it.key !in dismissed }
    }

    fun dismiss(context: Context, festival: Festival) {
        val p = prefs(context)
        // Only this year's are worth keeping.
        val kept = p.getStringSet(K_DISMISSED, emptySet()).orEmpty().filter { it.startsWith(festival.date.year.toString()) }
        p.edit { putStringSet(K_DISMISSED, (kept + festival.key).toSet()) }
    }

    private suspend fun calendar(context: Context, country: String): List<Festival> = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val cached = p.getString(K_CACHE, null)?.takeIf {
            p.getString(K_CACHE_FOR, null) == country && System.currentTimeMillis() - p.getLong(K_CACHE_AT, 0) < REFRESH_MS
        }
        val ics = cached ?: runCatching {
            val id = calendarId(country)
            val url = "https://calendar.google.com/calendar/ical/en.$id%23holiday%40group.v.calendar.google.com/public/basic.ics"
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()?.also {
            p.edit { putString(K_CACHE, it); putString(K_CACHE_FOR, country); putLong(K_CACHE_AT, System.currentTimeMillis()) }
        } ?: p.getString(K_CACHE, null)?.takeIf { p.getString(K_CACHE_FOR, null) == country }
        ics?.let(::parse).orEmpty()
    }

    /** The festive days in an iCalendar feed: holidays to celebrate, not clock changes, tax days or days of mourning. */
    internal fun parse(ics: String): List<Festival> {
        val out = mutableListOf<Festival>()
        var date: LocalDate? = null
        var name: String? = null
        for (raw in ics.lineSequence()) {
            val line = raw.trimEnd('\r')
            when {
                line == "BEGIN:VEVENT" -> { date = null; name = null }
                line.startsWith("DTSTART") -> date = line.substringAfter(':').takeIf { it.length >= 8 }?.let {
                    runCatching { LocalDate.of(it.substring(0, 4).toInt(), it.substring(4, 6).toInt(), it.substring(6, 8).toInt()) }.getOrNull()
                }
                line.startsWith("SUMMARY") -> name = line.substringAfter(':').replace("\\,", ",").trim()
                line == "END:VEVENT" -> {
                    val d = date
                    val n = name?.let(::festiveName)
                    if (d != null && n != null) out += Festival(n, d)
                }
            }
        }
        return out.distinctBy { it.key }.sortedBy { it.date }
    }

    /** The name to wish someone a happy one with, or null for a day that isn't a celebration. */
    internal fun festiveName(summary: String): String? {
        if (NOT_FESTIVE.containsMatchIn(summary)) return null
        return summary
            .replace(Regex("""^First Day of\s+""", RegexOption.IGNORE_CASE), "")
            // "(tentative)", "(Smarta)", "(Assam)": notes on the date, not part of the name.
            .replace(Regex("""\s*\([^)]*\)"""), "")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private val NOT_FESTIVE = Regex(
        """daylight saving|tax day|substitute|observed|bank holiday|election|memorial|remembrance|veterans|martyr|""" +
            """mourning|death anniversary|punyatithi|good friday|ash wednesday|holy saturday|ashura|muharram|holocaust|""" +
            """equinox|solstice|ramadan start|jamat ul-vida|(?:first|last) day of (?:ramadan|hanukkah|passover)|end of""",
        RegexOption.IGNORE_CASE,
    )

    /** Google's name for a country's holiday calendar; most take their region code, some their own name. */
    internal fun calendarId(country: String): String = NAMES[country.uppercase(Locale.ROOT)] ?: country.lowercase(Locale.ROOT)

    private val NAMES = mapOf(
        "IN" to "indian", "US" to "usa", "GB" to "uk", "DE" to "german", "JP" to "japanese", "AU" to "australian",
        "CA" to "canadian", "CN" to "china", "SG" to "singapore", "MY" to "malaysia", "ID" to "indonesian",
        "PH" to "philippines", "BR" to "brazilian", "FR" to "french", "ES" to "spain", "IT" to "italian",
        "NL" to "dutch", "NZ" to "new_zealand", "IE" to "irish", "MX" to "mexican", "RU" to "russian",
        "TR" to "turkish", "SA" to "saudiarabian", "HK" to "hong_kong", "TW" to "taiwan", "KR" to "south_korea",
        "VN" to "vietnamese",
    )
}
