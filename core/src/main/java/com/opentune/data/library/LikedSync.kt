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
 * The first run for an account only takes what the account has — nothing
 * is rated or removed — and remembers it as the agreement; the honest
 * two-way reckoning runs from the second time on. A different account
 * starts over the same way.
 */
object LikedSync {
    private const val TAG = "LikedSync"

    /** Two runs never start closer together than this. */
    private const val MIN_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()

    @Volatile private var lastRun = 0L

    /** Watches for sign-in from the start; each one is a chance to sync. */
    fun init() {
        scope.launch {
            AccountStore.signedIn.collect { signedIn -> if (signedIn) sync() }
        }
    }

    /** Syncs now, when signed in — unless one is running or just ran. */
    fun sync() {
        scope.launch {
            if (!running.tryLock()) return@launch
            try {
                if (!AccountStore.signedIn.value) return@launch
                val now = System.currentTimeMillis()
                if (now - lastRun < MIN_INTERVAL_MS) return@launch
                lastRun = now
                try {
                    reconcile()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Liked sync didn't finish; nothing was changed", e)
                }
            } finally {
                running.unlock()
            }
        }
    }

    private suspend fun reconcile() {
        val owner = LibraryCache.owner()
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
        val remoteIds = remote.mapTo(HashSet()) { it.videoId }

        if (agreedOwner != owner) {
            // First run for this account: take what it has, agree on it,
            // push nothing and remove nothing.
            val additions = remote.filter { it.videoId !in localIds && it.videoId !in disliked }
            LibraryStore.applyLikedSync(owner, additions.map { SongRef.of(it) } + localAll, remoteIds)
            return
        }

        // What each side did since the last agreement.
        val addedHere = localIds - remoteIds - agreed
        val removedHere = agreed - localIds - remoteIds
        val removedThere = agreed.intersect(localIds) - remoteIds
        val addedThere = remoteIds - localIds - agreed

        val pushedLikes = HashSet<String>()
        for (id in addedHere) if (rate(id, LikeStatus.LIKE)) pushedLikes += id
        val pushedUnlikes = HashSet<String>()
        for (id in removedHere) if (rate(id, LikeStatus.INDIFFERENT)) pushedUnlikes += id

        val newLocal = remote.filter { it.videoId in addedThere && it.videoId !in disliked }
            .map { SongRef.of(it) } +
                localAll.filterNot { it.videoId in removedThere }
        // What the two sides share now is the next agreement — no more, no less.
        val remoteNow = remoteIds + pushedLikes - pushedUnlikes
        val agreedNow = newLocal.mapTo(HashSet()) { it.videoId }.intersect(remoteNow)
        LibraryStore.applyLikedSync(owner, newLocal, agreedNow)
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
