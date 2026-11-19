package com.opentune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.together.RoomCode
import com.opentune.data.together.Together

/**
 * Listen together, as on the phone and the web player: start a room and share its code, or
 * join a friend's; everyone hears what the host plays, in step. The host lets people in,
 * can hand everyone the controls, and can put someone out. Songs picked as a guest go to
 * the room's queue. The chat runs alongside; voice notes play on a phone.
 */
@Composable
internal fun ListenTogetherPage() {
    val room by Together.room.collectAsState()
    ScrollPage("Listen together", spacing = 22.dp) {
        val r = room
        if (r == null) item(key = "start") { StartOrJoin() } else {
            item(key = "head") { RoomHead(r) }
            if (r.hosting && r.requests.isNotEmpty()) item(key = "requests") { Requests(r) }
            item(key = "members") { Members(r) }
            item(key = "options") { RoomOptions(r) }
            item(key = "chat") { Chat(r) }
        }
    }
}

@Composable
private fun StartOrJoin() {
    var name by remember { mutableStateOf(Together.savedName.ifBlank { com.opentune.data.account.AccountStore.account.value?.name.orEmpty() }) }
    var code by remember { mutableStateOf("") }
    val parsed = remember(code) { RoomCode.parse(code) }
    Column(Modifier.padding(horizontal = GUTTER).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Start a room and friends hear what you play, in step, on their own phones, computers or the web player. Or join a friend's room with their code.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(name, { name = it.take(30) }, label = { Text("Your name") }, placeholder = { Text("How friends will see you") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { Together.create(name) }, enabled = name.isNotBlank()) { Text("Start a room") }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Join a friend's room", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            code, { code = it }, label = { Text("Room code or invite link") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = code.isNotBlank() && parsed == null,
            supportingText = if (code.isNotBlank() && parsed == null) ({ Text("That isn't a room code.") }) else null,
        )
        Button(onClick = { parsed?.let { Together.join(it, name) } }, enabled = parsed != null && name.isNotBlank()) { Text("Join") }
    }
}

@Composable
private fun RoomHead(r: Together.Room) {
    val phase = when (r.phase) {
        Together.Phase.Connecting -> "Connecting…"
        Together.Phase.FindingHost -> "Looking for the host…"
        Together.Phase.Waiting -> "Waiting for ${r.hostName ?: "the host"} to let you in"
        Together.Phase.Live -> if (r.hosting) "Live · friends hear what you play" else if (r.holding) "Paused for you · the room plays on" else "Live · listening with ${r.hostName ?: "the host"}"
        Together.Phase.LostHost -> "Lost the host. Trying again…"
        Together.Phase.Ended -> r.endedReason ?: "The room has ended"
    }
    // The room on a card of its own, lit faintly from the top in the key colour, as the web player's.
    val key = keyColour()
    Column(
        Modifier.padding(horizontal = GUTTER).widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(quinaryFill())
            .drawBehind { drawRect(Brush.radialGradient(listOf(key.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.width * 0.7f)) }
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoomStatus(phase, r.phase)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                r.code.pretty, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 3.sp),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedButton(onClick = { copyText(r.code.link); Notice.show("Invite link copied") }) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy invite link")
            }
            TextButton(onClick = Together::leave) { Text(if (r.hosting) "End the room" else "Leave") }
        }
        r.track?.let { t -> Text("Playing ${t.title} · ${t.artist}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Where the room is, after a dot: green with a soft ring while live, red once lost or ended, grey on the way. */
@Composable
private fun RoomStatus(text: String, phase: Together.Phase) {
    val live = phase == Together.Phase.Live
    val dot = when (phase) {
        Together.Phase.Live -> Color(0xFF32D74B)
        Together.Phase.LostHost, Together.Phase.Ended -> Color(0xFFFF453A)
        else -> Color(0xFF98989D)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (live) Box(Modifier.matchParentSize().clip(CircleShape).background(dot.copy(alpha = 0.25f)))
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun Requests(r: Together.Room) {
    Column(Modifier.padding(horizontal = GUTTER), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Asking to join", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        r.requests.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { Together.reject(m.id) }) { Text("Turn away") }
                Button(onClick = { Together.approve(m.id) }) { Text("Let in") }
            }
        }
    }
}

@Composable
private fun Members(r: Together.Room) {
    Column(Modifier.padding(horizontal = GUTTER), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (r.members.size == 1) "1 person here" else "${r.members.size} people here", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        r.members.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(if (m.host) Icons.Rounded.Star else Icons.Rounded.Person, if (m.host) "Host" else null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    m.name + if (m.id == r.myId) " (you)" else "", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                if (m.id in r.talking) Text("talking", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (r.hosting && !m.host && m.id != r.myId) HoverIcon(Icons.Rounded.Close, "Remove ${m.name}", size = 30.dp, iconSize = 17.dp) { Together.remove(m.id) }
            }
        }
    }
}

@Composable
private fun RoomOptions(r: Together.Room) {
    val picksPlayNow by Together.picksPlayNow.collectAsState()
    Column(Modifier.padding(horizontal = GUTTER).widthIn(max = 720.dp)) {
        if (r.hosting) {
            SwitchRow("Let everyone control playback", "Guests can play, pause, seek and skip for everyone", r.open) { Together.setOpen(it) }
            SwitchRow("Ask to join", "People with the code wait for you to let them in", r.askToJoin) { Together.setAskToJoin(it) }
        } else {
            SwitchRow("Songs I pick play now", "When the host lets guests control playback; otherwise they join the room's queue", picksPlayNow) { Together.setPicksPlayNow(it) }
        }
    }
}

@Composable
private fun Chat(r: Together.Room) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = GUTTER).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Chat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        r.chat.takeLast(60).forEach { line ->
            when {
                line.system -> Text(
                    line.text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                line.voice != null -> ChatBubble(line.name, "Sent a voice note. Hear it on a phone.", mine = line.mine)
                else -> ChatBubble(line.name, line.text, mine = line.mine)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it.take(300) }, placeholder = { Text("Say something") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { Together.say(text.trim()); text = "" }, enabled = text.isNotBlank() && r.phase == Together.Phase.Live) { Text("Send") }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * A line of the chat as a bubble, as the web player's: others' on the left in
 * a soft grey with their name over it, yours on the right in the key colour,
 * each with its corner by the speaker tucked in.
 */
@Composable
private fun ChatBubble(name: String, text: String, mine: Boolean) {
    val big = 16.dp
    val small = 4.dp
    val fill = if (mine) (if (darkTheme()) androidx.compose.ui.graphics.lerp(railAccent(), Color.Black, 0.18f) else keyColour()) else quaternaryFill()
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.fillMaxWidth(0.85f).wrapContentWidth(if (mine) Alignment.End else Alignment.Start)
                .clip(RoundedCornerShape(big, big, if (mine) small else big, if (mine) big else small))
                .background(fill).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!mine) Text(name, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
            Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp), color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface)
        }
    }
}
