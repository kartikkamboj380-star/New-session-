package com.universalbrain.ai

import com.universalbrain.ai.domain.ads.AdsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ads reward math: caps, streaks, honest readiness states. */
class AdsEngineTest {

    @Test
    fun `points accrue until the daily cap`() {
        val (balance, today) = AdsEngine.pointsAfterWatch(5, 0)!!
        assertEquals(6, balance)
        assertEquals(1, today)
        assertNull(AdsEngine.pointsAfterWatch(5, AdsEngine.DAILY_CAP))
    }

    @Test
    fun `cap completion awards streak bonus`() {
        val (balance, _) = AdsEngine.pointsAfterWatch(10, AdsEngine.DAILY_CAP - 1)!!
        assertEquals(10 + 1 + AdsEngine.STREAK_BONUS_POINTS, balance)
    }

    @Test
    fun `streak grows on consecutive days and resets on gaps`() {
        val today = AdsEngine.dayEpoch(1_000_000L)
        assertEquals(1, AdsEngine.streakAfter(0L, today, 0))
        assertEquals(3, AdsEngine.streakAfter(today - 1L, today, 2))
        assertEquals(2, AdsEngine.streakAfter(today, today, 2))
        assertEquals(1, AdsEngine.streakAfter(today - 3L, today, 9))
    }

    @Test
    fun `state blocks watching without consent or provider`() {
        val noConsent = AdsEngine.state(10, 0, 0, consent = false, providerConfigured = false)
        assertFalse(noConsent.canWatch)
        assertTrue(noConsent.message.contains("consent"))

        val noProvider = AdsEngine.state(10, 0, 0, consent = true, providerConfigured = false)
        assertFalse(noProvider.canWatch)
        assertTrue(noProvider.message.contains("SDK"))

        val ready = AdsEngine.state(10, 0, 2, consent = true, providerConfigured = true)
        assertTrue(ready.canWatch)
        assertEquals(AdsEngine.DAILY_CAP, ready.cap)
    }

    @Test
    fun `capped state explains the cap`() {
        val capped = AdsEngine.state(50, AdsEngine.DAILY_CAP, 3, consent = true, providerConfigured = true)
        assertFalse(capped.canWatch)
        assertTrue(capped.message.contains("cap"))
    }
}
