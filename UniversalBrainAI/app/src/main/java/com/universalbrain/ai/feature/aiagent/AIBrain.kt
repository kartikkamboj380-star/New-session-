package com.universalbrain.ai.feature.aiagent

import com.universalbrain.ai.AppContainer
import com.universalbrain.ai.core.database.ActivityLog
import com.universalbrain.ai.domain.ai.CommandParser
import com.universalbrain.ai.domain.ai.DecisionEngine
import com.universalbrain.ai.domain.ai.IntentAnalyzer
import com.universalbrain.ai.domain.ai.ToolRegistry
import com.universalbrain.ai.domain.mining.SmartOptimizer
import com.universalbrain.ai.domain.report.ReportGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * MASTER BRAIN — the central agent. Deterministic local intelligence:
 * CommandParser -> IntentAnalyzer -> DecisionEngine -> ToolRegistry tools ->
 * TaskExecutor (this class) -> MemoryManager. Every step emits events so the
 * chat UI can stream tool status. Nothing is faked: tools read live app data
 * and only perform writes the user confirmed.
 */
class AIBrain(
    private val container: AppContainer,
    private val memory: MemoryManager
) {

    sealed interface AgentEvent {
        data object Thinking : AgentEvent
        data class ToolStarted(val tool: String) : AgentEvent
        data class ToolDone(val tool: String, val summary: String) : AgentEvent
        data class NeedsConfirmation(val planId: String, val plan: DecisionEngine.Plan, val preview: String) : AgentEvent
        data class Final(val text: String) : AgentEvent
    }

    /** Pending confirmation plans awaiting user approval, keyed by planId. */
    private val pending = linkedMapOf<String, DecisionEngine.Plan>()

    fun pendingPlan(planId: String): DecisionEngine.Plan? = pending[planId]

    fun clearPending(planId: String) {
        pending.remove(planId)
    }

    fun handle(userText: String): Flow<AgentEvent> = flow {
        emit(AgentEvent.Thinking)
        val parsed = CommandParser.parse(userText)
        val analysis = IntentAnalyzer.analyze(parsed)
        val plan = DecisionEngine.plan(analysis)
        val requiresConfirmation = plan.steps.any { it.requiresConfirmation }

        if (requiresConfirmation) {
            val planId = UUID.randomUUID().toString()
            pending[planId] = plan
            val preview = plan.steps.joinToString("\n") { "- [${it.tool}] ${it.description}" }
            emit(AgentEvent.NeedsConfirmation(planId, plan, preview))
            emit(AgentEvent.Final("This plan needs your approval:\n$preview"))
            return@flow
        }

        executePlan(plan, analysis).collect { emit(it) }
    }

    /** Executes an approved plan (confirmation flow) and returns the final text. */
    suspend fun executeApproved(plan: DecisionEngine.Plan): String {
        val analysis = IntentAnalyzer.Analysis(plan.intent, 1.0, false)
        var finalText = ""
        executePlan(plan, analysis).collect { event ->
            if (event is AgentEvent.Final) finalText = event.text
        }
        return finalText
    }

    private fun executePlan(plan: DecisionEngine.Plan, analysis: IntentAnalyzer.Analysis): Flow<AgentEvent> = flow {
        val results = mutableListOf<String>()
        plan.steps.forEach { step ->
            emit(AgentEvent.ToolStarted(step.tool))
            val result = runCatching { executeTool(step) }
                .getOrElse { "Tool ${step.tool} failed: ${it.message ?: it.javaClass.simpleName}" }
            results += result
            emit(AgentEvent.ToolDone(step.tool, result.take(120)))
        }
        val final = composeFinal(plan.intent, results)
        memory.remember(MemoryManager.Keys.LAST_REPORT, final.take(2000))
        container.db.opsDao().insertLog(
            ActivityLog(
                id = UUID.randomUUID().toString(),
                at = System.currentTimeMillis(),
                module = "AI_BRAIN",
                action = plan.intent.name,
                detail = "Executed ${plan.steps.size} tool(s)"
            )
        )
        emit(AgentEvent.Final(final))
    }

    private suspend fun executeTool(step: DecisionEngine.PlanStep): String {
        val permission = ToolRegistry.permissionOf(step.tool)
            ?: return "Unknown tool: ${step.tool}"
        return when (step.tool) {
            "query_accounts" -> {
                val accounts = container.db.accountDao().observeAll().first()
                if (accounts.isEmpty()) {
                    "No accounts configured yet. Add accounts in the Account Manager."
                } else {
                    val filtered = if (step.args["filter"] == "DISCONNECTED") {
                        accounts.filter { it.status != "ACTIVE" }
                    } else accounts
                    if (filtered.isEmpty()) "All ${accounts.size} accounts are ACTIVE. No disconnected accounts."
                    else filtered.joinToString("\n") {
                        "- ${it.label} [${it.groupName}] ${it.status} (@${it.username})"
                    }
                }
            }
            "query_instances" -> {
                val instances = container.db.cloneDao().observeAll().first()
                if (instances.isEmpty()) "No clone instances yet. Create one in the Multi-Clone Manager."
                else instances.joinToString("\n") {
                    "- ${it.name} (${it.kind}) ${it.status} · ${it.appPackage}"
                }
            }
            "launch_instances" -> {
                val instances = container.db.cloneDao().observeAll().first()
                    .filter { it.status == "ACTIVE" || it.status == "CREATED" || it.status == "IDLE" }
                if (instances.isEmpty()) {
                    "No launchable instances."
                } else {
                    var launched = 0
                    var errors = 0
                    instances.forEach { instance ->
                        val error = container.cloneRepository.launch(instance)
                        if (error == null) launched++ else errors++
                    }
                    "Launched $launched instance(s)${if (errors > 0) ", $errors failed (see Activity History)" else ""}."
                }
            }
            "create_instance" -> "Use the Multi-Clone Manager to create instances — pick an app and count there."
            "query_mining" -> {
                val workers = container.db.miningDao().observeWorkers().first()
                if (workers.isEmpty()) {
                    "No mining workers configured. Add one in Mining Configuration with your pool API URL + wallet."
                } else {
                    workers.joinToString("\n") { worker ->
                        "- ${worker.name} [${worker.coin}] ${worker.status} · " +
                            SmartOptimizer.formatHashrate(worker.lastHashrate) +
                            " · unpaid ${"%.8f".format(worker.lastUnpaid)}" +
                            (worker.lastError?.let { " · ERROR: $it" } ?: "")
                    }
                }
            }
            "query_earnings" -> {
                val today = container.db.miningDao().observeEarnings(50).first()
                if (today.isEmpty()) {
                    "No earnings recorded yet. Earnings are estimated from pool reward rates during worker polls."
                } else {
                    val grouped = today.groupBy {
                        DateFormat.getDateInstance().format(Date(it.at))
                    }
                    grouped.entries.take(7).joinToString("\n") { (day, list) ->
                        "- $day: ${"%.8f".format(list.sumOf { it.amount })} ${list.first().currency} (${list.size} record(s), estimated)"
                    }
                }
            }
            "analyze_optimization" -> {
                val vitals = container.deviceMonitor.sample()
                val network = container.networkMonitor.state.value
                val workers = container.db.miningDao().observeWorkers().first()
                val input = SmartOptimizer.OptimizerInput(
                    vitals = vitals,
                    networkConnected = network.connected,
                    workerCount = workers.size,
                    onlineWorkers = workers.count { it.status == "ONLINE" },
                    pollIntervalMin = container.miningRepository.baseIntervalMin.first(),
                    totalHashrate = workers.sumOf { it.lastHashrate }
                )
                val recommendations = SmartOptimizer.analyze(input)
                memory.remember(MemoryManager.Keys.LAST_OPTIMIZATION, recommendations.joinToString(" | ") { it.title })
                recommendations.joinToString("\n") { "[${it.priority}] ${it.title} — ${it.detail}" }
            }
            "configure_automation" -> {
                val rule = com.universalbrain.ai.core.database.AutomationRule(
                    id = UUID.randomUUID().toString(),
                    name = "AI: pause mining on high temperature",
                    conditionType = step.args["condition"] ?: "BATTERY_TEMP_OVER",
                    conditionValue = step.args["value"] ?: "43",
                    actionType = step.args["action"] ?: "PAUSE_MINING",
                    actionValue = "",
                    enabled = true,
                    lastTriggeredAt = null
                )
                container.db.automationDao().insert(rule)
                "Automation rule created and enabled: if ${rule.conditionType} >= ${rule.conditionValue}C then ${rule.actionType}."
            }
            "query_device" -> {
                val vitals = container.deviceMonitor.sample()
                "RAM: ${vitals.ramUsedPct.toInt()}% used (${vitals.ramAvailMb}/${vitals.ramTotalMb} MB free) · " +
                    "CPU: ${vitals.cpuPct?.let { "${it.toInt()}%" } ?: "N/A"} · " +
                    "Battery: ${vitals.batteryLevelPct}% at ${vitals.batteryTempC}C · " +
                    "Storage free: ${"%.1f".format(vitals.storageFreeGb)} GB"
            }
            "generate_report" -> {
                val vitals = container.deviceMonitor.sample()
                val network = container.networkMonitor.state.value
                val since = startingOfToday()
                ReportGenerator.performanceReport(
                    ReportGenerator.ReportInput(
                        generatedAt = System.currentTimeMillis(),
                        importedApps = container.db.appDao().observeCount().first(),
                        instances = container.db.cloneDao().count(),
                        activeInstances = container.db.cloneDao().observeAll().first().count { it.status == "ACTIVE" },
                        accounts = container.db.accountDao().observeCount().first(),
                        activeAccounts = container.db.accountDao().observeActiveCount().first(),
                        workers = container.db.miningDao().observeCount().first(),
                        onlineWorkers = container.db.miningDao().observeOnlineCount().first(),
                        totalHashrate = container.db.miningDao().observeTotalHashrate().first(),
                        earningsToday = container.db.miningDao().earningsSince(since),
                        currency = "COIN",
                        ramUsedPct = vitals.ramUsedPct,
                        cpuPct = vitals.cpuPct,
                        batteryTempC = vitals.batteryTempC,
                        batteryLevelPct = vitals.batteryLevelPct,
                        networkConnected = network.connected,
                        automationRulesEnabled = container.db.automationDao().enabledRules().size,
                        runningBackgroundTasks = container.db.opsDao().observeRunningCount().first()
                    )
                )
            }
            "control_engine" -> {
                if (step.args["action"] == "START") {
                    container.startEngine()
                    "Background engine started."
                } else {
                    container.stopEngine()
                    "Background engine stopped."
                }
            }
            "help" -> helpText()
            else -> "Tool ${step.tool} is not implemented."
        }.also {
            if (permission == ToolRegistry.Permission.WRITE) {
                container.db.opsDao().insertLog(
                    ActivityLog(
                        id = UUID.randomUUID().toString(),
                        at = System.currentTimeMillis(),
                        module = "AI_TOOL",
                        action = step.tool,
                        detail = null
                    )
                )
            }
        }
    }

    private fun composeFinal(intent: IntentAnalyzer.Intent, results: List<String>): String {
        val header = when (intent) {
            IntentAnalyzer.Intent.SHOW_ACCOUNTS -> "Your accounts:"
            IntentAnalyzer.Intent.CHECK_DISCONNECTED_ACCOUNTS -> "Disconnected account check:"
            IntentAnalyzer.Intent.SHOW_INSTANCES -> "Your clone instances:"
            IntentAnalyzer.Intent.OPEN_INSTANCES -> "Launch result:"
            IntentAnalyzer.Intent.MINING_PERFORMANCE -> "Mining performance:"
            IntentAnalyzer.Intent.SHOW_EARNINGS -> "Earnings:"
            IntentAnalyzer.Intent.OPTIMIZE_MINING -> "Optimization analysis:"
            IntentAnalyzer.Intent.DEVICE_STATUS -> "Device status:"
            IntentAnalyzer.Intent.GENERATE_REPORT -> ""
            else -> "Result:"
        }
        return if (header.isEmpty()) results.joinToString("\n\n") else "$header\n${results.joinToString("\n\n")}"
    }

    fun helpText(): String = buildString {
        appendLine("I am MASTER BRAIN, your local agent. Real commands I understand:")
        appendLine("- Show all my accounts")
        appendLine("- Check which accounts are disconnected")
        appendLine("- Show my instances / Open all active instances")
        appendLine("- Check mining performance")
        appendLine("- Show today's earnings")
        appendLine("- Optimize the mining configuration")
        appendLine("- Pause mining when temperature becomes high")
        appendLine("- Generate a complete performance report")
        appendLine("- Device status")
        appendLine("- Start/stop the engine")
        append("Tools I may use are listed in the Master Brain screen, each with its permission level.")
    }

    private fun startingOfToday(): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
