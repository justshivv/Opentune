package com.opentune.data

import java.util.Locale

/**
 * Which charts a new listener's suggestions start from.
 *
 * YouTube Music has charts for about seventy countries. Asked for any other
 * one, it answers with the global charts, whose top artists are mostly
 * Indian film singers; started from those, Home filled up with Hindi songs
 * for listeners in Pakistan, Bangladesh, Greece or Venezuela alike. So a
 * country without charts of its own takes a big market of the phone's
 * language instead.
 *
 * India's charts are one list for the whole country, in Hindi and Punjabi
 * whatever the listener speaks. YouTube also keeps a weekly top list per
 * language there, so a Tamil or Telugu speaker, or someone in Tamil Nadu
 * with the phone in English, starts from their own language's artists.
 */
object ChartRegion {
    /** The code YouTube's country menu gives the global charts. */
    const val GLOBAL = "ZZ"

    /** Where a phone in [language] (an ISO 639 code) looks when its own country has no charts. */
    fun fallback(language: String): String = BY_LANGUAGE[language.lowercase(Locale.ROOT)] ?: "US"

    private val BY_LANGUAGE = mapOf(
        "es" to "MX", "pt" to "BR", "fr" to "FR", "de" to "DE", "it" to "IT", "nl" to "NL",
        "ru" to "RU", "uk" to "UA", "pl" to "PL", "cs" to "CZ", "sk" to "CZ", "hu" to "HU",
        "ro" to "RO", "sr" to "RS", "et" to "EE", "is" to "IS", "sv" to "SE", "da" to "DK",
        "nb" to "NO", "no" to "NO", "nn" to "NO", "fi" to "FI", "tr" to "TR", "ar" to "EG",
        "he" to "IL", "iw" to "IL", "ja" to "JP", "ko" to "KR", "zh" to "TW", "th" to "TH",
        "vi" to "VN", "id" to "ID", "in" to "ID", "ms" to "MY", "fil" to "PH", "tl" to "PH",
        "sw" to "KE", "hi" to "IN", "ur" to "IN", "bn" to "IN", "pa" to "IN", "ta" to "IN",
        "te" to "IN", "mr" to "IN", "gu" to "IN", "kn" to "IN", "ml" to "IN", "ne" to "IN",
    )

    /**
     * An Indian language with its own weekly top list on YouTube Music: the
     * list's playlist id, the language codes that speak it and the states
     * where it's the language most people speak.
     */
    enum class IndianLanguage(val playlistId: String, val languages: Set<String>, val states: Set<String>) {
        TAMIL("PL4fGSI1pDJn4WX22qg1Po7qKOwOb4H6Sk", setOf("ta"), setOf("tamil nadu", "puducherry")),
        TELUGU("PL4fGSI1pDJn5ALuqpEj_YZ8mEyw9WN8jd", setOf("te"), setOf("andhra pradesh", "telangana")),
        PUNJABI("PL4fGSI1pDJn5JXkyIohg2RstsbL2SnRew", setOf("pa"), setOf("punjab", "chandigarh")),
        HARYANVI("PL4fGSI1pDJn4tiNLMZVGGt2Kghgw__2u0", setOf("bgc"), setOf("haryana")),
        BHOJPURI("PL4fGSI1pDJn4ivDqrsepD3tvHsp0KTDRM", setOf("bho"), setOf("bihar")),
    }

    /**
     * The language list to start an Indian listener from: the phone's
     * language when it has one, else the one of the [state] they're in.
     * Null for Hindi, which the national charts already are, and for
     * languages YouTube keeps no list for.
     */
    fun indianLanguage(language: String, state: String?): IndianLanguage? {
        val lang = language.lowercase(Locale.ROOT)
        if (lang == "hi") return null
        IndianLanguage.entries.firstOrNull { lang in it.languages }?.let { return it }
        // Another language of India with no list of its own (Malayalam, Kannada, Bengali…):
        // the place says no more about it than the language does.
        if (lang != "en") return null
        val place = state?.trim()?.lowercase(Locale.ROOT) ?: return null
        return IndianLanguage.entries.firstOrNull { place in it.states }
    }
}
