package com.universalbrain.ai.domain.ai

/**
 * Maps normalized commands to agent intents via scored keyword matching.
 * This is a real deterministic NLU layer (not an LLM) - transparent and
 * unit tested. Remote LLM bridging is an optional separate path.
 */
object IntentAnalyzer {

    enum class Intent {
        SHOW_ACCOUNTS,
        SHOW_INSTANCES,
        OPEN_INSTANCES,
        CREATE_INSTANCE,
        CHECK_DISCONNECTED_ACCOUNTS,
        MINING_PERFORMANCE,
        SHOW_EARNINGS,
        OPTIMIZE_MINING,
        PAUSE_MINING_ON_TEMP,
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
        val mentionsToday: Boolean
    )

    private val keywordScores: Map<Intent, List<String>> = mapOf(
        Intent.SHOW_ACCOUNTS to listOf("account", "accounts"),
        Intent.SHOW_INSTANCES to listOf("instance", "instances", "clone", "clones"),
        Intent.OPEN_INSTANCES to listOf("open", "launch", "start instances"),
        Intent.CREATE_INSTANCE to listOf("create instance", "create clone", "new clone", "new instance"),
        Intent.CHECK_DISCONNECTED_ACCOUNTS to listOf("disconnected", "offline account", "logged out"),
        Intent.MINING_PERFORMANCE to listOf("mining performance", "hashrate", "hash rate", "worker status", "mining status"),
        Intent.SHOW_EARNINGS to listOf("earning", "earnings", "revenue", "income", "profit"),
        Intent.OPTIMIZE_MINING to listOf("optimize", "optimal", "best configuration", "improve mining"),
        Intent.PAUSE_MINING_ON_TEMP to listOf("pause mining", "stop mining", "temperature high", "when temperature"),
        Intent.GENERATE_REPORT to listOf("report", "summary", "summarize"),
        Intent.DEVICE_STATUS to listOf("cpu", "ram", "memory", "battery", "temperature", "device status", "storage"),
        Intent.START_ENGINE to listOf("start engine", "start background", "start monitoring", "start service"),
        Intent.STOP_ENGINE to listOf("stop engine", "stop background", "stop monitoring", "stop service"),
        Intent.HELP to listOf("help", "what can you do", "commands", "abilities")
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

        val confidence = if (bestScore == 0.0) 0.0 else (bestScore / 3.0).coerceAtMost(1.0)
        return Analysis(bestIntent, confidence, parsed.mentionsToday)
    }
}
