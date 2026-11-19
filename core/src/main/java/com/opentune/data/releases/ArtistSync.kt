package com.opentune.data.releases

import kotlinx.coroutines.flow.first
import android.content.Context
import android.content.SharedPreferences
import com.opentune.data.DebugLog as Log
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import com.opentune.data.innertube.Innertube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps Followed artists in step with the artists the signed-in account subscribes to on
 * YouTube Music.
 *
 * On sign-in, and when Followed artists opens, the account's subscriptions are read whole and
 * reconciled against the artists the two sides last agreed on ([plan]):
 *
 *  - subscribed there and not followed here: followed here (nothing already out is announced);
 *  - unsubscribed there since, and still followed here: no longer followed here;
 *  - unfollowed here since (the star, or Followed artists): unsubscribed there;
 *  - followed here with the star while signed in: subscribed there at once ([setFollowed]),
 *    and again on a later sync if that failed.
 *
 * An artist followed here before any of this (on the first-run picker, say, or signed out) is
 * left as it is: the sync never subscribes the account to someone the person didn't follow
 * with the account in mind, and never takes away a follow YouTube Music never had. A read that
 * fails, or comes back empty, changes nothing.
 */
object ArtistSync {
    private const val TAG = "ArtistSync"
    private const val MIN_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    @Volatile private var lastRun = 0L
    @Volatile private var lastOwner: String? = null

    /** How the last sync went, for Followed artists to say. */
    data class Status(val running: Boolean = false, val signedIn: Boolean = false, val failed: Boolean = false, val syncedAt: Long = 0L)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** What a sync does, worked out from the three lists alone. */
    data class Plan(
        val followHere: List<String>,
        val unfollowHere: List<String>,
        val subscribeThere: List<String>,
        val unsubscribeThere: List<String>,
    )

    /**
     * [remote]: subscribed on YouTube Music now. [local]: followed here now. [agreed]: on both
     * sides at the last sync, or null before the first. [pending]: followed here with the star
     * while signed in, whose subscribe hasn't gone through yet.
     */
    internal fun plan(remote: Set<String>, local: Set<String>, agreed: Set<String>?, pending: Set<String>): Plan {
        val known = agreed.orEmpty()
        return Plan(
            followHere = (remote - local).filter { agreed == null || it !in known },
            // An empty answer is never taken to mean "unsubscribed from everyone".
            unfollowHere = if (remote.isEmpty()) emptyList() else (local - remote).filter { it in known && it !in pending },
            subscribeThere = (pending intersect (local - remote)).toList(),
            unsubscribeThere = (remote - local).filter { agreed != null && it in known },
        )
    }

