package com.opentune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.data.model.Song

/**
 * Songs picked in a list, to act on together, as the phone's long press picks them
 * (app/.../components/SongSelection.kt). On the desktop a row is picked by its box (shown under
 * the pointer, and on every row once anything is picked), by Ctrl or ⌘ and a click, or a run of
 * rows by Shift and a click; Ctrl+A picks the whole list and Esc lets go.
 *
 * A song is picked by its id, so it stays picked as a list is narrowed by its search field. The
 * rows of each list on show join the selection as they're drawn ([attach]), so it knows the list
 * that Select all and a Shift run go by: the one last clicked in, or else the last to show.
 */
@Stable
internal class SongSelection {
    /** The songs picked, by id. */
    val picked = mutableStateMapOf<String, Song>()

    /** Where the last click or toggle was, by id: one end of a Shift run. */
    var anchor: String? = null
        private set

    /** What a picked row can be taken out of, on your own playlist's page; null elsewhere. */
    var remover: ((List<Song>) -> Unit)? by mutableStateOf(null)
    var removeLabel by mutableStateOf("Remove from this playlist")

    /** The selection of the queue, whose bar moves and removes songs rather than queueing them. */
    var forQueue = false

    private class Shown(val songs: List<Song>, var rows: Int)
    private val shown = ArrayList<Shown>()
    private var focus: List<Song>? = null
    private var lists by mutableIntStateOf(0)

    val active: Boolean get() = picked.isNotEmpty()
    val count: Int get() = picked.size

    /** The list the selection goes by now. */
    val list: List<Song>
        get() {
            lists // read, so a change of lists is seen
            val f = focus
            if (f != null && shown.any { it.songs === f }) return f
            return shown.lastOrNull()?.songs ?: f ?: emptyList()
        }

    /** A row of [songs] is on show. */
    fun attach(songs: List<Song>) {
        val at = shown.firstOrNull { it.songs === songs }
        if (at != null) { at.rows++; return }
        shown += Shown(songs, 1)
        lists++
    }

    /** A row of [songs] went out of sight. */
    fun detach(songs: List<Song>) {
        val at = shown.firstOrNull { it.songs === songs } ?: return
        if (--at.rows <= 0) {
            shown.remove(at)
            lists++
        }
    }

    fun isPicked(song: Song): Boolean = picked.containsKey(song.videoId)

    /** Picks or lets go the song at [i] of [songs]. */
    fun toggle(songs: List<Song>, i: Int) {
        val song = songs.getOrNull(i) ?: return
        focus = songs
        if (picked.remove(song.videoId) == null) picked[song.videoId] = song
        anchor = song.videoId
    }

    /** Picks every song from the anchor to [i] of [songs], as Shift and a click does; with no anchor in [songs], just that one. */
    fun range(songs: List<Song>, i: Int) {
        if (i !in songs.indices) return
        focus = songs
        val from = anchor?.let { id -> songs.indexOfFirst { it.videoId == id } }?.takeIf { it >= 0 }
        if (from == null) {
            picked[songs[i].videoId] = songs[i]
            anchor = songs[i].videoId
            return
        }
        for (k in minOf(from, i)..maxOf(from, i)) picked[songs[k].videoId] = songs[k]
    }

    /** Picks every song of [songs] (the list the selection goes by, unless named). */
    fun selectAll(songs: List<Song> = list) {
        if (songs.isEmpty()) return
        focus = songs
        songs.forEach { picked[it.videoId] = it }
    }

    /** Whether every song of the list is picked. */
    val all: Boolean get() = list.let { l -> l.isNotEmpty() && l.all { picked.containsKey(it.videoId) } }

    fun clear() {
        picked.clear()
        anchor = null
    }

    /**
     * A click on the row at [i] of [songs]: Shift picks a run, Ctrl or ⌘ one more, and once
     * anything is picked a plain click picks or lets go too. False when it's an ordinary click,
     * which plays.
     */
    fun click(songs: List<Song>, i: Int, ctrl: Boolean, shift: Boolean): Boolean {
        when {
            shift -> range(songs, i)
            ctrl || active -> toggle(songs, i)
            else -> return false
        }
        return true
    }

    /** The songs picked, in the list's order, then any picked in a list no longer shown. */
    fun inOrder(): List<Song> {
        val l = list
        val ordered = l.filter { picked.containsKey(it.videoId) }.distinctBy { it.videoId }
        val seen = ordered.mapTo(HashSet()) { it.videoId }
        return ordered + picked.values.filter { it.videoId !in seen }
    }

    /** Where the picked songs are in [songs]. */
    fun indicesIn(songs: List<Song>): List<Int> = songs.indices.filter { picked.containsKey(songs[it].videoId) }
}

/** Every page's selection: one at a time is shown, and going to another page lets it go. */
internal val PageSelection = SongSelection()

/** The selections the keyboard can reach: the page's and any of a panel's of its own (the queue's). */
internal object Selections {
    private val all = mutableListOf(PageSelection)
    /** The one clicked in last, for Ctrl+A. */
    var focused: SongSelection = PageSelection

