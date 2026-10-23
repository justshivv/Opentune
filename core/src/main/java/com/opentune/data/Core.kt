package com.opentune.data

import android.app.Application
import com.opentune.data.account.AccountStore
import com.opentune.data.autoeq.AutoEq
import com.opentune.data.covers.AlbumCovers
import com.opentune.data.download.Downloads
import com.opentune.data.history.History
import com.opentune.data.innertube.InnerTubeXResolver
import com.opentune.data.innertube.UpgradedTracks
import com.opentune.data.lastfm.LastFm
import com.opentune.data.library.LibraryStore
import com.opentune.data.listenbrainz.ListenBrainz
import com.opentune.data.settings.AppSettings
import com.opentune.data.subsonic.Subsonic
import com.opentune.playback.QueueStore

/** Starts the shared services, in the order they depend on each other. */
object Core {
    /**
     * [phone] is false on the watch, which leaves out what only the phone
     * and TV app does: new-release alerts, update checks and usage counts.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun init(app: Application, phone: Boolean = true) {
        // Media3's warnings and errors (a failed load, a decoder that won't
        // start) into the app's own log as well as logcat, for "Copy log".
        androidx.media3.common.util.Log.setLogger(object : androidx.media3.common.util.Log.Logger {
            private val system = androidx.media3.common.util.Log.Logger.DEFAULT
            override fun d(tag: String, message: String, throwable: Throwable?) = system.d(tag, message, throwable)
            override fun i(tag: String, message: String, throwable: Throwable?) = system.i(tag, message, throwable)
            override fun w(tag: String, message: String, throwable: Throwable?) {
                AppLog.add('W', tag, message, throwable)
                system.w(tag, message, throwable)
            }
            override fun e(tag: String, message: String, throwable: Throwable?) {
                AppLog.add('E', tag, message, throwable)
                system.e(tag, message, throwable)
            }
        })
        AppSettings.init(app)
        com.opentune.data.addons.AddonStore.init(app)
        com.opentune.data.addons.AddonOutput.init(app)
        com.opentune.data.addons.AddonStore.onChanged = com.opentune.data.lossless.ExternalStreams::retryLookups
        History.init(app)
        com.opentune.data.history.ListenedCollections.init(app)
        LoudnessStore.init(app)
        UpgradedTracks.init(app)
        QueueStore.init(app)
        com.opentune.data.together.Together.init(app)
        LastFm.init(app)
        Subsonic.init(app)
        ListenBrainz.init(app)
        com.opentune.data.radio.Radio.init(app)
        if (phone) {
            com.opentune.data.releases.NewReleases.init(app)
            com.opentune.data.UpdateCheck.init(app)
            com.opentune.data.UpdateCheck.schedule(app)
            com.opentune.data.Usage.init(app)
        }
        // Spotify sign-in is gone; drop any tokens an earlier version kept.
        app.deleteSharedPreferences("spotify")
        // The waveform seek bar is gone; drop the waveforms an earlier version saved.
        java.io.File(app.filesDir, "waveforms").takeIf { it.exists() }?.let { dir -> Thread { dir.deleteRecursively() }.start() }
        com.opentune.data.podcasts.Podcasts.init(app)
        AlbumCovers.init(app)
        AutoEq.init(app)
        com.opentune.data.library.LibraryArtwork.init(app)
        LibraryStore.init(app)
        Downloads.init(app)
        com.opentune.data.download.DownloadedLists.init(app)
        AccountStore.init(app)
        com.opentune.data.library.LibraryCache.init(app)
        // After library and account: it sends playlist changes still waiting, when signed in.
        com.opentune.data.library.PlaylistSync.init(app)
        InnerTubeXResolver.init(app)
    }

    /**
     * Saves at once what's still waiting for its save (each store waits a moment
     * after a change, so a run of changes is written once): the app is leaving
     * the screen or the player is closing, and the process may go any moment.
     */
    fun flush() {
        LibraryStore.flush()
        History.flush()
        com.opentune.data.history.ListenedCollections.flush()
        Downloads.flush()
    }
}
