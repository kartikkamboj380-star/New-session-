package com.universalbrain.ai.integrations.mining

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.universalbrain.ai.core.database.Earning
import com.universalbrain.ai.core.database.MiningDao
import com.universalbrain.ai.core.database.MiningSample
import com.universalbrain.ai.core.database.MiningWorker
import com.universalbrain.ai.core.networking.PoolApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.miningDataStore by preferencesDataStore(name = "uba_mining")

/**
 * Mining worker registry + polling. Workers are monitored through documented
 * pool stats APIs; each successful poll stores a sample and, when the pool
 * reports a rewards-per-day rate, an ESTIMATED earning record. Nothing here
 * is simulated: no API data means N/A in the UI.
 */
class MiningRepository(
    private val context: Context,
    private val miningDao: MiningDao,
    private val poolClient: PoolApiClient
) {

    private val pausedKey = booleanPreferencesKey("mining_paused")
    private val baseIntervalKey = intPreferencesKey("base_poll_interval_min")

    val paused: Flow<Boolean> = context.miningDataStore.data.map { it[pausedKey] ?: false }
    val baseIntervalMin: Flow<Int> = context.miningDataStore.data.map { it[baseIntervalKey] ?: 10 }

    suspend fun setPaused(value: Boolean) {
        context.miningDataStore.edit { it[pausedKey] = value }
    }

    suspend fun setBaseInterval(minutes: Int) {
        context.miningDataStore.edit { it[baseIntervalKey] = minutes.coerceIn(1, 120) }
    }

    fun observeWorkers(): Flow<List<MiningWorker>> = miningDao.observeWorkers()

    suspend fun addWorker(name: String, apiUrl: String, wallet: String, coin: String, intervalMin: Int) {
        miningDao.insert(
            MiningWorker(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { "Worker" },
                poolApiUrl = apiUrl.trim(),
                wallet = wallet.trim(),
                coin = coin.ifBlank { "COIN" },
                pollIntervalMin = intervalMin.coerceIn(1, 120),
                status = "PENDING",
                lastHashrate = 0.0,
                lastWorkersOnline = 0,
                lastUnpaid = 0.0,
                lastError = null,
                lastSyncAt = 0L
            )
        )
    }

    suspend fun deleteWorker(id: String) = miningDao.delete(id)

    suspend fun worker(id: String): MiningWorker? = miningDao.get(id)

    /**
     * Polls one worker now. Returns an error string when the pool could not
     * be reached or the response had no recognizable stats (honest failure).
     */
    suspend fun pollWorker(worker: MiningWorker): String? {
        val stats = poolClient.fetchStats(worker.poolApiUrl, worker.wallet)
        val now = System.currentTimeMillis()
        if (stats == null || stats.isEmpty) {
            miningDao.update(
                worker.copy(status = "ERROR", lastError = "Pool unreachable or unrecognized response", lastSyncAt = now)
            )
            return "Pool unreachable or unrecognized response for ${worker.name}"
        }
        val online = stats.workersOnline ?: 0
        miningDao.update(
            worker.copy(
                status = if (online > 0 || (stats.hashrate ?: 0.0) > 0) "ONLINE" else "OFFLINE",
                lastHashrate = stats.hashrate ?: worker.lastHashrate,
                lastWorkersOnline = online,
                lastUnpaid = stats.unpaid ?: worker.lastUnpaid,
                lastError = null,
                lastSyncAt = now
            )
        )
        miningDao.insertSample(
            MiningSample(
                id = UUID.randomUUID().toString(),
                workerId = worker.id,
                at = now,
                hashrate = stats.hashrate ?: 0.0,
                workersOnline = online,
                unpaid = stats.unpaid ?: 0.0
            )
        )
        // Estimated earning for the elapsed interval from the pool's rate.
        val rate = stats.coinsPerDay
        if (rate != null && rate > 0) {
            val elapsedHours = if (worker.lastSyncAt in 1 until now) {
                ((now - worker.lastSyncAt).coerceAtMost(24L * 60 * 60 * 1000)) / 3_600_000.0
            } else 0.0
            if (elapsedHours > 0.01) {
                miningDao.insertEarning(
                    Earning(
                        id = UUID.randomUUID().toString(),
                        workerId = worker.id,
                        at = now,
                        amount = rate * elapsedHours / 24.0,
                        currency = worker.coin,
                        source = "ESTIMATED (pool rate)"
                    )
                )
            }
        }
        return null
    }

    suspend fun pollAllWorkers(): Int {
        if (paused.first()) return 0
        val workers = miningDao.allWorkers()
        var ok = 0
        workers.forEach { worker ->
            if (pollWorker(worker) == null) ok++
        }
        return ok
    }
}
