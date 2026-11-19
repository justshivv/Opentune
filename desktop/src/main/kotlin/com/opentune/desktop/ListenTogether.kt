package com.opentune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.model.Song
import com.opentune.data.together.Member
import com.opentune.data.together.RoomCode
import com.opentune.data.together.Together
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Listen together, as on the phone and the web player: start a room and share its code, link or
 * QR, or join a friend's; everyone hears what the host plays, in step. The host lets people in,
 * can hand everyone the controls, and can put someone out. Songs picked as a guest go to the
 * room's queue. The room runs over the phone's own protocol (core's RoomSession), so phones, the
 * web player and computers share rooms. Voice notes play on a phone; here they show as a line.
 *
 * On a wide window the room takes two columns: what's playing, who's in and the host's switches
 * on the left; the invite and the chat, which fills its column, on the right. Narrow, one.
 */
@Composable
internal fun ListenTogetherPage() {
    val room by Together.room.collectAsState()
    ListenTogetherContent(room)
}

/** The page for [room] (null: not in one), apart from where the room comes from, for pictures of it. */
@Composable
internal fun ListenTogetherContent(room: Together.Room?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = Web.gutter(maxWidth)
        val wide = maxWidth >= 940.dp
        androidx.compose.runtime.CompositionLocalProvider(LocalGutter provides gutter, LocalPageWidth provides maxWidth) {
            if (room == null) StartScreen(wide) else RoomScreen(room, wide, maxHeight)
        }
    }
}

// ---- Not in a room -------------------------------------------------------------------

