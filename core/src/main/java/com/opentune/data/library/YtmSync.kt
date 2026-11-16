package com.opentune.data.library

import com.opentune.data.DebugLog as Log
import com.opentune.data.LibrarySignedOut
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * YouTube Music's whole check, run the way SpotifySync runs Spotify's: the
 * account's playlists and its liked songs in one run, reported as one status
 * for the Playlists face's pill to show. Each part keeps its own engine —
 * [MusicRepository.libraryPlaylists] reads the playlists, [LikedSync]
 * reconciles the liked songs — this only runs them in turn and speaks for
 * both, so the pill checks everything, not just the likes.
 */
object YtmSync {
    private const val TAG = "YtmSync"

    /** Two runs never start closer together than this, unless forced. */
    private const val MIN_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()

    /** How the whole check is going, for the Playlists face's pill to show. */
    data class Status(
        val running: Boolean = false,
        /** The part being checked, and where it is in the run. */
        val current: String? = null,
        val index: Int = 0,
        val count: Int = 0,
        val lastSynced: Long = 0,
        val error: String? = null,
        /** Asking YouTube Music what there is, before reading any of it. */
        val checking: Boolean = false,
        /** How many liked songs the last check found changed. */
        val changed: Int = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var lastRun = 0L
    @Volatile private var lastSyncedAt = 0L

    /** Checks everything now, when signed in — unless one is running or just ran. */
    fun check(force: Boolean = false) {
        if (!AccountStore.signedIn.value) return
        scope.launch {
            if (!running.tryLock()) return@launch
            try {
                if (!force && System.currentTimeMillis() - lastRun < MIN_INTERVAL_MS) return@launch
                run()
            } finally {
                running.unlock()
            }
        }
    }

    private suspend fun run() {
        _status.value = Status(running = true, checking = true, lastSynced = lastSyncedAt)
        try {
            _status.value = Status(running = true, current = "Playlists", index = 1, count = 2, lastSynced = lastSyncedAt)
            MusicRepository.libraryPlaylists()
            _status.value = Status(running = true, current = "Liked Music", index = 2, count = 2, lastSynced = lastSyncedAt)
            val liked = LikedSync.awaitSync()
            if (liked.failed) {
                _status.value = Status(error = "Couldn't check YouTube Music", lastSynced = lastSyncedAt)
            } else {
                lastRun = System.currentTimeMillis()
                lastSyncedAt = System.currentTimeMillis()
                _status.value = Status(changed = liked.changed, lastSynced = lastSyncedAt)
            }
        } catch (e: CancellationException) {
            _status.value = Status(lastSynced = lastSyncedAt)
            throw e
        } catch (e: LibrarySignedOut) {
            _status.value = Status(
                error = "YouTube Music says this phone isn't signed in any more. Sign in again from Settings.",
                lastSynced = lastSyncedAt,
            )
        } catch (e: Exception) {
            Log.w(TAG, "YouTube Music check didn't finish; it can be retried", e)
            _status.value = Status(error = "Couldn't check YouTube Music", lastSynced = lastSyncedAt)
        }
    }
}
