package com.universalbrain.ai.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.universalbrain.ai.UbaApp
import java.util.concurrent.TimeUnit

/** Periodic mining worker polling (survives process death, network-constrained). */
class MiningPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as UbaApp).container
        return runCatching {
            container.miningRepository.pollAllWorkers()
            Result.success()
        }.getOrDefault(Result.retry())
    }

    companion object {
        fun schedule(context: Context, intervalMinutes: Int) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "uba_mining_poll",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<MiningPollWorker>(
                    intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES
                ).build()
            )
        }
    }
}

/** Periodic automation rule evaluation even when the engine service is off. */
class RuleEvalWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as UbaApp).container
        return runCatching {
            val rules = container.db.automationDao().enabledRules()
            if (rules.isNotEmpty()) {
                val vitals = container.deviceMonitor.sample()
                val network = container.networkMonitor.state.value
                val triggered = com.universalbrain.ai.domain.automation.RuleEngine.evaluate(
                    rules.map { com.universalbrain.ai.domain.automation.RuleEngine.fromEntity(it) },
                    com.universalbrain.ai.domain.automation.RuleEngine.Snapshot(
                        vitals = vitals,
                        networkConnected = network.connected,
                        previousNetworkConnected = network.connected, // periodic pass has no edge context
                        offlineWorkers = container.db.miningDao().allWorkers().count { it.status == "OFFLINE" }
                    )
                )
                triggered.forEach { action ->
                    if (action.action == com.universalbrain.ai.domain.automation.RuleEngine.ActionType.NOTIFY) {
                        container.db.opsDao().insertNotification(
                            com.universalbrain.ai.core.database.NotificationItem(
                                id = java.util.UUID.randomUUID().toString(),
                                title = action.ruleName,
                                text = action.detail,
                                at = System.currentTimeMillis(),
                                read = false
                            )
                        )
                    }
                }
            }
            Result.success()
        }.getOrDefault(Result.success())
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "uba_rule_eval",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RuleEvalWorker>(30, TimeUnit.MINUTES).build()
            )
        }
    }
}
