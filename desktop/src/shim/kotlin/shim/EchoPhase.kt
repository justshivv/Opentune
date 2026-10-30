// The phone's draw-phase clock for the player buttons' Echo motion, which on
// Android also pauses with the app's lifecycle and the system's animator
// switch. See Downloads.kt for why this file sits here.
package com.opentune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** A clock from 0 to 1 every [periodMillis], frozen in place while not [running]. */
@Composable
internal fun rememberEchoPhase(running: Boolean, periodMillis: Int): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, periodMillis) {
        if (!running) return@LaunchedEffect
        var previous = 0L
        while (currentCoroutineContext().isActive) {
            withFrameNanos { now ->
                if (previous != 0L) {
                    val elapsed = ((now - previous) / 1_000_000f).coerceIn(0f, 50f)
                    phase.floatValue = (phase.floatValue + elapsed / periodMillis) % 1f
                }
                previous = now
            }
        }
    }
    return phase
}
