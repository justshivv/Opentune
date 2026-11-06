package com.opentune.ui.remote

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentune.data.Pills
import com.opentune.data.remote.PairedTv
import com.opentune.data.remote.RemoteClient
import com.opentune.data.remote.RemoteKey
import com.opentune.data.remote.RemoteMessage
import com.opentune.data.settings.AppSettings
import com.opentune.ui.account.QrScannerDialog
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone as the TV's remote. Pick a TV that has been paired (or pair one by scanning the
 * code it shows), and the phone becomes a trackpad that moves a cursor on the TV's screen, or,
 * switched over, a pad of buttons: arrows, OK, Back, Home, media and volume.
 */
@Composable
fun TvRemoteScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    vm: TvRemoteViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val paired by vm.paired.collectAsState()
    var triedAuto by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.look() }
    DisposableEffect(Unit) { onDispose { vm.stopLooking(); vm.send(RemoteMessage.Cursor(false)); vm.disconnect() } }
    // With a TV already paired, open straight onto it.
    LaunchedEffect(paired.size) {
        if (!triedAuto && paired.isNotEmpty() && state is RemoteClient.State.Idle) { triedAuto = true; vm.connect(paired.first()) }
    }

    AnimatedContent(
        targetState = state is RemoteClient.State.Connected,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "remoteState",
    ) { connected ->
        if (connected) {
            val tvName = (state as? RemoteClient.State.Connected)?.tv ?: ""
            val now by vm.now.collectAsState()
            RemotePad(tvName, now, vm::send, contentPadding, onLeave = { vm.disconnect() })
        }
        else TvPicker(vm, contentPadding, onBack)
    }
}

// ---- Choosing a TV ----

@Composable
private fun TvPicker(vm: TvRemoteViewModel, contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val paired by vm.paired.collectAsState()
    val found by vm.found.collectAsState()
    val pairing by vm.pairing.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<PairedTv?>(null) }

    if (scanning) QrScannerDialog(onDismiss = { scanning = false }) { text ->
        scanning = false
        if (!vm.pair(text)) Pills.error("That isn't a code from OpenTune on a TV", context)
    }
    forgetting?.let { tv ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${tv.name}?") },
            text = { Text("This phone stops being able to control it until you pair again.") },
            confirmButton = { TextButton(onClick = { vm.forget(tv); forgetting = null }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Control the TV", onBack = onBack) }
        item {
            Text(
                "Use this phone as a trackpad and remote for OpenTune on your TV. Both need to be on the same Wi-Fi, with OpenTune open on the TV.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            )
        }
        (state as? RemoteClient.State.Failed)?.let { failed ->
            item { Notice(failed.reason, isError = true) }
        }
        (pairing as? TvRemoteViewModel.Pairing.Failed)?.let { failed ->
            item { Notice(failed.reason, isError = true, onDismiss = vm::dismissPairing) }
        }
        if (pairing is TvRemoteViewModel.Pairing.Working) item { Notice("Pairing with the TV…", working = true) }
        if (state is RemoteClient.State.Connecting) item { Notice("Connecting to ${(state as RemoteClient.State.Connecting).tv}…", working = true) }

        if (paired.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pair your TV once", style = MaterialTheme.typography.titleMedium)
                    Text("1. On the TV, open OpenTune, then Settings, then Pair a phone.", style = MaterialTheme.typography.bodyMedium)
                    Text("2. Scan the code it shows.", style = MaterialTheme.typography.bodyMedium)
                    Text("After that this phone finds the TV by itself.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(paired, key = { it.tvId }) { tv ->
                val online = found.any { it.tvId == tv.tvId }
                TvRow(tv, online, onClick = { vm.connect(tv) }, onForget = { forgetting = tv })
            }
        }
        item {
            Row(Modifier.padding(20.dp)) {
                BigButton(if (paired.isEmpty()) "Scan the code on the TV" else "Pair another TV", Icons.Rounded.QrCodeScanner, onClick = { scanning = true })
            }
        }
    }
}

@Composable
private fun Notice(text: String, isError: Boolean = false, working: Boolean = false, onDismiss: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp).also { Spacer(Modifier.width(12.dp)) }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
        if (onDismiss != null) Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(20.dp).clickable(onClick = onDismiss))
    }
}

