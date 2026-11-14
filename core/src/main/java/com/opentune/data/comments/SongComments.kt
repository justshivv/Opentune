package com.opentune.data.comments

import com.opentune.data.DebugLog as Log
import com.opentune.data.innertube.Innertube
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The comments on a song, from YouTube: those under the song's video, the same
 * people see on youtube.com. YouTube Music doesn't show comments, so these are read
 * through YouTube's own site API. Many song uploads (the "Topic" kind) have their
 * comments turned off; then the comments under the song's music video are shown.
 * With a YouTube sign-in, YouTube offers a comment box, and a comment can be posted:
 * it goes up publicly on YouTube, under that account.
 */
object SongComments {
    data class Comment(
        val id: String,
        val author: String,
        val avatarUrl: String?,
        val text: String,
        /** As YouTube words it: "3 years ago (edited)". */
        val published: String?,
        /** As YouTube words it: "429K"; null for none. */
        val likes: String?,
        val replies: String?,
        /** Written by the video's own channel. */
        val byCreator: Boolean,
        val mine: Boolean,
    )

    /** A page of comments, the token for the next ([next], null at the end), and the comment box's parameters when signed in. */
    data class Page(val comments: List<Comment>, val next: String?, val createParams: String?)

    /** Where a song's comments are: the video read, and the first page's token; [turnedOff] when there are none to read. */
    data class Thread(val videoId: String, val first: String?, val turnedOff: Boolean, val fromVideo: Boolean)

