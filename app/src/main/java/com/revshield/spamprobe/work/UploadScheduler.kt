package com.revshield.spamprobe.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object UploadScheduler {
    private const val ONE_TIME = "revshield-upload-now"
    private const val PERIODIC = "revshield-upload-periodic"

    private val onlyWhenConnected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Kick an immediate drain — used on capture and on "Sync now". The CONNECTED constraint also makes
     * WorkManager auto-run this when connectivity returns (the "reconnect" trigger).
     */
    fun syncNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(onlyWhenConnected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, req)
    }

    /** Safety net so pending records still drain even if a one-time run was killed by a battery manager. */
    fun ensurePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(onlyWhenConnected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
