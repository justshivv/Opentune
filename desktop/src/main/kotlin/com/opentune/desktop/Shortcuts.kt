package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The keyboard, as the web player takes it. The window hears a key only when nothing
 * focused used it first, so typing in the search box never plays or skips.
 */
internal object Shortcuts {
    /** What the window itself does for a key: open a page or a panel, close the player, show help. */
    enum class Command { SEARCH, LYRICS, QUEUE, CLOSE, HELP, SETTINGS }

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    /** Counts asks to put the cursor in the search box, so an open search page takes it again. */
    private val _searchFocus = MutableStateFlow(0)
    val searchFocus: StateFlow<Int> = _searchFocus.asStateFlow()

    /** The keys and what they do, for the help dialog. */
    val help: List<Pair<String, String>> by lazy {
        val mod = if (DesktopWindow.isMac) "⌘" else "Ctrl"
        listOf(
            "Space" to "Play or pause",
            "$mod →" to "Next song",
            "$mod ←" to "Previous song",
            "Shift →" to "10 seconds on",
            "Shift ←" to "10 seconds back",
            "$mod ↑" to "Volume up",
            "$mod ↓" to "Volume down",
            "M" to "Mute",
            "S" to "Shuffle",
            "R" to "Repeat",
            "L" to "Lyrics",
            "Q" to "Up next",
            "/  or  $mod F" to "Search",
            "$mod ," to "Settings",
            "Esc" to "Close the full player",
            "?" to "These shortcuts",
        )
    }

    /** Handles [e] when it's one of the shortcuts; true when it was. */
    fun handle(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val mod = if (DesktopWindow.isMac) e.isMetaPressed else e.isCtrlPressed
        val plain = !e.isCtrlPressed && !e.isMetaPressed && !e.isAltPressed
        when {
            mod && e.key == Key.DirectionRight -> Player.next()
            mod && e.key == Key.DirectionLeft -> Player.previous()
            mod && e.key == Key.DirectionUp -> Player.setVolume(Player.volume.value + 0.05)
            mod && e.key == Key.DirectionDown -> Player.setVolume(Player.volume.value - 0.05)
            mod && e.key == Key.F -> search()
            mod && e.key == Key.Comma -> _commands.tryEmit(Command.SETTINGS)
            !plain -> return false
            e.isShiftPressed && e.key == Key.DirectionRight -> Player.seekBy(10_000)
            e.isShiftPressed && e.key == Key.DirectionLeft -> Player.seekBy(-10_000)
            e.isShiftPressed && e.key == Key.Slash -> _commands.tryEmit(Command.HELP)
            e.isShiftPressed -> return false
            e.key == Key.Spacebar -> Player.toggle()
            e.key == Key.M -> Player.toggleMute()
            e.key == Key.S -> Player.toggleShuffle()
            e.key == Key.R -> Player.cycleRepeat()
            e.key == Key.L -> _commands.tryEmit(Command.LYRICS)
            e.key == Key.Q -> _commands.tryEmit(Command.QUEUE)
            e.key == Key.Slash -> search()
            e.key == Key.Escape -> _commands.tryEmit(Command.CLOSE)
            else -> return false
        }
        return true
    }

    private fun search() {
        _searchFocus.value++
        _commands.tryEmit(Command.SEARCH)
    }
}

/**
 * The shortcuts, listed as the web player's "?" panel lists them (ShortcutsHelp.tsx): a 420px
 * card with 14px corners on the page's colour, its title in 22px bold with a round close button,
 * then each shortcut on a row of its own, hairlines between, its keys as small grey caps. It
 * fades and grows in from 0.97, as the web's does; Escape or a click outside closes it.
 */
@Composable
internal fun ShortcutsDialog(onDismiss: () -> Unit) {
    val still = stillMotion()
    val shown = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier.graphicsLayer {
                alpha = (shown.value / 0.75f).coerceAtMost(1f)
                val grow = 0.97f + 0.03f * shown.value
                scaleX = grow; scaleY = grow
            }
                .padding(16.dp)
                .widthIn(max = 420.dp).fillMaxWidth()
                .heightIn(max = 640.dp)
                .shadow(28.dp, shape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
                .clip(shape).background(MaterialTheme.colorScheme.background)
                .semantics { contentDescription = "Keyboard shortcuts" },
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Keyboard Shortcuts", style = WebType.title2, modifier = Modifier.weight(1f).semantics { heading() })
                CircleButton(Icons.Rounded.Close, "Close", size = 30.dp, iconSize = 16.dp, ring = false, onClick = onDismiss)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                val divider = WebInk.divider
                Shortcuts.help.forEachIndexed { i, (keys, what) ->
                    val last = i == Shortcuts.help.lastIndex
                    Row(
                        Modifier.fillMaxWidth()
                            .drawBehind { if (!last) drawLine(divider, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx()) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(what, style = WebType.body, color = WebInk.primary, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            keyCaps(keys).forEach { k ->
                                if (k == "or") Text("or", style = WebType.callout, color = WebInk.secondary, modifier = Modifier.padding(horizontal = 2.dp))
                                else Text(
                                    k, style = WebType.calloutEmph, color = WebInk.secondary, textAlign = TextAlign.Center,
                                    modifier = Modifier.widthIn(min = 24.dp).clip(RoundedCornerShape(6.dp)).background(WebInk.quaternary)
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Ctrl →" as its keys, ["Ctrl", "→"], and "/  or  Ctrl F" as ["/", "or", "Ctrl", "F"]. */
private fun keyCaps(keys: String): List<String> = keys.split(' ').filter { it.isNotBlank() }
