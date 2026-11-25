package com.opentune.ui.remote

import com.opentune.data.remote.RemoteMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The remote's keyboard for a text field on the TV. When the TV says one has the focus
 * ([RemoteMessage.Field]), it opens by itself with what the field holds; what's typed goes to the
 * field as it's typed ([RemoteMessage.Edit]), and Enter runs it. When the field loses the focus,
 * or the TV leaves the page, it closes. Closed by hand while the field still has the focus, it
 * stays closed until the field gets the focus again (or the keyboard button opens it).
 */
class RemoteKeyboard(private val send: (RemoteMessage) -> Unit) {
    data class State(
        /** Showing on the phone. */
        val open: Boolean = false,
        /** What's in the TV's field, as this phone last knew it. */
        val text: String = "",
        /** What the field says when it's empty. */
        val hint: String? = null,
        /** A field on the TV has the focus now. */
        val focused: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** What the TV said about its fields; null when there's no TV connected. */
    fun onField(field: RemoteMessage.Field?) {
        val now = _state.value
        _state.value = when {
            field == null || !field.focused -> State()
            // A field just got the focus: open on what it holds, ready to type.
            !now.focused -> State(open = true, text = field.text.take(MAX), hint = field.hint, focused = true)
            // Typed on the TV meanwhile (its own keyboard), or the field was cleared there.
            else -> now.copy(text = field.text.take(MAX), hint = field.hint)
        }
    }

    /** [text] typed on the phone: the TV's field shows it at once. */
    fun type(text: String) {
        val t = text.take(MAX)
        if (t == _state.value.text) return
        _state.value = _state.value.copy(text = t)
        send(RemoteMessage.Edit(t))
    }

    /** Enter: the TV runs what's in the field, and the phone's keyboard goes away. */
    fun submit() {
        val s = _state.value
        send(RemoteMessage.Edit(s.text, submit = true))
        _state.value = s.copy(open = false)
    }

    /** Closed by hand; the TV's field keeps the focus. */
    fun dismiss() { _state.value = _state.value.copy(open = false) }

    /** Opened by hand (the keyboard button), when a field on the TV has the focus. False when none has. */
    fun open(): Boolean {
        if (!_state.value.focused) return false
        _state.value = _state.value.copy(open = true)
        return true
    }

    companion object {
        /** As much as the TV takes. */
        const val MAX = 200
    }
}
