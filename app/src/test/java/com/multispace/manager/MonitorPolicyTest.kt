package com.multispace.manager

import com.multispace.manager.core.BatteryReading
import com.multispace.manager.core.CpuSnapshot
import com.multispace.manager.core.MonitorPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Background monitor policy + low-RAM behaviour + system stat parsing. */
class MonitorPolicyTest {

    // ---- low RAM behaviour ---------------------------------------------------

    @Test
    fun `low ram device uses slower interval`() {
        val plan = MonitorPolicy.plan(isLowRamDevice = true, availRamRatio = 0.8f, screenOn = true)
        assertEquals(MonitorPolicy.LOW_RAM_INTERVAL_MS, plan.intervalMs)
        assertTrue(plan.trimAggressive)
    }

    @Test
    fun `memory starved device slows sampling and trims aggressively`() {
        val plan = MonitorPolicy.plan(isLowRamDevice = false, availRamRatio = 0.10f, screenOn = true)
        assertEquals(MonitorPolicy.LOW_RAM_INTERVAL_MS, plan.intervalMs)
        assertTrue(plan.trimAggressive)
    }

    @Test
    fun `screen off doubles the interval`() {
        val normal = MonitorPolicy.plan(isLowRamDevice = false, availRamRatio = 0.5f, screenOn = true)
        val off = MonitorPolicy.plan(isLowRamDevice = false, availRamRatio = 0.5f, screenOn = false)
        assertEquals(normal.intervalMs * MonitorPolicy.SCREEN_OFF_FACTOR, off.intervalMs)
    }

    @Test
    fun `healthy device uses base interval`() {
        val plan = MonitorPolicy.plan(isLowRamDevice = false, availRamRatio = 0.6f, screenOn = true)
        assertEquals(MonitorPolicy.BASE_INTERVAL_MS, plan.intervalMs)
        assertTrue(!plan.trimAggressive)
        assertTrue(plan.snapshotSessions)
    }

    @Test
    fun `out of range ratios are clamped safely`() {
        assertNotNull(MonitorPolicy.plan(false, availRamRatio = 99f, screenOn = true))
        assertNotNull(MonitorPolicy.plan(false, availRamRatio = -5f, screenOn = true))
    }

    // ---- CPU parsing -----------------------------------------------------------

    @Test
    fun `proc stat cpu line parses`() {
        val line = "cpu  74608 2520 114330 1812485 10563 0 1030 0 0 0"
        val snapshot = CpuSnapshot.parse(line)
        assertNotNull(snapshot)
        val idle = 1812485L + 10563L
        assertEquals(idle, snapshot!!.idleTicks)
        assertEquals(74608L + 2520L + 114330L + 1812485L + 10563L + 1030L, snapshot.totalTicks)
    }

    @Test
    fun `malformed cpu lines return null (CPU shown as N-A)`() {
        assertNull(CpuSnapshot.parse(null))
        assertNull(CpuSnapshot.parse(""))
        assertNull(CpuSnapshot.parse("garbage line"))
        assertNull(CpuSnapshot.parse("cpu  abc def"))
    }

    @Test
    fun `cpu usage percentage computes busy over total`() {
        val prev = CpuSnapshot(idleTicks = 90, totalTicks = 100)
        val curr = CpuSnapshot(idleTicks = 120, totalTicks = 200)
        val pct = curr.usagePercent(prev)
        // total delta 100, idle delta 30 -> busy 70%
        assertEquals(70f, pct!!, 0.01f)
    }

    @Test
    fun `cpu usage needs a previous sample`() {
        assertNull(CpuSnapshot(idleTicks = 1, totalTicks = 2).usagePercent(null))
        assertNull(CpuSnapshot(idleTicks = 5, totalTicks = 2).usagePercent(
            CpuSnapshot(idleTicks = 90, totalTicks = 100)
        ))
    }

    // ---- battery temperature ----------------------------------------------------

    @Test
    fun `battery temperature converts tenths of celsius`() {
        assertEquals(31.4f, BatteryReading.temperatureCelsius(314), 0.001f)
        assertEquals(0f, BatteryReading.temperatureCelsius(0), 0.001f)
        assertEquals(45f, BatteryReading.temperatureCelsius(450), 0.001f)
    }

    @Test
    fun `battery temperature labels escalate`() {
        assertEquals("Normal", BatteryReading.label(30f))
        assertEquals("Warm", BatteryReading.label(38f))
        assertEquals("Hot — throttling risk", BatteryReading.label(46f))
    }
}
