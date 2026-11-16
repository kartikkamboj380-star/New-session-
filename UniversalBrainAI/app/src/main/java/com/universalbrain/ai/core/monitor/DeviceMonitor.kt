package com.universalbrain.ai.core.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

/**
 * Real device vitals: RAM (ActivityManager), CPU (parsed /proc/stat — null
 * when the vendor seals it), battery temperature/level (sticky
 * ACTION_BATTERY_CHANGED), storage. Used by the dashboard, the Smart Mining
 * Optimizer and automation rules.
 */
class DeviceMonitor(private val context: Context) {

    data class Vitals(
        val ramTotalMb: Long,
        val ramAvailMb: Long,
        val ramUsedPct: Float,
        val cpuPct: Float?,
        val batteryTempC: Float,
        val batteryLevelPct: Int,
        val batteryCharging: Boolean,
        val storageFreeGb: Float,
        val timestampMs: Long
    ) {
        val ramAvailRatio: Float get() = if (ramTotalMb > 0) ramAvailMb.toFloat() / ramTotalMb else 0f
    }

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    @Volatile
    private var previousCpu: CpuSnapshot? = null

    fun isLowRamDevice(): Boolean = activityManager.isLowRamDevice

    fun totalRamMb(): Long = memoryInfo().totalMem / (1024L * 1024L)

    fun sample(): Vitals {
        val mem = memoryInfo()
        val battery = sampleBattery()
        return Vitals(
            ramTotalMb = mem.totalMem / (1024L * 1024L),
            ramAvailMb = mem.availMem / (1024L * 1024L),
            ramUsedPct = if (mem.totalMem > 0) {
                ((mem.totalMem - mem.availMem) * 100f) / mem.totalMem
            } else 0f,
            cpuPct = sampleCpu(),
            batteryTempC = battery.first,
            batteryLevelPct = battery.second,
            batteryCharging = battery.third,
            storageFreeGb = storageFreeGb(),
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info
    }

    private fun sampleCpu(): Float? {
        val line = runCatching {
            File("/proc/stat").useLines { lines -> lines.firstOrNull() }
        }.getOrNull()
        val current = CpuSnapshot.parse(line) ?: return null
        val pct = current.usagePercent(previousCpu)
        previousCpu = current
        return pct
    }

    private fun sampleBattery(): Triple<Float, Int, Boolean> {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val temp = if (rawTemp == Int.MIN_VALUE) 0f else rawTemp / 10f
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Triple(temp, pct, charging)
    }

    private fun storageFreeGb(): Float = runCatching {
        context.filesDir.usableSpace / (1024f * 1024f * 1024f)
    }.getOrDefault(0f)
}

/** CPU ticks parsed from /proc/stat (pure — unit tested). */
data class CpuSnapshot(val idleTicks: Long, val totalTicks: Long) {

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
            var idle = ticks[3]
            if (ticks.size > 4) idle += ticks[4]
            val total = ticks.sum()
            if (total <= 0) return null
            return CpuSnapshot(idleTicks = idle, totalTicks = total)
        }
    }
}
