package com.opentune.ui.tv

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.opentune.ui.ChromeTab
import com.opentune.ui.components.FocusRing

internal enum class TvMediaAction { TOGGLE, PLAY, PAUSE, NEXT, PREVIOUS, FORWARD, REWIND, STOP }
internal fun tvMediaAction(code: Int): TvMediaAction? = when (code) {
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> TvMediaAction.TOGGLE
    KeyEvent.KEYCODE_MEDIA_PLAY -> TvMediaAction.PLAY
    KeyEvent.KEYCODE_MEDIA_PAUSE -> TvMediaAction.PAUSE
    KeyEvent.KEYCODE_MEDIA_NEXT -> TvMediaAction.NEXT
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> TvMediaAction.PREVIOUS
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> TvMediaAction.FORWARD
    KeyEvent.KEYCODE_MEDIA_REWIND -> TvMediaAction.REWIND
    KeyEvent.KEYCODE_MEDIA_STOP -> TvMediaAction.STOP
    else -> null
}

/** Let ordinary D-pad/OK/Back reach Compose. Consume media keys once in the foreground;
 * the MediaSession continues handling them when the activity is in the background. */
internal fun Modifier.tvMediaKeys(onAction: (TvMediaAction) -> Unit): Modifier = composed {
    val action by rememberUpdatedState(onAction)
    if (!LocalIsTv.current) this else onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        val command = tvMediaAction(key.keyCode)
        if (command == null) false else {
            if (key.action == KeyEvent.ACTION_DOWN &&
                (key.repeatCount == 0 || command == TvMediaAction.FORWARD || command == TvMediaAction.REWIND)) action(command)
            true
        }
    }
}

/** First focus on launch; returning from another window preserves the existing focus. */
@Composable
internal fun TvRemoteSurface(content: @Composable () -> Unit) {
    val requester = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        input.requestInputMode(InputMode.Keyboard)
        withFrameNanos { }
        requester.requestFocus()
    }
    Box(Modifier.fillMaxSize().focusRequester(requester).focusRestorer().focusGroup()) { content() }
}

/** Set when the rail opened a page: the remote's focus stays on the rail then. */
internal object TvFocus {
    @Volatile var fromRail = false
}

/**
 * A page that, on a TV, takes the remote's focus back when it's come back to: it goes
 * when another page opens over it, and its focus fell to the rail's first button. A page
 * restored from its saved state is one come back to (Back, or a pop); one the rail opened
 * leaves the focus on the rail.
 */
@Composable
internal fun TvPage(content: @Composable () -> Unit) {
    if (!LocalIsTv.current) { content(); return }
    val requester = remember { FocusRequester() }
    var visited by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().focusRequester(requester).focusRestorer().focusGroup()) { content() }
    LaunchedEffect(Unit) {
        // A few frames: the page's rows are laid out and its scroll put back. The focus goes to
        // the first row in view: the one that opened the page went with the page when it left.
        repeat(3) { withFrameNanos { } }
        if (visited && !TvFocus.fromRail) runCatching { requester.requestFocus() }
        TvFocus.fromRail = false
        visited = true
    }
}

internal val TV_RAIL_GUTTER = 144.dp

/** A stable, labeled destination for every remote user, even with phone chrome settings saved. */
@Composable
internal fun TvNavRail(
    tabs: List<ChromeTab>, selected: Int?, onSelect: (Int) -> Unit,
    onSearch: () -> Unit, onSettings: () -> Unit, onPlayer: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(TV_RAIL_GUTTER).fillMaxHeight().padding(end = 16.dp)
        .focusRestorer().focusGroup().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("OpenTune", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
        TvDestination("Search", Icons.Rounded.Search, false, onSearch)
        tabs.forEachIndexed { i, tab -> TvDestination(tab.label, tab.icon, i == selected) { onSelect(i) } }
        if (onPlayer != null) TvDestination("Player", Icons.Rounded.PlayArrow, false, onPlayer)
        TvDestination("Settings", Icons.Rounded.Settings, false, onSettings)
    }
}

@Composable
private fun TvDestination(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, click: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Surface(shape = shape, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().clip(shape).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = FocusRing(shape), role = Role.Button,
            onClick = { TvFocus.fromRail = true; click() },
        ).heightIn(min = 52.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
