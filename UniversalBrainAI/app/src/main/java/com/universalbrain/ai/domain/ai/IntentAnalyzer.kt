package com.universalbrain.ai.domain.ai

/**
 * Maps normalized commands to agent intents via scored keyword matching with
 * synonyms, verb-target disambiguation and nearest-intent suggestions.
 * Deterministic local NLU - transparent and unit tested. Remote LLM bridging
 * is an optional separate path on top of this tool brain.
 */
object IntentAnalyzer {

    enum class Intent {
        SHOW_ACCOUNTS,
        SHOW_INSTANCES,
        OPEN_INSTANCES,
        CREATE_INSTANCE,
        DELETE_INSTANCE,
        SET_INSTANCE_URL,
        LIST_APPS,
        IMPORT_APPS,
        OPEN_APP,
        ADD_ACCOUNT,
        ASSIGN_ACCOUNT,
        CHECK_DISCONNECTED_ACCOUNTS,
        MINING_PERFORMANCE,
        SHOW_EARNINGS,
        OPTIMIZE_MINING,
        APPLY_OPTIMIZATION,
        MINING_PAUSE,
        MINING_RESUME,
        MINING_ADD_WORKER,
        PAUSE_MINING_ON_TEMP,
        AUTOMATION_LIST,
        AUTOMATION_CREATE,
        ADS_STATUS,
        ADS_WATCH,
        BATTERY_SAVER,
        WIPE_SECRETS,
        RECOMMEND,
        GENERATE_REPORT,
        DEVICE_STATUS,
        START_ENGINE,
        STOP_ENGINE,
        HELP,
        UNKNOWN
    }

    data class Analysis(
        val intent: Intent,
        val confidence: Double,
        val mentionsToday: Boolean,
        val suggestion: String? = null
    )

    private val keywordScores: Map<Intent, List<String>> = linkedMapOf(
        Intent.PAUSE_MINING_ON_TEMP to listOf("when temperature", "temperature high", "temp high", "temperature becomes", "pause mining when", "stop mining when", "when temp"),
        Intent.MINING_PAUSE to listOf("pause mining", "stop mining", "mining pause", "halt mining"),
        Intent.MINING_RESUME to listOf("resume mining", "start mining", "unpause mining", "continue mining", "restart mining"),
        Intent.MINING_ADD_WORKER to listOf("add worker", "add mining worker", "new worker", "track pool", "monitor pool", "add pool"),
        Intent.OPTIMIZE_MINING to listOf("optimize", "optimal", "best configuration", "improve mining", "optimize mining"),
        Intent.APPLY_OPTIMIZATION to listOf("apply optimization", "apply optimal", "apply settings", "apply recommendations", "use optimal settings"),
        Intent.MINING_PERFORMANCE to listOf("mining performance", "hashrate", "hash rate", "worker status", "mining status", "mining health", "pool status"),
        Intent.SHOW_EARNINGS to listOf("earning", "earnings", "revenue", "income", "profit", "unpaid balance"),
        Intent.SHOW_ACCOUNTS to listOf("account", "accounts", "profiles"),
        Intent.ADD_ACCOUNT to listOf("add account", "new account", "register account", "create account"),
        Intent.ASSIGN_ACCOUNT to listOf("assign account", "attach account", "link account", "assign to instance"),
        Intent.CHECK_DISCONNECTED_ACCOUNTS to listOf("disconnected", "offline account", "logged out", "which accounts are not"),
        Intent.SHOW_INSTANCES to listOf("instance", "instances", "clone", "clones"),
        Intent.OPEN_INSTANCES to listOf("open instance", "open instances", "launch instances", "open clone", "launch clone"),
        Intent.CREATE_INSTANCE to listOf("create instance", "create clone", "new clone", "new instance", "add instance", "bulk create"),
        Intent.DELETE_INSTANCE to listOf("delete instance", "delete clone", "remove instance", "remove clone"),
        Intent.SET_INSTANCE_URL to listOf("set url", "change url", "set website", "set link", "instance url"),
        Intent.LIST_APPS to listOf("imported apps", "my apps", "app list", "list apps", "installed apps", "scan apps"),
        Intent.IMPORT_APPS to listOf("import apps", "import all", "scan and import", "import installed"),
        Intent.OPEN_APP to listOf("open app", "launch app", "open application", "start app"),
        Intent.ADS_STATUS to listOf("ads status", "ad status", "ad points", "reward points", "ads points"),
        Intent.ADS_WATCH to listOf("watch ads", "watch ad", "view ads", "see ads", "run ads", "ads on", "watch rewards", "show ads"),
        Intent.AUTOMATION_LIST to listOf("automation rules", "my rules", "rules list", "list rules", "show rules"),
        Intent.AUTOMATION_CREATE to listOf("create rule", "new rule", "add rule", "automation rule", "notify me when"),
        Intent.BATTERY_SAVER to listOf("battery saver", "power saving", "low power mode", "battery saving"),
        Intent.WIPE_SECRETS to listOf("wipe secrets", "wipe all secrets", "delete all secrets", "clear secrets", "wipe passwords"),
        Intent.RECOMMEND to listOf("recommend", "suggestion", "suggestions", "what should i do", "any idea", "advise", "advice", "improve my setup"),
        Intent.GENERATE_REPORT to listOf("report", "summary", "summarize", "full status"),
        Intent.DEVICE_STATUS to listOf("cpu", "ram", "memory", "battery", "temperature", "device status", "storage", "vitals"),
        Intent.START_ENGINE to listOf("start engine", "start background", "start monitoring", "start service", "start brain"),
        Intent.STOP_ENGINE to listOf("stop engine", "stop background", "stop monitoring", "stop service", "stop brain"),
        Intent.HELP to listOf("help", "what can you do", "commands", "abilities", "how do i")
    )

