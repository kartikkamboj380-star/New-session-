package com.multispace.manager

import android.app.Application
import android.content.Context
import android.content.Intent
import com.multispace.manager.apk.ApkImporter
import com.multispace.manager.core.WebSlotRegistry
import com.multispace.manager.data.db.AppDatabase
import com.multispace.manager.profile.ProfileIntegration
import com.multispace.manager.service.BackgroundManagerService
import com.multispace.manager.service.SystemMonitor
import com.multispace.manager.session.SessionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Simple manual DI container — no framework overhead, important on low-RAM
 * devices. Everything is created lazily and shared app-wide.
 */
class AppContainer(val appContext: Context) {

    val db: AppDatabase by lazy { AppDatabase.build(appContext) }

    val sessionManager: SessionManager by lazy { SessionManager(db, appContext) }

    val apkImporter: ApkImporter by lazy { ApkImporter(appContext, db.appDao()) }

    val systemMonitor: SystemMonitor by lazy { SystemMonitor(appContext) }

    val profileIntegration: ProfileIntegration by lazy { ProfileIntegration(appContext) }

    /** Slots for isolated WebView host processes (each slot = 1 isolated session). */
    val webSlots: WebSlotRegistry = WebSlotRegistry()

    /** Latest device stats sampled by the background service (null = not running). */
    val serviceStats = MutableStateFlow<SystemMonitor.Stats?>(null)

    val serviceRunning = MutableStateFlow(false)

    /** Latest install status from the platform PackageInstaller (shown in-app). */
    val installEvent = MutableStateFlow<com.multispace.manager.apk.InstallEvent?>(null)

    val isLowRamDevice: Boolean by lazy { systemMonitor.isLowRamDevice() }

    fun startBackgroundManager() {
        val intent = Intent(appContext, BackgroundManagerService::class.java)
            .setAction(BackgroundManagerService.ACTION_START)
        appContext.startForegroundService(intent)
    }

    fun stopBackgroundManager() {
        val intent = Intent(appContext, BackgroundManagerService::class.java)
            .setAction(BackgroundManagerService.ACTION_STOP)
        appContext.startService(intent)
    }
}

class MultiSpaceApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        @Volatile
        var instance: MultiSpaceApp? = null
            private set
    }
}
