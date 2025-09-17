package com.example.demoapplication.domain

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.demoapplication.data.ImagesVectorDB
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.concurrent.TimeUnit

class CleanupWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Perform cleanup
            ImagesVectorDB(applicationContext).removeExpiredRecords()
            Log.d("CleanupWorker", "Cleanup executed")

            // Schedule the next cleanup in 1 minute
            scheduleNextCleanup(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e("CleanupWorker", "Error in cleanup: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            // Schedule the next cleanup even on failure to ensure continuity
            scheduleNextCleanup(applicationContext)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "CleanupWork"

        fun scheduleNextCleanup(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<CleanupWorker>()
                .setInitialDelay(15, TimeUnit.MINUTES) // Delay of 15 minute
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE, // Replace any existing work to avoid duplicates
                workRequest
            )
            Log.d("CleanupWorker", "Scheduled next cleanup in 15 minute")
        }
    }
}
