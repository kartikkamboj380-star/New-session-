package com.universalbrain.ai.feature.aiagent

import com.universalbrain.ai.AppContainer
import com.universalbrain.ai.core.database.ActivityLog
import com.universalbrain.ai.core.database.ImportedApp
import com.universalbrain.ai.domain.ai.CommandParser
import com.universalbrain.ai.domain.ai.DecisionEngine
import com.universalbrain.ai.domain.ai.IntentAnalyzer
import com.universalbrain.ai.domain.ai.RecommendationEngine
import com.universalbrain.ai.domain.ai.ToolRegistry
import com.universalbrain.ai.domain.ads.AdsEngine
import com.universalbrain.ai.domain.cloning.ClonePolicy
import com.universalbrain.ai.domain.mining.SmartOptimizer
import com.universalbrain.ai.domain.perf.PerformanceProfile
import com.universalbrain.ai.domain.report.ReportGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * MASTER BRAIN - the central agent. Deterministic local intelligence:
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

    object MemoryKeys {
        const val BATTERY_SAVER = "battery_saver"
        const val ADS_CONSENT = "ads_consent"
        const val ADS_STREAK_DAYS = "ads_streak_days"
        const val ADS_LAST_WATCH_DAY = "ads_last_watch_day"
        const val LLM_CONFIGURED = "llm_configured"
        const val LAST_RECOMMENDATIONS = "last_recommendations"
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
        val plan = DecisionEngine.plan(analysis, parsed)
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

    // ---------- helpers ----------

    private suspend fun findInstance(query: String) =
        container.db.cloneDao().observeAll().first().firstOrNull {
            query.isBlank() ||
                it.name.lowercase().contains(query) ||
                it.appLabel.lowercase().contains(query) ||
                it.appPackage.lowercase().contains(query) ||
                "#${it.index}" == query || it.id == query
        }

    private suspend fun findAccount(query: String) =
        container.db.accountDao().observeAll().first().firstOrNull {
            it.label.lowercase().contains(query) || it.username.lowercase().contains(query)
        }

    private fun findInstalledApp(query: String) =
        container.cloneRepository.installedApps().firstOrNull {
            it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
        }

    private suspend fun batterySaverOn(): Boolean =
        memory.recall(MemoryKeys.BATTERY_SAVER) == "true"

    private suspend fun adsState(): AdsEngine.State {
        val dao = container.db.adRewardDao()
        val balance = dao.totalPoints() ?: 0
        val dayStart = startingOfToday()
        val todayCount = dao.countSince(dayStart)
        val streak = memory.recall(MemoryKeys.ADS_STREAK_DAYS)?.toIntOrNull() ?: 0
        val consent = memory.recall(MemoryKeys.ADS_CONSENT) == "true"
        // Honest: no rewarded-ad SDK is integrated in this build, so the
        // provider is NOT configured. Points can only come from a real SDK
        // callback wired through UbaViewModel.recordAdReward.
        return AdsEngine.state(balance, todayCount, streak, consent, providerConfigured = false)
    }

    private suspend fun performanceProfile(): PerformanceProfile.Profile =
        PerformanceProfile.forDevice(
            container.deviceMonitor.totalRamMb(),
            container.deviceMonitor.isLowRamDevice(),
            batterySaverOn()
        )

    suspend fun recommendationSnapshot(): RecommendationEngine.Snapshot {
        val instances = container.db.cloneDao().observeAll().first()
        val accounts = container.db.accountDao().observeAll().first()
        val workers = container.db.miningDao().observeWorkers().first()
        val vitals = container.deviceMonitor.sample()
        val decision = container.cloneRepository.policy(
            container.deviceMonitor.totalRamMb(),
            container.deviceMonitor.isLowRamDevice()
        )
        return RecommendationEngine.Snapshot(
            importedApps = container.db.appDao().observeCount().first(),
            instances = instances.size,
            maxInstances = decision.maxInstances,
            activeInstances = instances.count { it.status == "ACTIVE" },
            accounts = accounts.size,
            accountsWithoutInstance = accounts.count { it.instanceId == null },
            disconnectedAccounts = accounts.count { it.status != "ACTIVE" },
            workers = workers.size,
            onlineWorkers = workers.count { it.status == "ONLINE" },
            offlineWorkers = workers.count { it.status == "OFFLINE" || it.status == "ERROR" },
            hashrateKnown = workers.any { it.lastSyncAt > 0 },
            batteryTempC = vitals.batteryTempC,
            batteryLevelPct = vitals.batteryLevelPct,
            batteryCharging = vitals.batteryCharging,
            networkConnected = container.networkMonitor.state.value.connected,
            rulesEnabled = container.db.automationDao().enabledRules().size,
            engineRunning = container.engineRunning.value,
            adsConsent = memory.recall(MemoryKeys.ADS_CONSENT) == "true",
            lowRamDevice = container.deviceMonitor.isLowRamDevice(),
            ramUsedPct = vitals.ramUsedPct,
            llmConfigured = memory.recall(MemoryKeys.LLM_CONFIGURED) == "true"
        )
    }

    // ---------- tool executor ----------

    private suspend fun executeTool(step: DecisionEngine.PlanStep): String {
        val permission = ToolRegistry.permissionOf(step.tool)
            ?: return "Unknown tool: ${step.tool}"
        return when (step.tool) {
            "query_accounts" -> {
                val accounts = container.db.accountDao().observeAll().first()
                if (accounts.isEmpty()) {
                    "No accounts configured yet. Add accounts in the Account Manager or tell me: add account \"Name\"."
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
                if (instances.isEmpty()) "No clone instances yet. Create one in the Multi-Clone Manager or say: create web clone."
                else instances.joinToString("\n") {
                    "- ${it.name} (${it.kind}) ${it.status} · ${it.appLabel}" +
                        (if (it.kind == "WEB") " · ${if (it.startUrl.isBlank()) "no URL set" else it.startUrl}" +
                            (if (it.freshStart) " · fresh-start ON" else "") else "")
                }
            }
            "query_apps" -> {
                val apps = container.db.appDao().observeAll().first()
                if (apps.isEmpty()) {
                    "No apps imported yet. Open App Import to scan, or say: import apps."
                } else {
                    "Imported ${apps.size} app(s):\n" + apps.take(10).joinToString("\n") {
                        "- ${it.label} (${it.packageName}) v${it.versionName} · ${it.compatibility}"
                    } + if (apps.size > 10) "\n… and ${apps.size - 10} more (App Import screen)" else ""
                }
            }
            "query_rules" -> {
                val rules = container.db.automationDao().observeRules().first()
                if (rules.isEmpty()) "No automation rules yet. Say e.g.: notify me when network is lost."
                else rules.joinToString("\n") {
                    "- ${it.name}: IF ${it.conditionType} ${it.conditionValue} THEN ${it.actionType} (${if (it.enabled) "enabled" else "disabled"})"
                }
            }
            "open_app" -> {
                val target = step.args["target"].orEmpty()
                if (target.isBlank()) {
                    "Which app should I open? Say e.g.: open ctpool"
                } else {
                    val app = findInstalledApp(target)
                    if (app == null) {
                        "No installed app matches \"$target\". Check App Import for exact names."
                    } else {
                        val intent = container.appContext.packageManager.getLaunchIntentForPackage(app.packageName)
                        if (intent == null) "${app.label} has no launcher activity."
                        else {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            container.appContext.startActivity(intent)
                            "Opened ${app.label}. Note: the app opens with its own stored session — for separate sign-ins create WEB clones with isolated storage."
                        }
                    }
                }
            }
            "create_instance" -> {
                val words = step.args
                val kind = words["kind"] ?: "APP"
                val count = (words["count"] ?: "1").toIntOrNull()?.coerceIn(1, 20) ?: 1
                val appQuery = words["app"].orEmpty()
                val url = words["url"].orEmpty()
                val fresh = words["fresh"]?.toBooleanStrictOrNull() ?: (kind == "WEB")
                val app = findInstalledApp(appQuery)
                val label = app?.label ?: appQuery.ifBlank { if (kind == "WEB") "Web App" else "App" }
                val pkg = app?.packageName ?: if (kind == "WEB") "web:$url" : ""
                val ram = container.deviceMonitor.totalRamMb()
                val decision = container.cloneRepository.policy(ram, container.deviceMonitor.isLowRamDevice())
                val created = mutableListOf<String>()
                repeat(count) {
                    val current = container.db.cloneDao().count()
                    if (!ClonePolicy.canCreate(current, decision)) {
                        created += "Stopped at cap ${decision.maxInstances}: ${decision.reason}"
                        return@repeat
                    }
                    val instance = container.cloneRepository.create(pkg, label, kind, null, url, fresh)
                    created += "Created ${instance.name}" + (if (kind == "WEB" && url.isBlank()) " (no URL yet — set one so the sign-in page loads)" else "")
                }
                created.joinToString("\n") + "\nCap: ${decision.maxInstances} (${decision.reason})"
            }
            "delete_instance" -> {
                val target = step.args["target"].orEmpty()
                val instance = findInstance(target)
                if (instance == null) "No instance matches \"$target\". Show instances first."
                else {
                    container.cloneRepository.delete(instance.id)
                    "Deleted ${instance.name} and wiped its data directory."
                }
            }
            "set_instance_url" -> {
                val instance = findInstance(step.args["target"].orEmpty())
                if (instance == null) {
                    "No matching instance. Say e.g.: set url \"CTPool 01\" https://ctpool.com"
                } else if (instance.kind != "WEB") {
                    "${instance.name} is an APP instance — Android launches the real app with its own session. For isolated sign-ins create a WEB clone of the site instead."
                } else {
                    val url = step.args["url"].orEmpty()
                    val fresh = step.args["fresh"]?.toBooleanStrictOrNull() ?: true
                    container.db.cloneDao().update(instance.copy(startUrl = url, freshStart = fresh))
                    "Updated ${instance.name}: URL=$url, fresh-start ${if (fresh) "ON (sign-in page every launch)" else "OFF (session kept)"}"
                }
            }
            "import_apps" -> {
                val installed = container.cloneRepository.installedApps().filter { !it.isSystem }
                val now = System.currentTimeMillis()
                var imported = 0
                installed.forEach { app ->
                    if (container.db.appDao().countByPackage(app.packageName) == 0) {
                        container.db.appDao().insertAll(
                            listOf(
                                ImportedApp(
                                    id = UUID.randomUUID().toString(),
                                    packageName = app.packageName, label = app.label,
                                    versionName = app.versionName, versionCode = app.versionCode,
                                    isSystem = app.isSystem,
                                    compatibility = "SUPPORTED", importedAt = now
                                )
                            )
                        )
                        imported++
                    }
                }
                "Imported $imported user app(s) from ${installed.size} installed. System apps are skipped (limited support)."
            }
            "add_account" -> {
                val label = step.args["label"].orEmpty()
                if (label.isBlank()) "Say e.g.: add account \"CTPool main\" group mining username user1"
                else {
                    val id = UUID.randomUUID().toString()
                    val secret = step.args["secret"].orEmpty()
                    val secretRef = if (secret.isNotBlank()) "acct_$id" else ""
                    if (secret.isNotBlank()) container.secureStore.put(secretRef, secret)
                    container.db.accountDao().insert(
                        com.universalbrain.ai.core.database.Account(
                            id = id, label = label,
                            groupName = step.args["group"]?.ifBlank { "Default" } ?: "Default",
                            username = step.args["username"].orEmpty(),
                            secretRef = secretRef, status = "ACTIVE",
                            instanceId = null,
                            createdAt = System.currentTimeMillis(),
                            lastActiveAt = System.currentTimeMillis()
                        )
                    )
                    "Account \"$label\" created." +
                        (if (secret.isBlank()) " No password stored yet — add it in Account Manager (stored Keystore-encrypted)." else " Secret stored Keystore-encrypted.")
                }
            }
            "assign_account" -> {
                val instance = findInstance(step.args["target"].orEmpty())
                val account = findAccount(step.args["account"].orEmpty())
                when {
                    instance == null -> "No matching instance. Show instances first."
                    account == null -> "No matching account. Show accounts first."
                    else -> {
                        container.db.cloneDao().assignAccount(instance.id, account.id)
                        container.db.accountDao().update(account.copy(instanceId = instance.id))
                        "Assigned ${account.label} to ${instance.name}."
                    }
                }
            }
            "query_mining" -> {
                val workers = container.db.miningDao().observeWorkers().first()
                if (workers.isEmpty()) {
                    "No mining workers configured. Say: add worker \"Worker1\" url https://api.ethermine.org/miner/WALLET/currentStats wallet 0xYourWallet"
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
                val range = step.args["range"] ?: "ALL"
                val records = container.db.miningDao().observeEarnings(200).first()
                if (records.isEmpty()) {
                    "No earnings recorded yet. Earnings are estimated from pool reward rates during worker polls."
                } else {
                    val list = if (range == "TODAY") {
                        val start = startingOfToday()
                        records.filter { it.at >= start }
                    } else records
                    if (list.isEmpty()) "No earnings today yet."
                    else list.groupBy { DateFormat.getDateInstance().format(Date(it.at)) }
                        .entries.take(7).joinToString("\n") { (day, rows) ->
                            "- $day: ${"%.8f".format(rows.sumOf { it.amount })} ${rows.first().currency} (${rows.size} record(s), estimated)"
                        }
                }
            }
            "analyze_optimization" -> {
                val recs = optimizationRecommendations()
                memory.remember(MemoryManager.Keys.LAST_OPTIMIZATION, recs.joinToString(" | ") { it.title })
                recs.joinToString("\n") { "[${it.priority}] ${it.title} — ${it.detail}" }
            }
            "apply_optimization" -> {
                val recs = optimizationRecommendations()
                val base = container.miningRepository.baseIntervalMin.first()
                val hot = container.deviceMonitor.sample().batteryTempC >= 43f
                val newInterval = if (hot) (base * 2).coerceAtMost(120) else base
                container.miningRepository.setBaseInterval(newInterval)
                com.universalbrain.ai.work.MiningPollWorker.schedule(container.appContext, newInterval)
                container.db.miningDao().observeWorkers().first().forEach { worker ->
                    if (worker.lastError != null) {
                        container.db.miningDao().update(worker.copy(status = "PENDING", lastError = null))
                    }
                }
                "Applied: poll interval ${newInterval}min${if (hot) " (thermal doubling — device was hot)" else ""}; " +
                    "reset ${container.db.miningDao().observeWorkers().first().count { it.status == "PENDING" }} errored worker(s) for re-poll. " +
                    "Optimizer notes: " + recs.joinToString(" | ") { it.title }
            }
            "control_mining" -> {
                val pause = step.args["action"] == "PAUSE"
                container.miningRepository.setPaused(pause)
                memory.remember(
                    MemoryManager.Keys.MINING_PAUSE_REASON,
                    if (pause) "Paused by AI command" else ""
                )
                if (pause) "Mining polling paused. Say \"resume mining\" to continue."
                else "Mining polling resumed."
            }
            "add_worker" -> {
                val url = step.args["url"].orEmpty()
                val wallet = step.args["wallet"].orEmpty()
                if (url.isBlank() || wallet.isBlank()) {
                    "I need the pool stats API URL and wallet. Say e.g.: add worker \"W1\" url https://api.ethermine.org/miner/YOUR_WALLET/currentStats wallet YOUR_WALLET coin ETH"
                } else {
                    container.miningRepository.addWorker(
                        step.args["name"].orEmpty(), url, wallet,
                        step.args["coin"].orEmpty().ifBlank { "COIN" },
                        (step.args["interval"] ?: "10").toIntOrNull() ?: 10
                    )
                    com.universalbrain.ai.work.MiningPollWorker.schedule(
                        container.appContext,
                        (step.args["interval"] ?: "10").toIntOrNull() ?: 10
                    )
                    "Worker registered. It will be polled on the next cycle; verify stats on the Mining screen."
                }
            }
            "configure_automation" -> {
                val rule = com.universalbrain.ai.core.database.AutomationRule(
                    id = UUID.randomUUID().toString(),
                    name = "AI: ${step.args["condition"]} rule",
                    conditionType = step.args["condition"] ?: "BATTERY_TEMP_OVER",
                    conditionValue = step.args["value"] ?: "43",
                    actionType = step.args["action"] ?: "NOTIFY",
                    actionValue = "",
                    enabled = true,
                    lastTriggeredAt = null
                )
                container.db.automationDao().insert(rule)
                "Automation rule created and enabled: if ${rule.conditionType} >= ${rule.conditionValue} then ${rule.actionType}."
            }
            "query_device" -> {
                val vitals = container.deviceMonitor.sample()
                "RAM: ${vitals.ramUsedPct.toInt()}% used (${vitals.ramAvailMb}/${vitals.ramTotalMb} MB free) · " +
                    "CPU: ${vitals.cpuPct?.let { "${it.toInt()}%" } ?: "N/A"} · " +
                    "Battery: ${vitals.batteryLevelPct}% at ${vitals.batteryTempC}C${if (vitals.batteryCharging) " (charging)" else ""} · " +
                    "Storage free: ${"%.1f".format(vitals.storageFreeGb)} GB · " +
                    "Profile: ${performanceProfile().tier} (${performanceProfile().reason})"
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
            "recommend" -> {
                val snapshot = recommendationSnapshot()
                val recs = RecommendationEngine.recommendations(snapshot)
                memory.remember(MemoryKeys.LAST_RECOMMENDATIONS, recs.joinToString(" | ") { it.title })
                if (recs.isEmpty()) "Everything looks healthy — no recommendations right now. Run a report for the full picture."
                else "Top actions I recommend now:\n" + recs.joinToString("\n\n") {
                    "${it.priority}. ${it.title}\n   ${it.detail}\n   → run: \"${it.command}\""
                }
            }
            "ads_status" -> {
                val state = adsState()
                "Ads: ${state.balance} point(s) · today ${state.todayCount}/${state.cap} · streak ${state.streakDays} day(s)\n${state.message}"
            }
            "ads_watch" -> {
                val state = adsState()
                if (!state.canWatch) state.message
                else {
                    // Honest: with no SDK integrated there is nothing to show.
                    "Consent is on and the ledger is ready, but this build has no rewarded-ad SDK integrated, so I cannot serve a real ad yet. Once a provider SDK is integrated (Settings notes where), I will schedule sessions up to ${AdsEngine.DAILY_CAP}/day and record points only from real rewarded callbacks."
                }
            }
            "control_battery_saver" -> {
                val on = step.args["on"]?.toBooleanStrictOrNull() ?: true
                memory.remember(MemoryKeys.BATTERY_SAVER, on.toString())
                val profile = performanceProfile()
                com.universalbrain.ai.work.MiningPollWorker.schedule(container.appContext, profile.defaultPollMinutes)
                if (on) "Battery saver ON: ${profile.reason}. Vitals every ${profile.vitalsSampleMs / 1000}s, mining poll every ${profile.defaultPollMinutes}min."
                else "Battery saver OFF: back to ${profile.tier} profile (vitals every ${profile.vitalsSampleMs / 1000}s)."
            }
            "wipe_secrets" -> {
                container.secureStore.clearAll()
                "All stored secrets wiped from the Keystore-backed store (account passwords, LLM key)."
            }
            "launch_instances" -> {
                val instances = container.db.cloneDao().observeAll().first()
                    .filter { it.status == "ACTIVE" || it.status == "CREATED" || it.status == "IDLE" }
                if (instances.isEmpty()) {
                    "No launchable instances."
                } else {
                    var launched = 0
                    var errors = 0
                    val messages = mutableListOf<String>()
                    instances.forEach { instance ->
                        val error = container.cloneRepository.launch(instance)
                        if (error == null) launched++ else { errors++; messages += error }
                    }
                    "Launched $launched instance(s)${if (errors > 0) ", $errors failed: ${messages.joinToString("; ")}" else ""}."
                }
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
            "help" -> helpText(step.args["suggestion"].orEmpty())
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

    private suspend fun optimizationRecommendations() =
        SmartOptimizer.analyze(optimizationInput())

    private suspend fun optimizationInput(): SmartOptimizer.OptimizerInput {
        val vitals = container.deviceMonitor.sample()
        val network = container.networkMonitor.state.value
        val workers = container.db.miningDao().observeWorkers().first()
        return SmartOptimizer.OptimizerInput(
            vitals = vitals,
            networkConnected = network.connected,
            workerCount = workers.size,
            onlineWorkers = workers.count { it.status == "ONLINE" },
            pollIntervalMin = container.miningRepository.baseIntervalMin.first(),
            totalHashrate = workers.sumOf { it.lastHashrate }
        )
    }

    private fun composeFinal(intent: IntentAnalyzer.Intent, results: List<String>): String {
        val header = when (intent) {
            IntentAnalyzer.Intent.SHOW_ACCOUNTS -> "Your accounts:"
            IntentAnalyzer.Intent.ADD_ACCOUNT -> "Account created:"
            IntentAnalyzer.Intent.ASSIGN_ACCOUNT -> "Assignment result:"
            IntentAnalyzer.Intent.CHECK_DISCONNECTED_ACCOUNTS -> "Disconnected account check:"
            IntentAnalyzer.Intent.SHOW_INSTANCES -> "Your clone instances:"
            IntentAnalyzer.Intent.OPEN_INSTANCES -> "Launch result:"
            IntentAnalyzer.Intent.OPEN_APP -> "Launch result:"
            IntentAnalyzer.Intent.CREATE_INSTANCE -> "Instance creation:"
            IntentAnalyzer.Intent.DELETE_INSTANCE -> "Deletion result:"
            IntentAnalyzer.Intent.SET_INSTANCE_URL -> "Instance config:"
            IntentAnalyzer.Intent.LIST_APPS -> "Your imported apps:"
            IntentAnalyzer.Intent.IMPORT_APPS -> "Import result:"
            IntentAnalyzer.Intent.MINING_PERFORMANCE -> "Mining performance:"
            IntentAnalyzer.Intent.SHOW_EARNINGS -> "Earnings:"
            IntentAnalyzer.Intent.OPTIMIZE_MINING -> "Optimization analysis:"
            IntentAnalyzer.Intent.APPLY_OPTIMIZATION -> "Applied optimization:"
            IntentAnalyzer.Intent.MINING_PAUSE, IntentAnalyzer.Intent.MINING_RESUME -> "Mining control:"
            IntentAnalyzer.Intent.MINING_ADD_WORKER -> "Worker registration:"
            IntentAnalyzer.Intent.AUTOMATION_LIST -> "Automation rules:"
            IntentAnalyzer.Intent.AUTOMATION_CREATE, IntentAnalyzer.Intent.PAUSE_MINING_ON_TEMP -> "Automation:"
            IntentAnalyzer.Intent.ADS_STATUS -> "Ads status:"
            IntentAnalyzer.Intent.ADS_WATCH -> "Rewarded ads:"
            IntentAnalyzer.Intent.BATTERY_SAVER -> "Battery saver:"
            IntentAnalyzer.Intent.WIPE_SECRETS -> "Security wipe:"
            IntentAnalyzer.Intent.RECOMMEND -> "Recommendations:"
            IntentAnalyzer.Intent.DEVICE_STATUS -> "Device status:"
            else -> "Result:"
        }
        return if (header.isEmpty()) results.joinToString("\n\n") else "$header\n${results.joinToString("\n\n")}"
    }

    /** Live system prompt for the optional LLM bridge, grounded in real app state. */
    suspend fun contextSummary(): String {
        val s = recommendationSnapshot()
        val ads = adsState()
        return buildString {
            append("Device: ${container.deviceMonitor.totalRamMb()}MB RAM, lowRam=${s.lowRamDevice}, temp=${s.batteryTempC}C, battery=${s.batteryLevelPct}%, network=${if (s.networkConnected) "ON" else "OFF"}. ")
            append("Apps imported=${s.importedApps}, instances=${s.instances}/${s.maxInstances} (active ${s.activeInstances}), accounts=${s.accounts} (${s.disconnectedAccounts} disconnected, ${s.accountsWithoutInstance} unassigned). ")
            append("Mining workers=${s.workers} (online ${s.onlineWorkers}, offline ${s.offlineWorkers}), engineRunning=${s.engineRunning}, rules=${s.rulesEnabled}. ")
            append("Ads: ${ads.balance} pts, today ${ads.todayCount}/${ads.cap}. Battery saver=${batterySaverOn()}.")
        }
    }

    fun helpText(suggestion: String = ""): String = buildString {
        if (suggestion.isNotBlank()) appendLine("I didn't fully understand that. $suggestion")
        appendLine("I am MASTER BRAIN — here is what I can really do (all commands run real tools):")
        appendLine("APPS & CLONES: import apps · list apps · create web clone of \"App\" · set url \"Name\" https://site · open ctpool · open all active instances · delete \"Name\" clone · show instances")
        appendLine("ACCOUNTS: add account \"Name\" group work · assign account to \"Instance\" · show accounts · which accounts are disconnected")
        appendLine("MINING: add worker \"W1\" url https://api.../wallet/currentStats wallet 0x.. coin ETH · check mining performance · show today's earnings · optimize mining · apply optimization · pause mining · resume mining")
        appendLine("AUTOMATION & ENGINE: notify me when network is lost · pause mining when temperature is over 45 · show rules · start/stop the engine")
        appendLine("DEVICE & POWER: device status · battery saver on/off")
        appendLine("ADS & SECURITY: ads status · watch ads · wipe all secrets")
        appendLine("META: recommend · generate a report · help")
        append("Destructive actions always ask for your confirmation first.")
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