    private val instanceWords = setOf("instance", "instances", "clone", "clones")
    private val appNamesBlacklist = setOf(
        "all", "my", "the", "a", "an", "active", "instance", "instances", "clone", "clones",
        "engine", "brain", "monitoring", "background", "service", "mining", "miners", "app",
        "apps", "application", "accounts", "account", "ads", "report", "rules"
    )

    fun analyze(parsed: CommandParser.ParsedCommand): Analysis {
        var bestIntent = Intent.UNKNOWN
        var bestScore = 0.0

        keywordScores.forEach { (intent, keywords) ->
            var score = 0.0
            keywords.forEach { keyword ->
                if (keyword.contains(" ")) {
                    if (parsed.normalized.contains(keyword)) score += 2.0
                } else if (parsed.words.contains(keyword)) {
                    score += 1.0
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestIntent = intent
            }
        }

        // Disambiguation: "show my instances" vs "open instances".
        if (bestIntent == Intent.SHOW_INSTANCES && parsed.words.contains("open")) {
            bestIntent = Intent.OPEN_INSTANCES
        }
        if (bestIntent == Intent.OPEN_INSTANCES && !parsed.words.any { it == "open" || it == "launch" }) {
            bestIntent = Intent.SHOW_INSTANCES
        }

        // "create web clone" / "new instance" style explicit creation beats the bare noun match.
        if (bestIntent != Intent.CREATE_INSTANCE &&
            (parsed.words.contains("create") || parsed.words.contains("new")) &&
            parsed.words.any { instanceWords.contains(it) } &&
            bestIntent != Intent.DELETE_INSTANCE
        ) {
            bestIntent = Intent.CREATE_INSTANCE
            bestScore = bestScore.coerceAtLeast(2.0)
        }

        // Verb + target: "open ctpool", "launch whatsapp" -> OPEN_APP with target.
        if ((bestIntent == Intent.UNKNOWN || bestIntent == Intent.OPEN_INSTANCES || bestIntent == Intent.SHOW_INSTANCES) &&
            targetAfterVerb(parsed.words) != null
        ) {
            bestIntent = Intent.OPEN_APP
            bestScore = bestScore.coerceAtLeast(2.0)
        }

        // "delete ctpool clone" style targets.
        if (bestIntent == Intent.SHOW_INSTANCES &&
            parsed.words.firstOrNull { it == "delete" || it == "remove" } != null
        ) {
            bestIntent = Intent.DELETE_INSTANCE
        }

        val confidence = if (bestScore == 0.0) 0.0 else (bestScore / 3.0).coerceAtMost(1.0)
        val suggestion = if (bestIntent == Intent.UNKNOWN) nearestIntents(parsed) else null
        return Analysis(bestIntent, confidence, parsed.mentionsToday, suggestion)
    }

    /** The word after open/launch/start when it names an app (not an instance/engine/etc). */
    fun targetAfterVerb(words: List<String>): String? {
        val idx = words.indexOfFirst { it == "open" || it == "launch" || it == "start" }
        if (idx < 0 || idx + 1 >= words.size) return null
        val target = words[idx + 1].trim('.').trim('_')
        if (target in appNamesBlacklist || target in instanceWords || numberWords.containsKey(target)) return null
        if (target.toIntOrNull() != null) return null
        if (words.getOrNull(idx - 1) == "when") return null
        return target
    }

    private val numberWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )

    /** Close intents by shared word tokens so UNKNOWN replies with "did you mean ...". */
    private fun nearestIntents(parsed: CommandParser.ParsedCommand): String? {
        val scored = keywordScores.map { (intent, keywords) ->
            intent to keywords.sumOf { kw ->
                if (kw.contains(" ")) {
                    kw.split(" ").count { parsed.words.contains(it) }
                } else if (parsed.words.contains(kw)) 1 else 0
            }
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        val top = scored.take(2).map { it.first.name.lowercase().replace('_', ' ') }
        return if (top.isEmpty()) null else "Did you mean: ${top.joinToString(" or ")}?"
    }
}
