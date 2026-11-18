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
        /** As YouTube words it: "429K"; null for none. Counted without the reader's own like. */
        val likes: String?,
        val replies: String?,
        /** Written by the video's own channel. */
        val byCreator: Boolean,
        val mine: Boolean,
        /** The count with the reader's own like in it, as YouTube words it. */
        val likesWithMine: String? = null,
        /** The reader (signed in) has liked it. */
        val liked: Boolean = false,
        /** What YouTube's like and unlike buttons send; null signed out, where they only ask to sign in. */
        val likeAction: String? = null,
        val unlikeAction: String? = null,
        /** The reply box's parameters, signed in. */
        val replyParams: String? = null,
        /** What the menu's Delete sends; only on the reader's own comments. */
        val deleteAction: String? = null,
        /** Where its replies start; null when it has none. */
        val repliesToken: String? = null,
        /** A reply, rather than a comment of its own. */
        val isReply: Boolean = false,
    ) {
        /** The count to show, for whether the reader likes it now. */
        fun likesShown(likedNow: Boolean): String? = if (likedNow) (likesWithMine ?: likes) else likes
    }

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

    /** The replies to a comment, from its [Comment.repliesToken] (and then each page's [Page.next]). */
    suspend fun replies(token: String): Page? = page(token)

    /** [block]'s result, or what went wrong; a cancelled call (the sheet closed) is no failure, so it isn't caught. */
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Posts [text]; the comment as posted when YouTube sends it back, or why it wasn't. */
    suspend fun post(createParams: String, text: String): Result<Comment?> = attempt {
        val r = Innertube.createComment(createParams, text.trim())
        checkResult(r, "YouTube didn't take the comment")
        posted(r)
    }

    /** Posts [text] as a reply; the reply as posted when YouTube sends it back, or why it wasn't. */
    suspend fun reply(replyParams: String, text: String): Result<Comment?> = attempt {
        val r = Innertube.createCommentReply(replyParams, text.trim())
        checkResult(r, "YouTube didn't take the reply")
        posted(r)?.copy(isReply = true)
    }

    /** Likes, unlikes or deletes a comment: sends what that button of it carries ([action]). */
    suspend fun act(action: String, failed: String = "YouTube didn't take that"): Result<Unit> = attempt {
        checkResult(Innertube.performCommentAction(action), failed)
    }

    /** The comment an answer to a post sends back, with its buttons (Delete among them). */
    internal fun posted(r: JsonObject): Comment? {
        val keyed = entityComments(r)
        val entities = entities(r)
        val vm = findFirst(r) { it["commentKey"] is JsonPrimitive && it["toolbarStateKey"] is JsonPrimitive }
        val base = vm?.str("commentKey")?.let { keyed[it] } ?: return keyed.values.firstOrNull()
        return withButtons(base, vm, entities, null)
    }

    /** Fails with what YouTube said when an answer says it didn't do it. */
    private fun checkResult(r: JsonObject, otherwise: String) {
        val results = listOfNotNull(r["actionResult"] as? JsonObject) + r.arr("actionResults").mapNotNull { it as? JsonObject }
        val bad = results.firstOrNull { it.str("status").let { s -> s != null && s != "STATUS_SUCCEEDED" } } ?: return
        error(bad.str("feedback", "0", "text")?.takeIf { it.isNotBlank() } ?: otherwise)
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

    /** A page of comments (or of a comment's replies), from a continuation's answer. */
    internal fun parsePage(root: JsonObject): Page {
        val byKey = comments(root).associateBy { it.id }
        val keyed = entityComments(root)
        val entities = entities(root)
        val endpoints = root["onResponseReceivedEndpoints"] as? JsonArray ?: JsonArray(emptyList())
        val items = endpoints.flatMap { e ->
            val o = e as? JsonObject ?: return@flatMap emptyList()
            val cmd = (o["reloadContinuationItemsCommand"] ?: o["appendContinuationItemsAction"]) as? JsonObject ?: return@flatMap emptyList()
            // The header (sort menu, comment box) is its own slot; only the body holds comments.
            if (cmd.str("slot") == "RELOAD_CONTINUATION_SLOT_HEADER") return@flatMap emptyList()
            (cmd["continuationItems"] as? JsonArray).orEmpty()
        }
        // In the order YouTube lists the threads (or replies); the comments themselves are in the
        // entity list, and their buttons in the entities the item's view model names.
        val ordered = items.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val vm = findFirst(o) { it["commentKey"] is JsonPrimitive && it["toolbarStateKey"] is JsonPrimitive } ?: return@mapNotNull null
            val base = vm.str("commentKey")?.let { keyed[it] } ?: return@mapNotNull null
            withButtons(base, vm, entities, (o["commentThreadRenderer"] as? JsonObject)?.get("replies"))
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

    /** Every entity of the answer, by its key: the payload's one inner object. */
    private fun entities(root: JsonObject): Map<String, JsonObject> =
        root.arr("frameworkUpdates", "entityBatchUpdate", "mutations").mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val key = o.str("entityKey") ?: return@mapNotNull null
            val inner = (o["payload"] as? JsonObject)?.values?.firstOrNull() as? JsonObject ?: return@mapNotNull null
            key to inner
        }.toMap()

    /** [c] with what its buttons do, from the toolbar entities its view model [vm] names. */
    private fun withButtons(c: Comment, vm: JsonObject, entities: Map<String, JsonObject>, replies: JsonElement?): Comment {
        val surface = vm.str("toolbarSurfaceKey")?.let { entities[it] }
        val state = vm.str("toolbarStateKey")?.let { entities[it] }
        fun endpoint(e: JsonElement?, name: String, field: String): String? =
            e?.let { findFirst(it) { o -> o[name] is JsonObject }?.str(name, field) }
        fun action(command: String) = endpoint(surface?.get(command), "performCommentActionEndpoint", "action")
        // The menu's Delete (on the reader's own comments), whether it asks to confirm first or not.
        val deleteItem = surface?.get("menuCommand")?.let { menu -> findFirst(menu) { o -> (o["icon"] as? JsonObject)?.str("iconType") == "DELETE" } }
        return c.copy(
            liked = state?.str("likeState") == "TOOLBAR_LIKE_STATE_LIKED",
            likeAction = action("likeCommand"),
            unlikeAction = action("unlikeCommand"),
            replyParams = endpoint(surface?.get("replyCommand"), "createCommentReplyEndpoint", "createReplyParams"),
            deleteAction = endpoint(deleteItem, "performCommentActionEndpoint", "action"),
            repliesToken = endpoint(replies, "continuationCommand", "token")?.takeIf { c.replies != null },
        )
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
            likesWithMine = p.str("toolbar", "likeCountLiked")?.trim()?.takeIf { it.isNotEmpty() && it != "0" },
            isReply = (p.str("properties", "replyLevel")?.toIntOrNull() ?: 0) > 0,
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
