package com.example.demoapplication.domain

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.demoapplication.data.ImagesVectorDB

class CleanupWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        ImagesVectorDB(applicationContext).removeExpiredRecords()
        return Result.success()
    }
}
