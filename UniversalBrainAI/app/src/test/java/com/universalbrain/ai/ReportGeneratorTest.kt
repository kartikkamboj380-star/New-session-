package com.universalbrain.ai

import com.universalbrain.ai.domain.report.ReportGenerator
import org.junit.Assert.assertTrue
import org.junit.Test

/** Report generation from real structured inputs. */
class ReportGeneratorTest {

    @Test
    fun `report contains all sections`() {
        val report = ReportGenerator.performanceReport(
            ReportGenerator.ReportInput(
                generatedAt = 0L,
                importedApps = 4, instances = 6, activeInstances = 2,
                accounts = 5, activeAccounts = 3,
                workers = 2, onlineWorkers = 1, totalHashrate = 12_500_000.0,
                earningsToday = 0.00042, currency = "XMR",
                ramUsedPct = 62f, cpuPct = 41f, batteryTempC = 34.5f, batteryLevelPct = 77,
                networkConnected = true, automationRulesEnabled = 3, runningBackgroundTasks = 1
            )
        )
        assertTrue(report.contains("PERFORMANCE REPORT"))
        assertTrue(report.contains("Imported apps: 4"))
        assertTrue(report.contains("Instances: 6 (active: 2)"))
        assertTrue(report.contains("Accounts: 5 (active: 3)"))
        assertTrue(report.contains("Workers: 2 (online: 1)"))
        assertTrue(report.contains("12.50 MH/s"))
        assertTrue(report.contains("0.00042000 XMR"))
        assertTrue(report.contains("RAM: 62%"))
        assertTrue(report.contains("CPU: 41%"))
        assertTrue(report.contains("Battery: 77%"))
        assertTrue(report.contains("Network: connected"))
        assertTrue(report.contains("Automation rules enabled: 3"))
    }

    @Test
    fun `missing data shows honest N-A`() {
        val report = ReportGenerator.performanceReport(
            ReportGenerator.ReportInput(
                generatedAt = 0L,
                importedApps = 0, instances = 0, activeInstances = 0,
                accounts = 0, activeAccounts = 0,
                workers = 0, onlineWorkers = 0, totalHashrate = null,
                earningsToday = null, currency = "COIN",
                ramUsedPct = 10f, cpuPct = null, batteryTempC = 0f, batteryLevelPct = 0,
                networkConnected = false, automationRulesEnabled = 0, runningBackgroundTasks = 0
            )
        )
        assertTrue(report.contains("N/A"))
        assertTrue(report.contains("Network: OFFLINE"))
    }
}
