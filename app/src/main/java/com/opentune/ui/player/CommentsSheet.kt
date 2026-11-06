package com.opentune.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentune.data.comments.SongComments
import com.opentune.data.model.Song
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingCard
import kotlinx.coroutines.launch

/**
 * The comments on [song], from YouTube: the song's own, or its music video's when the
 * song's are turned off. Signed in to YouTube Music, a comment can be added; it goes up
 * publicly on YouTube under that account, as the box says.
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
    val signedIn by com.opentune.data.account.AccountStore.signedIn.collectAsState()
    var thread by remember(song.videoId) { mutableStateOf<SongComments.Thread?>(null) }
    var loading by remember(song.videoId) { mutableStateOf(true) }
    var failed by remember(song.videoId) { mutableStateOf(false) }
    val comments = remember(song.videoId) { mutableStateListOf<SongComments.Comment>() }
    var next by remember(song.videoId) { mutableStateOf<String?>(null) }
    var createParams by remember(song.videoId) { mutableStateOf<String?>(null) }
    var more by remember(song.videoId) { mutableStateOf(false) }

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

    FloatingCard(
        onDismiss = onDismiss,
        title = "Comments",
        icon = Icons.Rounded.ChatBubbleOutline,
        subtitle = when {
            thread?.fromVideo == true -> "From the music video on YouTube"
            else -> "From YouTube"
        },
        expandable = true,
        // Open at full height straight away, the comment box at its foot; a slide down closes it.
        startExpanded = true,
        // Pulled up (or scrolled), it takes over the page; pulled down it goes back to half, then closes.
        fullFraction = 1f,
    ) {
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator() }
            failed -> Note("Comments didn't load. Check the connection and try again.")
            thread?.first == null -> Note("Comments are turned off for this song on YouTube.")
            comments.isEmpty() -> Note("No comments yet.")
            else -> LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(comments, key = { it.id }) { CommentRow(it) }
                if (next != null) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                }
            }
        }
        // Signed in, the box is always there; YouTube says with its first page whether it takes
        // comments on this video, and if it didn't, sending says so rather than the box being missing.
        when {
            signedIn && thread?.first != null -> Composer(onPost = { text ->
                val params = createParams
                if (params == null) "YouTube isn't taking comments on this video from here"
                else {
                    val r = SongComments.post(params, text)
                    r.onSuccess { posted -> if (posted != null) comments.add(0, posted) }
                    r.exceptionOrNull()?.message ?: if (r.isFailure) "YouTube didn't take the comment" else null
                }
            }, scope = scope)
            !signedIn && thread?.first != null -> Note("Sign in with YouTube Music to add a comment.")
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 20.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CommentRow(c: SongComments.Comment) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Artwork(c.avatarUrl, Modifier.size(36.dp), CircleShape, placeholder = Icons.Rounded.Person)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.author, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = if (c.byCreator) scheme.primary else scheme.onSurface, maxLines = 1)
                c.published?.let {
                    Text("  ·  $it", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Text(c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            if (c.likes != null || c.replies != null) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (c.likes != null) {
                        Icon(Icons.Rounded.ThumbUp, null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
                        Text(" ${c.likes}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                    if (c.replies != null) {
                        Text((if (c.likes != null) "    " else "") + "${c.replies} ${if (c.replies == "1") "reply" else "replies"}",
                            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** A box for a new comment; [onPost] sends it and answers with why it failed, or null. */
@Composable
private fun Composer(onPost: suspend (String) -> String?, scope: kotlinx.coroutines.CoroutineScope) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it.take(MAX_COMMENT); error = null },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Add a comment") },
                maxLines = 4,
                isError = error != null,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            )
            IconButton(enabled = text.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    val why = onPost(text)
                    if (why == null) text = "" else error = why
                    busy = false
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Rounded.Send, "Post comment")
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
