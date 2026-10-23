package com.opentune.ui.together

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.opentune.data.settings.AppSettings
import com.opentune.data.together.Member
import com.opentune.data.together.RoomCode
import com.opentune.data.together.Together
import com.opentune.ui.KeepChromeOut
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.GlassPage
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.RowDivider
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.ToggleRow
import com.opentune.ui.components.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Listen together: start a room or join one by code, then see who's in,
 * what's playing, and chat. [code] fills in the join box, from an invite link.
 */
@Composable
fun TogetherScreen(contentPadding: PaddingValues, code: String?, onBack: () -> Unit) {
    val room by Together.room.collectAsState()
    val r = room
    var handled by rememberSaveable(code) { mutableStateOf(false) }
    var switchRoom by rememberSaveable(code) { mutableStateOf(false) }
    val invite = RoomCode.parse(code)
    val inviteName = Together.savedName.ifBlank { com.opentune.data.account.AccountStore.account.value?.name.orEmpty() }
    LaunchedEffect(code) {
        if (!handled && invite != null) {
            handled = true
            if (r != null && r.code != invite) switchRoom = true
        }
    }
    if (switchRoom && invite != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { switchRoom = false },
            title = { com.opentune.ui.components.FrostBehind(enabled = !com.opentune.data.settings.AppSettings.ui.collectAsState().value.reduceBlur); Text("Join the invited room?") },
            text = { Text("This leaves your current room.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                switchRoom = false
                Together.join(invite, inviteName.ifBlank { "Listener" })
            }) { Text("Join room") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { switchRoom = false }) { Text("Stay here") } },
        )
    }
    // A new room stays on the Start button, as a spinner, until a relay
    // answers (or a few seconds pass), so the room opens ready to share,
    // with its invite on top.
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    var opening by rememberSaveable { mutableStateOf(false) }
    var inviteFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(opening) {
        if (!opening) return@LaunchedEffect
        val since = System.currentTimeMillis()
        withTimeoutOrNull(OPENING_MAX_MS) { Together.room.first { it == null || it.phase != Together.Phase.Connecting } }
        // Long enough to see what happened, when the relays answer at once.
        if (!still) delay((OPENING_MIN_MS - (System.currentTimeMillis() - since)).coerceAtLeast(0))
        val now = Together.room.value
        if (now != null && now.hosting) inviteFor = now.code.raw
        opening = false
    }
    // Both pages scroll behind the dock and stop short of it; the room's
    // island sits on top of the dock.
    if (r == null || (opening && r.hosting)) {
        StartRoom(code, onBack, contentPadding, opening = opening && r != null, onStart = { name ->
            Together.create(name)
            opening = true
        })
    } else {
        RoomView(r, onBack, contentPadding, openInvite = inviteFor == r.code.raw)
    }
}

/** The longest a new room waits on the Start button for a relay, and the shortest it shows there. */
private const val OPENING_MAX_MS = 5_000L
private const val OPENING_MIN_MS = 700L