@Composable
private fun StartScreen(wide: Boolean) {
    val gutter = LocalGutter.current
    var name by remember { mutableStateOf(Together.savedName.ifBlank { com.opentune.data.account.AccountStore.account.value?.name.orEmpty() }) }
    var code by remember { mutableStateOf("") }
    val parsed = remember(code) { RoomCode.find(code) ?: RoomCode.parse(code) }
    // A room link or code already copied (sent by a friend), offered as it is.
    val copied by produceState<RoomCode?>(null) { value = withContext(Dispatchers.IO) { RoomCode.find(clipboardText()) } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = gutter, end = gutter, top = LocalPageTop.current + 24.dp, bottom = LocalPageBottom.current),
    ) {
        PageTitle("Listen together", "Hear the same music as friends, in step, on their phones, computers or the web player.")
        Spacer(Modifier.height(28.dp))
        val start = @Composable { m: Modifier ->
            RoomCard(m, glow = true) {
                RoomHeading("Start a room", Icons.Rounded.Groups)
                Text(
                    "You're the host: whatever you play, everyone in the room hears, from where you are. Share the code, link or QR code, " +
                        "let people in, and hand them the controls if you like. Guests add songs to your queue.",
                    style = PageType.cardLine, color = PageInk.grey, modifier = Modifier.padding(top = 8.dp),
                )
                FieldLabel("Your name")
                PageSearchField(name, { name = it.take(30) }, "How friends will see you", Modifier.fillMaxWidth(), icon = false, onEnter = { if (name.isNotBlank()) Together.create(name) })
                Spacer(Modifier.weight(1f, fill = false).height(20.dp))
                PageButton("Start a room", primary = true, enabled = name.isNotBlank()) { Together.create(name) }
            }
        }
        val join = @Composable { m: Modifier ->
            RoomCard(m) {
                RoomHeading("Join with a code", Icons.Rounded.Link)
                Text(
                    "Type the 12-letter code a friend shared, or paste their invite link.",
                    style = PageType.cardLine, color = PageInk.grey, modifier = Modifier.padding(top = 8.dp),
                )
                FieldLabel("Room code or link")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PageSearchField(
                        code, { code = it.take(200) }, "ABCD-EFGH-JKLM", Modifier.weight(1f), icon = false,
                        onEnter = { parsed?.let { Together.join(it, name.ifBlank { "Listener" }) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    PageIconButton(Icons.Rounded.ContentPaste, "Paste", size = 44.dp, iconSize = 20.dp) { code = clipboardText().orEmpty().trim().take(200) }
                }
                if (code.isNotBlank() && parsed == null) Text("That isn't a room code or link.", style = PageType.small, color = Studio.Destructive, modifier = Modifier.padding(top = 6.dp))
                if (parsed != null) Text("Room ${parsed.pretty}", style = PageType.small, color = PageInk.grey, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PageButton("Join", primary = true, enabled = parsed != null) { parsed?.let { Together.join(it, name.ifBlank { "Listener" }) } }
                    val c = copied
                    if (c != null && c != parsed) PageButton("Join ${c.pretty} from your clipboard") { Together.join(c, name.ifBlank { "Listener" }) }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                start(Modifier.weight(1f).fillMaxHeight())
                join(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            start(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            join(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Rooms are private: everything said in one is encrypted with its code, and only “what's playing, where, playing or not” travels. " +
                "Each of you plays the music yourself.",
            style = PageType.small, color = PageInk.grey, modifier = Modifier.widthIn(max = 760.dp),
        )
    }
}

// ---- In a room -----------------------------------------------------------------------

@Composable
private fun RoomScreen(r: Together.Room, wide: Boolean, height: Dp) {
    val gutter = LocalGutter.current
    val top = LocalPageTop.current + 24.dp
    val bottom = LocalPageBottom.current
    val title = if (r.hosting) "Your room" else "${r.hostName ?: "A friend"}'s room"
    if (wide) {
        Column(Modifier.fillMaxSize().padding(start = gutter, end = gutter, top = top, bottom = bottom)) {
            RoomHeader(r, title)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LazyColumn(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    item(key = "now") { NowInRoom(r) }
                    if (r.hosting && r.requests.isNotEmpty()) item(key = "requests") { Requests(r) }
                    item(key = "people") { Listeners(r) }
                    item(key = "settings") { RoomSettings(r) }
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (r.phase != Together.Phase.Ended) Invite(r)
                    Chat(r, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = top, bottom = bottom),
        ) {
            item(key = "head") { RoomHeader(r, title) }
            item(key = "now") { NowInRoom(r) }
            if (r.phase != Together.Phase.Ended) item(key = "invite") { Invite(r) }
            if (r.hosting && r.requests.isNotEmpty()) item(key = "requests") { Requests(r) }
            item(key = "people") { Listeners(r) }
            item(key = "settings") { RoomSettings(r) }
            item(key = "chat") { Chat(r, Modifier.fillMaxWidth().height((height * 0.7f).coerceIn(380.dp, 620.dp))) }
        }
    }
}

/** The room's name and how it's going, with End or Leave at the far end. */
@Composable
private fun RoomHeader(r: Together.Room, title: String) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("LISTEN TOGETHER", style = PageType.kicker, color = PageInk.grey)
            Text(title, style = PageType.pageTitle, color = PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
            Box(Modifier.padding(top = 10.dp)) { LiveStatus(r) }
        }
        Spacer(Modifier.width(16.dp))
        val ended = r.phase == Together.Phase.Ended
        DangerPill(
            when { ended -> "Close"; r.hosting -> "End the room"; else -> "Leave the room" },
        ) { if (r.hosting && !ended) confirm = true else Together.leave() }
    }
    if (confirm) PageConfirmDialog(
        "End the room?", "Everyone in it stops listening together, and the code stops working.", "End the room",
        onDismiss = { confirm = false },
    ) { confirm = false; Together.leave() }
}

/**
 * Where the room stands, as a pill: a green dot with a soft ring once live, red when the host is
 * lost or the room ended, grey on the way; and how many relays carry it.
 */
@Composable
private fun LiveStatus(r: Together.Room) {
    val text = when (r.phase) {
        Together.Phase.Connecting -> "Connecting…"
        Together.Phase.FindingHost -> "Looking for the host…"
        Together.Phase.Waiting -> "Waiting for ${r.hostName ?: "the host"} to let you in…"
        Together.Phase.Live -> when {
            r.hosting -> (r.members.size - 1).let { n -> if (n <= 0) "Live · share the code to bring friends in" else if (n == 1) "Live · 1 friend listening" else "Live · $n friends listening" }
            r.holding -> "Paused for you · the room plays on"
            else -> "In step with ${r.hostName ?: "the host"}"
        }
        Together.Phase.LostHost -> "Lost touch with the host. Trying again…"
        Together.Phase.Ended -> r.endedReason ?: "The room has ended"
    }
    val tone = when (r.phase) {
        Together.Phase.Live -> Color(0xFF32D74B)
        Together.Phase.LostHost, Together.Phase.Ended -> Studio.Destructive
        else -> PageInk.grey
    }
    Row(
        Modifier.clip(CircleShape).background(tone.copy(alpha = 0.13f)).padding(start = 10.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (r.phase == Together.Phase.Live) Box(Modifier.matchParentSize().clip(CircleShape).background(tone.copy(alpha = 0.3f)))
            Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
        }
        val relays = if (r.relays > 0 && r.phase != Together.Phase.Ended) " · ${r.relays} relays" else ""
        Text(text + relays, style = PageType.small.copy(fontWeight = FontWeight.SemiBold), color = if (r.phase == Together.Phase.Live) tone else PageInk.text.copy(alpha = 0.8f), modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * What the room is playing, from the room's own state: the cover large, the song and its artist,
 * and the controls, for the host and for guests the host lets play. A guest who paused for
 * themselves catches up from here. Then what's next in the room.
 */
@Composable
private fun NowInRoom(r: Together.Room) {
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val playing by Player.playing.collectAsState()
    val mine = queue.getOrNull(index)
    val t = r.track
    val title = t?.title ?: mine?.title.takeIf { r.hosting }
    val artist = t?.artist ?: mine?.artist.takeIf { r.hosting }
    val cover = t?.thumb ?: mine?.thumbnailUrl.takeIf { r.hosting }
    val accent = coverAccent(cover)
    val base = MaterialTheme.colorScheme.background
    val canPlay = r.hosting || (r.following && r.open)
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(tint(accent, base, 0.34f), tint(accent, base, 0.1f))))
            .border(0.5.dp, glassEdge(), shape).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(132.dp).clip(RoundedCornerShape(10.dp)).background(PageInk.field), contentAlignment = Alignment.Center) {
                if (cover != null) CoverFill(cover, Modifier.fillMaxSize()) else Icon(Icons.Rounded.MusicNote, null, tint = PageInk.grey, modifier = Modifier.size(44.dp))
            }
            Column(Modifier.weight(1f).padding(start = 20.dp)) {
                Text("PLAYING IN THE ROOM", style = PageType.kicker, color = PageInk.text.copy(alpha = 0.7f))
                Text(
                    title ?: if (r.hostPrivate) "A song only on the host's device" else "Nothing yet",
                    style = PageType.heading, color = PageInk.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                )
                if (artist != null) Text(artist, style = PageType.owner, color = PageInk.text.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PageRoundButton(Icons.Rounded.SkipPrevious, "Previous", size = 40.dp, enabled = canPlay, onClick = Player::previous)
                    PageRoundButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", solid = true, size = 48.dp, enabled = canPlay || r.holding, onClick = Player::toggle)
                    PageRoundButton(Icons.Rounded.SkipNext, "Next", size = 40.dp, enabled = canPlay, onClick = Player::next)
                }
            }
        }
        val note = when {
            r.holding -> null
            r.hosting && t == null && !r.hostPrivate && mine == null -> "Play anything and the room hears it."
            r.hosting -> null
            r.open -> "${r.hostName ?: "The host"} lets everyone control playback. Songs you add from any song's menu go to the room."
            else -> "Only ${r.hostName ?: "the host"} plays, pauses and skips. Add songs from any song's menu: they go to the room's queue."
        }
        note?.let { Text(it, style = PageType.small, color = PageInk.text.copy(alpha = 0.7f), modifier = Modifier.padding(top = 14.dp)) }
        if (r.holding) {
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PageInk.field).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Paused for you. The room keeps playing.", style = PageType.cardLine, color = PageInk.text, modifier = Modifier.weight(1f))
                PageButton("Catch up", primary = true) { Together.intercept(Together.Control.PlayPause) }
            }
        }
        // What's next in the room: the host's queue, which a guest's follows.
        val next = if (r.hosting || r.following) queue.drop(index + 1).take(5) else emptyList()
        if (next.isNotEmpty()) {
            Text("UP NEXT", style = PageType.kicker, color = PageInk.text.copy(alpha = 0.7f), modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            next.forEach { s -> NextRow(s) }
        }
    }
}

@Composable
private fun NextRow(s: Song) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)).background(PageInk.field)) { CoverFill(s.thumbnailUrl, Modifier.fillMaxSize()) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(s.title, style = PageType.small.copy(fontWeight = FontWeight.SemiBold), color = PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist, style = PageType.small, color = PageInk.grey, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(s.durationText.orEmpty(), style = PageType.small, color = PageInk.grey)
    }
}