    private fun owner(): String? = AccountStore.accounts.value.firstOrNull { it.active }?.id
        ?.takeIf { AccountStore.signedIn.value }

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("artist_sync", Context.MODE_PRIVATE)
        scope.launch { com.opentune.data.AppInUse.started.first { it }; AccountStore.accounts.collect { _status.value = _status.value.copy(signedIn = owner() != null); if (owner() != null) sync() } }
    }

    private fun agreed(account: String): Set<String>? = prefs?.getStringSet("agreed:$account", null)?.toSet()
    private fun pending(account: String): Set<String> = prefs?.getStringSet("pending:$account", null)?.toSet().orEmpty()
    private fun save(account: String, agreed: Set<String>, pending: Set<String>) {
        prefs?.edit()?.putStringSet("agreed:$account", agreed)?.putStringSet("pending:$account", pending)?.apply()
    }

    /**
     * Follows or stops following [browseId] here, and, signed in, subscribes or unsubscribes
     * there too. The artist page's star and Followed artists come through here; the first-run
     * picker doesn't (see the class comment).
     */
    fun setFollowed(context: Context, browseId: String, name: String, thumbnailUrl: String?, on: Boolean, follow: () -> Unit = {
        NewReleases.follow(context, browseId, name, thumbnailUrl)
    }) {
        if (on) follow() else NewReleases.unfollow(context, browseId)
        val account = owner() ?: return
        if (!browseId.startsWith("UC")) return
        scope.launch {
            running.withLock {
                val agreed = agreed(account) ?: emptySet()
                val pending = pending(account)
                val done = runCatching { Innertube.setSubscribed(browseId, on) }
                    .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Couldn't ${if (on) "subscribe to" else "unsubscribe from"} $browseId; a later sync tries again", it) }
                    .isSuccess
                when {
                    on && done -> save(account, agreed + browseId, pending - browseId)
                    on -> save(account, agreed, pending + browseId)
                    done -> save(account, agreed - browseId, pending - browseId)
                    // A failed unsubscribe stays agreed: the next sync sees it unfollowed here and asks again.
                    else -> save(account, agreed, pending - browseId)
                }
            }
        }
    }

    /** Syncs now, when signed in, unless one is running or just ran. */
    fun sync() {
        scope.launch {
            val context = appContext ?: return@launch
            running.withLock {
                val account = owner() ?: return@withLock
                val now = System.currentTimeMillis()
                if (lastOwner == account && now - lastRun < MIN_INTERVAL_MS) return@withLock
                _status.value = _status.value.copy(running = true, signedIn = true)
                try {
                    reconcile(context, account)
                    lastOwner = account
                    lastRun = System.currentTimeMillis()
                    _status.value = Status(signedIn = true, syncedAt = lastRun)
                } catch (e: CancellationException) {
                    _status.value = _status.value.copy(running = false)
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Artist sync didn't finish; it can be retried", e)
                    _status.value = _status.value.copy(running = false, failed = true)
                }
            }
        }
    }

    private suspend fun reconcile(context: Context, account: String) {
        val cookie = Innertube.cookie
        val artists = MusicRepository.subscribedArtists()
        if (owner() != account || Innertube.cookie != cookie) throw CancellationException("Artist sync account changed")
        val remote = artists.mapNotNullTo(LinkedHashSet()) { it.browseId }
        val local = NewReleases.followed.value.mapTo(HashSet()) { it.browseId }
        val plan = plan(remote, local, agreed(account), pending(account))
        val byId = artists.associateBy { it.browseId }
        // Newest subscriptions are listed first; following them oldest first keeps that order on top.
        plan.followHere.asReversed().forEach { id -> byId[id]?.let { NewReleases.follow(context, id, it.title, it.thumbnailUrl) } }
        plan.unfollowHere.forEach { NewReleases.unfollow(context, it) }
        val stillPending = HashSet<String>()
        plan.subscribeThere.forEach { id -> if (!write(id, true)) stillPending += id }
        val stillThere = HashSet<String>()
        plan.unsubscribeThere.forEach { id -> if (!write(id, false)) stillThere += id }
        val followedNow = NewReleases.followed.value.mapTo(HashSet()) { it.browseId }
        // On both sides now: what was there and is followed here, plus what was just subscribed,
        // plus unsubscribes that failed (still there, and to be asked again).
        val agreed = (remote intersect followedNow) + (plan.subscribeThere.toSet() - stillPending) + stillThere
        save(account, agreed, stillPending)
        if (plan.followHere.isNotEmpty() || plan.unfollowHere.isNotEmpty() || plan.subscribeThere.isNotEmpty() || plan.unsubscribeThere.isNotEmpty()) {
            Log.d(TAG, "followed ${plan.followHere.size}, unfollowed ${plan.unfollowHere.size}, subscribed ${plan.subscribeThere.size - stillPending.size}, unsubscribed ${plan.unsubscribeThere.size - stillThere.size}")
        }
    }

    private suspend fun write(channelId: String, on: Boolean): Boolean =
        try {
            Innertube.setSubscribed(channelId, on)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't ${if (on) "subscribe to" else "unsubscribe from"} $channelId", e)
            false
        }
}
