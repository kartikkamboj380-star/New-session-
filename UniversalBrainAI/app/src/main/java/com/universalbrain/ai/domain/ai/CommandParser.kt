package com.universalbrain.ai.domain.ai

/**
 * Normalizes user chat input for intent analysis and extracts structured
 * parameters (numbers, URLs, wallets, quoted names, thresholds). Pure -
 * unit tested. Sentence punctuation is stripped; decimal points and URLs
 * survive normalization.
 */
object CommandParser {

    private val numberWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "couple" to 2, "few" to 3
    )

    data class ParsedCommand(
        val normalized: String,
        val words: List<String>,
        val mentionsToday: Boolean,
        val mentionsAll: Boolean,
        val number: Int?,
        val url: String?,
        val wallet: String?,
        val quotedName: String?,
        val threshold: Double?,
        val mentionsFresh: Boolean,
        val onOff: Boolean?
    )

    fun parse(raw: String): ParsedCommand {
        val normalized = raw.lowercase()
            .trim()
            .replace(Regex("[^a-z0-9\\s%.:/_-]"), " ")
            // Sentence periods are word separators; decimal points stay attached.
            .replace(Regex("(?<![0-9])\\.(?![0-9])"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val words = normalized.split(" ").filter { it.isNotBlank() }

        val digit = words.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull()
        val wordNum = words.firstNotNullOfOrNull { numberWords[it] }

        val url = Regex("https?://[a-z0-9._:/-]+|\\b[a-z0-9-]+\\.(?:com|net|org|io|xyz|pool|me|app)(?:/[a-z0-9._/-]*)?")
            .find(normalized)?.value

        val walletIdx = words.indexOfFirst { it == "wallet" }
        val wallet = if (walletIdx >= 0 && walletIdx + 1 < words.size && !walletIsStopWord(words[walletIdx + 1]))
            words[walletIdx + 1] else null

        val quoted = Regex("\"([^\"]{1,60})\"|'([^']{1,60})'").find(raw.lowercase())?.groupValues
        val quotedName = quoted?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: quoted?.getOrNull(2)?.takeIf { it.isNotBlank() }

        val thresholdIdx = words.indexOfFirst { it == "over" || it == "above" || it == "under" || it == "below" || it == "crosses" || it == "exceeds" }
        val threshold = if (thresholdIdx >= 0) {
            (words.drop(thresholdIdx + 1).firstNotNullOfOrNull { it.replace("%", "").replace("c", "").toDoubleOrNull() })
        } else null

        return ParsedCommand(
            normalized = normalized,
            words = words,
            mentionsToday = normalized.contains("today") || normalized.contains("tonight"),
            mentionsAll = normalized.contains("all") || normalized.contains("every"),
            number = digit ?: wordNum,
            url = url,
            wallet = wallet,
            quotedName = quotedName,
            threshold = threshold,
            mentionsFresh = normalized.contains("fresh") || normalized.contains("new session") ||
                normalized.contains("sign in") || normalized.contains("signout") ||
                normalized.contains("sign out") || normalized.contains("logout") ||
                normalized.contains("clear session") || normalized.contains("reset session"),
            onOff = when {
                normalized.contains(" off") || words.contains("off") || normalized.contains("disable") -> false
                normalized.contains(" on") || words.contains("on") || normalized.contains("enable") -> true
                else -> null
            }
        )
    }

    private fun walletIsStopWord(word: String): Boolean = word in setOf(
        "address", "is", "to", "the", "of", "for", "and", "with", "from", "balance", "unpaid", "in"
    )
}
