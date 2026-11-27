package com.opentune.ui.account

import com.opentune.data.account.PairingExtras
import com.opentune.data.account.WebShare
import com.opentune.data.account.WebShareOffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Seals [PairingExtras] for a web player's code and says which it took (WebShare.send). */
internal typealias WebSender = suspend (WebShareOffer, PairingExtras) -> Set<String>

/**
 * The "Sign in the web player" sheet for one scanned [offer], apart from how it's drawn:
 * what it says while it sends, once the web player confirms, and when it can't. Cancel and
 * a swipe down are the same [cancel]: the sending stops and the code is put away for good.
 */
internal class WebSignIn(
    val offer: WebShareOffer,
    private val scope: CoroutineScope,
    private val send: WebSender = OVER_RELAYS,
) {
    sealed interface Step {
        data object Asking : Step
        data object Sending : Step
        /** The web player confirmed it took [took]. */
        data class Done(val took: Set<String>) : Step
        /** [retry]: Sign it in may be tapped again with the same code. */
        data class Failed(val message: String, val retry: Boolean) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Asking)
    val step: StateFlow<Step> = _step.asStateFlow()
    private var job: Job? = null

    fun approve(extras: PairingExtras) {
        val now = _step.value
        if (now == Step.Sending || now is Step.Done || (now is Step.Failed && !now.retry)) return
        if (!offer.valid()) { _step.value = Step.Failed(EXPIRED, retry = false); return }
        _step.value = Step.Sending
        job = scope.launch {
            _step.value = try {
                val took = send(offer, extras)
                if (took.isEmpty()) Step.Failed(NOT_TAKEN, retry = false) else Step.Done(took)
            } catch (e: CancellationException) { throw e }
            // It left the phone; whether it arrived is unknown, so the phone doesn't say it failed.
            catch (_: WebShare.Unconfirmed) { Step.Failed(UNCONFIRMED, retry = false) }
            catch (_: Exception) { Step.Failed(UNREACHABLE, retry = true) }
        }
    }

    /** Cancel, a swipe down, back or a tap outside: whatever is under way stops and the sheet goes. */
    fun cancel() {
        job?.cancel()
        job = null
        PairingLinks.dismissWeb(offer)
    }

    companion object {
        val OVER_RELAYS: WebSender = { offer, extras -> withContext(Dispatchers.IO) { WebShare.send(offer, extras) } }
        /** How long the sheet shows "Signed in" before it goes by itself. */
        const val DONE_SHOWN_MS = 1_600L

        const val EXPIRED = "That code from the web player has run out or didn't scan whole. Make a new one there and scan it again."
        const val NOT_TAKEN = "The web player didn't take it. Make a new code there and try again."
        const val UNCONFIRMED = "Sent, but the web player didn't confirm it. If it doesn't show you signed in, make a fresh code there and scan it again."
        const val UNREACHABLE = "Couldn't reach the web player. Keep its page open, make a fresh code there and scan it again."
    }
}