@Composable
internal fun StartRoom(
    code: String?,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    opening: Boolean = false,
    onStart: (name: String) -> Unit = { Together.create(it) },
) {
    val invite = remember(code) { RoomCode.parse(code) }
    var name by rememberSaveable {
        mutableStateOf(Together.savedName.ifBlank {
            com.opentune.data.account.AccountStore.account.value?.name.orEmpty()
        }.ifBlank { if (invite != null) "Listener" else "" })
    }
    var typed by rememberSaveable(code) { mutableStateOf(invite?.raw ?: "") }
    var shake by remember { mutableIntStateOf(0) }
    var turnedAway by remember { mutableStateOf(false) }
    val parsed = if (invite != null) invite else CodeEntry.code(typed)
    val canJoin = parsed != null && name.isNotBlank() && !opening
    val join = { parsed?.let { Together.join(it, name) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { PageHeader(if (invite != null) "You’re invited" else "Listen together", onBack = onBack) }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Hero()
                Text(
                    if (invite != null) "Listen with your friends in OpenTune. Tap Join when you’re ready."
                    else "Start a room and friends hear what you play, in step, on their own phones. Or join a friend's room with their code.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(28.dp))
                FieldLabel("Your name")
                NameField(name, { name = it.take(30) }, enabled = !opening)
                Spacer(Modifier.height(20.dp))
                if (invite != null) {
                    FieldLabel("Room")
                    Text(
                        invite.pretty,
                        Modifier.fillMaxWidth().roomCard(inset = 0.dp).padding(vertical = 18.dp),
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 3.sp),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { join() }, enabled = canJoin, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Join") }
                } else {
                    StartButton(opening = opening, enabled = name.isNotBlank()) { onStart(name) }
                    Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            "or join with a code",
                            Modifier.padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    CodeField(
                        typed,
                        onChange = { edit ->
                            typed = edit.raw
                            if (edit.rejected) { turnedAway = true; shake++ } else turnedAway = false
                        },
                        onGo = { if (canJoin) join() else if (parsed == null) { turnedAway = false; shake++ } },
                        shake = shake,
                        enabled = !opening,
                    )
                    Text(
                        when {
                            turnedAway -> "Room codes never use 0, 1, I or O"
                            parsed != null && name.isBlank() -> "Add your name above to join"
                            parsed != null -> "Ready to join ${parsed.pretty}"
                            else -> "Type the code, or paste the code or invite link"
                        },
                        Modifier.padding(start = 4.dp, top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            turnedAway -> MaterialTheme.colorScheme.error
                            parsed != null -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { join() }, enabled = canJoin, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Join") }
                }
                Spacer(Modifier.height(28.dp))
                PrivacyNote()
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * The group icon with rings that keep spreading out from it and fading, and
 * three friends drifting round it. Still, with reduce animation on.
 */
@Composable
private fun Hero() {
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val scheme = MaterialTheme.colorScheme
    val time = if (still) null else rememberInfiniteTransition(label = "hero")
    val pulse = time?.animateFloat(0f, 1f, infiniteRepeatable(tween(3_600, easing = LinearEasing), RepeatMode.Restart), label = "rings")
    val turn = time?.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "orbit")
    Box(
        Modifier.fillMaxWidth().height(176.dp).drawBehind {
            val inner = 40.dp.toPx()
            val outer = 84.dp.toPx()
            val p = pulse?.value ?: 0f
            for (i in 0 until RINGS) {
                val f = (p + i.toFloat() / RINGS) % 1f
                // Fades in from the icon, out at the edge.
                val alpha = (if (f < 0.15f) f / 0.15f else (1f - f) / 0.85f) * 0.28f
                drawCircle(scheme.primary.copy(alpha = alpha), radius = inner + (outer - inner) * f, style = Stroke(1.5.dp.toPx()))
            }
            val orbit = inner + (outer - inner) * 0.55f
            val start = turn?.value ?: 20f
            ORBIT_COLORS.forEachIndexed { i, c ->
                val a = Math.toRadians((start + i * 120.0) % 360.0)
                val at = Offset(center.x + orbit * kotlin.math.cos(a).toFloat(), center.y + orbit * kotlin.math.sin(a).toFloat())
                drawCircle(Color(c).copy(alpha = 0.9f), radius = (5 + i).dp.toPx(), center = at)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(76.dp).background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Groups, null, Modifier.size(40.dp), tint = scheme.onPrimaryContainer) }
    }
}

private const val RINGS = 3
private val ORBIT_COLORS = listOf(0xFF4FC3F7, 0xFFFFD54F, 0xFFF06292)

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 4.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A soft rounded field with a person icon; its edge lights up while typing. */
@Composable
private fun NameField(name: String, onChange: (String) -> Unit, enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val edge by animateColorAsState(if (focused) scheme.primary else scheme.outlineVariant.copy(alpha = 0.7f), label = "edge")
    val shape = RoundedCornerShape(18.dp)
    BasicTextField(
        name, onChange,
        enabled = enabled,
        singleLine = true,
        interactionSource = interaction,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().height(56.dp)
            .background(scheme.surfaceContainerHigh, shape)
            .border(if (focused) 2.dp else 1.dp, edge, shape)
            .semantics { contentDescription = "Your name" },
        decorationBox = { field ->
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Person, null, Modifier.size(22.dp), tint = if (focused) scheme.primary else scheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (name.isEmpty()) Text("How friends will see you", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant.copy(alpha = 0.7f))
                    field()
                }
            }
        },
    )
}

/** Start a room: while the room opens, the button draws in and turns into a spinner. */
@Composable
private fun StartButton(opening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val scheme = MaterialTheme.colorScheme
    val haptics = com.opentune.ui.components.rememberHaptics()
    val narrow by animateFloatAsState(
        if (opening) 1f else 0f,
        if (still) snap() else spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "morph",
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth(1f - 0.28f * narrow).height(56.dp)
                .graphicsLayer { alpha = if (enabled || opening) 1f else 0.4f }
                .clip(CircleShape)
                .background(scheme.primary)
                .clickable(enabled = enabled && !opening, role = Role.Button) { haptics.press(); onClick() }
                .semantics { if (opening) stateDescription = "Opening the room" },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                opening,
                transitionSpec = { if (still) fadeIn(snap()) togetherWith fadeOut(snap()) else fadeIn(tween(180, 80)) togetherWith fadeOut(tween(120)) },
                label = "start",
            ) { busy ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = scheme.onPrimary, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Groups, null, Modifier.size(22.dp), tint = scheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (busy) "Opening the room…" else "Start a room",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** What rooms are and what travels through them, a line at a time. */
@Composable
private fun PrivacyNote() {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .background(scheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Lock, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("How rooms work", style = MaterialTheme.typography.titleSmall)
            listOf(
                "Rooms run over public Nostr relays, which only pass messages along and don't keep them.",
                "Music, chat and voice messages are encrypted.",
                "If the host has approval on, you wait for them to let you in.",
                "Each phone plays the music itself.",
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
        }
    }
}

/**
 * The room's glass: a film of the surface, lit a little along the top, with
 * a bright hairline edge, as on the app's popups. [inset] is the gap to the
 * screen edge.
 */
@Composable
private fun Modifier.roomCard(inset: Dp = 16.dp): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() <= 0.5f
    val shape = RoundedCornerShape(26.dp)
    return this.fillMaxWidth().padding(horizontal = inset).clip(shape)
        .background(scheme.surfaceContainerHigh.copy(alpha = if (dark) 0.78f else 0.88f))
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.06f else 0.16f), 0.4f to Color.White.copy(alpha = 0f)))
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.16f else 0.5f), Color.White.copy(alpha = 0.03f))), shape)
}

