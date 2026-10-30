package com.opentune.data

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Upload durable daily totals at most every four hours; no per-song HTTP requests. */
class UsageUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        Usage.flushPending()
        // The first report is retried until it's taken; later ones wait for the next batch.
        if (tags.contains(FIRST) && !Usage.counted()) return Result.retry()
        return Result.success() // Pending totals survive errors until the next scheduled/foreground attempt.
    }

    companion object {
        private const val FIRST = "usage-first-report"

        fun schedule(context: Context) {
            val manager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            val request = PeriodicWorkRequestBuilder<UsageUploadWorker>(4, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork("usage-batch-upload", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Counts a new installation as soon as there's a network, retried with backoff until it's taken. */
        fun scheduleFirst(context: Context) {
            val manager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            val request = OneTimeWorkRequestBuilder<UsageUploadWorker>()
                .addTag(FIRST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniqueWork("usage-first-report", ExistingWorkPolicy.KEEP, request)
        }
    }
}
