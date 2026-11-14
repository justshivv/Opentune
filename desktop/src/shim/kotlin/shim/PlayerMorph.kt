// What the shared dock reads of the phone's cover flight between the mini player
// and the full player (app's ui/components/PlayerCoverMorph.kt). The desktop's
// player is docked beside the page, with no mini player to dismiss by a swipe.
// See Downloads.kt for why this file sits here.
package com.opentune.ui.components

object PlayerMorph {
    /** How far a swipe has pushed the mini player away: never, on the desktop. */
    val miniDismiss: Float get() = 0f
}
