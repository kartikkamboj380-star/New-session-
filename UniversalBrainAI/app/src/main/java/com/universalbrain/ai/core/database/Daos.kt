package com.universalbrain.ai.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM imported_apps ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<ImportedApp>>

    @Query("SELECT COUNT(*) FROM imported_apps")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM imported_apps WHERE packageName = :pkg")
    suspend fun countByPackage(pkg: String): Int

    @Insert
    suspend fun insertAll(apps: List<ImportedApp>)

    @Query("DELETE FROM imported_apps WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM imported_apps")
    suspend fun clear()
}

@Dao
interface CloneDao {
    @Query("SELECT * FROM instances ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<CloneInstance>>

    @Query("SELECT * FROM instances WHERE id = :id")
    suspend fun get(id: String): CloneInstance?

    @Query("SELECT * FROM instances WHERE appPackage = :pkg")
    suspend fun forApp(pkg: String): List<CloneInstance>

    @Query("SELECT COUNT(*) FROM instances")
    suspend fun count(): Int

    @Insert
    suspend fun insert(instance: CloneInstance)

    @Update
    suspend fun update(instance: CloneInstance)

    @Query("UPDATE instances SET status = :status, lastActiveAt = :at WHERE id = :id")
    suspend fun setStatus(id: String, status: String, at: Long)

    @Query("UPDATE instances SET accountId = :accountId WHERE id = :id")
    suspend fun assignAccount(id: String, accountId: String?)

    @Query("DELETE FROM instances WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<Account>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: String): Account?

    @Query("SELECT * FROM accounts WHERE status = :status")
    suspend fun byStatus(status: String): List<Account>

    @Query("SELECT COUNT(*) FROM accounts")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM accounts WHERE status = 'ACTIVE'")
    fun observeActiveCount(): Flow<Int>

    @Insert
    suspend fun insert(account: Account)

    @Update
    suspend fun update(account: Account)

    @Query("UPDATE accounts SET status = :status, lastActiveAt = :at WHERE id = :id")
    suspend fun setStatus(id: String, status: String, at: Long)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)

    @Insert
    suspend fun insertSession(session: AccountSession)

    @Query("SELECT * FROM account_sessions WHERE accountId = :accountId ORDER BY startedAt DESC")
    suspend fun sessions(accountId: String): List<AccountSession>

    @Query("UPDATE account_sessions SET endedAt = :at, status = :status WHERE accountId = :accountId AND endedAt IS NULL")
    suspend fun closeOpenSessions(accountId: String, at: Long, status: String)
}

@Dao
interface MiningDao {
    @Query("SELECT * FROM mining_workers ORDER BY name ASC")
    fun observeWorkers(): Flow<List<MiningWorker>>

    @Query("SELECT * FROM mining_workers")
    suspend fun allWorkers(): List<MiningWorker>

    @Query("SELECT * FROM mining_workers WHERE id = :id")
    suspend fun get(id: String): MiningWorker?

    @Query("SELECT COUNT(*) FROM mining_workers")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM mining_workers WHERE status = 'ONLINE'")
    fun observeOnlineCount(): Flow<Int>

    @Insert
    suspend fun insert(worker: MiningWorker)

    @Update
    suspend fun update(worker: MiningWorker)

    @Query("DELETE FROM mining_workers WHERE id = :id")
    suspend fun delete(id: String)

    @Insert
    suspend fun insertSample(sample: MiningSample)

    @Query("SELECT * FROM mining_samples WHERE workerId = :workerId ORDER BY at DESC LIMIT :limit")
    suspend fun samples(workerId: String, limit: Int): List<MiningSample>

    @Query("SELECT SUM(lastHashrate) FROM mining_workers")
    fun observeTotalHashrate(): Flow<Double?>

    @Insert
    suspend fun insertEarning(earning: Earning)

    @Query("SELECT * FROM earnings ORDER BY at DESC LIMIT :limit")
    fun observeEarnings(limit: Int): Flow<List<Earning>>

    @Query("SELECT SUM(amount) FROM earnings WHERE at >= :since")
    suspend fun earningsSince(since: Long): Double?
}

@Dao
interface AiDao {
    @Query("SELECT * FROM ai_messages WHERE conversationId = :conversationId ORDER BY at ASC")
    fun observeConversation(conversationId: String): Flow<List<AiMessage>>

    @Insert
    suspend fun insertMessage(message: AiMessage)

    @Query("DELETE FROM ai_messages WHERE conversationId = :conversationId")
    suspend fun clearConversation(conversationId: String)

    @Query("SELECT * FROM ai_memory WHERE `key` = :key")
    suspend fun memory(key: String): AiMemory?

    @Insert
    suspend fun upsertMemory(memory: AiMemory)

    @Query("SELECT COUNT(*) FROM ai_memory")
    fun observeMemoryCount(): Flow<Int>

    @Query("DELETE FROM ai_memory")
    suspend fun clearMemory()
}

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automation_rules ORDER BY name ASC")
    fun observeRules(): Flow<List<AutomationRule>>

    @Query("SELECT * FROM automation_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<AutomationRule>

    @Query("SELECT COUNT(*) FROM automation_rules")
    suspend fun count(): Int

    @Insert
    suspend fun insert(rule: AutomationRule)

    @Insert
    suspend fun insertAll(rules: List<AutomationRule>)

    @Update
    suspend fun update(rule: AutomationRule)

    @Query("UPDATE automation_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE automation_rules SET lastTriggeredAt = :at WHERE id = :id")
    suspend fun markTriggered(id: String, at: Long)

    @Query("DELETE FROM automation_rules WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface OpsDao {
    @Insert
    suspend fun insertTask(task: BackgroundTask)

    @Update
    suspend fun updateTask(task: BackgroundTask)

    @Query("SELECT * FROM background_tasks ORDER BY startedAt DESC LIMIT :limit")
    fun observeTasks(limit: Int): Flow<List<BackgroundTask>>

    @Query("SELECT * FROM background_tasks WHERE status IN ('RUNNING','PAUSED')")
    suspend fun activeTasks(): List<BackgroundTask>

    @Query("SELECT COUNT(*) FROM background_tasks WHERE status = 'RUNNING'")
    fun observeRunningCount(): Flow<Int>

    @Query("UPDATE background_tasks SET status = :status, lastHeartbeatAt = :at WHERE id = :id")
    suspend fun setTaskStatus(id: String, status: String, at: Long)

    @Insert
    suspend fun insertNotification(item: NotificationItem)

    @Query("SELECT * FROM notifications ORDER BY at DESC LIMIT :limit")
    fun observeNotifications(limit: Int): Flow<List<NotificationItem>>

    @Query("SELECT COUNT(*) FROM notifications WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("UPDATE notifications SET read = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM notifications")
    suspend fun clearNotifications()

    @Insert
    suspend fun insertLog(log: ActivityLog)

    @Query("SELECT * FROM activity_logs ORDER BY at DESC LIMIT :limit")
    fun observeLogs(limit: Int): Flow<List<ActivityLog>>

    @Query("DELETE FROM activity_logs")
    suspend fun clearLogs()
}