/** The gap above and below each card on the room page. */
private val GAP = 6.dp

@Composable
internal fun RoomView(r: Together.Room, onBack: () -> Unit, contentPadding: PaddingValues = PaddingValues(), openInvite: Boolean = false) {
    val context = LocalContext.current
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    var message by rememberSaveable { mutableStateOf("") }
    var inviting by rememberSaveable(r.code.raw) { mutableStateOf(openInvite) }
    var open by rememberSaveable(r.code.raw) { mutableStateOf(false) }
    val typing = remember { FocusRequester() }
    val list = rememberLazyListState()
    // The island sits on the dock, and on the keyboard while it's up.
    KeepChromeOut()
    KeyboardResizes()
    val island = r.phase != Together.Phase.Ended
    val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val above = maxOf(contentPadding.calculateBottomPadding() - ISLAND_DROP, keyboard + 8.dp, 8.dp)
    var islandHeight by remember { mutableStateOf(ISLAND_HEIGHT) }
    val listBottom = if (island) above + islandHeight + 16.dp else contentPadding.calculateBottomPadding()
    val last = { (list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0) }
    // New messages are followed only by someone already reading the end of
    // the chat, or writing in it, so a room always opens at its top: the
    // code, the invite and who's in.
    val atEnd by remember {
        derivedStateOf {
            val seen = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) && seen >= list.layoutInfo.totalItemsCount - 2
        }
    }
    var heard by remember(r.code) { mutableIntStateOf(r.chat.size) }
    LaunchedEffect(r.chat.size) {
        val added = r.chat.size > heard
        heard = r.chat.size
        if (added && (open || atEnd)) list.animateScrollToItem(last())
    }
    // Open, the newest messages stay in sight as the island and the keyboard rise.
    LaunchedEffect(open, listBottom) { if (open) list.scrollToItem(last()) }
    val close = { open = false; focus.clearFocus() }
    androidx.activity.compose.BackHandler(enabled = open, onBack = close)
    // The room rises into place when it opens.
    val shown = remember(r.code) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(r.code) { if (!still) shown.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 240f)) }
    if (inviting) InviteSheet(r.code, r.hosting, onDismiss = { inviting = false })
    val meSpeaking by Together.speaking.collectAsState()
    val speaking = r.talking.toSet() + listOfNotNull(r.myId.takeIf { meSpeaking })
    GlassPage(
        Modifier.fillMaxSize().graphicsLayer {
            alpha = shown.value.coerceIn(0f, 1f)
            translationY = (1f - shown.value) * 28.dp.toPx()
        },
        overlay = {
            if (island) {
                RoomIsland(
                    r, speaking, open,
                    onOpen = { if (it) open = true else close() },
                    message = message,
                    onMessage = { message = it.take(300) },
                    onSend = { Together.say(message); message = "" },
                    typing = typing,
                    still = still,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = above)
                        .onSizeChanged { islandHeight = with(density) { it.height.toDp() } },
                )
            }
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize().pointerInput(open) {
                // A tap on the page, off any button, folds the island away.
                if (open) detectTapGestures { close() }
            },
            state = list,
            contentPadding = PaddingValues(bottom = listBottom),
        ) {
            item { PageHeader(if (r.hosting) "Your room" else "${r.hostName ?: "Friend"}'s room", onBack = onBack) }
            item { CodeCard(r, onInvite = { inviting = true }, onCopy = { copyCode(context, r.code) }) }
            item { NowInRoom(r) }
            if (r.holding) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = GAP)
                            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(22.dp)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Paused for you. The room keeps playing.", Modifier.weight(1f), color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Button(onClick = { Together.intercept(Together.Control.PlayPause) }) { Text("Catch up") }
                    }
                }
            }
            if (r.hosting && r.requests.isNotEmpty()) {
                item { SectionHeader("Asking to join", subtitle = "${r.requests.size} waiting") }
                items(r.requests.size, key = { "req:" + r.requests[it].id }) { i -> RequestRow(r.requests[i]) }
            }
            item { SectionHeader("Listening", subtitle = "${r.members.size} in the room") }
            item { Members(r.members, r.myId, canRemove = r.hosting, speaking = speaking) }
            item { SectionHeader("Settings") }
            item { RoomSettings(r) }
            // The chat comes last, just above the island where you write.
            item { SectionHeader("Chat") }
            if (r.chat.isEmpty()) {
                item { Text("Say hi.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(r.chat.size) { i -> ChatBubble(r.chat[i]) }
        }
        // The page fades out above the island and is still under it and the
        // dock, so they rest on something rather than hang over the chat.
        if (island) {
            val base = MaterialTheme.colorScheme.background
            val fade = 36.dp
            val solid = (fade + islandHeight / 2) / (fade + islandHeight + above)
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(fade + islandHeight + above)
                    .background(Brush.verticalGradient(0f to base.copy(alpha = 0f), solid * 0.5f to base.copy(alpha = 0.7f), solid to base, 1f to base)),
            )
        }
    }
}

