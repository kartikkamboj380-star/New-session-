package com.universalbrain.ai.domain.ai

/**
 * Turns an analyzed intent into an explicit execution plan of tool calls.
 * Pure — unit tested. Actions flagged `requiresConfirmation` must be
 * approved by the user in the chat UI before the TaskExecutor runs them.
 */
object DecisionEngine {

    data class PlanStep(
        val tool: String,
        val args: Map<String, String> = emptyMap(),
        val requiresConfirmation: Boolean = false,
        val description: String
    )

    data class Plan(
        val intent: IntentAnalyzer.Intent,
        val steps: List<PlanStep>
    )

    fun plan(analysis: IntentAnalyzer.Analysis): Plan {
        val steps = when (analysis.intent) {
            IntentAnalyzer.Intent.SHOW_ACCOUNTS -> listOf(
                PlanStep("query_accounts", description = "Read accounts from the database")
            )
            IntentAnalyzer.Intent.CHECK_DISCONNECTED_ACCOUNTS -> listOf(
                PlanStep("query_accounts", args = mapOf("filter" to "DISCONNECTED"), description = "Read accounts and filter disconnected ones")
            )
            IntentAnalyzer.Intent.SHOW_INSTANCES -> listOf(
                PlanStep("query_instances", description = "Read clone instances from the database")
            )
            IntentAnalyzer.Intent.OPEN_INSTANCES -> listOf(
                PlanStep("query_instances", description = "Read active instances"),
                PlanStep("launch_instances", args = mapOf("scope" to "active"), requiresConfirmation = true, description = "Launch active instances (needs confirmation)")
            )
            IntentAnalyzer.Intent.CREATE_INSTANCE -> listOf(
                PlanStep("create_instance", requiresConfirmation = true, description = "Create a new clone instance (needs confirmation)")
            )
            IntentAnalyzer.Intent.MINING_PERFORMANCE -> listOf(
                PlanStep("query_mining", description = "Read mining workers and latest stats")
            )
            IntentAnalyzer.Intent.SHOW_EARNINGS -> listOf(
                PlanStep("query_earnings", args = mapOf("range" to if (analysis.mentionsToday) "TODAY" else "ALL"), description = "Read earnings records")
            )
            IntentAnalyzer.Intent.OPTIMIZE_MINING -> listOf(
                PlanStep("analyze_optimization", description = "Analyze device vitals and worker performance")
            )
            IntentAnalyzer.Intent.PAUSE_MINING_ON_TEMP -> listOf(
                PlanStep("configure_automation", args = mapOf(
                    "condition" to "BATTERY_TEMP_OVER",
                    "value" to "43",
                    "action" to "PAUSE_MINING"
                ), requiresConfirmation = true, description = "Create thermal pause automation rule (needs confirmation)")
            )
            IntentAnalyzer.Intent.GENERATE_REPORT -> listOf(
                PlanStep("query_accounts", description = "Read accounts"),
                PlanStep("query_instances", description = "Read instances"),
                PlanStep("query_mining", description = "Read mining workers"),
                PlanStep("generate_report", description = "Compose the full performance report")
            )
            IntentAnalyzer.Intent.DEVICE_STATUS -> listOf(
                PlanStep("query_device", description = "Sample live device vitals")
            )
            IntentAnalyzer.Intent.START_ENGINE -> listOf(
                PlanStep("control_engine", args = mapOf("action" to "START"), requiresConfirmation = true, description = "Start the background engine (needs confirmation)")
            )
            IntentAnalyzer.Intent.STOP_ENGINE -> listOf(
                PlanStep("control_engine", args = mapOf("action" to "STOP"), requiresConfirmation = true, description = "Stop the background engine (needs confirmation)")
            )
            IntentAnalyzer.Intent.HELP -> listOf(
                PlanStep("help", description = "Explain available commands")
            )
            IntentAnalyzer.Intent.UNKNOWN -> listOf(
                PlanStep("help", description = "Unknown command — offer help")
            )
        }
        return Plan(analysis.intent, steps)
    }
}
