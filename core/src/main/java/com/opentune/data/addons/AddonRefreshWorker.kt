package com.opentune.data.addons

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Android batches these checks with other background work; no polling service or wake lock.
 * Every few hours: opening the app and the add-on settings check too, and a half-hourly check
 * started the whole app in the background about 48 times a day, each start a burst of work.
 */
class AddonRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        if (AddonRefresh.refresh()) Result.success() else Result.retry()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.retry() }

    companion object {
        fun schedule(context: Context) {
            val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            val request = PeriodicWorkRequestBuilder<AddonRefreshWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            // UPDATE, so phones that scheduled the old half-hourly check move to this one.
            work.enqueueUniquePeriodicWork("audio-source-refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