/**
 * The page's padding stops a little short of the dock; the island sits
 * closer, at the dock's own spacing, so it reads as the top of that stack.
 */
private val ISLAND_DROP = 4.dp

/** The island's height at rest: its top row and the edge round it, as tall as the dock. */
private val ISLAND_ROW = 52.dp
private val ISLAND_EDGE = 6.dp
private val ISLAND_HEIGHT = ISLAND_ROW + ISLAND_EDGE * 2
private val ISLAND_SHAPE = RoundedCornerShape(32.dp)

/**
 * While the room is shown, the keyboard doesn't push the whole window up:
 * the island rides up on it instead (see [RoomView]).
 */
@Composable
private fun KeyboardResizes() {
    val window = androidx.activity.compose.LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val was = window.attributes.softInputMode
        @Suppress("DEPRECATION")
        val resize = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        window.setSoftInputMode((was and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or resize)
        onDispose { window.setSoftInputMode(was) }
    }
}

/**
 * The room's island: one glass capsule on the dock that shows the room at
 * a glance (who's in, who's talking, the call) with Talk and the call to
 * hand. Tapped, it opens out on a spring into the message box and whether
 * you hear talk; tapping its top again, Back, or a tap on the page folds it.
 */
@Composable
private fun RoomIsland(
    r: Together.Room,
    speaking: Set<String>,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    message: String,
    onMessage: (String) -> Unit,
    onSend: () -> Unit,
    typing: FocusRequester,
    still: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val live = r.phase == Together.Phase.Live
    val inCall by Together.inCall.collectAsState()
    val recording by Together.recording.collectAsState()
    // Its edge takes the colour of what's going on: red while you record, green while anyone talks.
    val edge by animateColorAsState(
        when {
            recording != null -> scheme.error
            speaking.isNotEmpty() -> SPEAKING
            else -> Color.Transparent
        },
        if (still) snap() else tween(240),
        label = "edge",
    )
    // Opened by a tap, it's to write: the box takes the keyboard once it's there.
    var wantsKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(open, wantsKeyboard) {
        if (open && wantsKeyboard) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { typing.requestFocus() }
            wantsKeyboard = false
        }
    }
    val turn by animateFloatAsState(if (open) 1f else 0f, if (still) snap() else spring(dampingRatio = 0.8f, stiffness = 420f), label = "turn")
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .glass(ISLAND_SHAPE)
            .border(1.5.dp, edge, ISLAND_SHAPE)
            .padding(ISLAND_EDGE),
    ) {
        Row(Modifier.fillMaxWidth().height(ISLAND_ROW), verticalAlignment = Alignment.CenterVertically) {
            val (title, line) = islandText(r, speaking, open, message)
            Row(
                Modifier.weight(1f).height(ISLAND_ROW).clip(CircleShape)
                    .clickable(onClickLabel = if (open) "Close" else "Open the chat") {
                        if (!open) wantsKeyboard = true
                        onOpen(!open)
                    }
                    .padding(start = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Faces(r.members, speaking)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title, Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Rounded.KeyboardArrowUp, null,
                            Modifier.size(18.dp).graphicsLayer { rotationZ = 180f * turn },
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                    AnimatedContent(
                        line,
                        transitionSpec = { if (still) fadeIn(snap()) togetherWith fadeOut(snap()) else fadeIn(tween(200, 60)) togetherWith fadeOut(tween(120)) },
                        label = "line",
                    ) {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (live) TalkButtons(inCall)
        }
        AnimatedVisibility(
            open,
            enter = if (still) EnterTransition.None else expandVertically(spring(dampingRatio = 0.82f, stiffness = 380f)) + fadeIn(tween(220, 60)),
            exit = if (still) ExitTransition.None else shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
        ) {
            Column {
                if (live) HearTalk(inCall)
                Spacer(Modifier.height(if (live) 2.dp else ISLAND_EDGE))
                MessageBox(message, onMessage, onSend, typing, voiceNotes = live)
            }
        }
    }
}

/** What the island says in its top row: the headline and a line under it. */
@Composable
private fun islandText(r: Together.Room, speaking: Set<String>, open: Boolean, message: String): Pair<String, String> {
    val inCall by Together.inCall.collectAsState()
    val callMuted by Together.callMuted.collectAsState()
    val meSpeaking by Together.speaking.collectAsState()
    val recording by Together.recording.collectAsState()
    val talkMuted by Together.talkMuted.collectAsState()
    val talkers = r.members.filter { it.id in speaking && it.id != r.myId }.map { it.name }
    val talking = when (talkers.size) {
        0 -> null
        1 -> "${talkers[0]} is talking"
        2 -> "${talkers[0]} and ${talkers[1]} are talking"
        else -> "${talkers[0]} and ${talkers.size - 1} more are talking"
    }
    val others = r.members.size - 1
    val rest = when {
        message.isNotBlank() && !open -> "Draft: $message"
        open -> if (r.phase == Together.Phase.Live) "Hold the mic to talk, or call" else "Write to the room"
        others <= 0 && r.hosting && r.phase == Together.Phase.Live -> "Share the code to bring friends in"
        else -> "Tap to chat"
    }
    return when {
        r.phase == Together.Phase.Connecting -> "Connecting…" to rest
        r.phase == Together.Phase.FindingHost -> "Looking for the host…" to rest
        r.phase == Together.Phase.Waiting -> "Waiting to be let in…" to "${r.hostName ?: "The host"} has to say yes"
        r.phase == Together.Phase.LostHost -> "Lost touch with the host" to "Still trying…"
        recording == true -> "You're talking" to "Let go to stop"
        recording == false -> "Recording a voice note" to "Let go to send it"
        inCall -> (talking ?: "In the call") to when {
            callMuted -> "You're muted"
            meSpeaking -> "You're on"
            else -> "Just talk"
        }
        talking != null -> talking to if (talkMuted) "Talk is off for you" else rest
        others <= 0 -> "Just you here" to rest
        else -> "${r.members.size} listening" to rest
    }
}

/** Up to three faces, the people talking first, then how many more. */
@Composable
private fun Faces(members: List<Member>, speaking: Set<String>) {
    val scheme = MaterialTheme.colorScheme
    val shown = members.sortedByDescending { it.id in speaking }.take(FACES)
    val more = members.size - shown.size
    // Overlapping, each cut out of the one under it by a ring of the island's colour.
    Row(horizontalArrangement = Arrangement.spacedBy((-10).dp), verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { i, m ->
            Box(
                Modifier.zIndex((FACES - i).toFloat()).size(34.dp).background(scheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Avatar(m.name, 30.dp, speaking = m.id in speaking) }
        }
        if (more > 0) {
            Box(
                Modifier.size(34.dp).background(scheme.surfaceContainerHigh, CircleShape).padding(2.dp)
                    .background(scheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("+$more", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

private const val FACES = 3

/**
 * Talk and the call, round and as tall as the island's row: call and hold
 * the mic to talk, or, in the call, mute and hang up.
 */
@Composable
private fun TalkButtons(inCall: Boolean) {
    val context = LocalContext.current
    val callMuted by Together.callMuted.collectAsState()
    val recording by Together.recording.collectAsState()
    val haptics = com.opentune.ui.components.rememberHaptics()
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && Together.startCall()) haptics.press()
    }
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (inCall) {
            FilledTonalIconButton(
                onClick = { haptics.tick(); Together.setCallMuted(!callMuted) },
                modifier = Modifier.size(ISLAND_ROW),
                colors = if (callMuted) IconButtonDefaults.filledTonalIconButtonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer)
                    else IconButtonDefaults.filledTonalIconButtonColors(containerColor = scheme.surfaceContainerHighest),
            ) {
                Icon(if (callMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (callMuted) "Unmute" else "Mute")
            }
            FilledIconButton(
                onClick = { haptics.tick(); Together.endCall() },
                modifier = Modifier.size(ISLAND_ROW),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.error, contentColor = scheme.onError),
            ) { Icon(Icons.Rounded.CallEnd, "Leave the call") }
        } else {
            FilledTonalIconButton(
                onClick = {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) ask.launch(android.Manifest.permission.RECORD_AUDIO)
                    else if (Together.startCall()) haptics.press()
                },
                modifier = Modifier.size(ISLAND_ROW),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = scheme.surfaceContainerHighest),
            ) { Icon(Icons.Rounded.Call, "Join the call") }
            val talking = recording == true
            val fill by animateColorAsState(if (talking) scheme.error else scheme.primary, label = "talk")
            HoldToRecord(
                live = true,
                Modifier.size(ISLAND_ROW).background(fill, CircleShape).semantics { contentDescription = "Hold to talk" },
            ) { held ->
                Icon(Icons.Rounded.Mic, null, Modifier.size(24.dp), tint = if (held) scheme.onError else scheme.onPrimary)
            }
        }
    }
}

/** Whether you hear talk, as a chip, with the one thing worth knowing about it. */
@Composable
private fun HearTalk(inCall: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val muted by Together.talkMuted.collectAsState()
    val haptics = com.opentune.ui.components.rememberHaptics()
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(CircleShape)
                .background(if (muted) scheme.surfaceContainerHighest else scheme.secondaryContainer)
                .toggleable(!muted, role = Role.Switch) { haptics.tick(); Together.setTalkMuted(!it) }
                .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ink = if (muted) scheme.onSurfaceVariant else scheme.onSecondaryContainer
            Icon(if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(18.dp), tint = ink)
            Spacer(Modifier.width(6.dp))
            Text("Hear talk", style = MaterialTheme.typography.labelLarge, color = ink)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (inCall) "Headphones keep the room from echoing" else "Nothing is saved",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The message box, with send once there's something to send, or the voice note mic. */
@Composable
private fun MessageBox(message: String, onChange: (String) -> Unit, onSend: () -> Unit, typing: FocusRequester, voiceNotes: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val recording by Together.recording.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            message, onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (message.isNotBlank()) onSend() }),
            modifier = Modifier.weight(1f).height(ISLAND_ROW).focusRequester(typing)
                .background(scheme.surfaceContainerHighest, CircleShape)
                .semantics { contentDescription = "Message the room" },
            decorationBox = { field ->
                Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                    if (message.isEmpty()) Text("Message the room", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    field()
                }
            },
        )
        if (message.isNotBlank()) {
            FilledIconButton(onClick = onSend, Modifier.size(ISLAND_ROW)) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
        } else if (voiceNotes) {
            val note = recording == false
            HoldToRecord(
                live = false,
                Modifier.size(ISLAND_ROW).background(if (note) scheme.error else scheme.surfaceContainerHighest, CircleShape),
            ) { held ->
                Icon(Icons.Rounded.GraphicEq,"Hold to record a voice note", Modifier.size(24.dp), tint = if (held) scheme.onError else scheme.onSurfaceVariant)
            }
        }
    }
}

