package com.opentune.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.library.LocalPlaylist
import com.opentune.data.model.PlaylistPrivacy
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.UserPlaylist
import com.opentune.data.model.artworkAt
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text

/** A playlist to pick, with how much of what's being added is in it already. */
internal data class PickTarget(
    val key: String,
    val title: String,
    /** "12 songs", or the playlist's own line on YouTube Music. */
    val detail: String,
    /** How many of the songs being added are in it already; null while that's being checked. */
    val already: Int?,
    val cover: @Composable (Modifier) -> Unit,
    val onPick: () -> Unit,
)

/**
 * Where a song, or several, goes: a card with what's being added at the top, a New playlist
 * tile, then your playlists here and on YouTube Music, each with a + that turns into a tick for
 * one that has it already. With many playlists, a field to find one by name. New playlist turns
 * the card into naming it, the songs' covers above the name, without leaving it.
 */
@Composable
internal fun PlaylistPicker(
    songs: List<Song>,
    local: List<PickTarget>,
    account: List<PickTarget>,
    /** Offers keeping a copy on YouTube Music when making one. */
    online: Boolean,
    onCreate: (name: String, privacy: PlaylistPrivacy?) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(local.isEmpty() && account.isEmpty()) }
    var name by remember { mutableStateOf("") }
    var saveOnline by remember { mutableStateOf(true) }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var query by remember { mutableStateOf("") }
    val many = local.size + account.size > SEARCH_FROM
    val single = songs.singleOrNull()
    FloatingCard(
        onDismiss = onDismiss,
        title = if (naming) "New playlist" else if (single != null) "Add to playlist" else com.opentune.i18n.Tr.f("Add {0} songs", songs.size),
        expandable = !naming,
        startExpanded = !naming,
        header = {
            AddingStrip(songs, Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp))
            if (!naming && many) FindField(query, { query = it }, Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp))
        },
        actions = if (naming) ({
            Box(Modifier.weight(1f)) {
                SheetButton(if (local.isEmpty() && account.isEmpty()) "Cancel" else "Back",
                    onClick = { if (local.isEmpty() && account.isEmpty()) onDismiss() else naming = false },
                    tone = SheetTone.Neutral, closes = local.isEmpty() && account.isEmpty())
            }
            Box(Modifier.weight(1f)) {
                SheetButton("Create", onClick = { onCreate(name.trim().ifEmpty { "New playlist" }, privacy.takeIf { online && saveOnline }) }, tone = SheetTone.Primary)
            }
        }) else null,
    ) {
        AnimatedContent(naming, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "picker") { isNaming ->
            if (isNaming) {
                NameStep(songs, name, { name = it }, online, saveOnline, { saveOnline = it }, privacy, { privacy = it })
            } else {
                val q = query.trim()
                fun List<PickTarget>.found() = if (q.isEmpty()) this else filter { it.title.contains(q, ignoreCase = true) }
                val mine = local.found()
                val theirs = account.found()
                Column(Modifier.fillMaxWidth().heightIn(min = 120.dp).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                    if (q.isEmpty()) NewTile(if (single != null) "With this song in it" else com.opentune.i18n.Tr.f("With these {0} songs in it", songs.size)) { naming = true }
                    if (mine.isNotEmpty()) {
                        SectionLabel(Icons.Rounded.PhoneAndroid, "Your playlists")
                        mine.forEach { TargetRow(it, songs.size) }
                    }
                    if (theirs.isNotEmpty()) {
                        SectionLabel(Icons.AutoMirrored.Rounded.QueueMusic, "On YouTube Music")
                        theirs.forEach { TargetRow(it, songs.size) }
                    }
                    if (q.isNotEmpty() && mine.isEmpty() && theirs.isEmpty()) {
                        Text(com.opentune.i18n.Tr.f("No playlist called \"{0}\"", q), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 20.dp))
                        NewTile(com.opentune.i18n.Tr.f("Make \"{0}\"", q)) { name = q; naming = true }
                    }
                }
            }
        }
    }
}