    /**
     * Finds the comments for [videoId]: its own, or, when they're turned off, those of its
     * music video ([counterpartOf] tells which that is). Null when it couldn't be read.
     */
    suspend fun open(videoId: String, counterpartOf: suspend (String) -> String? = ::musicVideoOf): Thread? {
        val own = runCatching { section(Innertube.webNext(videoId), videoId, fromVideo = false) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "Comments for $videoId didn't load", it) }
            .getOrNull() ?: return null
        if (own.first != null) return own
        val other = runCatching { counterpartOf(videoId) }.getOrNull()?.takeIf { it != videoId } ?: return own
        val video = runCatching { section(Innertube.webNext(other), other, fromVideo = true) }.getOrNull() ?: return own
        return if (video.first != null) video else own
    }

    suspend fun page(token: String): Page? = runCatching { parsePage(Innertube.webContinuation(token)) }
        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "A page of comments didn't load", it) }
        .getOrNull()

    /** Posts [text]; the comment as posted when YouTube sends it back, or why it wasn't. */
    suspend fun post(createParams: String, text: String): Result<Comment?> = runCatching {
        val r = Innertube.createComment(createParams, text.trim())
        val status = r.str("actionResult", "status")
        if (status != null && status != "STATUS_SUCCEEDED") error(r.str("actionResult", "feedback", "0", "text")?.takeIf { it.isNotBlank() } ?: "YouTube didn't take the comment")
        comments(r).firstOrNull()
    }

    /** The music video YouTube Music pairs with [videoId] (its Song/Video switch), if any. */
    suspend fun musicVideoOf(videoId: String): String? = counterpart(Innertube.next(videoId), videoId)

    // ---- Reading YouTube's answers ----------------------------------------------------------

    /** The comment section of a watch page for [videoId]. */
    internal fun section(root: JsonObject, videoId: String, fromVideo: Boolean): Thread {
        val sec = findFirst(root) { o -> (o["itemSectionRenderer"] as? JsonObject)?.str("sectionIdentifier") == "comment-item-section" }
            ?.get("itemSectionRenderer") as? JsonObject
        val off = sec != null && texts(sec).any { it.contains("turned off", ignoreCase = true) }
        val token = sec?.let { s -> findFirst(s) { it["continuationCommand"] is JsonObject }?.str("continuationCommand", "token") }
        return Thread(videoId, if (off) null else token, turnedOff = off || sec == null, fromVideo = fromVideo)
    }

    /** A page of comments, from a continuation's answer. */
    internal fun parsePage(root: JsonObject): Page {
        val byKey = comments(root).associateBy { it.id }
        val keyed = entityComments(root)
        val endpoints = root["onResponseReceivedEndpoints"] as? JsonArray ?: JsonArray(emptyList())
        val items = endpoints.flatMap { e ->
            val o = e as? JsonObject ?: return@flatMap emptyList()
            val cmd = (o["reloadContinuationItemsCommand"] ?: o["appendContinuationItemsAction"]) as? JsonObject ?: return@flatMap emptyList()
            // The header (sort menu, comment box) is its own slot; only the body holds comments.
            if (cmd.str("slot") == "RELOAD_CONTINUATION_SLOT_HEADER") return@flatMap emptyList()
            (cmd["continuationItems"] as? JsonArray).orEmpty()
        }
        // In the order YouTube lists the threads; the comments themselves are in the entity list.
        val ordered = items.mapNotNull { item ->
            val key = (item as? JsonObject)?.str("commentThreadRenderer", "commentViewModel", "commentViewModel", "commentKey")
            key?.let { keyed[it] }
        }.ifEmpty { byKey.values.toList() }
        val next = items.lastOrNull()?.let { last ->
            val c = (last as? JsonObject)?.get("continuationItemRenderer") as? JsonObject ?: return@let null
            findFirst(c) { it["continuationCommand"] is JsonObject }?.str("continuationCommand", "token")
        }
        val create = findFirst(root) { it["createCommentParams"] is JsonPrimitive }?.str("createCommentParams")
        return Page(ordered, next, create)
    }

    /** The video YouTube Music pairs with [videoId] in its watch queue, from a WEB_REMIX `next` answer. */
    internal fun counterpart(root: JsonObject, videoId: String): String? {
        var found: String? = null
        findFirst(root) { o ->
            val wrapper = o["playlistPanelVideoWrapperRenderer"] as? JsonObject ?: return@findFirst false
            val primary = wrapper.str("primaryRenderer", "playlistPanelVideoRenderer", "videoId")
            val other = (wrapper["counterpart"] as? JsonArray)?.firstNotNullOfOrNull {
                (it as? JsonObject)?.str("counterpartRenderer", "playlistPanelVideoRenderer", "videoId")
            }
            if (primary == videoId && other != null) { found = other; true } else false
        }
        return found
    }

    private fun entityComments(root: JsonObject): Map<String, Comment> {
        val mutations = root.arr("frameworkUpdates", "entityBatchUpdate", "mutations")
        return mutations.mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val key = o.str("entityKey") ?: return@mapNotNull null
            val payload = (o["payload"] as? JsonObject)?.get("commentEntityPayload") as? JsonObject ?: return@mapNotNull null
            commentOf(payload)?.let { key to it }
        }.toMap()
    }

    private fun comments(root: JsonObject): List<Comment> = entityComments(root).values.toList()

    private fun commentOf(p: JsonObject): Comment? {
        val text = p.str("properties", "content", "content") ?: return null
        return Comment(
            id = p.str("properties", "commentId") ?: p.str("key") ?: return null,
            author = p.str("author", "displayName") ?: "",
            avatarUrl = p.str("author", "avatarThumbnailUrl"),
            text = text,
            published = p.str("properties", "publishedTime"),
            likes = p.str("toolbar", "likeCountNotliked")?.trim()?.takeIf { it.isNotEmpty() && it != "0" },
            replies = p.str("toolbar", "replyCount")?.trim()?.takeIf { it.isNotEmpty() && it != "0" },
            byCreator = p.str("author", "isCreator") == "true",
            mine = p.str("author", "isCurrentUser") == "true",
        )
    }

    // ---- JSON helpers -------------------------------------------------------------------------

    private fun JsonObject.at(vararg path: String): JsonElement? {
        var cur: JsonElement? = this
        for (k in path) {
            cur = when (cur) {
                is JsonObject -> cur[k]
                is JsonArray -> k.toIntOrNull()?.let { cur.getOrNull(it) }
                else -> null
            }
        }
        return cur
    }

    private fun JsonObject.str(vararg path: String): String? = (at(*path) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.arr(vararg path: String): JsonArray = at(*path) as? JsonArray ?: JsonArray(emptyList())
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    /** The first object, depth first, that [match] takes. */
    private fun findFirst(e: JsonElement, match: (JsonObject) -> Boolean): JsonObject? = when (e) {
        is JsonObject -> if (match(e)) e else e.values.firstNotNullOfOrNull { findFirst(it, match) }
        is JsonArray -> e.firstNotNullOfOrNull { findFirst(it, match) }
        else -> null
    }

    /** Every "text" string under [e]: what a message in the section says. */
    private fun texts(e: JsonElement): List<String> = when (e) {
        is JsonObject -> e.entries.flatMap { (k, v) -> if (k == "text" && v is JsonPrimitive) listOfNotNull(v.contentOrNull) else texts(v) }
        is JsonArray -> e.flatMap { texts(it) }
        else -> emptyList()
    }

    private const val TAG = "SongComments"
}
