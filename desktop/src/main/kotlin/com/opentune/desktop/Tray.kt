package com.opentune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** OpenTune in the system tray: what's playing, and play, pause and skip without the window. */
internal object TrayPrefs {
    val supported: Boolean by lazy { runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false) }

    private val prefs by lazy { appContext.getSharedPreferences("tray", android.content.Context.MODE_PRIVATE) }
    private val _keepPlaying = MutableStateFlow(runCatching { prefs.getBoolean("keep_playing", true) }.getOrDefault(true))
    /** Closing the window leaves the music playing in the tray. */
    val keepPlaying: StateFlow<Boolean> = _keepPlaying.asStateFlow()

    fun setKeepPlaying(on: Boolean) {
        _keepPlaying.value = on
        runCatching { prefs.edit().putBoolean("keep_playing", on).apply() }
    }
}

@Composable
internal fun ApplicationScope.TrayMenu(onShow: () -> Unit, onQuit: () -> Unit) {
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val playing by Player.playing.collectAsState()
    val song = queue.getOrNull(index)
    Tray(
        icon = rememberLogo(Logo.APP),
        tooltip = song?.let { "OpenTune · ${it.title} · ${it.artist}" } ?: "OpenTune",
        onAction = onShow,
        menu = {
            song?.let { Item("${it.title} · ${it.artist}", enabled = false, onClick = {}) }
            Item(if (playing) "Pause" else "Play", enabled = song != null, onClick = Player::toggle)
            Item("Next", enabled = song != null, onClick = Player::next)
            Item("Previous", enabled = song != null, onClick = Player::previous)
            Separator()
            Item("Show OpenTune", onClick = onShow)
            Item("Quit", onClick = onQuit)
        },
    )
}
