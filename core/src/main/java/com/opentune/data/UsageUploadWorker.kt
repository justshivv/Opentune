package com.opentune.data

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Upload durable daily totals at most every four hours; no per-song HTTP requests. */
class UsageUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        Usage.flushPending()
        return Result.success() // Pending totals survive errors until the next scheduled/foreground attempt.
    }

    companion object {
        fun schedule(context: Context) {
            val manager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            val request = PeriodicWorkRequestBuilder<UsageUploadWorker>(4, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork("usage-batch-upload", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