@Composable
private fun TvRow(tv: PairedTv, online: Boolean, onClick: () -> Unit, onForget: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Tv, null, Modifier.size(28.dp), tint = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(tv.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (online) "On this network" else "Not seen yet. Open OpenTune on the TV",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onForget) { Text("Forget") }
    }
}

@Composable
private fun BigButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
    }
}

// ---- The remote ----

@Composable
internal fun RemotePad(tvName: String, now: RemoteMessage.Now?, send: (RemoteMessage) -> Unit, contentPadding: PaddingValues, onLeave: () -> Unit) {
    val ui by AppSettings.ui.collectAsState()
    val haptics = rememberHaptics()
    var trackpad by rememberSaveable { mutableStateOf(ui.remoteStartOnTrackpad) }
    var typing by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val volumeKeys by rememberUpdatedState(ui.remoteVolumeKeys)

    // The cursor is there for the trackpad, and out of the way for the buttons.
    LaunchedEffect(trackpad) { send(RemoteMessage.Cursor(trackpad)); AppSettings.updateUi { it.copy(remoteStartOnTrackpad = trackpad) } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    if (typing) TypeToTv(onSend = { text -> send(RemoteMessage.Text(text)); typing = false }, onDismiss = { typing = false })
    if (options) RemoteOptions(onDismiss = { options = false })
    if (help) GestureHelp(onDismiss = { help = false })

    Column(
        Modifier.fillMaxSize().padding(contentPadding).focusRequester(focus).focusable()
            // The phone's own volume buttons turn the TV's volume while the remote is open.
            .onPreviewKeyEvent { e ->
                if (!volumeKeys || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.VolumeUp -> { send(RemoteMessage.Key(RemoteKey.VOLUME_UP)); true }
                    Key.VolumeDown -> { send(RemoteMessage.Key(RemoteKey.VOLUME_DOWN)); true }
                    else -> false
                }
            },
    ) {
        PageHeader(
            tvName, onBack = onLeave,
            actions = {
                Icon(Icons.Rounded.HelpOutline, "Gestures", Modifier.size(44.dp).clip(CircleShape).clickable { help = true }.padding(10.dp))
                Icon(Icons.Rounded.Tune, "Remote settings", Modifier.size(44.dp).clip(CircleShape).clickable { options = true }.padding(10.dp))
            },
        )
        NowStrip(now)
        ModeSwitch(trackpad, onChange = { trackpad = it; haptics.tick() })
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (trackpad) {
                val config = TrackpadConfig(ui.remotePointerSpeed, ui.remoteNaturalScroll, ui.remoteTapToClick)
                Trackpad(send, config, haptics = { feel ->
                    when (feel) {
                        TrackpadEngine.Feel.GRAB -> haptics.press()
                        else -> haptics.tick()
                    }
                })
            } else ButtonPad(send, haptics = { haptics.tick() })
        }
        BottomBar(send, playing = now?.playing == true, onKeyboard = { typing = true }, tick = { haptics.tick() })
    }
}

