package com.opentune.data.addons

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** URL paths may contain private access tokens. Store locally and never include them in diagnostics. */
data class AudioAddon(val key: String, val base: String, val manifest: AddonManifest, val enabled: Boolean = true, val admin: Boolean = false,
    /** An admin source its owner turned off in the published list: nothing is sent to it, whatever the listener's switch says. */
    val ownerOff: Boolean = false)
object AddonStore {
    private var preferences: android.content.SharedPreferences? = null
    private val state = MutableStateFlow<List<AudioAddon>>(emptyList())
    val addons = state.asStateFlow()
    val revision = MutableStateFlow(0L)
    var outputAllowed: () -> Boolean = { false }
    var onChanged: () -> Unit = {}
    fun init(context: Context) {
        FirstPartyAddonAuth.init(context)
        preferences = context.getSharedPreferences("audio_addons", Context.MODE_PRIVATE)
        state.value = runCatching {
            val a = JSONArray(preferences?.getString("items", "[]"))
            (0 until a.length()).map { i -> val e = a.getJSONObject(i)
                AudioAddon(e.getString("key"), AddonProtocol.normalize(e.getString("base")), AddonProtocol.manifest(e.getJSONObject("manifest").toString()), e.optBoolean("enabled", true), e.optBoolean("admin", false), e.optBoolean("ownerOff", false)) }
        }.getOrDefault(emptyList())
        // Retire obsolete verification credentials without touching saved music.
        context.getSharedPreferences("spotiflac", Context.MODE_PRIVATE).edit().clear().apply()
    }
    @Synchronized fun add(base: String, manifest: AddonManifest) {
        val normalized = AddonProtocol.normalize(base)
        require(state.value.count { !it.admin } < 20 || state.value.any { it.base == normalized }) { "You can add up to 20 audio add-ons." }
        val old = state.value.firstOrNull { it.base == normalized }
        save(if (old == null) state.value + AudioAddon(UUID.randomUUID().toString(), normalized, manifest)
            else state.value.map { if (it.key == old.key) it.copy(manifest = if (it.admin) manifest.copy(name = it.manifest.name) else manifest) else it })
    }
    @Synchronized fun remove(key: String) = save(state.value.filterNot { it.key == key })
    @Synchronized fun enable(key: String, enabled: Boolean) = save(state.value.map { if (it.key == key) it.copy(enabled = enabled) else it })
    @Synchronized fun move(key: String, offset: Int) {
        val list = state.value.toMutableList(); val index = list.indexOfFirst { it.key == key }
        if (index < 0) return
        val target = (index + offset).coerceIn(0, list.lastIndex)
        list.add(target, list.removeAt(index)); save(list)
    }
    @Synchronized fun refreshed(key: String, manifest: AddonManifest, expectedBase: String? = null) {
        val old = state.value.firstOrNull { it.key == key } ?: return
        if (expectedBase != null && old.base != expectedBase) return
        val visible = if (old.admin) manifest.copy(name = old.manifest.name) else manifest
        if (old.manifest == visible) return
        // A new name, version or setting list changes nothing about what may be played: kept
        // without a new revision. Each one used to throw away every verified file found so far
        // and restart the song's upgrade, once per add-on after every launch.
        val matters = old.manifest.outputRequired != visible.outputRequired || old.manifest.downloads != visible.downloads
        save(state.value.map { if (it.key == key) it.copy(manifest = visible) else it }, bump = matters)
    }
    @Synchronized fun syncCommunity(incoming: List<AudioAddon>) {
        val incomingByKey = incoming.associateBy { it.key }
        val retained = state.value.mapNotNull { old ->
            if (!old.admin) old else incomingByKey[old.key]?.copy(enabled = old.enabled)
        }
        val currentKeys = retained.map { it.key }.toSet()
        val next = retained + incoming.filter { it.key !in currentKeys }
        if (next != state.value) save(next)
    }
    fun usable(addon: AudioAddon): Boolean = addon.enabled && !addon.ownerOff && (!addon.manifest.outputRequired || outputAllowed())
    private fun save(value: List<AudioAddon>, bump: Boolean = true) {
        val array = JSONArray()
        value.forEach { e ->
            val settings = JSONArray()
            e.manifest.defaults.forEach { (key, default) -> settings.put(JSONObject().put("key", key).put("default", default).apply {
                if (key == "quality") put("options", JSONArray(e.manifest.qualities.map { JSONObject().put("value", it) }))
            }) }
            if ("quality" !in e.manifest.defaults && e.manifest.qualities.isNotEmpty()) settings.put(JSONObject().put("key", "quality").put("options", JSONArray(e.manifest.qualities.map { JSONObject().put("value", it) })))
            val manifest = JSONObject().put("id", e.manifest.id).put("name", e.manifest.name).put("version", e.manifest.version)
                .put("allowDownloads", e.manifest.downloads).put("checkValidLossless", e.manifest.outputRequired).put("settings", settings)
            array.put(JSONObject().put("key", e.key).put("base", e.base).put("enabled", e.enabled).put("admin", e.admin).put("ownerOff", e.ownerOff).put("manifest", manifest))
        }
        check(preferences?.edit()?.putString("items", array.toString())?.commit() == true) { "Could not save add-ons." }
        state.value = value
        if (bump) { revision.value++; onChanged() }
    }
}
