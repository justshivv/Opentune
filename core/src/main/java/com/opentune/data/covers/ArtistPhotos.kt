package com.opentune.data.covers

import android.content.Context
import android.content.SharedPreferences

/**
 * The photo an artist's page showed, by the artist's browse id, kept for a
 * day. Coming back to the page, or opening it again tomorrow morning, shows
 * the same picture, which the image cache still has, instead of choosing
 * again and loading whichever picture won this time.
 *
 * A better photo found meanwhile (Apple's, say) takes the place only once
 * it's downloaded, through [upgrade]; the day still counts from the first.
 */
object ArtistPhotos {
    private const val KEEP_MS = 24 * 60 * 60 * 1000L
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("artist_photos", Context.MODE_PRIVATE)
        prefs = p
        val now = System.currentTimeMillis()
        val stale = p.all.filter { (_, v) -> (v as? String)?.let(::parse)?.takeIf { now - it.first < KEEP_MS } == null }.keys
        if (stale.isNotEmpty()) p.edit().apply { stale.forEach(::remove) }.apply()
    }

    /** The photo shown for [browseId] within the last day, or null. */
    fun pinned(browseId: String): String? {
        val (at, url) = prefs?.getString(browseId, null)?.let(::parse) ?: return null
        return url.takeIf { System.currentTimeMillis() - at < KEEP_MS }
    }

    /** Keeps [url] for [browseId] unless a photo from the last day is already kept. */
    fun pin(browseId: String, url: String) {
        if (pinned(browseId) == null) put(browseId, System.currentTimeMillis(), url)
    }

    /** Swaps in [url], downloaded and ready, for the next visit, keeping the time of the first. */
    fun upgrade(browseId: String, url: String) {
        val at = prefs?.getString(browseId, null)?.let(::parse)?.first ?: System.currentTimeMillis()
        put(browseId, at, url)
    }

    private fun put(browseId: String, at: Long, url: String) {
        prefs?.edit()?.putString(browseId, "$at|$url")?.apply()
    }

    private fun parse(value: String): Pair<Long, String>? {
        val bar = value.indexOf('|')
        if (bar <= 0) return null
        val at = value.substring(0, bar).toLongOrNull() ?: return null
        val url = value.substring(bar + 1).takeIf { it.isNotEmpty() } ?: return null
        return at to url
    }
}
