package com.universalbrain.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.universalbrain.ai.AppContainer
import com.universalbrain.ai.MainActivity
import com.universalbrain.ai.R
import com.universalbrain.ai.core.database.ActivityLog
import com.universalbrain.ai.core.database.BackgroundTask
import com.universalbrain.ai.core.database.NotificationItem
import com.universalbrain.ai.domain.automation.RuleEngine
import com.universalbrain.ai.domain.mining.SmartOptimizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Universal Brain background engine: foreground service (specialUse) that
 * samples device vitals, evaluates automation rules, polls mining workers
 * and posts alerts — user-controlled, resource-aware, honest.
 */
class BrainService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var container: AppContainer

    @Volatile
    private var previousNetworkConnected: Boolean = true

    @Volatile
    private var paused: Boolean = false

    override fun onCreate() {
        super.onCreate()
        container = (application as com.universalbrain.ai.UbaApp).container
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> paused = true
            ACTION_RESUME -> paused = false
            else -> {
                paused = false
                startInForeground()
                container.engineRunning.value = true
                ensureTaskRow()
                scope.launch { engineLoop() }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val notification = buildNotification("Brain engine active", "Monitoring device, rules and workers…")
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureTaskRow() {
        scope.launch {
            val existing = container.db.opsDao().activeTasks().any { it.type == TASK_TYPE }
            if (!existing) {
                container.db.opsDao().insertTask(
                    BackgroundTask(
                        id = UUID.randomUUID().toString(),
                        name = "Brain monitoring engine",
                        type = TASK_TYPE,
                        status = "RUNNING",
                        startedAt = System.currentTimeMillis(),
                        endedAt = null,
                        lastHeartbeatAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private suspend fun engineLoop() {
        while (scope.isActive) {
            if (!paused) {
                runCatching { tick() }
            }
            val interval = runCatching {
                val vitals = container.deviceMonitor.sample()
                val base = container.miningRepository.baseIntervalMin.first()
                SmartOptimizer.pollIntervalMinutes(base, vitals) * 60_000L
            }.getOrDefault(600_000L)
            delay(interval)
        }
    }

    private suspend fun tick() {
        val vitals = container.deviceMonitor.sample()
        val network = container.networkMonitor.state.value

        // 1) Mining workers polling (network-gated).
        var offlineWorkers = 0
        if (network.connected) {
            val workers = container.db.miningDao().allWorkers()
            workers.forEach { worker ->
                val error = container.miningRepository.pollWorker(worker)
                if (error != null) offlineWorkers++
            }
            offlineWorkers += workers.count { it.status == "OFFLINE" }
        }

        // 2) Automation rules.
        val rules = container.db.automationDao().enabledRules().map { RuleEngine.fromEntity(it) }
        val snapshot = RuleEngine.Snapshot(
            vitals = vitals,
            networkConnected = network.connected,
            previousNetworkConnected = previousNetworkConnected,
            offlineWorkers = offlineWorkers
        )
        val triggered = RuleEngine.evaluate(rules, snapshot)
        triggered.forEach { action ->
            executeAction(action)
            container.db.automationDao().markTriggered(action.ruleId, System.currentTimeMillis())
        }
        previousNetworkConnected = network.connected

        // 3) Heartbeat + notification refresh.
        container.db.opsDao().activeTasks()
            .filter { it.type == TASK_TYPE }
            .forEach { container.db.opsDao().setTaskStatus(it.id, "RUNNING", System.currentTimeMillis()) }

        val workerSummary = container.db.miningDao().observeOnlineCount().first()
        val notification = buildNotification(
            "Engine ${if (paused) "PAUSED" else "active"}",
            "RAM ${vitals.ramUsedPct.toInt()}% · ${vitals.batteryTempC}°C · workers online: $workerSummary"
        )
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun executeAction(action: RuleEngine.TriggeredAction) {
        container.db.opsDao().insertLog(
            ActivityLog(
                id = UUID.randomUUID().toString(),
                at = System.currentTimeMillis(),
                module = "AUTOMATION",
                action = action.action.name,
                detail = action.detail
            )
        )
        when (action.action) {
            RuleEngine.ActionType.NOTIFY -> postAlert(action.ruleName, action.detail)
            RuleEngine.ActionType.PAUSE_MINING -> {
                container.miningRepository.setPaused(true)
                container.memoryManager.remember(
                    com.universalbrain.ai.feature.aiagent.MemoryManager.Keys.MINING_PAUSE_REASON,
                    action.detail
                )
                postAlert("Mining monitoring paused", action.detail)
            }
            RuleEngine.ActionType.RESUME_MINING -> {
                container.miningRepository.setPaused(false)
                postAlert("Mining monitoring resumed", action.detail)
            }
            RuleEngine.ActionType.START_SERVICE -> Unit // already running
            RuleEngine.ActionType.STOP_SERVICE -> {
                postAlert("Engine stopped by rule", action.detail)
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun postAlert(title: String, text: String) {
        scope.launch {
            container.db.opsDao().insertNotification(
                NotificationItem(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    text = text,
                    at = System.currentTimeMillis(),
                    read = false
                )
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALERT_NOTIFICATION_ID_BASE + (title.hashCode() and 0xFFFF), buildAlert(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BrainService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, BrainService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val resumeIntent = PendingIntent.getService(
            this, 3,
            Intent(this, BrainService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, ENGINE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Universal Brain · $title")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, if (paused) "Resume" else "Pause", if (paused) resumeIntent else pauseIntent)
            .addAction(0, "Stop", stopIntent)
        return builder.build()
    }

    private fun buildAlert(title: String, text: String): Notification =
        NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        container.engineRunning.value = false
        scope.launch {
            container.db.opsDao().activeTasks()
                .filter { it.type == TASK_TYPE }
                .forEach {
                    container.db.opsDao().setTaskStatus(it.id, "STOPPED", System.currentTimeMillis())
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        container.engineRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.universalbrain.ai.action.ENGINE_START"
        const val ACTION_STOP = "com.universalbrain.ai.action.ENGINE_STOP"
        const val ACTION_PAUSE = "com.universalbrain.ai.action.ENGINE_PAUSE"
        const val ACTION_RESUME = "com.universalbrain.ai.action.ENGINE_RESUME"
        const val NOTIFICATION_ID = 3001
        const val ALERT_NOTIFICATION_ID_BASE = 4000
        const val ENGINE_CHANNEL_ID = "uba_engine"
        const val ALERT_CHANNEL_ID = "uba_alerts"
        const val TASK_TYPE = "BRAIN_ENGINE"

        fun ensureChannels(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    ENGINE_CHANNEL_ID,
                    context.getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = context.getString(R.string.service_channel_desc) }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    context.getString(R.string.alert_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = context.getString(R.string.alert_channel_desc) }
            )
        }
    }
}
