package com.universalbrain.ai

import com.universalbrain.ai.core.monitor.DeviceMonitor
import com.universalbrain.ai.core.monitor.NetworkMonitor
import com.universalbrain.ai.domain.automation.RuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automation rule evaluation against real-condition snapshots. */
class RuleEngineTest {

    private fun vitals(tempC: Float = 33f, batteryPct: Int = 80, ramAvailMb: Long = 2000) =
        DeviceMonitor.Vitals(
            ramTotalMb = 4000, ramAvailMb = ramAvailMb, ramUsedPct = 50f, cpuPct = 30f,
            batteryTempC = tempC, batteryLevelPct = batteryPct, batteryCharging = false,
            storageFreeGb = 5f, timestampMs = 0L
        )

    private val rule = RuleEngine.EvalRule(
        id = "r1", name = "Thermal pause", conditionType = "BATTERY_TEMP_OVER",
        conditionValue = "43", actionType = "PAUSE_MINING", actionValue = "", enabled = true
    )

    @Test
    fun `temperature rule triggers above threshold`() {
        val snapshot = RuleEngine.Snapshot(vitals(tempC = 45f), networkConnected = true, previousNetworkConnected = true, offlineWorkers = 0)
        val actions = RuleEngine.evaluate(listOf(rule), snapshot)
        assertEquals(1, actions.size)
        assertEquals(RuleEngine.ActionType.PAUSE_MINING, actions[0].action)
    }

    @Test
    fun `temperature rule does not trigger below threshold`() {
        val snapshot = RuleEngine.Snapshot(vitals(tempC = 35f), networkConnected = true, previousNetworkConnected = true, offlineWorkers = 0)
        assertTrue(RuleEngine.evaluate(listOf(rule), snapshot).isEmpty())
    }

    @Test
    fun `disabled rules never trigger`() {
        val snapshot = RuleEngine.Snapshot(vitals(tempC = 50f), networkConnected = true, previousNetworkConnected = true, offlineWorkers = 0)
        assertTrue(RuleEngine.evaluate(listOf(rule.copy(enabled = false)), snapshot).isEmpty())
    }

    @Test
    fun `network lost fires only on the transition edge`() {
        val lost = RuleEngine.EvalRule("r2", "Net lost", "NETWORK_LOST", "1", "NOTIFY", "", true)
        assertTrue(RuleEngine.evaluate(
            listOf(lost),
            RuleEngine.Snapshot(vitals(), networkConnected = false, previousNetworkConnected = true, offlineWorkers = 0)
        ).isNotEmpty())
        assertTrue(RuleEngine.evaluate(
            listOf(lost),
            RuleEngine.Snapshot(vitals(), networkConnected = false, previousNetworkConnected = false, offlineWorkers = 0)
        ).isEmpty())
    }

    @Test
    fun `worker offline rule uses count threshold`() {
        val rule = RuleEngine.EvalRule("r3", "Workers down", "WORKER_OFFLINE", "2", "NOTIFY", "", true)
        assertTrue(RuleEngine.evaluate(
            listOf(rule),
            RuleEngine.Snapshot(vitals(), networkConnected = true, previousNetworkConnected = true, offlineWorkers = 2)
        ).isNotEmpty())
        assertTrue(RuleEngine.evaluate(
            listOf(rule),
            RuleEngine.Snapshot(vitals(), networkConnected = true, previousNetworkConnected = true, offlineWorkers = 1)
        ).isEmpty())
    }

    @Test
    fun `invalid condition value never triggers`() {
        val bad = RuleEngine.EvalRule("r4", "Bad", "BATTERY_TEMP_OVER", "not-a-number", "NOTIFY", "", true)
        assertTrue(RuleEngine.evaluate(
            listOf(bad),
            RuleEngine.Snapshot(vitals(tempC = 60f), networkConnected = true, previousNetworkConnected = true, offlineWorkers = 0)
        ).isEmpty())
    }
}
