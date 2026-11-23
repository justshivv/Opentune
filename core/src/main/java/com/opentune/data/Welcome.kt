package com.opentune.data

import android.content.Context
import android.os.Build
import com.opentune.data.account.AccountStore
import com.opentune.data.download.Downloads
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.WelcomeState

/**
 * Whether the first-run welcome (language, artists, look, sign-in, community) is
 * shown. Only on a fresh install: someone who already uses the app, which
 * every phone updating from an older version does, never sees it unless
 * they ask for it again in Settings.
 *
 * The question is settled once, at the first start that finds it open, and
 * kept in settings, so a welcome left halfway is shown again rather than
 * judged anew from the artists it has already followed.
 */
object Welcome {
    /** What the phone already holds, read at start. */
    data class Signals(
        val plays: Int,
        /** Liked songs, local playlists and saved albums and playlists. */
        val libraryItems: Int,
        val followedArtists: Int,
        val downloads: Int,
        val signedIn: Boolean,
        /** The app was installed as an older version and updated since. */
        val updated: Boolean,
    )

    internal fun alreadyInUse(s: Signals): Boolean =
        s.plays > 0 || s.libraryItems > 0 || s.followedArtists > 0 || s.downloads > 0 || s.signedIn || s.updated

    /** What [state] becomes at start, given what's on the phone. */
    internal fun decide(state: WelcomeState, signals: Signals): WelcomeState = when (state) {
        WelcomeState.UNDECIDED -> if (alreadyInUse(signals)) WelcomeState.SEEN else WelcomeState.DUE
        else -> state
    }

    /** Called from the Application once settings, history, library and account are loaded. */
    fun settle(context: Context) {
        val now = AppSettings.ui.value.welcome
        if (now != WelcomeState.UNDECIDED) return
        val signals = Signals(
            plays = History.records.value.size,
            libraryItems = LibraryStore.liked.value.size + LibraryStore.playlists.value.size + LibraryStore.savedLists.value.size,
            followedArtists = com.opentune.data.releases.NewReleases.followed.value.size,
            downloads = Downloads.entries.value.size,
            signedIn = AccountStore.signedIn.value,
            updated = updatedSinceInstall(context),
        )
        val next = decide(now, signals)
        AppSettings.updateUi { it.copy(welcome = next) }
    }

    /** Done or skipped: Home from now on. */
    fun finish() = AppSettings.updateUi { it.copy(welcome = WelcomeState.SEEN) }

    /** From Settings: the welcome comes up again straight away. */
    fun showAgain() = AppSettings.updateUi { it.copy(welcome = WelcomeState.DUE) }

    /** Same test What's new uses to tell a fresh install from an update. */
    private fun updatedSinceInstall(context: Context): Boolean = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.lastUpdateTime > info.firstInstallTime
    }.getOrDefault(false)
}