/** Above the list: the song being added, or the covers of the songs, fanned. */
@Composable
private fun AddingStrip(songs: List<Song>, modifier: Modifier = Modifier) {
    val single = songs.singleOrNull()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = FIELD_TINT)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (single != null) {
            Artwork(single.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(44.dp), RoundedCornerShape(10.dp))
        } else {
            Box(Modifier.width(44.dp + 12.dp * (minOf(songs.size, 3) - 1)).height(44.dp)) {
                songs.take(3).forEachIndexed { i, s ->
                    Artwork(s.thumbnailUrl.artworkAt(ROW_ART_PX),
                        Modifier.offset(x = 12.dp * i).size(44.dp).border(2.dp, MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)),
                        RoundedCornerShape(10.dp))
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(single?.title ?: com.opentune.i18n.Tr.f("{0} songs", songs.size), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(single?.artist ?: songs.map { it.artist }.distinct().take(3).joinToString(", "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FindField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(c.onSurface.copy(alpha = FIELD_TINT)).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f).padding(start = 10.dp)) {
            if (query.isEmpty()) Text("Find a playlist", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            BasicTextField(query, onQuery, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear", Modifier.size(20.dp)) }
    }
}

@Composable
private fun SectionLabel(icon: ImageVector, text: String) {
    Row(Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(com.opentune.ui.theme.tr(text).uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A New playlist tile, filled with the accent so it's the first thing to see. */
@Composable
private fun NewTile(line: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp).clip(RoundedCornerShape(20.dp)).background(c.primary.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(c.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, null, tint = c.onPrimary, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("New playlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One playlist: its cover, name and size, and a + (a tick once everything is in it). */
@Composable
private fun TargetRow(t: PickTarget, adding: Int) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val all = t.already != null && t.already >= adding
    val some = t.already != null && t.already in 1 until adding
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { haptics.press(); t.onPick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))) { t.cover(Modifier.fillMaxSize()) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    all && adding == 1 -> com.opentune.i18n.Tr.t("Already in it")
                    all -> com.opentune.i18n.Tr.t("All of them are in it")
                    some -> com.opentune.i18n.Tr.f("{0} of {1} already in it · {2}", t.already!!, adding, t.detail)
                    else -> t.detail
                },
                style = MaterialTheme.typography.bodyMedium, color = if (all) c.primary else c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .then(if (all) Modifier.background(c.primary) else Modifier.border(1.5.dp, c.outline, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (all) Icon(Icons.Rounded.Check, "In this playlist", tint = c.onPrimary, modifier = Modifier.size(20.dp))
            else Icon(Icons.Rounded.Add, com.opentune.i18n.Tr.f("Add to {0}", t.title), tint = c.onSurface, modifier = Modifier.size(20.dp))
        }
    }
}

/** Naming a new playlist: what will be its cover, big, and its name under it. */
@Composable
private fun NameStep(
    songs: List<Song>, name: String, onName: (String) -> Unit,
    online: Boolean, saveOnline: Boolean, onSaveOnline: (Boolean) -> Unit,
    privacy: PlaylistPrivacy, onPrivacy: (PlaylistPrivacy) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(132.dp).clip(RoundedCornerShape(24.dp))) {
            PlaylistCover(name.ifBlank { "New playlist" }, songs.map { it.thumbnailUrl }, Modifier.fillMaxSize(), RectangleShape)
        }
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(18.dp)).background(c.onSurface.copy(alpha = FIELD_TINT)).padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (name.isEmpty()) Text("Name your playlist", style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant)
            BasicTextField(name, { onName(it.take(150)) }, singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = c.onSurface, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        if (online) AccountCopyOptions(saveOnline, onSaveOnline, privacy, onPrivacy, Modifier.fillMaxWidth().padding(top = 14.dp))
    }
}

/** How strongly a field or strip stands out from the card it sits on. */
internal const val FIELD_TINT = 0.08f

/** From this many playlists, a field to find one by name. */
private const val SEARCH_FROM = 6

/** A playlist made here, to pick. */
internal fun localTarget(p: LocalPlaylist, already: Int, onPick: () -> Unit) = PickTarget(
    "local:${p.id}", p.name,
    if (p.songs.size == 1) com.opentune.i18n.Tr.t("1 song") else com.opentune.i18n.Tr.f("{0} songs", p.songs.size),
    already,
    { m -> PlaylistCover(p.name, p.songs.map { it.thumbnailUrl }, m, RectangleShape) },
    onPick,
)

/** One of the account's playlists on YouTube Music, to pick. */
internal fun accountTarget(p: UserPlaylist, already: Int?, onPick: () -> Unit) = PickTarget(
    "yt:${p.playlistId}", p.title, p.subtitle.ifBlank { "YouTube Music" }, already,
    { m -> Artwork(p.thumbnailUrl, m, RectangleShape, placeholder = Icons.AutoMirrored.Rounded.QueueMusic) },
    onPick,
)
