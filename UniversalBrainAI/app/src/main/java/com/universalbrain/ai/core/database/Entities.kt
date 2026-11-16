package com.universalbrain.ai.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Imported application (registered from the installed-app scanner). */
@Entity(tableName = "imported_apps")
data class ImportedApp(
    @PrimaryKey val id: String,
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val compatibility: String,
    val importedAt: Long
)

/** A clone instance of an imported app. */
@Entity(tableName = "instances")
data class CloneInstance(
    @PrimaryKey val id: String,
    val appPackage: String,
    val appLabel: String,
    val name: String,
    val index: Int,
    val kind: String,
    val status: String,
    val dataDirPath: String?,
    val accountId: String?,
    val createdAt: Long,
    val lastActiveAt: Long
)

/** Managed account (credentials live in the Keystore-backed secure store). */
@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey val id: String,
    val label: String,
    val groupName: String,
    val username: String,
    val secretRef: String,
    val status: String,
    val instanceId: String?,
    val createdAt: Long,
    val lastActiveAt: Long
)

@Entity(tableName = "account_sessions")
data class AccountSession(
    @PrimaryKey val id: String,
    val accountId: String,
    val startedAt: Long,
    val endedAt: Long?,
    val status: String,
    val note: String?
)

/** External mining worker monitored through a documented pool API. */
@Entity(tableName = "mining_workers")
data class MiningWorker(
    @PrimaryKey val id: String,
    val name: String,
    val poolApiUrl: String,
    val wallet: String,
    val coin: String,
    val pollIntervalMin: Int,
    val status: String,
    val lastHashrate: Double,
    val lastWorkersOnline: Int,
    val lastUnpaid: Double,
    val lastError: String?,
    val lastSyncAt: Long
)

@Entity(tableName = "mining_samples")
data class MiningSample(
    @PrimaryKey val id: String,
    val workerId: String,
    val at: Long,
    val hashrate: Double,
    val workersOnline: Int,
    val unpaid: Double
)

@Entity(tableName = "earnings")
data class Earning(
    @PrimaryKey val id: String,
    val workerId: String?,
    val at: Long,
    val amount: Double,
    val currency: String,
    val source: String
)

@Entity(tableName = "ai_messages")
data class AiMessage(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val text: String,
    val tool: String?,
    val at: Long
)

@Entity(tableName = "ai_memory")
data class AiMemory(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long
)

@Entity(tableName = "automation_rules")
data class AutomationRule(
    @PrimaryKey val id: String,
    val name: String,
    val conditionType: String,
    val conditionValue: String,
    val actionType: String,
    val actionValue: String,
    val enabled: Boolean,
    val lastTriggeredAt: Long?
)

@Entity(tableName = "background_tasks")
data class BackgroundTask(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val lastHeartbeatAt: Long
)

@Entity(tableName = "notifications")
data class NotificationItem(
    @PrimaryKey val id: String,
    val title: String,
    val text: String,
    val at: Long,
    val read: Boolean
)

@Entity(tableName = "activity_logs")
data class ActivityLog(
    @PrimaryKey val id: String,
    val at: Long,
    val module: String,
    val action: String,
    val detail: String?
)
