package com.universalbrain.ai

import com.universalbrain.ai.core.monitor.DeviceMonitor
import com.universalbrain.ai.domain.cloning.ClonePolicy
import com.universalbrain.ai.domain.mining.MiningStatsParser
import com.universalbrain.ai.domain.mining.SmartOptimizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mining pool stats parsing + smart optimizer + clone resource policy. */
class MiningAndCloningTest {

    private fun parse(json: String) = MiningStatsParser.parse(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `ethermine style response parses`() {
        val stats = parse("""{"status":"OK","data":{"currentHashrate":48230000.0,"unpaid":352100000000000.0,"workers":3,"validShares":1200,"invalidShares":2}}""")
        assertEquals(48230000.0, stats.hashrate!!, 0.001)
        assertEquals(3, stats.workersOnline)
        assertEquals(352100000000000.0, stats.unpaid!!, 0.001)
    }

    @Test
    fun `nanopool style response parses`() {
        val stats = parse("""{"status":true,"result":{"hashrate":12500000.0,"workersOnline":2,"balance":0.05,"coinsPerDay":0.0004}}""")
        assertEquals(12500000.0, stats.hashrate!!, 0.001)
        assertEquals(0.0004, stats.coinsPerDay!!, 1e-9)
    }

    @Test
    fun `empty object yields empty stats`() {
        val stats = parse("""{"status":"OK"}""")
        assertTrue(stats.isEmpty)
        assertNull(stats.hashrate)
    }

    @Test
    fun `garbage values do not crash`() {
        val stats = parse("""{"data":{"hashrate":"abc","workers":null}}""")
        assertNull(stats.hashrate)
        assertNull(stats.workersOnline)
    }

    // ---- optimizer ----

    private fun vitals(
        tempC: Float = 33f,
        ramAvailRatio: Float = 0.5f,
        batteryPct: Int = 80,
        charging: Boolean = true,
        cpuPct: Float? = 30f
    ) = DeviceMonitor.Vitals(
        ramTotalMb = 4000, ramAvailMb = (4000 * ramAvailRatio).toLong(),
        ramUsedPct = (1 - ramAvailRatio) * 100, cpuPct = cpuPct,
        batteryTempC = tempC, batteryLevelPct = batteryPct, batteryCharging = charging,
        storageFreeGb = 5f, timestampMs = 0L
    )

    @Test
    fun `healthy configuration yields optimal recommendation`() {
        val input = SmartOptimizer.OptimizerInput(
            vitals = vitals(), networkConnected = true,
            workerCount = 2, onlineWorkers = 2, pollIntervalMin = 10, totalHashrate = 0.0
        )
        val recommendations = SmartOptimizer.analyze(input)
        assertEquals(1, recommendations.size)
        assertEquals("LOW", recommendations[0].priority)
    }

    @Test
    fun `high temperature produces thermal warning with doubled interval`() {
        val v = vitals(tempC = 46f)
        val input = SmartOptimizer.OptimizerInput(vitals = v, networkConnected = true, workerCount = 2, onlineWorkers = 2, pollIntervalMin = 10, totalHashrate = 0.0)
        val recommendations = SmartOptimizer.analyze(input)
        assertTrue(recommendations.any { it.priority == "HIGH" && it.title.contains("Thermal") })
        assertEquals(20, SmartOptimizer.pollIntervalMinutes(10, v))
    }

    @Test
    fun `offline workers produce medium recommendation`() {
        val input = SmartOptimizer.OptimizerInput(
            vitals = vitals(), networkConnected = true,
            workerCount = 3, onlineWorkers = 1, pollIntervalMin = 10, totalHashrate = 0.0
        )
        val recommendations = SmartOptimizer.analyze(input)
        assertTrue(recommendations.any { it.title.contains("offline") })
    }

    @Test
    fun `hashrate formatting scales`() {
        assertEquals("12.50 MH/s", SmartOptimizer.formatHashrate(12_500_000.0))
        assertEquals("1.20 GH/s", SmartOptimizer.formatHashrate(1_200_000_000.0))
        assertEquals("0.50 H/s", SmartOptimizer.formatHashrate(0.5))
    }

    // ---- clone policy ----

    @Test
    fun `instance cap scales with ram`() {
        assertEquals(10, ClonePolicy.maxInstances(8000, false, 4).maxInstances)
        assertEquals(6, ClonePolicy.maxInstances(5000, false, 4).maxInstances)
        assertEquals(4, ClonePolicy.maxInstances(3000, false, 4).maxInstances)
        assertEquals(2, ClonePolicy.maxInstances(1500, false, 4).maxInstances)
    }

    @Test
    fun `low ram devices are capped at two regardless of slots`() {
        val decision = ClonePolicy.maxInstances(8000, true, 4)
        assertEquals(2, decision.maxInstances)
        assertTrue(decision.reason.contains("Low-RAM"))
    }

    @Test
    fun `cap respects slot budget`() {
        assertEquals(2, ClonePolicy.maxInstances(8000, false, 1).maxInstances)
    }

    @Test
    fun `canCreate enforces the cap`() {
        val decision = ClonePolicy.Decision(3, "test")
        assertTrue(ClonePolicy.canCreate(2, decision))
        assertTrue(!ClonePolicy.canCreate(3, decision))
    }
}
