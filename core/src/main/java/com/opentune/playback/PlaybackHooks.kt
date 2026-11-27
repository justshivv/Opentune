package com.opentune.playback

import android.content.Context
import androidx.media3.session.MediaNotification

/**
 * What an app built on core adds to the shared player service. The watch
 * sets [notificationProvider] to mark the playback notification as an
 * ongoing activity; the phone sets nothing and gets Media3's default.
 */
object PlaybackHooks {
    @Volatile var notificationProvider: ((Context) -> MediaNotification.Provider)? = null
    /** A player connection the app already holds, for the service to use instead of making its own (the watch's). */
    @Volatile var sharedPlayer: ((Context) -> PlayerConnection)? = null
    /** Positions that slip back a little without a seek are put right (see [PositionSteadier]): the watch's Bluetooth needs it. */
    @Volatile var steadyPosition = false
    /**
     * While this device controls another that plays, the service keeps Connect going with the app
     * closed, and so the media controls showing that device (the watch's).
     */
    @Volatile var keepRemote = false
}
