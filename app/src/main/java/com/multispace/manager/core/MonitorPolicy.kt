package com.multispace.manager.core

/**
 * Decides how often the background manager samples device stats and how
 * aggressively it should trim, based on device class, available RAM and
 * screen state. Pure function — unit tested for low-RAM behaviour.
 */
data class MonitorPlan(
    val intervalMs: Long,
    val trimAggressive: Boolean,
    val snapshotSessions: Boolean
)

object MonitorPolicy {

    const val BASE_INTERVAL_MS: Long = 5_000L
    const val LOW_RAM_INTERVAL_MS: Long = 10_000L
    const val SCREEN_OFF_FACTOR: Int = 2
    const val LOW_RAM_RATIO: Float = 0.15f

    /**
     * @param isLowRamDevice Android declares the device as low-RAM (Go class).
     * @param availRamRatio available RAM / total RAM (0..1); values outside the
     *        range are clamped.
     * @param screenOn false when the screen is off — sampling slows down.
     */
    fun plan(
        isLowRamDevice: Boolean,
        availRamRatio: Float,
        screenOn: Boolean
    ): MonitorPlan {
        val ratio = availRamRatio.coerceIn(0f, 1f)
        val memoryStarved = ratio < LOW_RAM_RATIO

        var interval = if (isLowRamDevice || memoryStarved) LOW_RAM_INTERVAL_MS else BASE_INTERVAL_MS
        if (!screenOn) interval *= SCREEN_OFF_FACTOR

        val trim = isLowRamDevice || memoryStarved || ratio < 0.25f
        // On truly starved devices stop writing snapshots to save I/O; state
        // recovery then relies on the last persisted snapshot only.
        val snapshot = !memoryStarved || ratio >= LOW_RAM_RATIO / 2f

        return MonitorPlan(
            intervalMs = interval,
            trimAggressive = trim,
            snapshotSessions = snapshot
        )
    }
}