/** People who knocked, each on a card of their own: let them in or turn them away. */
@Composable
private fun Requests(r: Together.Room) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (r.requests.size == 1) "1 person asking to join" else "${r.requests.size} people asking to join", style = PageType.cardTitle, color = PageInk.text, modifier = Modifier.semantics { heading() })
        r.requests.forEach { m ->
            val key = keyColour()
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(key.copy(alpha = 0.1f)).border(1.dp, key.copy(alpha = 0.35f), shape).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(m.name, 40.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(m.name, style = PageType.rowTitle, color = PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("wants to listen with you", style = PageType.small, color = PageInk.grey)
                }
                PageButton("Decline") { Together.reject(m.id) }
                Spacer(Modifier.width(8.dp))
                PageButton("Let in", primary = true) { Together.approve(m.id) }
            }
        }
    }
}

/** Who's here: each with their initial in their colour, Host and You marked; the host can put someone out. */
@Composable
private fun Listeners(r: Together.Room) {
    var removing by remember { mutableStateOf<Member?>(null) }
    RoomCard(Modifier.fillMaxWidth()) {
        RoomHeading(if (r.members.size == 1) "1 person listening" else "${r.members.size} people listening")
        if (r.members.isEmpty()) Text(
            if (r.phase == Together.Phase.Live) "Just you so far." else "Who's here shows once you're in.",
            style = PageType.cardLine, color = PageInk.grey, modifier = Modifier.padding(top = 8.dp),
        )
        Column(Modifier.padding(top = 6.dp)) {
            r.members.forEach { m ->
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (hovered) PageInk.hover else Color.Transparent)
                        .hoverable(hover).padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(m.name, 38.dp, talking = m.id in r.talking)
                    Text(m.name, style = PageType.rowTitle, color = PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(start = 12.dp))
                    if (m.host) Badge("Host", keyColour())
                    if (m.id == r.myId) Badge("You", PageInk.grey)
                    if (m.id in r.talking) Text("talking", style = PageType.small, color = Color(0xFF32D74B), modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    if (r.hosting && !m.host && m.id != r.myId) PageIconButton(Icons.Rounded.PersonRemove, "Remove ${m.name}") { removing = m }
                }
            }
        }
    }
    removing?.let { m ->
        PageConfirmDialog(
            "Remove ${m.name}?", "They leave the room, and the room moves to a new key, so they can't follow along or read the chat any more.", "Remove",
            onDismiss = { removing = null },
        ) { Together.remove(m.id); removing = null }
    }
}

