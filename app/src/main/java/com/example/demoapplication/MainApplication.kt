package com.example.demoapplication

import android.app.Application
import android.content.Intent
import android.util.Log
import com.example.demoapplication.data.ObjectBoxStore
import com.example.demoapplication.domain.CleanupWorker
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.os.Process
import kotlin.system.exitProcess

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
        setupExceptionHandler()
    }

    private fun setupExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Log the crash
            Log.e("AppCrash", "App crashed, restarting...", throwable)
            // Restart the app
            val intent = Intent(this, LauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("CRASH_RESTART", true)
            }
            startActivity(intent)
            // Kill the current process
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }
}