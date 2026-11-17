package com.opentune.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import com.opentune.ui.theme.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.opentune.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.opentune.data.Pills
import com.opentune.data.comments.SongComments
import com.opentune.data.model.Song
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingCard
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

/** The replies of one comment, as far as they're loaded. */
private class Replies(token: String?) {
    val list = mutableStateListOf<SongComments.Comment>()
    var next by mutableStateOf(token)
    var loading by mutableStateOf(false)
    var open by mutableStateOf(true)
}

/** Who a reply being written answers: [to], in the thread of the comment [threadId]. */
private data class ReplyTarget(val to: SongComments.Comment, val threadId: String)

/**
 * The comments on [song], from YouTube: the song's own, or its music video's when the
 * song's are turned off. A comment's replies open under it. Signed in to YouTube Music,
 * comments can be liked, answered and added, and the reader's own deleted; all of it
 * happens publicly on YouTube under that account, as the box says.
 */
@Composable
internal fun CommentsSheet(
    song: Song,
    onDismiss: () -> Unit,
    /** Where the comments come from: YouTube, or made-up ones in a test. */
    open: suspend (String) -> SongComments.Thread? = { SongComments.open(it) },
    pageOf: suspend (String) -> SongComments.Page? = { SongComments.page(it) },
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val signedIn by com.opentune.data.account.AccountStore.signedIn.collectAsState()
    var thread by remember(song.videoId) { mutableStateOf<SongComments.Thread?>(null) }
    var loading by remember(song.videoId) { mutableStateOf(true) }
    var failed by remember(song.videoId) { mutableStateOf(false) }
    val comments = remember(song.videoId) { mutableStateListOf<SongComments.Comment>() }
    var next by remember(song.videoId) { mutableStateOf<String?>(null) }
    var createParams by remember(song.videoId) { mutableStateOf<String?>(null) }
    var more by remember(song.videoId) { mutableStateOf(false) }
    // What the reader did here, over what YouTube said when the page came.
    val likedNow = remember(song.videoId) { mutableStateMapOf<String, Boolean>() }
    val replies = remember(song.videoId) { mutableStateMapOf<String, Replies>() }
    var replyingTo by remember(song.videoId) { mutableStateOf<ReplyTarget?>(null) }
    var deleting by remember(song.videoId) { mutableStateOf<SongComments.Comment?>(null) }

    LaunchedEffect(song.videoId) {
        val t = open(song.videoId)
        thread = t
        if (t == null) { failed = true; loading = false; return@LaunchedEffect }
        val first = t.first
        if (first != null) {
            val page = pageOf(first)
            if (page == null) failed = true
            else { comments += page.comments; next = page.next; createParams = page.createParams }
        }
        loading = false
    }

    val list = rememberLazyListState()
    // Near the end of what's loaded: the next page.
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 4 } == true } }
    LaunchedEffect(nearEnd, next) {
        val token = next ?: return@LaunchedEffect
        if (!nearEnd || more) return@LaunchedEffect
        more = true
        pageOf(token)?.let { page ->
            val seen = comments.map { it.id }.toHashSet()
            comments += page.comments.filter { it.id !in seen }
            next = page.next
            if (createParams == null) createParams = page.createParams
        }
        more = false
    }

    /** The next page of [parent]'s replies, opening them. */
    fun loadReplies(parent: SongComments.Comment) {
        val r = replies.getOrPut(parent.id) { Replies(parent.repliesToken) }
        r.open = true
        val token = r.next ?: return
        if (r.loading) return
        r.loading = true
        scope.launch {
            val page = pageOf(token)
            if (page == null) Pills.error("Replies didn't load", context)
            else {
                val seen = r.list.map { it.id }.toHashSet()
                r.list += page.comments.filter { it.id !in seen }
                r.next = page.next
            }
            r.loading = false
        }
    }

    fun toggleLike(c: SongComments.Comment) {
        val was = likedNow[c.id] ?: c.liked
        val action = (if (was) c.unlikeAction else c.likeAction) ?: return
        likedNow[c.id] = !was
        scope.launch {
            SongComments.act(action, "YouTube didn't take the like").onFailure {
                likedNow[c.id] = was
                Pills.error(it.message ?: "YouTube didn't take the like", context)
            }
        }
    }

    fun row(c: SongComments.Comment, threadId: String?): @Composable () -> Unit = {
        CommentRow(
            c,
            liked = likedNow[c.id] ?: c.liked,
            onLike = if (signedIn && (c.likeAction != null || c.unlikeAction != null)) ({ toggleLike(c) }) else null,
            onReply = if (signedIn && c.replyParams != null) ({ replyingTo = ReplyTarget(c, threadId ?: c.id) }) else null,
            repliesOpen = replies[c.id]?.open == true,
            onReplies = if (threadId == null && c.repliesToken != null) ({
                val r = replies[c.id]
                if (r?.open == true) r.open = false
                else if (r != null && r.list.isNotEmpty()) r.open = true
                else loadReplies(c)
            }) else null,
            onDelete = if (signedIn && c.deleteAction != null) ({ deleting = c }) else null,
        )
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete comment?") },
            text = { Text("It will be deleted from YouTube for good, along with any replies.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    val action = c.deleteAction ?: return@TextButton
                    scope.launch {
                        SongComments.act(action, "YouTube didn't delete the comment")
                            .onSuccess {
                                comments.removeAll { it.id == c.id }
                                replies.remove(c.id)
                                replies.values.forEach { r -> r.list.removeAll { it.id == c.id } }
                                if (replyingTo?.to?.id == c.id || replyingTo?.threadId == c.id) replyingTo = null
                                Pills.done("Comment deleted", context)
                            }
                            .onFailure { Pills.error(it.message ?: "YouTube didn't delete the comment", context) }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    FloatingCard(
        onDismiss = onDismiss,
        title = "Comments",
        icon = ImageVector.vectorResource(AppIcons.ChatBubbleOutline),
        subtitle = when {
            thread?.fromVideo == true -> "From the music video on YouTube"
            else -> "From YouTube"
        },
        expandable = true,
        // Start halfway; the handle grows the comments to the full available screen.
        startExpanded = false,
        fitContent = false,
        // Pulled up (or scrolled), it takes over the page; pulled down it goes back to half, then closes.
        fullFraction = 1f,
    ) {
        when {
            loading -> Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) { CircularProgressIndicator() }
            failed -> Note("Comments didn't load. Check the connection and try again.", Modifier.weight(1f))
            thread?.first == null -> Note("Comments are turned off for this song on YouTube.", Modifier.weight(1f))
            comments.isEmpty() -> Note("No comments yet.", Modifier.weight(1f))
            else -> LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                comments.forEach { c ->
                    item(key = c.id) { row(c, null)() }
                    val r = replies[c.id]
                    if (r != null && r.open) {
                        items(r.list, key = { "${c.id}/${it.id}" }) { reply ->
                            Box(Modifier.padding(start = 48.dp)) { row(reply, c.id)() }
                        }
                        when {
                            r.loading -> item(key = "${c.id}/loading") {
                                Box(Modifier.fillMaxWidth().padding(start = 48.dp), Alignment.CenterStart) {
                                    CircularProgressIndicator(Modifier.padding(start = 12.dp).size(18.dp), strokeWidth = 2.dp)
                                }
                            }
                            r.next != null -> item(key = "${c.id}/more") {
                                TextButton(onClick = { loadReplies(c) }, modifier = Modifier.padding(start = 40.dp)) { Text("Show more replies") }
                            }
                        }
                    }
                }
                if (next != null) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                }
            }
        }
        // Signed in, the box is always there; YouTube says with its first page whether it takes
        // comments on this video, and if it didn't, sending says so rather than the box being missing.
        when {
            signedIn && thread?.first != null -> {
                val target = replyingTo
                Composer(
                    replyingTo = target?.to,
                    onCancelReply = { replyingTo = null },
                    onPost = { text ->
                        if (target != null) {
                            val params = target.to.replyParams
                            if (params == null) "YouTube isn't taking replies to this comment from here"
                            else {
                                val r = SongComments.reply(params, text)
                                r.onSuccess { posted ->
                                    replyingTo = null
                                    val parent = comments.firstOrNull { it.id == target.threadId }
                                    val thr = replies.getOrPut(target.threadId) { Replies(parent?.repliesToken) }
                                    thr.open = true
                                    if (posted != null) thr.list.add(0, posted)
                                }
                                r.exceptionOrNull()?.message ?: if (r.isFailure) "YouTube didn't take the reply" else null
                            }
                        } else {
                            val params = createParams
                            if (params == null) "YouTube isn't taking comments on this video from here"
                            else {
                                val r = SongComments.post(params, text)
                                r.onSuccess { posted -> if (posted != null) comments.add(0, posted) }
                                r.exceptionOrNull()?.message ?: if (r.isFailure) "YouTube didn't take the comment" else null
                            }
                        }
                    },
                    scope = scope,
                )
            }
            !signedIn && thread?.first != null -> Note("Sign in with YouTube Music to like, reply to and add comments.")
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 20.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CommentRow(
    c: SongComments.Comment,
    liked: Boolean,
    /** Null where it can't be liked from here (signed out); the count still shows. */
    onLike: (() -> Unit)?,
    onReply: (() -> Unit)?,
    repliesOpen: Boolean,
    /** Opens or closes its replies; null for a reply, or a comment with none. */
    onReplies: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.labelMedium
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Artwork(c.avatarUrl, Modifier.size(if (c.isReply) 28.dp else 36.dp), CircleShape, placeholder = ImageVector.vectorResource(AppIcons.Person))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.author, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = if (c.byCreator) scheme.primary else scheme.onSurface, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false))
                c.published?.let {
                    Text("  ·  $it", style = small, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Text(c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            val likes = c.likesShown(liked)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val likeTint = if (liked) scheme.primary else scheme.onSurfaceVariant
                if (onLike != null) {
                    Row(Modifier.clip(Tap).clickable(onClick = onLike).padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.vectorResource(AppIcons.ThumbUp), if (liked) "Unlike" else "Like", Modifier.size(16.dp), tint = likeTint)
                        if (likes != null) Text(" $likes", style = small, color = likeTint)
                    }
                } else if (likes != null) {
                    Row(Modifier.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.vectorResource(AppIcons.ThumbUp), null, Modifier.size(14.dp), tint = likeTint)
                        Text(" $likes", style = small, color = likeTint)
                    }
                }
                if (onReply != null) {
                    Text("Reply", style = small, color = scheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(Tap).clickable(onClick = onReply).padding(vertical = 6.dp, horizontal = 8.dp))
                }
                Spacer(Modifier.weight(1f))
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(ImageVector.vectorResource(AppIcons.DeleteOutline), "Delete comment", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                    }
                }
            }
            if (c.replies != null) {
                val label = "${c.replies} ${if (c.replies == "1") "reply" else "replies"}"
                if (onReplies != null) {
                    Row(Modifier.clip(Tap).clickable(onClick = onReplies).padding(vertical = 4.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.vectorResource(if (repliesOpen) AppIcons.ExpandLess else AppIcons.ExpandMore), null, Modifier.size(18.dp), tint = scheme.primary)
                        Text(" ${if (repliesOpen) "Hide replies" else label}", style = small, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text(label, style = small, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

/** The shape a tapped like, Reply or replies label lights up in. */
private val Tap = RoundedCornerShape(8.dp)

/**
 * A box for a new comment, or a reply to [replyingTo]; [onPost] sends it and answers with
 * why it failed, or null.
 */
@Composable
private fun Composer(
    replyingTo: SongComments.Comment?,
    onCancelReply: () -> Unit,
    onPost: suspend (String) -> String?,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    // A reply to a reply goes into the same thread, so it names who it answers, as on YouTube.
    val start = replyingTo?.takeIf { it.isReply }?.author?.let { "$it " } ?: ""
    var text by remember(replyingTo?.id) { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(replyingTo?.id) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(replyingTo?.id) { if (replyingTo != null) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        if (replyingTo != null) {
            Row(Modifier.padding(start = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Replying to ${replyingTo.author}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
                IconButton(onClick = onCancelReply, modifier = Modifier.size(28.dp)) {
                    Icon(ImageVector.vectorResource(AppIcons.Close), "Cancel reply", Modifier.size(16.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it.copy(text = it.text.take(MAX_COMMENT)); error = null },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(if (replyingTo != null) "Add a reply" else "Add a comment") },
                maxLines = 4,
                isError = error != null,
                shape = RoundedCornerShape(24.dp),
            )
            IconButton(enabled = text.text.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    val why = onPost(text.text)
                    if (why == null) text = TextFieldValue("") else error = why
                    busy = false
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(ImageVector.vectorResource(AppIcons.Send), if (replyingTo != null) "Post reply" else "Post comment")
            }
        }
        Text(error ?: "Posted publicly on YouTube, under your account.",
            style = MaterialTheme.typography.labelSmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp))
    }
}

/** YouTube's own limit for a comment. */
private const val MAX_COMMENT = 10_000
