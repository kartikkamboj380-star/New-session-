package com.universalbrain.ai

import android.app.Application
import android.content.Context
import android.content.Intent
import com.universalbrain.ai.core.database.BrainDatabase
import com.universalbrain.ai.core.monitor.DeviceMonitor
import com.universalbrain.ai.core.monitor.NetworkMonitor
import com.universalbrain.ai.core.networking.PoolApiClient
import com.universalbrain.ai.core.security.SecureStore
import com.universalbrain.ai.feature.aiagent.MemoryManager
import com.universalbrain.ai.feature.cloning.CloneRepository
import com.universalbrain.ai.integrations.llm.LlmClient
import com.universalbrain.ai.integrations.mining.MiningRepository
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manual DI container (no framework overhead — deliberately lightweight for
 * budget devices). Everything is lazily created and shared app-wide.
 */
class AppContainer(val appContext: Context) {

    val db: BrainDatabase by lazy { BrainDatabase.build(appContext) }

    val secureStore: SecureStore by lazy { SecureStore(appContext) }

    val deviceMonitor: DeviceMonitor by lazy { DeviceMonitor(appContext) }

    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }

    val poolApiClient: PoolApiClient by lazy { PoolApiClient() }

    val cloneRepository: CloneRepository by lazy { CloneRepository(appContext, db.cloneDao(), WEB_SLOTS) }

    val miningRepository: MiningRepository by lazy { MiningRepository(appContext, db.miningDao(), poolApiClient) }

    val memoryManager: MemoryManager by lazy { MemoryManager(db.aiDao()) }

    val llmClient: LlmClient by lazy { LlmClient(secureStore) }

    val engineRunning = MutableStateFlow(false)

    val webSlotCount: Int get() = WEB_SLOTS

    val isLowRamDevice: Boolean by lazy { deviceMonitor.isLowRamDevice() }

    fun startEngine() {
        appContext.startForegroundService(
            Intent(appContext, com.universalbrain.ai.service.BrainService::class.java)
                .setAction(com.universalbrain.ai.service.BrainService.ACTION_START)
        )
    }

    fun stopEngine() {
        appContext.startService(
            Intent(appContext, com.universalbrain.ai.service.BrainService::class.java)
                .setAction(com.universalbrain.ai.service.BrainService.ACTION_STOP)
        )
    }

    companion object {
        const val WEB_SLOTS = 2
    }
}

class UbaApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        container.networkMonitor.start()
    }

    companion object {
        @Volatile
        var instance: UbaApp? = null
            private set
    }
}
