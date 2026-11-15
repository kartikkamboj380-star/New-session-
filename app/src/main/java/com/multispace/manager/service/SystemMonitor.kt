package com.multispace.manager.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.multispace.manager.core.BatteryReading
import com.multispace.manager.core.CpuSnapshot
import java.io.File

/**
 * Samples real device stats: RAM (ActivityManager.MemoryInfo), CPU (parsed
 * from /proc/stat — null when the vendor seals it), battery temperature and
 * charging state (sticky ACTION_BATTERY_CHANGED broadcast).
 */
class SystemMonitor(private val context: Context) {

    data class Stats(
        val ramTotalMb: Long,
        val ramAvailMb: Long,
        val ramUsedPct: Float,
        val lowMemory: Boolean,
        val cpuPct: Float?,
        val batteryTempC: Float,
        val batteryCharging: Boolean,
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

    fun sample(): Stats {
        val mem = memoryInfo()
        val cpuPct = sampleCpu()
        val battery = sampleBattery()
        return Stats(
            ramTotalMb = mem.totalMem / (1024L * 1024L),
            ramAvailMb = mem.availMem / (1024L * 1024L),
            ramUsedPct = if (mem.totalMem > 0) {
                ((mem.totalMem - mem.availMem) * 100f) / mem.totalMem
            } else 0f,
            lowMemory = mem.lowMemory,
            cpuPct = cpuPct,
            batteryTempC = battery.first,
            batteryCharging = battery.second,
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

    private fun sampleBattery(): Pair<Float, Boolean> {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val temp = if (rawTemp == Int.MIN_VALUE) 0f else BatteryReading.temperatureCelsius(rawTemp)
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return temp to charging
    }
}
