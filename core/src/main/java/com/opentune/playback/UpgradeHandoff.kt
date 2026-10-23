package com.opentune.playback

/** A loaded stream may be playable even when a seamless two-player handoff is not. */
internal enum class UpgradeBridgeResult { COMPLETED, READY_FOR_REPLACEMENT, UNAVAILABLE, ABORTED }

/** Only overlap audio when very close. A small offset still upgrades, with separate fades. */
internal enum class UpgradeAlignment { BLEND, SHORT_FADE, SEEK }
internal fun upgradeAlignment(driftMs: Long): UpgradeAlignment = when (driftMs) {
    in -20L..20L -> UpgradeAlignment.BLEND
    in -250L..250L -> UpgradeAlignment.SHORT_FADE
    else -> UpgradeAlignment.SEEK
}

internal suspend fun performUpgradeHandoff(
    bridge: suspend () -> UpgradeBridgeResult,
    stillCurrent: () -> Boolean,
    prebuffer: suspend () -> Boolean,
    replace: suspend () -> Boolean,
): Boolean {
    when (bridge()) {
        UpgradeBridgeResult.COMPLETED -> return true
        UpgradeBridgeResult.ABORTED -> return false
        UpgradeBridgeResult.READY_FOR_REPLACEMENT -> Unit
        UpgradeBridgeResult.UNAVAILABLE -> {
            if (!stillCurrent() || !prebuffer()) return false
        }
    }
    // A skip, a new rendition or a crossfade may have happened while loading.
    return stillCurrent() && replace()
}

/** A verified catalog candidate is not proof that the player's current format is lossless. */
internal fun upgradeFormatReady(lossless: Boolean, codec: String?): Boolean =
    codec != null && (!lossless || codec in setOf("audio/flac", "audio/alac", "audio/raw"))