/** The room's switches: the host's for the room, a guest's for themselves. */
@Composable
private fun RoomSettings(r: Together.Room) {
    val picksPlayNow by Together.picksPlayNow.collectAsState()
    RoomCard(Modifier.fillMaxWidth(), inset = 0.dp) {
        Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) { RoomHeading(if (r.hosting) "Room settings" else "Your settings") }
        if (r.hosting) {
            ToggleRow(
                "Everyone can control playback",
                if (r.open) "Guests can play, pause, seek and skip for everyone" else "Guests add songs; only you play, pause and skip",
                r.open, { Together.setOpen(it) },
            )
            ToggleRow(
                "Ask before letting people in",
                if (r.askToJoin) "People with the code wait until you let them in" else "Anyone with the code comes straight in",
                r.askToJoin, { Together.setAskToJoin(it) },
            )
        } else {
            ToggleRow(
                "Picking a song plays it now",
                when {
                    !picksPlayNow -> "Off: songs you pick go into the room's queue"
                    r.open -> "On: a song you pick starts for everyone"
                    else -> "On, once ${r.hostName ?: "the host"} lets guests control playback. Until then songs go into the queue"
                },
                picksPlayNow, { Together.setPicksPlayNow(it) },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** How to bring friends in: a QR code of the room's link to scan, the code to type, and the link to copy. */
@Composable
private fun Invite(r: Together.Room) {
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied != null) { delay(1_600); copied = null } }
    RoomCard(Modifier.fillMaxWidth(), glow = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RoomHeading(if (r.hosting) "Invite friends" else "Invite more friends")
                Text("Scan with a phone camera, or share the link or code.", style = PageType.small, color = PageInk.grey, modifier = Modifier.padding(top = 4.dp))
                Text(
                    r.code.pretty, style = PageType.heading.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp), color = PageInk.text, maxLines = 1,
                    modifier = Modifier.padding(top = 14.dp).semantics { contentDescription = "Room code ${r.code.pretty}" },
                )
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyPill(if (copied == "link") "Copied" else "Copy invite link", if (copied == "link") Icons.Rounded.Check else Icons.Rounded.Link) {
                        copyText("Listen with me on OpenTune. Room code: ${r.code.pretty}\n${r.code.link}"); copied = "link"
                    }
                    QuietPill(if (copied == "code") "Copied" else "Copy code", if (copied == "code") Icons.Rounded.Check else Icons.Rounded.ContentCopy) {
                        copyText(r.code.pretty); copied = "code"
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            QrCode(r.code.link, 132.dp)
        }
    }
}

