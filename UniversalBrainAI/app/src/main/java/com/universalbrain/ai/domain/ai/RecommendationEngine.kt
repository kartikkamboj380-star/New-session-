package com.universalbrain.ai.domain.ai

/**
 * Proactive advisor. Pure - unit tested. Takes a live snapshot of the whole
 * app (instances, accounts, workers, device, ads, engine) and returns the
 * highest-value actions the user can run right now, each mapped to a chat
 * command the brain already executes. This is how MASTER BRAIN watches every
 * module and surfaces problems before the user asks.
 */
object RecommendationEngine {

    data class Recommendation(
        val priority: Int,
        val title: String,
        val detail: String,
        val command: String
    )

    data class Snapshot(
        val importedApps: Int,
        val instances: Int,
        val maxInstances: Int,
        val activeInstances: Int,
        val accounts: Int,
        val accountsWithoutInstance: Int,
        val disconnectedAccounts: Int,
        val workers: Int,
        val onlineWorkers: Int,
        val offlineWorkers: Int,
        val hashrateKnown: Boolean,
        val batteryTempC: Float,
        val batteryLevelPct: Int,
        val batteryCharging: Boolean,
        val networkConnected: Boolean,
        val rulesEnabled: Int,
        val engineRunning: Boolean,
        val adsConsent: Boolean,
        val lowRamDevice: Boolean,
        val ramUsedPct: Float,
        val llmConfigured: Boolean
    )

    fun recommendations(s: Snapshot): List<Recommendation> {
        val list = mutableListOf<Recommendation>()

        if (s.batteryTempC >= 43f) {
            list += Recommendation(1, "Device is hot (${s.batteryTempC.toInt()}C)",
                "High temperature throttles the CPU and can drain the battery. Create an auto-pause rule so mining stops at 43C.",
                "Pause mining when temperature goes over 43")
        }
        if (!s.networkConnected) {
            list += Recommendation(1, "Network is offline",
                "Mining polls and account sessions need connectivity. Turn the network back on or enable the reconnect rule.",
                "Create a rule that notifies me when network is lost")
        }
        if (s.offlineWorkers > 0) {
            list += Recommendation(1, "${s.offlineWorkers} mining worker(s) offline",
                "Offline workers earn nothing. Check the pool API URL and wallet on the worker card, then re-poll.",
                "Check mining performance")
        }
        if (s.disconnectedAccounts > 0) {
            list += Recommendation(2, "${s.disconnectedAccounts} account(s) disconnected",
                "Disconnected accounts cannot hold sessions. Re-sign them in their instance and mark them active.",
                "Check which accounts are disconnected")
        }
        if (s.workers == 0) {
            list += Recommendation(2, "No mining workers configured",
                "Add a worker with your pool stats API URL and wallet so earnings tracking and the optimizer have real data.",
                "Optimize the mining configuration")
        } else if (!s.hashrateKnown) {
            list += Recommendation(2, "Workers have no pool data yet",
                "Run a poll so hashrate and unpaid balance come from your pool API.",
                "Check mining performance")
        }
        if (s.accountsWithoutInstance > 0) {
            list += Recommendation(3, "${s.accountsWithoutInstance} account(s) not assigned to any instance",
                "Assign each account to a clone instance so sessions stay separated per account.",
                "Show all my instances")
        }
        if (s.instances == 0 && s.importedApps == 0) {
            list += Recommendation(3, "Nothing imported yet",
                "Scan and import your installed apps, then create WEB instances with isolated sessions for each account.",
                "help")
        }
        if (s.instances >= s.maxInstances) {
            list += Recommendation(3, "Instance cap reached (${s.maxInstances})",
                "This device can isolate ${s.maxInstances} instances. Remove unused ones before creating more.",
                "Show my instances")
        }
        if (s.lowRamDevice && s.activeInstances >= 2) {
            list += Recommendation(3, "Low-RAM device running 2+ active instances",
                "Keep at most 2 instances alive at once and let the AI battery saver halve background polling.",
                "Battery saver on")
        }
        if (s.ramUsedPct > 90f) {
            list += Recommendation(3, "RAM pressure (${s.ramUsedPct.toInt()}%)",
                "Close idle instances - each WEB instance is a separate OS process holding its own WebView.",
                "Device status")
        }
        if (s.engineRunning && s.rulesEnabled == 0) {
            list += Recommendation(4, "Engine running with no automation rules",
                "Add thermal, network and worker-offline rules so the engine can act without you watching.",
                "Pause mining when temperature becomes high")
        }
        if (!s.engineRunning) {
            list += Recommendation(4, "Background engine is off",
                "Without the engine, automation rules and mining polls only run when the app is open.",
                "Start the engine")
        }
        if (s.batteryLevelPct <= 20 && !s.batteryCharging) {
            list += Recommendation(4, "Battery at ${s.batteryLevelPct}%",
                "Enable battery saver to halve sampling and polling until you can charge.",
                "Battery saver on")
        }
        if (!s.adsConsent) {
            list += Recommendation(5, "Ads module idle (consent off)",
                "Rewarded ads are a supplementary earnings source; consent is required before any provider is initialized.",
                "help")
        }
        if (!s.llmConfigured) {
            list += Recommendation(5, "Local brain only (no LLM bridge)",
                "Every command already works locally. Add an OpenAI-compatible endpoint in Settings > General for free-form conversation on top of the tool brain.",
                "help")
        }

        return list.sortedBy { it.priority }.take(6)
    }
}
