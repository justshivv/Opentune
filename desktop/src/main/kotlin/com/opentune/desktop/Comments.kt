package com.opentune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CommentsDisabled
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentune.data.comments.SongComments
import com.opentune.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A song's comments, as the phone's comments sheet shows them: YouTube's own, under the song's
 * video or, where those are turned off, its music video's. Opened from a song's menu or the full
 * player; one panel over the window at a time.
 */
internal object Comments {
    private val _song = MutableStateFlow<Song?>(null)
    val song: StateFlow<Song?> = _song.asStateFlow()
    fun open(song: Song) { _song.value = song }
    fun close() { _song.value = null }
}

/** The comments panel, when a song's comments are asked for. Put once over the window. */
@Composable
internal fun CommentsHost() {
    val song by Comments.song.collectAsState()
    song?.let { CommentsDialog(it, onDismiss = Comments::close) }
}

private class Replies(var next: String?) {
    val list = mutableStateListOf<SongComments.Comment>()
    var open by mutableStateOf(false)
    var loading by mutableStateOf(false)
}

/**
 * The comments in a raised panel: the song at its head, the comments with who wrote them, when,
 * their likes and their replies to open, the next page fetched as the end comes into view.
 * Signed in to YouTube, a box at the foot posts a comment, publicly, under that account.
 */
@Composable
internal fun CommentsDialog(song: Song, onDismiss: () -> Unit) {
    var thread by remember(song.videoId) { mutableStateOf<SongComments.Thread?>(null) }
    var loading by remember(song.videoId) { mutableStateOf(true) }
    var failed by remember(song.videoId) { mutableStateOf(false) }
    val comments = remember(song.videoId) { mutableStateListOf<SongComments.Comment>() }
    var next by remember(song.videoId) { mutableStateOf<String?>(null) }
    var createParams by remember(song.videoId) { mutableStateOf<String?>(null) }
    var more by remember(song.videoId) { mutableStateOf(false) }
    val replies = remember(song.videoId) { mutableStateMapOf<String, Replies>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(song.videoId) {
        val t = SongComments.open(song.videoId)
        thread = t
        if (t == null) { failed = true; loading = false; return@LaunchedEffect }
        t.first?.let { first ->
            val page = SongComments.page(first)
            if (page == null) failed = true
            else { comments += page.comments; next = page.next; createParams = page.createParams }
        }
        loading = false
    }
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 4 } == true } }
    LaunchedEffect(nearEnd, next) {
        val token = next ?: return@LaunchedEffect
        if (!nearEnd || more) return@LaunchedEffect
        more = true
        try {
            SongComments.page(token)?.let { page ->
                val seen = comments.mapTo(HashSet()) { it.id }
                comments += page.comments.filter { it.id !in seen }
                next = page.next
                if (createParams == null) createParams = page.createParams
            }
        } finally { more = false }
    }
    fun openReplies(c: SongComments.Comment) {
        val r = replies.getOrPut(c.id) { Replies(c.repliesToken) }
        if (r.open && r.next == null) { r.open = false; return }
        r.open = true
        val token = r.next ?: return
        if (r.loading) return
        r.loading = true
        scope.launch {
            val page = SongComments.replies(token)
            if (page == null) Notice.show("Replies didn't load")
            else {
                val seen = r.list.mapTo(HashSet()) { it.id }
                r.list += page.comments.filter { it.id !in seen }
                r.next = page.next
            }
            r.loading = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier.padding(24.dp).widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 760.dp)
                .shadow(28.dp, shape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
                .clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(PageInk.field)) { CoverFill(song.thumbnailUrl, Modifier.fillMaxSize()) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Comments", style = PageType.emptyTitle, color = PageInk.text, modifier = Modifier.semantics { heading() })
                    Text(
                        listOfNotNull(song.title, song.artist.takeIf { it.isNotBlank() }).joinToString(" · ") + if (thread?.fromVideo == true) " · from the music video" else "",
                        style = PageType.small, color = PageInk.grey, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                PageIconButton(Icons.Rounded.Close, "Close", onClick = onDismiss)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(PageInk.hairline))
            Box(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(min = 240.dp)) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.5.dp, color = PageInk.text)
                    failed -> PageEmpty(Icons.Rounded.CloudOff, "Comments didn't load", "Check your connection and try again.", Modifier.align(Alignment.Center))
                    comments.isEmpty() -> PageEmpty(
                        Icons.Rounded.CommentsDisabled,
                        if (thread?.turnedOff == true) "Comments are turned off" else "No comments yet",
                        if (thread?.turnedOff == true) "YouTube doesn't show comments on this song." else null,
                        Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(comments, key = { "c:" + it.id }) { c ->
                            CommentRow(c)
                            val r = replies[c.id]
                            if (c.repliesToken != null && c.replies != null) {
                                Text(
                                    when { r?.loading == true -> "Loading replies…"; r?.open == true && r.next == null -> "Hide replies"; r?.open == true -> "More replies"; else -> "${c.replies} replies" },
                                    style = PageType.small.copy(fontWeight = FontWeight.SemiBold), color = WebInk.key,
                                    modifier = Modifier.padding(start = 70.dp, bottom = 6.dp).clip(RoundedCornerShape(6.dp))
                                        .clickable(role = Role.Button) { openReplies(c) }.padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                            if (r?.open == true) r.list.forEach { reply -> Box(Modifier.padding(start = 48.dp)) { CommentRow(reply) } }
                        }
                        if (more) item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = PageInk.text) }
                        }
                    }
                }
            }
            val params = createParams
            if (params != null) CommentBox(params) { posted -> if (posted != null) comments.add(0, posted) }
        }
    }
}

@Composable
private fun CommentRow(c: SongComments.Comment) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(avatarColour(c.author)), contentAlignment = Alignment.Center) {
            Text(c.author.trimStart('@').take(1).uppercase(), style = webType(15f, 18f, 800), color = Color(0xFF0B0B10))
            if (c.avatarUrl != null) CoverFill(c.avatarUrl, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.author, style = PageType.small.copy(fontWeight = FontWeight.Bold),
                    color = if (c.byCreator) WebInk.key else PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                c.published?.let { Text(" · $it", style = PageType.small, color = PageInk.grey, maxLines = 1) }
            }
            Text(c.text, style = webType(15f, 21f), color = PageInk.text, modifier = Modifier.padding(top = 3.dp))
            c.likes?.let { n ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ThumbUp, null, tint = PageInk.grey, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(n, style = PageType.small, color = PageInk.grey)
                }
            }
        }
    }
}

/** Writes a comment, posted publicly on YouTube under the signed-in account. */
@Composable
private fun CommentBox(params: String, onPosted: (SongComments.Comment?) -> Unit) {
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val send = {
        if (text.isNotBlank() && !sending) {
            sending = true
            scope.launch {
                SongComments.post(params, text).onSuccess { onPosted(it); text = ""; Notice.show("Comment posted") }
                    .onFailure { Notice.show(it.message ?: "YouTube didn't take the comment") }
                sending = false
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(PageInk.hairline))
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PageSearchField(text, { text = it.take(2_000) }, "Add a public comment", Modifier.weight(1f), icon = false, onEnter = { send() })
        PageRoundButton(Icons.AutoMirrored.Rounded.Send, "Post", solid = true, size = 44.dp, iconSize = 20.dp, enabled = text.isNotBlank() && !sending) { send() }
    }
}
