package com.opentune.data.download

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.work.*
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Automatically managed offline copies; manually saved songs never belong to this budget. */
object SmartDownloads {
    internal const val TAG = "smart-download"
    private const val REFRESH = "smart-download-refresh"
    private const val NOW = "smart-download-now"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching: Job? = null

    fun init(context: Context) {
        val app = context.applicationContext
        watching?.cancel()
        watching = scope.launch {
            AppSettings.library.map { Triple(it.smartDownloads, it.smartDownloadLimit, it.smartDownloadMinBattery) }
                .distinctUntilChanged().collect { (enabled, limit, _) ->
                    val manager = WorkManager.getInstance(app)
                    if (!enabled) {
                        manager.cancelUniqueWork(REFRESH)
                        manager.cancelUniqueWork(NOW)
                        Downloads.entries.value.values.filter { it.smartDownload && it.state != DownloadState.DONE }
                            .forEach { Downloads.remove(app, it.song.videoId) }
                    } else {
                        // Lowering the limit takes effect even while offline, without touching manual downloads.
                        Downloads.entries.value.values.filter { it.smartDownload }.sortedByDescending { it.addedAt }
                            .drop(limit.coerceIn(10, 500)).forEach { Downloads.remove(app, it.song.videoId) }
                        manager.enqueueUniquePeriodicWork(REFRESH, ExistingPeriodicWorkPolicy.UPDATE,
                            PeriodicWorkRequestBuilder<SmartDownloadWorker>(12, TimeUnit.HOURS)
                                .setConstraints(constraints()).build())
                        manager.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE,
                            OneTimeWorkRequestBuilder<SmartDownloadWorker>().setConstraints(constraints()).build())
                    }
                }
        }
    }

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()

    internal fun allowed(context: Context): Boolean {
        val settings = AppSettings.library.value
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        val wifi = network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !manager.isActiveNetworkMetered
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return smartDownloadAllowed(settings.smartDownloads, wifi, charging, percent,
            settings.smartDownloadMinBattery, context.filesDir.usableSpace)
    }

    internal fun candidates(): List<Song> {
        val favourites = History.records.value.groupBy { it.videoId }.values
            .sortedByDescending { group -> group.sumOf { it.listenedMs } }
            .map { group -> group.maxBy { it.playedAt }.toSong() }
        return (LibraryStore.liked.value.map { it.toSong() } + favourites)
            .filter { com.opentune.data.isYouTubeId(it.videoId) && !LibraryStore.isDisliked(it.videoId) &&
                (!AppSettings.library.value.hideExplicit || it.isExplicit != true) }
            .distinctBy { it.videoId }
    }
}

internal fun smartDownloadAllowed(enabled: Boolean, wifi: Boolean, charging: Boolean,
    battery: Int, minimum: Int, freeBytes: Long): Boolean =
    enabled && wifi && (charging || battery >= minimum.coerceIn(15, 80)) && freeBytes >= SMART_RESERVE_BYTES

internal const val SMART_RESERVE_BYTES = 512L * 1024 * 1024

/** The desired smart set, excluding copies the listener saved manually. */
internal fun smartDownloadSelection(candidates: List<Song>, entries: Map<String, DownloadEntry>, limit: Int): List<Song> =
    candidates.distinctBy { it.videoId }.filter { entries[it.videoId]?.smartDownload != false }.take(limit.coerceIn(10, 500))

class SmartDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!AppSettings.library.value.smartDownloads) return Result.success()
        if (!SmartDownloads.allowed(applicationContext)) return Result.retry()
        val desired = smartDownloadSelection(SmartDownloads.candidates(), Downloads.entries.value,
            AppSettings.library.value.smartDownloadLimit)
        val ids = desired.mapTo(HashSet()) { it.videoId }
        Downloads.entries.value.values.filter { it.smartDownload && it.song.videoId !in ids }
            .forEach { Downloads.remove(applicationContext, it.song.videoId) }
        for (song in desired) {
            currentCoroutineContext().ensureActive()
            if (!SmartDownloads.allowed(applicationContext)) return Result.retry()
            Downloads.enqueueSmart(applicationContext, song)
        }
        Downloads.flush()
        return Result.success()
    }
}

internal class SmartDownloadDeferred : java.io.IOException("Smart downloads wait for Wi-Fi, battery and free space")
