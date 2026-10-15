package com.opentune.data.lossless

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Imports the catalogue, not executable .sflx packages. Adapters are reviewed and shipped in the APK. */
internal object ExtensionRegistry {
    const val OFFICIAL = "https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json"
    const val MIRROR = "https://raw.githubusercontent.com/zarzet/spotiflac-extension/main/registry.json"
    data class Entry(val id: String, val name: String, val version: String, val description: String, val supported: Boolean)
    fun canonicalUrl(value: String): String? = value.trim().toHttpUrlOrNull()?.let { url ->
        listOf(OFFICIAL, MIRROR).firstOrNull { url.toString().equals(it, true) }
    }
    suspend fun fetch(value: String): List<Entry> {
        val url = canonicalUrl(value) ?: error("Use one of the two supported SpotiFLAC registry URLs")
        return parse(LosslessHttp.text(url))
    }
    fun parse(text: String): List<Entry> {
        val root = JSONObject(text)
        require(root.optInt("version") == 1) { "Unsupported registry version" }
        val entries = root.getJSONArray("extensions")
        require(entries.length() in 1..100)
        return (0 until entries.length()).mapNotNull { index ->
            val item = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { Regex("[a-z0-9-]{1,64}").matches(it) } ?: return@mapNotNull null
            val version = item.optString("version").take(32)
            Entry(id, item.optString("display_name", id).take(80), version, item.optString("description").take(500),
                id == "amazon" && version == "2.3.11")
        }.distinctBy { it.id }
    }
}
