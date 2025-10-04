package com.uav.analytics

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import com.uav.analytics.data.ObjectBoxStore
import com.uav.analytics.domain.CleanupWorker
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.os.Process
import kotlin.system.exitProcess

class MainApplication : Application() {

    companion object {
        private const val PREFS_NAME = "app_crash_prefs"
        private const val KEY_CRASH_COUNT = "crash_count"
        private const val KEY_LAST_CRASH_TS = "last_crash_ts"
        // consider a window to treat crashes as "consecutive" (ms). Example: 10 minutes
        private const val CRASH_WINDOW_MS = 10 * 60 * 1000L
        private const val MAX_RESTARTS = 3
    }

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()

        // If last crash was long ago, reset count
        val lastTs = prefs.getLong(KEY_LAST_CRASH_TS, 0L)
        if (System.currentTimeMillis() - lastTs > CRASH_WINDOW_MS) {
            resetCrashCount()
        }

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
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("AppCrash", "App crashed, evaluating restart...", throwable)
                val now = System.currentTimeMillis()
                val lastTs = prefs.getLong(KEY_LAST_CRASH_TS, 0L)
                var crashCount = prefs.getInt(KEY_CRASH_COUNT, 0)

                // If last crash was outside the window, start counting fresh
                if (now - lastTs > CRASH_WINDOW_MS) {
                    crashCount = 0
                }

                crashCount += 1
                prefs.edit()
                    .putInt(KEY_CRASH_COUNT, crashCount)
                    .putLong(KEY_LAST_CRASH_TS, now)
                    .apply()

                if (crashCount < MAX_RESTARTS) {
                    // Restart the app
                    val intent = Intent(this, LauncherActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra("CRASH_RESTART", true)
                    }
                    startActivity(intent)
                    // ensure reports are sent before killing (best effort)
                    FirebaseCrashlytics.getInstance().recordException(throwable)
                } else {
                    // Reached max restarts — do NOT restart to avoid loop.
                    Log.e("AppCrash", "Crash happened $crashCount times within window. Not restarting.")
                    FirebaseCrashlytics.getInstance().recordException(
                        RuntimeException("App crashed $crashCount times in short period — no restart.")
                    )
                    // Optionally show a notification or write to disk here so support can inspect.
                }

            } catch (e: Exception) {
                // keep original crash handling robust; delegate to default handler if present
                Log.e("AppCrash", "Error while handling uncaught exception", e)
                defaultHandler?.uncaughtException(thread, throwable)
            } finally {
                // Kill the current process
                Process.killProcess(Process.myPid())
                exitProcess(1)
            }
        }
    }

    private fun resetCrashCount() {
        prefs.edit()
            .putInt(KEY_CRASH_COUNT, 0)
            .putLong(KEY_LAST_CRASH_TS, 0L)
            .apply()
    }
}