/**
 * The chat, filling what it's given: bubbles with who said it and when, yours on the right in
 * the key colour, the room's own lines centred; the box to write in pinned at its foot, Enter
 * sends. New lines are followed while the end is in sight.
 */
@Composable
private fun Chat(r: Together.Room, modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val live = r.phase == Together.Phase.Live
    LaunchedEffect(r.chat.size) { if (r.chat.isNotEmpty()) list.animateScrollToItem(r.chat.lastIndex) }
    val send = { if (text.isNotBlank() && live) { Together.say(text.trim()); text = "" } }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(quinaryFill()).border(0.5.dp, glassEdge(), shape)) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoomHeading("Chat")
            Spacer(Modifier.weight(1f))
            Text("${r.members.size.coerceAtLeast(1)} here", style = PageType.small, color = PageInk.grey)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (r.chat.isEmpty()) Text("Say hi. Everyone in the room sees it.", style = PageType.cardLine, color = PageInk.grey, modifier = Modifier.align(Alignment.Center))
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(r.chat, key = { i, l -> "$i:${l.at}" }) { i, line ->
                    val prev = r.chat.getOrNull(i - 1)
                    ChatLine(line, firstOfRun = prev == null || prev.system || prev.name != line.name || prev.mine != line.mine)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().drawBehind { drawLine(Color.Gray.copy(alpha = 0.25f), Offset(0f, 0f), Offset(size.width, 0f), 1f) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageSearchField(text, { text = it.take(300) }, if (live) "Message the room" else "The chat opens once you're in", Modifier.weight(1f), icon = false, onEnter = send)
            Spacer(Modifier.width(8.dp))
            PageRoundButton(Icons.AutoMirrored.Rounded.Send, "Send", solid = true, size = 44.dp, iconSize = 20.dp, enabled = live && text.isNotBlank(), onClick = send)
        }
    }
}

@Composable
private fun ChatLine(line: Together.ChatLine, firstOfRun: Boolean) {
    if (line.system) {
        Text(line.text, style = PageType.small, color = PageInk.grey, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        return
    }
    val time = remember(line.at) { java.time.Instant.ofEpochMilli(line.at).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) }
    val big = 16.dp
    val small = 4.dp
    val fill = if (line.mine) WebInk.keyFill else quaternaryFill()
    Row(
        Modifier.fillMaxWidth().padding(top = if (firstOfRun) 6.dp else 0.dp),
        horizontalArrangement = if (line.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!line.mine) {
            Box(Modifier.width(36.dp)) { if (firstOfRun) Avatar(line.name, 28.dp) }
        }
        Column(
            Modifier.fillMaxWidth(0.82f).wrapContentWidth(if (line.mine) Alignment.End else Alignment.Start)
                .clip(RoundedCornerShape(big, big, if (line.mine) small else big, if (line.mine) big else small))
                .background(fill).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!line.mine && firstOfRun) Text(line.name, style = PageType.small.copy(fontWeight = FontWeight.Bold), color = avatarColour(line.name))
            Text(
                if (line.voice != null) "🎙 Sent a voice note. Hear it on a phone." else line.text,
                style = webType(14f, 19f), color = if (line.mine) Color.White else PageInk.text,
            )
            Text(time, style = webType(11f, 14f), color = if (line.mine) Color.White.copy(alpha = 0.7f) else PageInk.grey, modifier = Modifier.align(Alignment.End))
        }
    }
}

// ---- Pieces ---------------------------------------------------------------------------

