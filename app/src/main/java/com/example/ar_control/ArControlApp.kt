package com.example.ar_control

import android.app.Application
import com.example.ar_control.di.AppContainer
import com.example.ar_control.di.DefaultAppContainer
import com.example.ar_control.diagnostics.CrashLoggingExceptionHandler
import com.example.ar_control.diagnostics.PersistentSessionLog
import com.example.ar_control.diagnostics.SessionLog
import java.io.File

class ArControlApp : Application() {
    private val startupSessionLog: SessionLog by lazy(LazyThreadSafetyMode.NONE) {
        PersistentSessionLog(
            file = File(filesDir, "diagnostics/session-log.txt")
        )
    }
    private val defaultAppContainer: AppContainer by lazy(LazyThreadSafetyMode.NONE) {
        DefaultAppContainer(
            application = this,
            providedSessionLog = startupSessionLog
        )
    }
    private var testingAppContainer: AppContainer? = null

    val appContainer: AppContainer
        get() = testingAppContainer ?: defaultAppContainer

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(
            CrashLoggingExceptionHandler(
                sessionLog = startupSessionLog,
                fatalCrashMarkerFile = File(filesDir, "recovery/fatal-crash.txt"),
                delegate = Thread.getDefaultUncaughtExceptionHandler()
            )
        )
        startupSessionLog.record("ArControlApp", "Application created")
    }

    fun replaceAppContainerForTesting(container: AppContainer) {
        testingAppContainer = container
    }
}
