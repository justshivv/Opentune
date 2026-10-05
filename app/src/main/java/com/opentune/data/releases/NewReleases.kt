package com.opentune.data.releases

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opentune.MainActivity
import com.opentune.R
import com.opentune.data.DebugLog as Log
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.ShelfItem
import com.opentune.data.settings.AppSettings
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Alerts for new albums and singles from artists you follow (the bell on
 * an artist's page). Twice a day, with a connection, each followed artist's
 * page is read and any album or single not seen before is announced in a
 * notification that opens it. Following needs no account: the list is kept
 * on the phone.
 */
object NewReleases {
    private const val TAG = "NewReleases"
    private const val WORK = "new-releases"
    private const val CHANNEL = "new-releases"
    private const val MAX_KNOWN = 300

    @Serializable
    data class Followed(
        val browseId: String,
        val name: String,
        val thumbnailUrl: String? = null,
        /** Release ids already seen, so only newer ones are announced. */
        val known: Set<String> = emptySet(),
        val followedAt: Long = System.currentTimeMillis(),
    )

    data class Release(val artist: Followed, val item: ShelfItem)

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var file: File? = null
    private var appContext: Context? = null
    private val _followed = MutableStateFlow<List<Followed>>(emptyList())
    val followed: StateFlow<List<Followed>> = _followed.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val f = File(context.filesDir, "followed-artists.json")
        file = f
        _followed.value = runCatching { json.decodeFromString(ListSerializer(Followed.serializer()), f.readText()) }.getOrDefault(emptyList())
        schedule(context)
    }

    fun isFollowed(browseId: String) = _followed.value.any { it.browseId == browseId }

    /**
     * Follows the artist on [page]. What's on the page now counts as seen:
     * only releases after today are announced.
     */
    fun follow(context: Context, browseId: String, page: ArtistPage) {
        val artist = Followed(browseId, page.name ?: "Artist", page.thumbnailUrl, releasesOf(page).mapTo(HashSet()) { it.browseId!! })
        _followed.value = listOf(artist) + _followed.value.filterNot { it.browseId == browseId }
        save()
        schedule(context)
    }

    fun unfollow(context: Context, browseId: String) {
        _followed.value = _followed.value.filterNot { it.browseId == browseId }
        save()
        schedule(context)
    }

    /** Albums, singles and EPs on an artist's page, newest first as YouTube lists them. */
    internal fun releasesOf(page: ArtistPage): List<ShelfItem> =
        page.sections
            .filter { s -> RELEASE_SHELF.containsMatchIn(s.title) }
            .flatMap { it.items }
            .filter { it.browseId?.startsWith("MPRE") == true }
            .distinctBy { it.browseId }

    /** Reads every followed artist's page; the releases not seen before. Updates what's been seen. */
    suspend fun check(): List<Release> = lock.withLock {
        val out = mutableListOf<Release>()
        val updated = _followed.value.map { artist ->
            val page = try {
                InnertubeParser.parseArtistPage(Innertube.browse(artist.browseId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read ${artist.name}", e)
                return@map artist
            }
            val (fresh, known) = diff(artist, releasesOf(page))
            fresh.forEach { out += Release(artist, it) }
            artist.copy(known = known, thumbnailUrl = page.thumbnailUrl ?: artist.thumbnailUrl)
        }
        // Someone may have followed or unfollowed while the pages were read.
        _followed.value = _followed.value.map { now -> updated.firstOrNull { it.browseId == now.browseId }?.copy(followedAt = now.followedAt) ?: now }
        save()
        out
    }

    /**
     * The releases in [current] that [artist] hasn't seen, and the new seen
     * set. An artist with nothing seen yet (followed before the page loaded)
     * takes everything as seen, announcing nothing.
     */
    internal fun diff(artist: Followed, current: List<ShelfItem>): Pair<List<ShelfItem>, Set<String>> {
        val ids = current.mapNotNull { it.browseId }
        val fresh = if (artist.known.isEmpty()) emptyList() else current.filter { it.browseId !in artist.known }
        val known = (ids + artist.known).distinct().take(MAX_KNOWN).toSet()
        return fresh to known
    }

    /** Runs the twice-daily check while anyone is followed and alerts are on. */
    fun schedule(context: Context) {
        // WorkManager is set up by AndroidX Startup before the app runs; if it
        // isn't (a test, a broken install), alerts just don't get scheduled.
        val work = runCatching { WorkManager.getInstance(context) }
            .onFailure { Log.w(TAG, "WorkManager unavailable", it) }
            .getOrNull() ?: return
        if (_followed.value.isEmpty() || !AppSettings.library.value.releaseAlerts) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<Worker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Android 13+ asks before an app may post notifications. */
    fun canNotify(context: Context) =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun notify(context: Context, releases: List<Release>) {
        if (releases.isEmpty() || !canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "New releases", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val manager = NotificationManagerCompat.from(context)
        releases.take(MAX_NOTIFICATIONS).forEach { r ->
            val open = PendingIntent.getActivity(
                context,
                r.item.browseId.hashCode(),
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData("https://music.youtube.com/browse/${r.item.browseId}".toUri()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_widget_music)
                .setContentTitle("New from ${r.artist.name}")
                .setContentText(r.item.title + r.item.subtitle.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
                .setContentIntent(open)
                .setAutoCancel(true)
                .setGroup(CHANNEL)
                .build()
            try {
                manager.notify(r.item.browseId.hashCode(), notification)
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed to notify", e)
            }
        }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(json.encodeToString(ListSerializer(Followed.serializer()), _followed.value))
            tmp.renameTo(f)
        }
    }

    private val RELEASE_SHELF = Regex("album|single|ep\\b", RegexOption.IGNORE_CASE)
    private const val MAX_NOTIFICATIONS = 8

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (!AppSettings.library.value.releaseAlerts) return Result.success()
            val found = runCatching { check() }.getOrElse { return Result.retry() }
            Log.i(TAG, "${found.size} new release(s) from ${followed.value.size} artists")
            notify(applicationContext, found)
            return Result.success()
        }
    }
}
