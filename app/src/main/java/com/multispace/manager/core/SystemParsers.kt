package com.multispace.manager.core

/**
 * CPU usage parsed from /proc/stat ("cpu  user nice system idle iowait irq
 * softirq steal ..."). Some vendors seal /proc/stat; callers must treat a
 * null parse result as "CPU usage unavailable" and show N/A, never fake it.
 */
data class CpuSnapshot(
    val idleTicks: Long,
    val totalTicks: Long
) {

    /** Usage percentage since [prev], or null when not enough data. */
    fun usagePercent(prev: CpuSnapshot?): Float? {
        if (prev == null) return null
        val totalDelta = totalTicks - prev.totalTicks
        val idleDelta = idleTicks - prev.idleTicks
        if (totalDelta <= 0) return null
        val busy = totalDelta - idleDelta
        if (busy < 0) return null
        return (busy * 100f) / totalDelta
    }

    companion object {
        fun parse(procStatLine: String?): CpuSnapshot? {
            if (procStatLine.isNullOrBlank()) return null
            val fields = procStatLine.trim().split(Regex("\\s+"))
            if (fields.size < 5 || fields[0] != "cpu") return null
            val ticks = fields.drop(1).mapNotNull { it.toLongOrNull() }
            if (ticks.size < 4) return null
            // idle + iowait count as idle time (standard Linux accounting).
            var idle = ticks[3]
            if (ticks.size > 4) idle += ticks[4]
            val total = ticks.sum()
            if (total <= 0) return null
            return CpuSnapshot(idleTicks = idle, totalTicks = total)
        }
    }
}

/**
 * Battery temperature arrives in tenths of a degree Celsius from
 * ACTION_BATTERY_CHANGED (EXTRA_TEMPERATURE).
 */
object BatteryReading {
    fun temperatureCelsius(rawTenths: Int): Float = rawTenths / 10f

    fun label(celsius: Float): String = when {
        celsius <= 0f -> "Cold"
        celsius < 35f -> "Normal"
        celsius < 43f -> "Warm"
        else -> "Hot — throttling risk"
    }
}
