package com.opentune.data.library

import com.opentune.data.DebugLog as Log
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import com.opentune.data.innertube.Innertube
import com.opentune.data.model.LikeStatus
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Keeps Liked in step with the signed-in account's liked songs on YouTube
 * Music, in both directions.
 *
 * Liking here already rates the song there (see [LibraryStore.setLiked]);
 * this is the other half. On sign-in, and whenever the Liked screen opens,
 * the account's list is read whole and the two are reconciled against the
 * ids the two sides last agreed on ([LibraryStore.likedSyncState]):
 *
 *  - liked there since then, and not here: added here;
 *  - unliked there since then, and still here: taken away here;
 *  - liked here since then (say, while signed out): rated there;
 *  - unliked here since then: unrated there.
 *
 * A rate that fails is not agreed on, so it is tried again next time. A
 * read that fails changes nothing at all, and an empty read is only
 * believed when it comes twice: a cut-down answer must never read as
 * "every song was unliked". Songs that live on this phone (`local:` ids)
 * and songs marked Dislike are never the sync's business.
 *
 * The first run merges both lists and uploads local likes missing from the
 * account. Failed uploads stay local and are retried on a later sync.
 */
object LikedSync {
    private const val TAG = "LikedSync"

    /** Two runs never start closer together than this. */
    private const val MIN_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()

    @Volatile private var lastRun = 0L
    @Volatile private var lastOwner: String? = null

    private fun owner(): String? = AccountStore.accounts.value.firstOrNull { it.active }?.id
        ?.takeIf { AccountStore.signedIn.value }

    /** Watches for sign-in from the start; each one is a chance to sync. */
    fun init() {
        scope.launch {
            AccountStore.accounts.collect { if (owner() != null) sync() }
        }
    }

    /** Syncs now, when signed in — unless one is running or just ran. */
    fun sync() {
        scope.launch {
            running.lock()
            try {
                val account = owner() ?: return@launch
                val now = System.currentTimeMillis()
                if (lastOwner == account && now - lastRun < MIN_INTERVAL_MS) return@launch
                try {
                    reconcile(account)
                    lastOwner = account
                    lastRun = System.currentTimeMillis()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Liked sync didn't finish; it can be retried", e)
                }
            } finally {
                running.unlock()
            }
        }
    }

    private suspend fun reconcile(accountId: String) {
        val cookie = Innertube.cookie
        fun checkAccount() {
            if (owner() != accountId || Innertube.cookie != cookie) throw CancellationException("Liked sync account changed")
        }
        val (agreedOwner, agreed) = LibraryStore.likedSyncState()
        val localAll = LibraryStore.liked.value
        val disliked = LibraryStore.disliked.value
        // Only YouTube ids take part; local files are this phone's own.
        val localIds = localAll.mapTo(HashSet()) { it.videoId }
            .filterNotTo(HashSet()) { it.startsWith("local:") }

        var remote = fetchLiked()
        if (remote.isEmpty() && (localIds.isNotEmpty() || agreed.isNotEmpty())) {
            // An empty answer is trusted only when it comes twice.
            remote = fetchLiked()
        }
        checkAccount()
        val result = mergeLikedSongs(localAll, remote, agreed.takeIf { agreedOwner == accountId }, disliked) { id, status ->
            checkAccount()
            // A heart tap during the read wins over the older snapshot.
            if (LibraryStore.isLiked(id) != (status == LikeStatus.LIKE)) false
            else rate(id, status)
        }
        checkAccount()
        LibraryStore.applyLikedSync(accountId, localAll, result.songs, result.agreed)
    }

    /** The account's liked songs, whole; a failed or cut-down read throws. */
    private suspend fun fetchLiked(): List<Song> =
        MusicRepository.accountLikedSongs().filterNot { it.videoId.startsWith("local:") }

    private suspend fun rate(id: String, status: LikeStatus): Boolean =
        try {
            Innertube.rate(id, status)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't rate $id on YouTube Music", e)
            false
        }
}
