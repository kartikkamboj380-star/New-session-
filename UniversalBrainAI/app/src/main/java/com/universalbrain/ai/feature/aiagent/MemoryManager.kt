package com.universalbrain.ai.feature.aiagent

import com.universalbrain.ai.core.database.AiDao
import com.universalbrain.ai.core.database.AiMemory
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Operational memory of the agent: durable key/value facts (last report,
 * paused state reasons, user preferences learned from commands) persisted in
 * Room so the brain keeps context across app restarts.
 */
class MemoryManager(private val aiDao: AiDao) {

    object Keys {
        const val LAST_REPORT = "last_report"
        const val MINING_PAUSE_REASON = "mining_pause_reason"
        const val LAST_OPTIMIZATION = "last_optimization"
        const val REPORT_COUNT = "report_count"
    }

    suspend fun remember(key: String, value: String) {
        aiDao.upsertMemory(AiMemory(key = key, value = value, updatedAt = System.currentTimeMillis()))
    }

    suspend fun recall(key: String): String? = aiDao.memory(key)?.value

    suspend fun incrementCounter(key: String): Int {
        val current = recall(key)?.toIntOrNull() ?: 0
        val next = current + 1
        remember(key, next.toString())
        return next
    }

    fun observeMemoryCount(): Flow<Int> = aiDao.observeMemoryCount()

    suspend fun clearAll() = aiDao.clearMemory()
}

/** Generates conversation ids. */
object Conversations {
    fun newId(): String = "conv-" + UUID.randomUUID().toString().take(8)
    const val DEFAULT = "default"
}
