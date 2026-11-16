package com.universalbrain.ai.domain.ads

/**
 * Ads module math. Pure - unit tested. Honest model: reward points are only
 * ever appended by a real rewarded-ad callback from a configured ad SDK.
 * Without an SDK integration the ledger stays empty and every surface says
 * so - nothing here simulates a view.
 */
object AdsEngine {

    const val DAILY_CAP = 20
    const val REWARD_POINTS_PER_AD = 1
    const val STREAK_BONUS_POINTS = 5

    data class State(
        val balance: Int,
        val todayCount: Int,
        val cap: Int,
        val streakDays: Int,
        val canWatch: Boolean,
        val message: String
    )

    fun state(balance: Int, todayCount: Int, streakDays: Int, consent: Boolean, providerConfigured: Boolean): State {
        val capped = todayCount >= DAILY_CAP
        val message = when {
            !consent -> "Ads consent is off. Enable consent in the Ads screen first."
            !providerConfigured -> "No ad SDK configured yet. Add a supported rewarded-ad provider (e.g. AdMob) app ID in Settings; rewards are only recorded from real ad callbacks."
            capped -> "Daily cap of $DAILY_CAP ads reached. Come back tomorrow - the cap protects your battery and data."
            else -> "Ready: ${DAILY_CAP - todayCount} rewarded ad(s) left today, $REWARD_POINTS_PER_AD point each${if (streakDays >= 2) " + streak bonus after milestones" else ""}."
        }
        return State(balance, todayCount, DAILY_CAP, streakDays, consent && providerConfigured && !capped, message)
    }

    /** @return updated streak in days. Consecutive days grow the streak, a gap resets it, same-day watches keep it. */
    fun streakAfter(lastWatchDayEpoch: Long, todayDayEpoch: Long, currentStreak: Int): Int = when {
        lastWatchDayEpoch <= 0L -> 1
        todayDayEpoch == lastWatchDayEpoch -> currentStreak.coerceAtLeast(1)
        todayDayEpoch - lastWatchDayEpoch == 86_400_000L -> currentStreak + 1
        else -> 1
    }

    /** @return new (balance, todayCount) or null when the daily cap is hit. */
    fun pointsAfterWatch(balance: Int, todayCount: Int): Pair<Int, Int>? {
        if (todayCount >= DAILY_CAP) return null
        val bonus = if (todayCount + 1 == DAILY_CAP) STREAK_BONUS_POINTS else 0
        return (balance + REWARD_POINTS_PER_AD + bonus) to (todayCount + 1)
    }

    /** UTC day bucket so streaks survive timezone travel consistently. */
    fun dayEpoch(timestampMs: Long): Long = timestampMs / 86_400_000L
}
