package com.universalbrain.ai

import com.universalbrain.ai.domain.perf.PerformanceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device performance tiers drive real sampling/polling limits. */
class PerformanceProfileTest {

    @Test
    fun `very low ram maps to LOW tier`() {
        val p = PerformanceProfile.forDevice(1500, false, false)
        assertEquals(PerformanceProfile.Tier.LOW, p.tier)
        assertEquals(2, p.maxRecommendedWorkers)
        assertFalse(p.animationsEnabled)
    }

    @Test
    fun `low ram flag overrides generous ram`() {
        assertEquals(PerformanceProfile.Tier.LOW, PerformanceProfile.forDevice(8000, true, false).tier)
    }

    @Test
    fun `entry devices get MID tier`() {
        assertEquals(PerformanceProfile.Tier.MID, PerformanceProfile.forDevice(3500, false, false).tier)
    }

    @Test
    fun `capable devices get HIGH tier with animations`() {
        val p = PerformanceProfile.forDevice(8000, false, false)
        assertEquals(PerformanceProfile.Tier.HIGH, p.tier)
        assertTrue(p.animationsEnabled)
    }

    @Test
    fun `battery saver halves sampling and polling`() {
        val base = PerformanceProfile.forDevice(8000, false, false)
        val saver = PerformanceProfile.forDevice(8000, false, true)
        assertEquals(base.vitalsSampleMs * 2, saver.vitalsSampleMs)
        assertEquals(base.defaultPollMinutes * 2, saver.defaultPollMinutes)
        assertFalse(saver.animationsEnabled)
    }
}
