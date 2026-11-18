package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
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

/** The shortcuts, listed. */
@Composable
internal fun ShortcutsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Keyboard shortcuts") },
        text = {
            Column(Modifier.widthIn(min = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Shortcuts.help.forEach { (keys, what) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(what, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            keys, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        },
    )
}
