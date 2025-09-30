package com.example.demoapplication

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.demoapplication.data.ObjectBoxStore
import com.example.demoapplication.domain.CleanupWorker
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        ObjectBoxStore.init(this)
        FirebaseApp.initializeApp(this)
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = true
        FirebaseCrashlytics.getInstance().sendUnsentReports()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            // background init: RemoteConfig fetch, model warm-up, etc.
            RemoteConfigHelper.init(this@MainApplication)
            RemoteConfigHelper.fetchAndActivate()
            // lazy ML init only when needed...
        }
        RemoteConfigHelper.fetchAndActivate() // early fetch

        // Schedule the first cleanup
        CleanupWorker.scheduleNextCleanup(this)
    }
}