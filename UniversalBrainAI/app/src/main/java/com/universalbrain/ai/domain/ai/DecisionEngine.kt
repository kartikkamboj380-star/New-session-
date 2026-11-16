package com.universalbrain.ai.domain.ai

/**
 * Turns an analyzed intent into an explicit execution plan of tool calls.
 * Pure - unit tested. Actions flagged `requiresConfirmation` must be
 * approved by the user in the chat UI before the TaskExecutor runs them.
 * `parsed` is optional so plans for confirmed/replays can be rebuilt with
 * default args.
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

    fun plan(analysis: IntentAnalyzer.Analysis, parsed: CommandParser.ParsedCommand? = null): Plan {
        val steps = when (analysis.intent) {
            IntentAnalyzer.Intent.SHOW_ACCOUNTS -> listOf(
                PlanStep("query_accounts", description = "Read accounts from the database")
            )
            IntentAnalyzer.Intent.CHECK_DISCONNECTED_ACCOUNTS -> listOf(
                PlanStep("query_accounts", args = mapOf("filter" to "DISCONNECTED"), description = "Read accounts and filter disconnected ones")
            )
            IntentAnalyzer.Intent.ADD_ACCOUNT -> listOf(
                PlanStep("add_account", args = addAccountArgs(parsed), requiresConfirmation = true,
                    description = "Add account with credentials into the secure store (needs confirmation)")
            )
            IntentAnalyzer.Intent.ASSIGN_ACCOUNT -> listOf(
                PlanStep("assign_account", args = targetArgs(parsed), requiresConfirmation = true,
                    description = "Assign an account to an instance (needs confirmation)")
            )
            IntentAnalyzer.Intent.SHOW_INSTANCES -> listOf(
                PlanStep("query_instances", description = "Read clone instances from the database")
            )
            IntentAnalyzer.Intent.OPEN_INSTANCES -> listOf(
                PlanStep("query_instances", description = "Read active instances"),
                PlanStep("launch_instances", args = mapOf("scope" to "active"), requiresConfirmation = true,
                    description = "Launch active instances (needs confirmation)")
            )
            IntentAnalyzer.Intent.OPEN_APP -> listOf(
                PlanStep("open_app", args = targetArgs(parsed), description = "Launch the named installed app")
            )
            IntentAnalyzer.Intent.CREATE_INSTANCE -> listOf(
                PlanStep("create_instance", args = createInstanceArgs(parsed), requiresConfirmation = true,
                    description = "Create a new clone instance (needs confirmation)")
            )
            IntentAnalyzer.Intent.DELETE_INSTANCE -> listOf(
                PlanStep("delete_instance", args = targetArgs(parsed), requiresConfirmation = true,
                    description = "Delete a clone instance and its data (needs confirmation)")
            )
            IntentAnalyzer.Intent.SET_INSTANCE_URL -> listOf(
                PlanStep("set_instance_url", args = setUrlArgs(parsed), requiresConfirmation = true,
                    description = "Configure a WEB instance URL and fresh-start behavior (needs confirmation)")
            )
            IntentAnalyzer.Intent.LIST_APPS -> listOf(
                PlanStep("query_apps", description = "Read imported apps and scanner availability")
            )
            IntentAnalyzer.Intent.IMPORT_APPS -> listOf(
                PlanStep("import_apps", args = mapOf("scope" to "USER"), requiresConfirmation = true,
                    description = "Scan installed apps and import them (needs confirmation)")
            )
            IntentAnalyzer.Intent.MINING_PERFORMANCE -> listOf(
                PlanStep("query_mining", description = "Read mining workers and latest stats")
            )
            IntentAnalyzer.Intent.SHOW_EARNINGS -> listOf(
                PlanStep("query_earnings", args = mapOf("range" to if (analysis.mentionsToday) "TODAY" else "ALL"),
                    description = "Read earnings records")
            )
            IntentAnalyzer.Intent.OPTIMIZE_MINING -> listOf(
                PlanStep("analyze_optimization", description = "Analyze device vitals and worker performance")
            )
            IntentAnalyzer.Intent.APPLY_OPTIMIZATION -> listOf(
                PlanStep("analyze_optimization", description = "Compute optimal settings"),
                PlanStep("apply_optimization", requiresConfirmation = true,
                    description = "Apply optimal polling settings to the mining engine (needs confirmation)")
            )
            IntentAnalyzer.Intent.MINING_PAUSE -> listOf(
                PlanStep("control_mining", args = mapOf("action" to "PAUSE"), description = "Pause mining polling (reversible)")
            )
            IntentAnalyzer.Intent.MINING_RESUME -> listOf(
                PlanStep("control_mining", args = mapOf("action" to "RESUME"), description = "Resume mining polling")
            )
            IntentAnalyzer.Intent.MINING_ADD_WORKER -> listOf(
                PlanStep("add_worker", args = addWorkerArgs(parsed), requiresConfirmation = true,
                    description = "Register a mining worker with pool API + wallet (needs confirmation)")
            )
            IntentAnalyzer.Intent.PAUSE_MINING_ON_TEMP -> listOf(
                PlanStep("configure_automation", args = automationArgs(parsed, "BATTERY_TEMP_OVER",
                    (parsed?.threshold ?: 43.0).toString(), "PAUSE_MINING"), requiresConfirmation = true,
                    description = "Create thermal pause automation rule (needs confirmation)")
            )
            IntentAnalyzer.Intent.AUTOMATION_LIST -> listOf(
                PlanStep("query_rules", description = "Read automation rules")
            )
            IntentAnalyzer.Intent.AUTOMATION_CREATE -> listOf(
                PlanStep("configure_automation", args = automationArgs(parsed, null, null, null),
                    requiresConfirmation = true, description = "Create automation rule (needs confirmation)")
            )
            IntentAnalyzer.Intent.ADS_STATUS -> listOf(
                PlanStep("ads_status", description = "Read ads consent, points, cap and streak")
            )
            IntentAnalyzer.Intent.ADS_WATCH -> listOf(
                PlanStep("ads_watch", description = "Check rewarded-ads readiness and queue a session")
            )
            IntentAnalyzer.Intent.BATTERY_SAVER -> listOf(
                PlanStep("control_battery_saver", args = mapOf("on" to (parsed?.onOff?.toString() ?: "true")),
                    description = "Toggle battery saver performance profile")
            )
            IntentAnalyzer.Intent.WIPE_SECRETS -> listOf(
                PlanStep("wipe_secrets", requiresConfirmation = true,
                    description = "Erase all stored secrets from the Keystore-backed store (needs confirmation)")
            )
            IntentAnalyzer.Intent.RECOMMEND -> listOf(
                PlanStep("recommend", description = "Scan every module and build prioritized suggestions")
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
                PlanStep("control_engine", args = mapOf("action" to "START"), requiresConfirmation = true,
                    description = "Start the background engine (needs confirmation)")
            )
            IntentAnalyzer.Intent.STOP_ENGINE -> listOf(
                PlanStep("control_engine", args = mapOf("action" to "STOP"), requiresConfirmation = true,
                    description = "Stop the background engine (needs confirmation)")
            )
            IntentAnalyzer.Intent.HELP -> listOf(
                PlanStep("help", description = "Explain available commands")
            )
            IntentAnalyzer.Intent.UNKNOWN -> listOf(
                PlanStep("help", args = mapOf("suggestion" to (analysis.suggestion ?: "")),
                    description = "Unknown command - offer closest matches and help")
            )
        }
        return Plan(analysis.intent, steps)
    }

    private fun targetArgs(parsed: CommandParser.ParsedCommand?): Map<String, String> {
        val target = parsed?.quotedName
            ?: parsed?.let { IntentAnalyzer.targetAfterVerb(it.words) }
            ?: parsed?.words?.lastOrNull { it !in setOf("delete", "remove", "clone", "instance", "the", "my", "app", "assign", "account", "to", "url", "for", "fresh", "change", "set", "link", "website", "open", "launch") }
            ?: ""
        return mapOf("target" to target)
    }

    private fun createInstanceArgs(parsed: CommandParser.ParsedCommand?): Map<String, String> {
        val words = parsed?.words ?: emptyList()
        val kind = if (words.contains("web") || words.contains("website") || words.contains("browser")) "WEB" else "APP"
        val app = parsed?.quotedName ?: parsed?.let { IntentAnalyzer.targetAfterVerb(it.words) }
            ?: words.getOrNull(words.indexOfFirst { it == "of" || it == "for" } + 1) ?: ""
        val args = mutableMapOf(
            "kind" to kind,
            "count" to (parsed?.number?.toString() ?: "1"),
            "app" to app,
            "fresh" to ((parsed?.mentionsFresh ?: false) || kind == "WEB").toString()
        )
        parsed?.url?.let { args["url"] = it }
        return args
    }

    private fun setUrlArgs(parsed: CommandParser.ParsedCommand?): Map<String, String> {
        val base = targetArgs(parsed).toMutableMap()
        base["url"] = parsed?.url ?: ""
        base["fresh"] = (parsed?.mentionsFresh ?: true).toString()
        return base
    }

    private fun addAccountArgs(parsed: CommandParser.ParsedCommand?): Map<String, String> {
        val words = parsed?.words ?: emptyList()
        val groupIdx = words.indexOfFirst { it == "group" }
        val group = if (groupIdx >= 0 && groupIdx + 1 < words.size) words[groupIdx + 1] else ""
        return mapOf(
            "label" to (parsed?.quotedName ?: parsed?.let { IntentAnalyzer.targetAfterVerb(it.words) } ?: ""),
            "group" to group,
            "username" to (parsed?.wallet ?: ""),
            "secret" to ""
        )
    }

    private fun addWorkerArgs(parsed: CommandParser.ParsedCommand?): Map<String, String> {
        val words = parsed?.words ?: emptyList()
        val coinIdx = words.indexOfFirst { it == "coin" }
        return mapOf(
            "name" to (parsed?.quotedName ?: parsed?.let { IntentAnalyzer.targetAfterVerb(it.words) } ?: ""),
            "url" to (parsed?.url ?: ""),
            "wallet" to (parsed?.wallet ?: ""),
            "coin" to (if (coinIdx >= 0 && coinIdx + 1 < words.size) words[coinIdx + 1] else "COIN"),
            "interval" to ((parsed?.number ?: 10).coerceIn(1, 120)).toString()
        )
    }

    private fun automationArgs(parsed: CommandParser.ParsedCommand?, condition: String?, value: String?, action: String?): Map<String, String> {
        if (condition != null) {
            return mapOf("condition" to condition, "value" to (value ?: "43"), "action" to (action ?: "PAUSE_MINING"))
        }
        val words = parsed?.words ?: emptyList()
        val (cond, defValue) = when {
            words.any { it == "temperature" || it == "temp" } -> "BATTERY_TEMP_OVER" to "43"
            words.any { it == "network" || it == "wifi" || it == "internet" } ->
                if (words.any { it == "lost" || it == "down" || it == "drop" || it == "drops" }) "NETWORK_LOST" to "1"
                else "NETWORK_RESTORED" to "1"
            words.any { it == "worker" || it == "workers" } -> "WORKER_OFFLINE" to "1"
            words.any { it == "battery" } -> "BATTERY_LOW" to "15"
            else -> "BATTERY_TEMP_OVER" to "43"
        }
        val act = if (words.any { it == "pause" || it == "stop" }) "PAUSE_MINING" else "NOTIFY"
        val threshold = parsed?.threshold?.toString() ?: defValue
        return mapOf("condition" to cond, "value" to threshold, "action" to act)
    }
}
