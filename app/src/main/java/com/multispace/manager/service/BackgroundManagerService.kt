package com.multispace.manager.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.multispace.manager.MultiSpaceApp
import com.multispace.manager.R
import com.multispace.manager.core.BatteryReading
import com.multispace.manager.core.MonitorPolicy
import com.multispace.manager.core.MonitorPlan
import com.multispace.manager.data.db.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * User-controlled foreground service ("Background Manager").
 *
 * What it genuinely does:
 *  - keeps a persistent notification so the OS does not kill session bookkeeping
 *    while the user is in other apps;
 *  - samples RAM / CPU / battery temperature and exposes them to the dashboard;
 *  - periodically persists session snapshots for state recovery;
 *  - adapts its interval for low-RAM devices and when the screen is off.
 *
 * What it honestly does NOT do: it cannot keep arbitrary third-party app
 * processes alive — only Android can do that, and Doze still defers network
 * and CPU. Those limits are documented in the README and the UI.
 */
class BackgroundManagerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var monitor: SystemMonitor

    @Volatile
    private var screenOn: Boolean = true

    @Volatile
    private var snapshotCounter: Int = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = intent?.action != Intent.ACTION_SCREEN_OFF
        }
    }

    override fun onCreate() {
        super.onCreate()
        monitor = SystemMonitor(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startInForeground()
                MultiSpaceApp.instance?.container?.serviceRunning?.value = true
                scope.launch { monitorLoop() }
                return START_STICKY
            }
        }
    }

    private fun startInForeground() {
        val notification = buildNotification("Background manager active", "Sampling device stats…")
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun monitorLoop() {
        val container = MultiSpaceApp.instance?.container ?: return
        var plan: MonitorPlan = MonitorPolicy.plan(
            isLowRamDevice = container.isLowRamDevice,
            availRamRatio = 1f,
            screenOn = screenOn
        )
        while (scope.isActive) {
            val stats = monitor.sample()
            container.serviceStats.value = stats

            plan = MonitorPolicy.plan(
                isLowRamDevice = container.isLowRamDevice,
                availRamRatio = stats.ramAvailRatio,
                screenOn = screenOn
            )

            snapshotCounter++
            if (plan.snapshotSessions && snapshotCounter % SNAPSHOT_EVERY_N_SAMPLES == 0) {
                persistAllSessionSnapshots()
            }

            val notification = buildNotification(
                title = "Sessions managed: ${activeSessionCount()}",
                text = buildString {
                    append("RAM ${stats.ramUsedPct.toInt()}% used")
                    stats.cpuPct?.let { append(" · CPU ${it.toInt()}%") }
                    append(" · ${String.format("%.1f", stats.batteryTempC)}°C ${BatteryReading.label(stats.batteryTempC)}")
                }
            )
            runCatching {
                val nm = androidx.core.app.NotificationManagerCompat.from(this)
                nm.notify(NOTIFICATION_ID, notification)
            }

            delay(plan.intervalMs)
        }
    }

    private suspend fun persistAllSessionSnapshots() {
        val container = MultiSpaceApp.instance?.container ?: return
        val sessions: List<SessionEntity> = container.db.sessionDao().getAll()
        sessions.forEach { session ->
            runCatching { container.sessionManager.persistSnapshot(session) }
        }
    }

    private suspend fun activeSessionCount(): Int {
        val container = MultiSpaceApp.instance?.container ?: return 0
        return runCatching { container.db.sessionDao().getAll() }
            .getOrDefault(emptyList())
            .count { it.status == com.multispace.manager.core.SessionStatus.ACTIVE.name }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val container = MultiSpaceApp.instance?.container
        val contentIntent = container?.let {
            PendingIntent.getActivity(
                this, 0,
                Intent(this, com.multispace.manager.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BackgroundManagerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, ensureChannel(this))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MultiSpace · $title")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        contentIntent?.let { builder.setContentIntent(it) }
        builder.addAction(0, "Stop", stopIntent)
        return builder.build()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        MultiSpaceApp.instance?.container?.serviceRunning?.value = false
        MultiSpaceApp.instance?.container?.serviceStats?.value = null
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        MultiSpaceApp.instance?.container?.serviceRunning?.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.multispace.manager.action.BACKGROUND_START"
        const val ACTION_STOP = "com.multispace.manager.action.BACKGROUND_STOP"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "multispace_background"
        const val SNAPSHOT_EVERY_N_SAMPLES = 12

        fun ensureChannel(context: Context): String {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.service_channel_name),
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.service_channel_desc)
            }
            manager.createNotificationChannel(channel)
            return CHANNEL_ID
        }
    }
}
