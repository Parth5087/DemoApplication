package com.example.demoapplication

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.demoapplication.data.ObjectBoxStore
import com.example.demoapplication.domain.CleanupWorker
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.concurrent.TimeUnit

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        ObjectBoxStore.init(this)
        FirebaseApp.initializeApp(this)
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = true
        FirebaseCrashlytics.getInstance().sendUnsentReports()
        RemoteConfigHelper.init(this)
        RemoteConfigHelper.fetchAndActivate() // early fetch

        // Schedule the first cleanup
        CleanupWorker.scheduleNextCleanup(this)
    }
}