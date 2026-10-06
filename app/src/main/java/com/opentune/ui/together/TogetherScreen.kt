package com.opentune.ui.together

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.together.Member
import com.opentune.data.together.RoomCode
import com.opentune.data.together.Together
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SectionHeader

/**
 * Listen together: start a room or join one by code, then see who's in,
 * what's playing, and chat. [code] fills in the join box, from an invite link.
 */
@Composable
fun TogetherScreen(contentPadding: PaddingValues, code: String?, onBack: () -> Unit) {
    val room by Together.room.collectAsState()
    val r = room
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        if (r == null) StartRoom(code, onBack) else RoomView(r, onBack)
    }
}

@Composable
internal fun StartRoom(code: String?, onBack: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(Together.savedName) }
    var typed by rememberSaveable(code) { mutableStateOf(RoomCode.parse(code)?.pretty ?: "") }
    val parsed = RoomCode.parse(typed)
    LazyColumn(Modifier.fillMaxSize()) {
        item { PageHeader("Listen together", onBack = onBack) }
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Box(
                    Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Groups, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Start a room and friends hear what you play, in step, on their own phones. Or join a friend's room with their code.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    name, { name = it.take(30) },
                    label = { Text("Your name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { Together.create(name) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Start a room") }

                Row(Modifier.padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text("or join one", Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.weight(1f))
                }
                OutlinedTextField(
                    typed, { typed = it.uppercase().take(16) },
                    label = { Text("Room code") },
                    placeholder = { Text("K7QX-M2PA-9DTE") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { parsed?.let { if (name.isNotBlank()) Together.join(it, name) } }),
                    isError = typed.length >= 12 && parsed == null,
                    supportingText = { if (typed.length >= 12 && parsed == null) Text("That isn't a room code") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { parsed?.let { Together.join(it, name) } },
                    enabled = parsed != null && name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Join") }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Rooms run over public Nostr relays. Everything said in a room is encrypted with its code, " +
                            "so relays only pass it along, and they don't keep it. Each phone plays the music itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
internal fun RoomView(r: Together.Room, onBack: () -> Unit) {
    val context = LocalContext.current
    var message by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(r.chat.size) { if (r.chat.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }
    val share = {
        val text = "Listen with me on OpenTune. Room code: ${r.code.pretty}\n${r.code.link}"
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite to your room"))
    }
    val copy = {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("OpenTune room", r.code.pretty))
        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), state = list) {
            item { PageHeader(if (r.hosting) "Your room" else "${r.hostName ?: "Friend"}'s room", onBack = onBack) }
            item { CodeCard(r, onShare = share, onCopy = copy) }
            item { NowInRoom(r) }
            if (r.holding) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(18.dp)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Paused for you. The room keeps playing.", Modifier.weight(1f), color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Button(onClick = { Together.intercept(Together.Control.PlayPause) }) { Text("Catch up") }
                    }
                }
            }
            if (r.hosting) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Everyone can control playback", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (r.open) "Guests can play, pause, seek and skip" else "Guests can add songs; only you play, pause and skip",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(r.open, { Together.setOpen(it) })
                    }
                }
            }
            item { SectionHeader("Listening", subtitle = "${r.members.size} in the room") }
            item { Members(r.members) }
            item { SectionHeader("Chat") }
            if (r.chat.isEmpty()) {
                item { Text("Say hi.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(r.chat.size) { i -> ChatBubble(r.chat[i]) }
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(onClick = { Together.leave() }) { Text(if (r.hosting) "End room" else "Leave room") }
                }
            }
        }
        if (r.phase != Together.Phase.Ended) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    message, { message = it.take(300) },
                    placeholder = { Text("Message the room") },
                    singleLine = true,
                    shape = RoundedCornerShape(26.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { Together.say(message); message = "" }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { Together.say(message); message = "" }, enabled = message.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Send")
                }
            }
        }
    }
}

@Composable
private fun CodeCard(r: Together.Room, onShare: () -> Unit, onCopy: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ROOM CODE", style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(r.code.pretty, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Status(r)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onShare) {
                Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Invite")
            }
            OutlinedButton(onClick = onCopy) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy code")
            }
        }
    }
}

@Composable
private fun Status(r: Together.Room) {
    val (text, busy) = when (r.phase) {
        Together.Phase.Connecting -> "Connecting…" to true
        Together.Phase.FindingHost -> "Looking for the host…" to true
        Together.Phase.Live -> (if (r.hosting) "Live · ${r.members.size - 1} joined" else "In step with ${r.hostName ?: "the host"}") to false
        Together.Phase.LostHost -> "Lost touch with the host. Still trying…" to true
        Together.Phase.Ended -> (r.endedReason ?: "The room has ended") to false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        else Box(Modifier.size(8.dp).background(if (r.phase == Together.Phase.Live) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.relays > 0) {
            Text(" · ${r.relays} relays", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NowInRoom(r: Together.Room) {
    val t = r.track
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(t?.thumb, Modifier.size(56.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Playing in the room", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when {
                    t != null -> t.title
                    r.hostPrivate -> "A song only on the host's phone"
                    else -> "Nothing yet"
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (t != null) Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    AnimatedVisibility(r.hosting && t == null && !r.hostPrivate) {
        Text(
            "Play anything and the room will hear it.",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!r.hosting) {
        Text(
            "Add songs from any song's menu with Add to queue: they go to the room.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Members(members: List<Member>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(members, key = { it.id }) { m ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 76.dp)) {
                Box(
                    Modifier.size(56.dp).background(avatarColor(m.name), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(m.name.take(1).uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B0B10)) }
                Spacer(Modifier.height(6.dp))
                Text(m.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (m.host) Text("Host", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private val AVATARS = listOf(0xFFFF8A65, 0xFF4FC3F7, 0xFF9CCC65, 0xFFFFD54F, 0xFFBA68C8, 0xFF4DB6AC, 0xFFF06292, 0xFF7986CB)
private fun avatarColor(name: String) = Color(AVATARS[Math.floorMod(name.hashCode(), AVATARS.size)])

@Composable
private fun ChatBubble(line: Together.ChatLine) {
    if (line.system) {
        Text(
            line.text,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = if (line.mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier.widthIn(max = 290.dp)
                .background(
                    if (line.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(20.dp),
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            if (!line.mine) Text(line.name, style = MaterialTheme.typography.labelMedium, color = avatarColor(line.name))
            Text(line.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
