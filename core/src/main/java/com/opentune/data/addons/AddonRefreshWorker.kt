package com.opentune.data.addons

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Android batches these checks with other background work; no polling service or wake lock. */
class AddonRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        if (AddonRefresh.refresh()) Result.success() else Result.retry()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.retry() }

    companion object {
        fun schedule(context: Context) {
            val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            val request = PeriodicWorkRequestBuilder<AddonRefreshWorker>(30, TimeUnit.MINUTES, 10, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            work.enqueueUniquePeriodicWork("audio-source-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
