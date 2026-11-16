package com.universalbrain.ai.domain.perf

/**
 * Device performance tiers. Pure - unit tested. Drives real resource limits:
 * vitals sampling rate, mining poll cadence, page sizes and animation flags.
 * Battery saver overrides any tier by halving sampling/polling, so even
 * capable phones can run lean.
 */
object PerformanceProfile {

    enum class Tier { LOW, MID, HIGH }

    data class Profile(
        val tier: Tier,
        val vitalsSampleMs: Long,
        val defaultPollMinutes: Int,
        val maxRecommendedWorkers: Int,
        val chatPageSize: Int,
        val earningsLookback: Int,
        val animationsEnabled: Boolean,
        val reason: String
    )

    fun forDevice(totalRamMb: Long, isLowRamDevice: Boolean, batterySaver: Boolean): Profile {
        val base = when {
            isLowRamDevice || totalRamMb < 2_000 ->
                profile(Tier.LOW, 15_000, 30, 2, 30, 50, false,
                    "Low-RAM device (Android Go class): minimal background footprint")
            totalRamMb < 4_000 ->
                profile(Tier.MID, 8_000, 15, 4, 60, 100, false,
                    "Entry device (~2-4GB RAM): balanced footprint")
            else ->
                profile(Tier.HIGH, 5_000, 10, 6, 100, 200, true,
                    "Capable device: full experience")
        }
        return if (batterySaver) base.copy(
            vitalsSampleMs = base.vitalsSampleMs * 2,
            defaultPollMinutes = (base.defaultPollMinutes * 2).coerceAtMost(120),
            animationsEnabled = false,
            reason = base.reason + " + battery saver (halved sampling and polling)"
        ) else base
    }

    private fun profile(
        tier: Tier,
        vitalsMs: Long,
        pollMin: Int,
        maxWorkers: Int,
        chatPage: Int,
        earningsLookback: Int,
        animations: Boolean,
        reason: String
    ) = Profile(tier, vitalsMs, pollMin, maxWorkers, chatPage, earningsLookback, animations, reason)
}
