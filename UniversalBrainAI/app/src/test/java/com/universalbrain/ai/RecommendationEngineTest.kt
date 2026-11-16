package com.universalbrain.ai

import com.universalbrain.ai.domain.ai.RecommendationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proactive advisor picks the highest-value actions from live state. */
class RecommendationEngineTest {

    private fun healthy() = RecommendationEngine.Snapshot(
        importedApps = 3, instances = 1, maxInstances = 10, activeInstances = 1,
        accounts = 1, accountsWithoutInstance = 0, disconnectedAccounts = 0,
        workers = 1, onlineWorkers = 1, offlineWorkers = 0, hashrateKnown = true,
        batteryTempC = 35f, batteryLevelPct = 80, batteryCharging = false,
        networkConnected = true, rulesEnabled = 2, engineRunning = true,
        adsConsent = true, lowRamDevice = false, ramUsedPct = 40f, llmConfigured = true
    )

    @Test
    fun `healthy state produces no noise`() {
        assertEquals(emptyList<RecommendationEngine.Recommendation>(), RecommendationEngine.recommendations(healthy()))
    }

    @Test
    fun `thermal load is top priority`() {
        val recs = RecommendationEngine.recommendations(healthy().copy(batteryTempC = 45f))
        assertEquals("Device is hot (45C)", recs.first().title)
        assertTrue(recs.first().command.contains("Pause mining"))
    }

    @Test
    fun `offline worker triggers mining check`() {
        val recs = RecommendationEngine.recommendations(healthy().copy(offlineWorkers = 1))
        assertTrue(recs.any { it.title.contains("offline") && it.command == "Check mining performance" })
    }

    @Test
    fun `no workers suggests setup`() {
        val recs = RecommendationEngine.recommendations(healthy().copy(workers = 0, hashrateKnown = false))
        assertTrue(recs.any { it.title.contains("No mining workers") })
    }

    @Test
    fun `disconnected accounts surface`() {
        val recs = RecommendationEngine.recommendations(healthy().copy(disconnectedAccounts = 2))
        assertTrue(recs.any { it.title.contains("2 account(s) disconnected") })
    }

    @Test
    fun `low ram with two active instances suggests battery saver`() {
        val recs = RecommendationEngine.recommendations(
            healthy().copy(lowRamDevice = true, activeInstances = 2)
        )
        assertTrue(recs.any { it.command == "Battery saver on" })
    }

    @Test
    fun `cap reached warns before creating more`() {
        val recs = RecommendationEngine.recommendations(healthy().copy(instances = 10, maxInstances = 10))
        assertTrue(recs.any { it.title.contains("Instance cap reached (10)") })
    }

    @Test
    fun `results are priority ordered and capped at six`() {
        val messy = healthy().copy(
            batteryTempC = 46f, offlineWorkers = 2, disconnectedAccounts = 3,
            accountsWithoutInstance = 4, ramUsedPct = 95f, engineRunning = false, adsConsent = false
        )
        val recs = RecommendationEngine.recommendations(messy)
        assertTrue(recs.size <= 6)
        assertEquals(recs.sortedBy { it.priority }, recs)
    }
}