/** A card on the page: a faint glass fill with a hairline edge, lit from its top in the key colour when it [glow]s. */
@Composable
private fun RoomCard(modifier: Modifier, glow: Boolean = false, inset: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val key = keyColour()
    Column(
        modifier.clip(shape).background(quinaryFill())
            .then(if (glow) Modifier.drawBehind { drawRect(Brush.radialGradient(listOf(key.copy(alpha = 0.14f), Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.width * 0.75f)) } else Modifier)
            .border(0.5.dp, glassEdge(), shape).padding(inset),
        content = content,
    )
}

@Composable
private fun PageTitle(title: String, line: String) {
    Text("LISTEN TOGETHER", style = PageType.kicker, color = PageInk.grey)
    Text(title, style = PageType.pageTitle, color = PageInk.text, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
    Text(line, style = PageType.cardLine, color = PageInk.grey, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun RoomHeading(text: String, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Box(Modifier.padding(end = 10.dp).size(32.dp).clip(CircleShape).background(keyColour().copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = keyColour(), modifier = Modifier.size(18.dp))
        }
        Text(text, style = PageType.cardTitle, color = PageInk.text, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = PageType.small.copy(fontWeight = FontWeight.SemiBold), color = PageInk.text.copy(alpha = 0.8f), modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
private fun Badge(text: String, colour: Color) {
    Text(
        text, style = webType(11f, 14f, 700), color = colour, maxLines = 1,
        modifier = Modifier.padding(start = 8.dp).clip(CircleShape).background(colour.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A pill in the key colour with white on it: the invite's main button. */
@Composable
private fun KeyPill(label: String, icon: ImageVector, onClick: () -> Unit) = Pill(label, icon, WebInk.keyFill, Color.White, onClick)

/** A pill with a faint fill. */
@Composable
private fun QuietPill(label: String, icon: ImageVector, onClick: () -> Unit) = Pill(label, icon, PageInk.button, PageInk.text, onClick)

/** Ends or leaves: red on a red-tinted pill with a red ring, so it can't be mistaken. */
@Composable
private fun DangerPill(label: String, onClick: () -> Unit) {
    val red = Studio.Destructive
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(red.copy(alpha = if (hovered) 0.22f else 0.12f), tween(PAGE_HOVER_MS), label = "danger fill")
    Row(
        Modifier.pressScale(source).height(40.dp).clip(CircleShape).background(fill).border(1.dp, red.copy(alpha = 0.6f), CircleShape).hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = red, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = PageType.button, color = red, maxLines = 1)
    }
}

@Composable
private fun Pill(label: String, icon: ImageVector, base: Color, ink: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered) lerp(base, ink, 0.1f) else base, tween(PAGE_HOVER_MS), label = "pill fill")
    Row(
        Modifier.pressScale(source).height(40.dp).clip(CircleShape).background(fill).hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = PageType.button, color = ink, maxLines = 1)
    }
}

private val AVATARS = listOf(0xFFFF8A65, 0xFF4FC3F7, 0xFF9CCC65, 0xFFFFD54F, 0xFFBA68C8, 0xFF4DB6AC, 0xFFF06292, 0xFF7986CB)

/** A person's colour, from their name, as the phone picks it. */
internal fun avatarColour(name: String) = Color(AVATARS[Math.floorMod(name.hashCode(), AVATARS.size)])

/** A person's initial on their colour; a green ring while they're [talking]. */
@Composable
private fun Avatar(name: String, size: Dp, talking: Boolean = false) {
    Box(
        Modifier.size(size).then(if (talking) Modifier.border(2.dp, Color(0xFF32D74B), CircleShape) else Modifier)
            .padding(if (talking) 3.dp else 0.dp).clip(CircleShape).background(avatarColour(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1).uppercase(), style = webType(size.value * 0.42f, size.value * 0.5f, 800), color = Color(0xFF0B0B10))
    }
}

/** What's on the clipboard as text, or null. */
private fun clipboardText(): String? = runCatching {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
}.getOrNull()

/** A small pill naming the room, for the top bar while in one; it opens the room's page. */
@Composable
internal fun RoomChip(onOpen: () -> Unit) {
    val room by Together.room.collectAsState()
    val r = room ?: return
    val live = r.phase == Together.Phase.Live
    val tone = if (live) Color(0xFF32D74B) else if (r.phase == Together.Phase.LostHost || r.phase == Together.Phase.Ended) Studio.Destructive else PageInk.grey
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val people = r.members.size.coerceAtLeast(1)
    Row(
        Modifier.height(30.dp).clip(CircleShape).background(tone.copy(alpha = if (hovered) 0.24f else 0.14f)).hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "Listen together room, $people listening" }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Groups, null, tint = tone, modifier = Modifier.size(17.dp))
        Text("$people", style = PageType.small.copy(fontWeight = FontWeight.Bold), color = tone, modifier = Modifier.padding(start = 5.dp))
    }
}