/** The code, how the room is doing, the ways to bring people in, and the way out. */
@Composable
private fun CodeCard(r: Together.Room, onInvite: () -> Unit, onCopy: () -> Unit) {
    Column(
        Modifier.padding(vertical = GAP).roomCard().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ROOM CODE", style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(r.code.pretty, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        LiveStatus(r)
        if (r.phase != Together.Phase.Ended) {
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onInvite, Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Rounded.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Invite")
                }
                FilledTonalButton(onClick = onCopy, Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy code")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Right beside Invite now, so ending everyone's room asks first; leaving or closing doesn't.
        var confirmEnd by remember { mutableStateOf(false) }
        if (confirmEnd) {
            com.opentune.ui.components.FloatingDialog(
                onDismissRequest = { confirmEnd = false },
                title = { Text("End the room?") },
                text = { Text("Everyone in it stops listening together.") },
                confirmButton = {
                    com.opentune.ui.components.SheetButton("End room", tone = com.opentune.ui.components.SheetTone.Danger, onClick = { confirmEnd = false; Together.leave() })
                },
                dismissButton = { com.opentune.ui.components.SheetButton("Keep it", onClick = { confirmEnd = false }, closes = true) },
            )
        }
        TextButton(
            onClick = { if (r.hosting && r.phase != Together.Phase.Ended) confirmEnd = true else Together.leave() },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(
                when {
                    r.phase == Together.Phase.Ended -> "Close"
                    r.hosting -> "End room"
                    else -> "Leave room"
                },
            )
        }
    }
}

/**
 * Where the room stands, as a pill: a spinner while it connects or looks
 * for the host, a softly beating green dot once it's live, and how many
 * relays carry it.
 */
@Composable
private fun LiveStatus(r: Together.Room) {
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val scheme = MaterialTheme.colorScheme
    val (text, busy) = when (r.phase) {
        Together.Phase.Connecting -> "Connecting…" to true
        Together.Phase.FindingHost -> "Looking for the host…" to true
        Together.Phase.Waiting -> "Waiting for ${r.hostName ?: "the host"} to let you in…" to true
        Together.Phase.Live -> (if (r.hosting) "Live · ${r.members.size - 1} joined" else "In step with ${r.hostName ?: "the host"}") to false
        Together.Phase.LostHost -> "Lost touch with the host. Still trying…" to true
        Together.Phase.Ended -> (r.endedReason ?: "The room has ended") to false
    }
    val live = r.phase == Together.Phase.Live
    val tone = when {
        live -> LIVE
        r.phase == Together.Phase.LostHost -> scheme.error
        else -> scheme.onSurfaceVariant
    }
    val beat = if (live && !still) rememberInfiniteTransition(label = "live") else null
    val glow = beat?.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart), label = "beat")
    Row(
        Modifier.background(tone.copy(alpha = 0.12f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(12.dp), color = tone, strokeWidth = 1.5.dp)
        else {
            Box(
                Modifier.size(8.dp).drawBehind {
                    val g = glow?.value ?: return@drawBehind
                    drawCircle(tone.copy(alpha = (1f - g) * 0.5f), radius = size.minDimension / 2 * (1f + g * 1.4f))
                }.background(if (live) tone else scheme.outline, CircleShape),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text + if (r.relays > 0 && r.phase != Together.Phase.Ended) " · ${r.relays} relays" else "",
            style = MaterialTheme.typography.labelLarge,
            color = if (live) tone else scheme.onSurfaceVariant,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

private val LIVE = Color(0xFF4CAF50)

@Composable
private fun NowInRoom(r: Together.Room) {
    val t = r.track
    Column(Modifier.padding(vertical = GAP).roomCard().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(t?.thumb, Modifier.size(56.dp), RoundedCornerShape(14.dp))
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
                Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!r.hosting) {
            Text(
                "Add songs from any song's menu with Add to queue: they go to the room.",
                Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The room's switches, in one card: the host's for the room, a guest's for themselves. */
@Composable
private fun RoomSettings(r: Together.Room) {
    Column(Modifier.padding(vertical = GAP).roomCard()) {
        if (r.hosting) {
            ToggleRow(
                "Everyone can control playback", r.open, { Together.setOpen(it) },
                summary = if (r.open) "Guests can play, pause, seek and skip" else "Guests can add songs; only you play, pause and skip",
                icon = Icons.Rounded.SportsEsports,
            )
            RowDivider()
            ToggleRow(
                "Ask before letting people in", r.askToJoin, { Together.setAskToJoin(it) },
                summary = if (r.askToJoin) "People with the code wait until you let them in" else "Anyone with the code comes straight in",
                icon = Icons.Rounded.HowToReg,
            )
        } else {
            val playNow by Together.picksPlayNow.collectAsState()
            ToggleRow(
                "Picking a song plays it now", playNow, { Together.setPicksPlayNow(it) },
                summary = when {
                    !playNow -> "Off: songs you pick go into the room's queue"
                    r.open -> "On: a song you pick starts for everyone"
                    else -> "On, once ${r.hostName ?: "the host"} lets guests control playback. Until then songs go into the queue"
                },
                icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            )
        }
    }
}

@Composable
private fun Members(members: List<Member>, myId: String?, canRemove: Boolean, speaking: Set<String> = emptySet()) {
    var removing by remember { mutableStateOf<Member?>(null) }
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    // Room around each avatar for its speaking ring.
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(members, key = { it.id }) { m ->
            // Someone new pops in: a small spring up from smaller and clear.
            val shown = remember { Animatable(if (still) 1f else 0f) }
            LaunchedEffect(Unit) { if (!still) shown.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 320f)) }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .then(if (still) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                    .graphicsLayer {
                        val s = 0.6f + 0.4f * shown.value
                        scaleX = s
                        scaleY = s
                        alpha = shown.value.coerceIn(0f, 1f)
                    }
                    .widthIn(max = 76.dp).then(
                        if (canRemove && !m.host) Modifier.clickable { removing = m } else Modifier,
                    ),
            ) {
                Avatar(m.name, 56.dp, speaking = m.id in speaking)
                Spacer(Modifier.height(8.dp))
                Text(m.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                when {
                    m.host && m.id == myId -> Text("Host · You", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    m.host -> Text("Host", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    m.id == myId -> Text("You", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    removing?.let { m ->
        com.opentune.ui.components.FloatingDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${m.name}?") },
            text = { Text("They leave the room, and the room moves to a new key, so their phone can't follow along or read the chat any more.") },
            confirmButton = { com.opentune.ui.components.SheetButton("Remove", tone = com.opentune.ui.components.SheetTone.Danger, onClick = { Together.remove(m.id); removing = null }) },
            dismissButton = { com.opentune.ui.components.SheetButton("Cancel", onClick = { removing = null }, closes = true) },
        )
    }
}

/**
 * A member's initial in their colour. While they're [speaking], a green
 * ring sits around it, and a second one keeps spreading out from it and
 * fading, like sound.
 */
@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp, speaking: Boolean = false) {
    val shown by animateFloatAsState(if (speaking) 1f else 0f, tween(220), label = "speaking")
    val wave = rememberInfiniteTransition(label = "speakingWave")
    val spread by wave.animateFloat(0f, 1f, infiniteRepeatable(tween(1_100), RepeatMode.Restart), label = "spread")
    Box(
        Modifier.size(size)
            .drawBehind {
                if (shown <= 0f) return@drawBehind
                val ring = SPEAKING
                val base = this.size.minDimension / 2 + 4.dp.toPx()
                drawCircle(ring.copy(alpha = shown), radius = base, style = Stroke(3.dp.toPx()))
                drawCircle(ring.copy(alpha = shown * (1f - spread) * 0.7f), radius = base + spread * 9.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
            .background(avatarColor(name), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(name.take(1).uppercase(), fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B0B10)) }
}

private val SPEAKING = Color(0xFF4CD964)

/** Someone who knocked: let them in or turn them away. */
@Composable
private fun RequestRow(m: Member) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(m.name, 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedButton(onClick = { Together.reject(m.id) }) { Text("Reject") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { Together.approve(m.id) }) { Text("Let in") }
    }
}

/**
 * Records while held: [live] talk, or a voice note sent when let go. Asks
 * for the microphone the first time.
 */
@Composable
private fun HoldToRecord(live: Boolean, modifier: Modifier, content: @Composable (recording: Boolean) -> Unit) {
    val context = LocalContext.current
    val recording by Together.recording.collectAsState()
    val haptics = com.opentune.ui.components.rememberHaptics()
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) com.opentune.data.Pills.info("Now hold to record", context)
    }
    Box(
        modifier.pointerInput(live) {
            detectTapGestures(onPress = {
                // Only a real hold starts it: a brush, or a touch that turns into a
                // scroll, lets go (or is taken by the scroll) before this runs out.
                val letGo = kotlinx.coroutines.withTimeoutOrNull(if (live) TALK_HOLD_MS else NOTE_HOLD_MS) { tryAwaitRelease() }
                if (letGo != null) {
                    if (letGo) com.opentune.data.Pills.info(if (live) "Hold to talk" else "Hold to record a voice note", context)
                    return@detectTapGestures
                }
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) { ask.launch(android.Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                if (!Together.startVoice(live)) return@detectTapGestures
                haptics.press()
                tryAwaitRelease()
                Together.stopVoice()
                haptics.tick()
            })
        },
        contentAlignment = Alignment.Center,
    ) { content(recording == live) }
}

/** How long the Talk button, and the voice note mic, have to be held before they record. */
private const val TALK_HOLD_MS = 450L
private const val NOTE_HOLD_MS = 300L

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
    val time = remember(line.at) { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(line.at)) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = if (line.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!line.mine) { Avatar(line.name, 28.dp); Spacer(Modifier.width(8.dp)) }
        Column(
            Modifier.widthIn(max = 290.dp)
                .background(
                    if (line.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(20.dp),
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            if (!line.mine) Text(line.name, style = MaterialTheme.typography.labelMedium, color = avatarColor(line.name))
            val clip = line.voice
            if (clip != null) VoiceBubble(clip) else Text(line.text, style = MaterialTheme.typography.bodyLarge)
            Text(
                time,
                Modifier.align(Alignment.End),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

/** A voice note or a piece of talk: play or stop, and how long it is. */
@Composable
private fun VoiceBubble(clip: Together.VoiceClip) {
    val playing by Together.playingClip.collectAsState()
    val isPlaying = playing == clip.id
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Together.playClip(clip) }, enabled = clip.cfg != null && clip.frames.isNotEmpty()) {
            Icon(if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (isPlaying) "Stop" else "Play")
        }
        Column {
            Text(
                when {
                    clip.live && !clip.complete -> "Talking…"
                    clip.live -> "Talk"
                    else -> "Voice note"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            val sec = clip.ms / 1000
            Text("%d:%02d".format(sec / 60, sec % 60), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