@Composable
private fun NowStrip(now: RemoteMessage.Now?) {
    val received = remember(now) { SystemClock.elapsedRealtime() }
    var tick by remember { mutableLongStateOf(received) }
    LaunchedEffect(now?.playing) { while (now?.playing == true) { tick = SystemClock.elapsedRealtime(); delay(250) } }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(now?.artworkUrl, Modifier.size(44.dp), RoundedCornerShape(8.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(now?.title ?: "Nothing playing", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(now?.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null && now.durationMs > 0) {
                // Moves on between the TV's updates, so it doesn't jerk once a second.
                val position = now.positionMs + if (now.playing) (tick - received).coerceAtLeast(0) else 0
                LinearProgressIndicator(
                    progress = { (position.toFloat() / now.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                )
            }
        }
    }
}

@Composable
private fun ModeSwitch(trackpad: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
    ) {
        for ((label, isTrackpad) in listOf("Trackpad" to true, "Buttons" to false)) {
            val on = trackpad == isTrackpad
            Box(
                Modifier.weight(1f).clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onChange(isTrackpad) }.padding(vertical = 10.dp)
                    .semantics { contentDescription = "$label mode" },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- The trackpad ----

@Composable
private fun Trackpad(send: (RemoteMessage) -> Unit, config: TrackpadConfig, haptics: (TrackpadEngine.Feel) -> Unit) {
    val density = LocalDensity.current.density
    val latestConfig by rememberUpdatedState(config)
    val latestHaptics by rememberUpdatedState(haptics)
    val engine = remember { TrackpadEngine(send, { latestConfig }, { latestHaptics(it) }) }
    // Where each finger is, drawn as a soft dot, so the pad answers the touch.
    val fingers = remember { mutableStateListOf<Offset>() }
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(32.dp))
            .semantics { contentDescription = "Trackpad" }
            .pointerInput(engine) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var last = down.uptimeMillis
                    engine.onEvent(SystemClock.uptimeMillis(), 1, 0f, 0f, 8)
                    fingers.clear(); fingers += down.position
                    while (true) {
                        // Wake up in time for a long press even if no finger moves.
                        val wait = engine.nextTickIn(SystemClock.uptimeMillis()) ?: Long.MAX_VALUE
                        val event = withTimeoutOrNull(minOf(wait, 5_000L)) { awaitPointerEvent() }
                        val now = SystemClock.uptimeMillis()
                        if (event == null) { engine.onTick(now); continue }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        var dx = 0f; var dy = 0f
                        pressed.forEach { dx += it.positionChange().x; dy += it.positionChange().y }
                        val t = event.changes.first().uptimeMillis
                        engine.onEvent(now, pressed.size, dx / pressed.size / density, dy / pressed.size / density, (t - last).coerceAtLeast(1))
                        last = t
                        fingers.clear(); fingers += pressed.map { it.position }
                        event.changes.forEach { it.consume() }
                    }
                    engine.onRelease(SystemClock.uptimeMillis())
                    fingers.clear()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (fingers.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text("Slide to move the cursor", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tap to click · hold and slide to drag · two fingers to scroll",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        val dot = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            fingers.forEach { p -> drawCircle(dot, radius = 28.dp.toPx(), center = p) }
        }
    }
}

// ---- The buttons ----

@Composable
private fun ButtonPad(send: (RemoteMessage) -> Unit, haptics: () -> Unit) {
    fun key(k: RemoteKey, repeat: Boolean = false) = send(RemoteMessage.Key(k, repeat))
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        DPad(
            onKey = { k, repeat -> if (!repeat) haptics(); key(k, repeat) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PadButton(Icons.Rounded.SkipPrevious, "Previous") { haptics(); key(RemoteKey.PREVIOUS) }
            PadButton(Icons.Rounded.FastRewind, "Back 10 seconds", repeating = { r -> key(RemoteKey.REWIND, r) }) { }
            PadButton(Icons.Rounded.FastForward, "Forward 10 seconds", repeating = { r -> key(RemoteKey.FORWARD, r) }) { }
            PadButton(Icons.Rounded.SkipNext, "Next") { haptics(); key(RemoteKey.NEXT) }
        }
    }
}

@Composable
private fun DPad(onKey: (RemoteKey, Boolean) -> Unit) {
    val ring = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(Modifier.size(248.dp).clip(CircleShape).background(ring), contentAlignment = Alignment.Center) {
        PadButton(Icons.Rounded.KeyboardArrowUp, "Up", Modifier.align(Alignment.TopCenter).padding(top = 8.dp), plain = true, repeating = { r -> onKey(RemoteKey.UP, r) }) {}
        PadButton(Icons.Rounded.KeyboardArrowDown, "Down", Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), plain = true, repeating = { r -> onKey(RemoteKey.DOWN, r) }) {}
        PadButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Left", Modifier.align(Alignment.CenterStart).padding(start = 8.dp), plain = true, repeating = { r -> onKey(RemoteKey.LEFT, r) }) {}
        PadButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Right", Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), plain = true, repeating = { r -> onKey(RemoteKey.RIGHT, r) }) {}
        Box(
            Modifier.size(92.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { onKey(RemoteKey.OK, false) }
                .semantics { contentDescription = "OK" },
            contentAlignment = Alignment.Center,
        ) { Text("OK", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary) }
    }
}

/**
 * A round button. With [repeating], holding it keeps pressing: once at once, then, after a
 * short wait, over and over (marked as a repeat) until the finger lifts.
 */
@Composable
private fun PadButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    plain: Boolean = false,
    size: androidx.compose.ui.unit.Dp = if (plain) 64.dp else 58.dp,
    repeating: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(
        modifier.size(size).clip(CircleShape)
            .background(if (plain) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = description }
            .then(
                if (repeating == null) Modifier.clickable(onClick = onClick)
                else Modifier.pointerInput(repeating) {
                    detectTapGestures(onPress = {
                        repeating(false)
                        val job = scope.launch { delay(HOLD_BEFORE_REPEAT_MS); while (true) { repeating(true); delay(REPEAT_EVERY_MS) } }
                        tryAwaitRelease()
                        job.cancel()
                    })
                },
            ),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(30.dp)) }
}

private const val HOLD_BEFORE_REPEAT_MS = 380L
private const val REPEAT_EVERY_MS = 110L

// ---- Always there ----

@Composable
private fun BottomBar(send: (RemoteMessage) -> Unit, playing: Boolean, onKeyboard: () -> Unit, tick: () -> Unit) {
    fun key(k: RemoteKey) { tick(); send(RemoteMessage.Key(k)) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        PadButton(Icons.AutoMirrored.Rounded.ArrowBack, "TV back", size = 50.dp) { key(RemoteKey.BACK) }
        PadButton(Icons.Rounded.Home, "TV home", size = 50.dp) { key(RemoteKey.HOME) }
        PadButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", size = 50.dp) { key(RemoteKey.PLAY_PAUSE) }
        PadButton(Icons.Rounded.VolumeDown, "Volume down", size = 50.dp, repeating = { r -> send(RemoteMessage.Key(RemoteKey.VOLUME_DOWN, r)) }) {}
        PadButton(Icons.Rounded.VolumeUp, "Volume up", size = 50.dp, repeating = { r -> send(RemoteMessage.Key(RemoteKey.VOLUME_UP, r)) }) {}
        PadButton(Icons.Rounded.VolumeOff, "Mute", size = 50.dp) { key(RemoteKey.MUTE) }
        PadButton(Icons.Rounded.Keyboard, "Type", size = 50.dp) { tick(); onKeyboard() }
    }
}

// ---- Dialogs ----

@Composable
internal fun TypeToTv(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search on the TV") },
        text = {
            OutlinedTextField(
                text, { text = it.take(200) }, singleLine = true, placeholder = { Text("A song, album or artist") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSend(text.trim()) }) { Text("Search") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RemoteOptions(onDismiss: () -> Unit) {
    val ui by AppSettings.ui.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remote settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    Text("Cursor speed", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = ui.remotePointerSpeed, onValueChange = { v -> AppSettings.updateUi { it.copy(remotePointerSpeed = v) } },
                        valueRange = 0.5f..2.5f,
                    )
                }
                OptionSwitch("Natural scrolling", "Two fingers drag the page along with them", ui.remoteNaturalScroll) { v -> AppSettings.updateUi { it.copy(remoteNaturalScroll = v) } }
                OptionSwitch("Tap to click", "A quick tap on the trackpad clicks", ui.remoteTapToClick) { v -> AppSettings.updateUi { it.copy(remoteTapToClick = v) } }
                OptionSwitch("Volume buttons", "This phone's volume keys change the TV's volume", ui.remoteVolumeKeys) { v -> AppSettings.updateUi { it.copy(remoteVolumeKeys = v) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun OptionSwitch(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, onChange)
    }
}

@Composable
private fun GestureHelp(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trackpad gestures") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((gesture, does) in listOf(
                    "Slide one finger" to "Moves the cursor. Faster slides go farther.",
                    "Tap" to "Clicks.",
                    "Hold, then slide" to "Drags, for the seek bar or a long list.",
                    "Slide two fingers" to "Scrolls under the cursor.",
                    "Tap with two fingers" to "Back.",
                    "Tap with three fingers" to "Home.",
                )) Column {
                    Text(gesture, style = MaterialTheme.typography.titleSmall)
                    Text(does, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}
