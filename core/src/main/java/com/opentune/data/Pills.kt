package com.opentune.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Short notes about something the listener just did, shown by the app as a
 * pill floating in at the top of the screen: "Added to Gym" with a tick
 * drawing itself in, say. Anything can post one, the service included.
 *
 * While no screen is showing pills (the app is in the background, or
 * another of its activities is in front), a note posted with a context falls
 * back to a toast, so it isn't lost.
 */
object Pills {
    enum class Kind { DONE, INFO, ERROR }

    class Pill(val text: String, val kind: Kind, val action: String? = null, val onAction: (() -> Unit)? = null)

    private val _posted = MutableSharedFlow<Pill>(extraBufferCapacity = 8)
    val posted: SharedFlow<Pill> = _posted.asSharedFlow()

    /** Something worked: shown with a tick. */
    fun done(text: String, context: Context? = null) = post(Pill(text, Kind.DONE), context)

    /** Something to know that isn't a success or a failure. */
    fun info(text: String, context: Context? = null) = post(Pill(text, Kind.INFO), context)

    /** Something didn't work. */
    fun error(text: String, context: Context? = null) = post(Pill(text, Kind.ERROR), context)

    /** A note with a button on it, such as Undo; [onAction] runs if it's tapped. */
    fun withAction(text: String, action: String, kind: Kind = Kind.DONE, onAction: () -> Unit) =
        post(Pill(text, kind, action, onAction), null)

    private fun post(pill: Pill, context: Context?) {
        if (_posted.subscriptionCount.value > 0 && _posted.tryEmit(pill)) return
        val app = context?.applicationContext ?: return
        val toast = { Toast.makeText(app, com.opentune.i18n.Tr.t(pill.text), Toast.LENGTH_SHORT).show() }
        if (Looper.myLooper() == Looper.getMainLooper()) toast() else Handler(Looper.getMainLooper()).post(toast)
    }
}
