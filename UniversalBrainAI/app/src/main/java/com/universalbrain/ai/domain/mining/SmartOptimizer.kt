package com.universalbrain.ai.domain.mining

import com.universalbrain.ai.core.monitor.DeviceMonitor

/**
 * Smart Mining Optimizer: analyzes real device vitals + worker performance
 * and recommends the most efficient monitoring/worker configuration within
 * the device's resources. Pure — unit tested.
 *
 * Honest scope: this optimizes MONITORING and worker configuration decisions;
 * it does not perform on-device mining (phone CPUs cannot mine profitably and
 * sustained load damages budget hardware).
 */
object SmartOptimizer {

    data class Recommendation(
        val priority: String, // HIGH / MEDIUM / LOW
        val title: String,
        val detail: String
    )

    data class OptimizerInput(
        val vitals: DeviceMonitor.Vitals,
        val networkConnected: Boolean,
        val workerCount: Int,
        val onlineWorkers: Int,
        val pollIntervalMin: Int,
        val totalHashrate: Double
    )

    fun analyze(input: OptimizerInput): List<Recommendation> {
        val recommendations = mutableListOf<Recommendation>()
        val v = input.vitals

        if (!input.networkConnected) {
            recommendations += Recommendation(
                "HIGH", "No network connection",
                "Worker polling is suspended until connectivity returns; reconnection is automatic."
            )
        }
        if (v.batteryTempC >= 43f) {
            recommendations += Recommendation(
                "HIGH", "Thermal risk (${v.batteryTempC}°C)",
                "Reduce monitoring frequency and stop non-essential background work until the device cools."
            )
        }
        if (v.ramAvailRatio < 0.15f) {
            recommendations += Recommendation(
                "HIGH", "Low RAM (${v.ramAvailMb} MB free)",
                "Close clone instances you are not using; the system will also throttle polling under memory pressure."
            )
        }
        if (!v.batteryCharging && v.batteryLevelPct < 20) {
            recommendations += Recommendation(
                "MEDIUM", "Battery low (${v.batteryLevelPct}%)",
                "Enable battery-aware polling (doubled intervals) or plug the device in."
            )
        }
        if (input.workerCount == 0) {
            recommendations += Recommendation(
                "MEDIUM", "No workers configured",
                "Add a mining worker with its pool API URL and wallet to start tracking hashrate and earnings."
            )
        } else if (input.onlineWorkers < input.workerCount) {
            recommendations += Recommendation(
                "MEDIUM", "Workers offline (${input.onlineWorkers}/${input.workerCount} online)",
                "Check the offline workers' pool status; automatic reconnection retries on every poll cycle."
            )
        }
        if (input.pollIntervalMin < 5 && input.workerCount > 3) {
            recommendations += Recommendation(
                "LOW", "Polling too aggressive",
                "With ${input.workerCount} workers, a 10-minute interval keeps network and battery use efficient."
            )
        }
        if (v.cpuPct != null && v.cpuPct > 85f) {
            recommendations += Recommendation(
                "MEDIUM", "CPU under load (${v.cpuPct.toInt()}%)",
                "Background sampling is contributing load; the engine already throttles when the screen is off."
            )
        }
        if (recommendations.isEmpty()) {
            recommendations += Recommendation(
                "LOW", "Configuration is optimal",
                buildString {
                    append("All monitored parameters are within healthy ranges")
                    input.totalHashrate.takeIf { it > 0 }?.let {
                        append(" - total hashrate ${formatHashrate(it)}")
                    }
                    append(".")
                }
            )
        }
        return recommendations
    }

    /** Battery/thermal-aware polling policy (pure - unit tested). */
    fun pollIntervalMinutes(baseMinutes: Int, vitals: DeviceMonitor.Vitals): Int {
        var interval = baseMinutes.coerceAtLeast(1)
        if (vitals.batteryTempC >= 43f) interval *= 2
        if (!vitals.batteryCharging && vitals.batteryLevelPct < 20) interval *= 2
        return interval
    }

    fun formatHashrate(hashratePerSecond: Double): String = when {
        hashratePerSecond >= 1e12 -> "%.2f TH/s".format(hashratePerSecond / 1e12)
        hashratePerSecond >= 1e9 -> "%.2f GH/s".format(hashratePerSecond / 1e9)
        hashratePerSecond >= 1e6 -> "%.2f MH/s".format(hashratePerSecond / 1e6)
        hashratePerSecond >= 1e3 -> "%.2f KH/s".format(hashratePerSecond / 1e3)
        else -> "%.2f H/s".format(hashratePerSecond)
    }
}
