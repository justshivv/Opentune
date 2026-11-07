package com.opentune.ui.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import com.opentune.ui.theme.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.audiobooks.Audiobooks
import com.opentune.data.audiobooks.Audiobooks.toSong
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.PageHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The row on the Podcasts page that opens Audiobooks. */
@Composable
internal fun AudiobooksEntry(onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("Audiobooks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Free classics read aloud, from LibriVox", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Audiobooks: the most listened-to LibriVox books, a search, and the books
 * started, to carry on with.
 */
@Composable
fun AudiobooksScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenBook: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var books by remember { mutableStateOf<List<Audiobooks.Book>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val progress by Audiobooks.progress.collectAsState()
    val started = remember(progress) { Audiobooks.inProgress() }
    LaunchedEffect(query, attempt) {
        books = null; error = null
        if (query.isNotBlank()) delay(350)
        try {
            books = Audiobooks.search(query.ifBlank { null })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't reach the Internet Archive"
        }
    }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Audiobooks", onBack = onBack) }
        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Title or author") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (query.isBlank() && started.isNotEmpty()) {
            item { SectionTitle("Continue listening") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(started, key = { it.bookId }) { p ->
                        BookCard(Audiobooks.Book(p.bookId, p.title, p.author), Modifier.width(132.dp)) { onOpenBook(p.bookId) }
                    }
                }
            }
        }
        item { SectionTitle(if (query.isBlank()) "Most listened to" else "Books") }
        val list = books
        when {
            error != null -> item { ErrorState(error!!, onRetry = { attempt++ }, modifier = Modifier.padding(top = 24.dp)) }
            list == null -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            list.isEmpty() -> item { Text("No books found", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> items(list, key = { it.id }) { book -> BookRow(book) { onOpenBook(book.id) } }
        }
        item {
            Text(
                "Free public-domain books read by LibriVox volunteers, played from the Internet Archive.",
                Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun BookCard(book: Audiobooks.Book, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Artwork(book.coverUrl, Modifier.fillMaxWidth().height(132.dp), RoundedCornerShape(12.dp))
        Text(book.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BookRow(book: Audiobooks.Book, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(book.coverUrl, Modifier.size(64.dp), RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(book.author, book.runtime?.let { com.opentune.i18n.Tr.f("{0} long", it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A book: its cover, what it's about, and its chapters; plays from where it was left. */
@Composable
fun AudiobookScreen(id: String, contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    var page by remember { mutableStateOf<Audiobooks.BookPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val progress by Audiobooks.progress.collectAsState()
    LaunchedEffect(id, attempt) {
        error = null
        try {
            page = Audiobooks.book(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't reach the Internet Archive"
        }
    }
    var expanded by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader(page?.book?.title ?: "Audiobook", onBack = onBack) }
        val p = page
        if (p == null) {
            item {
                if (error != null) ErrorState(error!!, onRetry = { attempt++ }, modifier = Modifier.padding(top = 24.dp))
                else Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            return@LazyColumn
        }
        val songs = p.chapters.map { it.toSong(p.book) }
        val at = progress[id]
        val resumeIndex = at?.let { a -> p.chapters.indexOfFirst { it.id == a.chapterId } }?.takeIf { it >= 0 }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(p.book.coverUrl, Modifier.size(200.dp), RoundedCornerShape(16.dp))
                Spacer(Modifier.height(14.dp))
                Text(p.book.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(listOfNotNull(p.book.author, p.book.runtime).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { if (songs.isNotEmpty()) actions.playAll(songs, resumeIndex ?: 0, false, p.book.title) },
                    enabled = songs.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (resumeIndex != null) com.opentune.i18n.Tr.f("Continue: {0}", p.chapters[resumeIndex].title) else "Play from the start", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                p.description?.let { text ->
                    Text(
                        text, Modifier.padding(top = 14.dp).clickable { expanded = !expanded },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item { SectionTitle(com.opentune.i18n.Tr.f("{0} chapters", p.chapters.size)) }
        itemsIndexed(p.chapters, key = { _, c -> c.id }) { i, c ->
            val playing = actions.currentVideoId == c.id
            Row(
                Modifier.fillMaxWidth().clickable { actions.playAll(songs, i, false, p.book.title) }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${c.number}", Modifier.width(36.dp), style = MaterialTheme.typography.titleMedium,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.bodyLarge, color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (at?.chapterId == c.id && at.positionMs > 0) {
                        Text(com.opentune.i18n.Tr.f("Left at {0}", clock(at.positionMs)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (c.durationMs > 0) Text(clock(c.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
