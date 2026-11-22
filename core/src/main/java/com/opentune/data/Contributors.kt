package com.opentune.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * The repo's contributors, read from GitHub when the About page's sheet
 * opens, so the list keeps itself current and nobody is added by hand.
 * The two developers are left out (they have their own cards on the page),
 * and so are tooling accounts and bots. GitHub counts the default branch,
 * so work still travelling on a branch appears once it lands there.
 */
object Contributors {
    data class Person(val name: String, val avatarUrl: String, val profileUrl: String, val contributions: Int)

    private const val API = "https://api.github.com/repos/justshivv/Opentune/contributors?per_page=100"
    private val EXCLUDED = setOf("justshivv", "aerluc", "codex", "claude")

    /** The last good read this session; a failed read is never kept. */
    @Volatile private var cache: List<Person>? = null

    /** The contributors, most contributions first; null when GitHub couldn't be read. */
    suspend fun people(): List<Person>? {
        cache?.let { return it }
        val fetched = withContext(Dispatchers.IO) { fetch() }
        if (fetched != null) cache = fetched
        return fetched
    }

    private fun fetch(): List<Person>? {
        return try {
            Http.client.newCall(
                Request.Builder().url(API).header("Accept", "application/vnd.github+json").build(),
            ).execute().use { r ->
                if (!r.isSuccessful) return null
                val rows = Json.parseToJsonElement(r.body?.string().orEmpty()).jsonArray
                rows.mapNotNull { el ->
                    val o = el.jsonObject
                    val login = o["login"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val type = o["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (type == "Bot" || login.endsWith("[bot]") || login.lowercase() in EXCLUDED) return@mapNotNull null
                    Person(
                        name = login,
                        avatarUrl = o["avatar_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        profileUrl = o["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$login",
                        contributions = o["contributions"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                }.sortedByDescending { it.contributions }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