    fun add(s: SongSelection) { if (s !in all) all += s }
    fun remove(s: SongSelection) { all -= s; if (focused === s) focused = PageSelection }

    /** Esc: lets go whatever's picked; true when something was. */
    fun clearAll(): Boolean {
        val any = all.any { it.active }
        all.forEach { it.clear() }
        return any
    }

    /** Ctrl+A: the whole list picked; true when there was a list to pick from. */
    fun selectAll(): Boolean {
        val s = focused.takeIf { it.list.isNotEmpty() } ?: all.firstOrNull { it.list.isNotEmpty() } ?: return false
        s.selectAll()
        return true
    }
}

/** The selection the song rows here join; a list with its own (the queue) provides it. */
internal val LocalSongSelection = compositionLocalOf<SongSelection?> { PageSelection }

/**
 * While a page that can lose songs (your own playlist) is shown, the selection bar offers to take
 * the picked ones out of it through [onRemove], one at a time.
 */
@Composable
internal fun PageSelectionRemover(onRemove: ((Song) -> Unit)?, label: String = "Remove from this playlist") {
    val selection = LocalSongSelection.current ?: return
    val latest = androidx.compose.runtime.rememberUpdatedState(onRemove)
    val has = onRemove != null
    DisposableEffect(selection, has) {
        val mine: ((List<Song>) -> Unit)? = if (has) ({ songs -> songs.forEach { s -> latest.value?.invoke(s) } }) else null
        selection.remover = mine
        selection.removeLabel = label
        onDispose { if (selection.remover === mine) selection.remover = null }
    }
}

/** A selection of a panel's own, for the keyboard to reach while it's shown. */
@Composable
internal fun rememberOwnSelection(forQueue: Boolean = false): SongSelection {
    val s = remember { SongSelection().also { it.forQueue = forQueue } }
    DisposableEffect(s) { Selections.add(s); onDispose { Selections.remove(s); s.clear() } }
    return s
}

/**
 * A row's part in its list's selection: whether it's picked, and its click, which picks with
 * Ctrl, ⌘ or Shift held (or once anything is picked) and otherwise does what the row does.
 */
@Stable
internal class RowPick(val selection: SongSelection) {
    var songs: List<Song> = emptyList()
    var index: Int = 0
    private var keys: PointerKeyboardModifiers? = null

    /** Notes the keys held as the pointer goes down, for the click that follows. */
    val capture: Modifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type == PointerEventType.Press) keys = e.keyboardModifiers
            }
        }
    }

    val picked: Boolean get() = songs.getOrNull(index)?.let(selection::isPicked) == true

    fun toggle() {
        Selections.focused = selection
        selection.toggle(songs, index)
    }

    /** The row's click: [otherwise] runs when it isn't a pick. */
    fun click(otherwise: () -> Unit) {
        val k = keys
        keys = null
        val ctrl = k != null && (k.isCtrlPressed || k.isMetaPressed)
        val shift = k?.isShiftPressed == true
        Selections.focused = selection
        if (!selection.click(songs, index, ctrl, shift)) otherwise()
    }
}

/** The row at [index] of [songs] in the selection here, or null where there's none. */
@Composable
internal fun rememberRowPick(songs: List<Song>, index: Int): RowPick? {
    val selection = LocalSongSelection.current ?: return null
    DisposableEffect(selection, songs) {
        selection.attach(songs)
        onDispose { selection.detach(songs) }
    }
    val pick = remember(selection) { RowPick(selection) }
    pick.songs = songs
    pick.index = index
    return pick
}

/**
 * The place at a row's start for its box: empty until the row is under the pointer ([hover]) or
 * anything in the list is picked, so the row doesn't shift when it shows.
 */
@Composable
internal fun PickSlot(pick: RowPick, title: String, hover: MutableInteractionSource, width: Dp = 36.dp) {
    val hovered by hover.collectIsHoveredAsState()
    Box(Modifier.size(width, 40.dp), contentAlignment = Alignment.CenterStart) {
        if (hovered || pick.selection.active) PickBox(pick.picked, title, onToggle = pick::toggle)
    }
}

/**
 * A row's box: a hairline square, or filled with the key colour and ticked once picked. A click
 * on it picks or lets go without playing.
 */
@Composable
internal fun PickBox(picked: Boolean, title: String, modifier: Modifier = Modifier, size: Dp = 20.dp, onToggle: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val key = keyColour()
    val fill by animateColorAsState(if (picked) key else PageInk.text.copy(alpha = if (hovered) 0.12f else 0f), tween(PAGE_HOVER_MS), label = "pick fill")
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier.size(size + 8.dp).hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Checkbox, onClick = onToggle)
            .semantics {
                contentDescription = if (picked) "Unselect $title" else "Select $title"
                stateDescription = if (picked) "Selected" else "Not selected"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).clip(shape).background(fill)
                .then(if (picked) Modifier else Modifier.border(1.5.dp, PageInk.text.copy(alpha = if (hovered) 0.9f else 0.55f), shape)),
            contentAlignment = Alignment.Center,
        ) {
            if (picked) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(size * 0.8f))
        }
    }
}
