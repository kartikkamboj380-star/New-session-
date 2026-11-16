package com.universalbrain.ai.domain.report

/**
 * Builds structured plain-text reports from real application data. Pure —
 * the AI Brain's report tool and the analytics screen share this format.
 */
object ReportGenerator {

    data class ReportInput(
        val generatedAt: Long,
        val importedApps: Int,
        val instances: Int,
        val activeInstances: Int,
        val accounts: Int,
        val activeAccounts: Int,
        val workers: Int,
        val onlineWorkers: Int,
        val totalHashrate: Double?,
        val earningsToday: Double?,
        val currency: String,
        val ramUsedPct: Float,
        val cpuPct: Float?,
        val batteryTempC: Float,
        val batteryLevelPct: Int,
        val networkConnected: Boolean,
        val automationRulesEnabled: Int,
        val runningBackgroundTasks: Int
    )

    fun performanceReport(input: ReportInput): String = buildString {
        appendLine("=== UNIVERSAL BRAIN AI - PERFORMANCE REPORT ===")
        appendLine("Generated: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(input.generatedAt))}")
        appendLine()
        appendLine("-- Applications and instances --")
        appendLine("Imported apps: ${input.importedApps}")
        appendLine("Instances: ${input.instances} (active: ${input.activeInstances})")
        appendLine()
        appendLine("-- Accounts --")
        appendLine("Accounts: ${input.accounts} (active: ${input.activeAccounts})")
        appendLine()
        appendLine("-- Mining --")
        appendLine("Workers: ${input.workers} (online: ${input.onlineWorkers})")
        appendLine("Total hashrate: ${input.totalHashrate?.let { com.universalbrain.ai.domain.mining.SmartOptimizer.formatHashrate(it) } ?: "N/A (no data yet)"}")
        appendLine("Earnings today: ${input.earningsToday?.let { "%.8f".format(it) + " " + input.currency } ?: "N/A"}")
        appendLine()
        appendLine("-- Device --")
        appendLine("RAM: ${input.ramUsedPct.toInt()}% used")
        appendLine("CPU: ${input.cpuPct?.let { "${it.toInt()}%" } ?: "N/A"}")
        appendLine("Battery: ${input.batteryLevelPct}% at ${input.batteryTempC}C")
        appendLine("Network: ${if (input.networkConnected) "connected" else "OFFLINE"}")
        appendLine()
        appendLine("-- Engine --")
        appendLine("Automation rules enabled: ${input.automationRulesEnabled}")
        appendLine("Running background tasks: ${input.runningBackgroundTasks}")
    }
}
