package com.universalbrain.ai.domain.ai

/**
 * Normalizes user chat input for intent analysis. Pure — unit tested.
 */
object CommandParser {

    data class ParsedCommand(
        val normalized: String,
        val words: List<String>,
        val mentionsToday: Boolean,
        val mentionsAll: Boolean,
        val number: Int?
    )

    fun parse(raw: String): ParsedCommand {
        val normalized = raw.lowercase()
            .trim()
            .replace(Regex("[^a-z0-9\\s%.]"), " ")
            // Sentence periods are word separators; decimal points stay attached.
            .replace(Regex("(?<![0-9])\\.(?![0-9])"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val words = normalized.split(" ").filter { it.isNotBlank() }
        return ParsedCommand(
            normalized = normalized,
            words = words,
            mentionsToday = normalized.contains("today") || normalized.contains("tonight"),
            mentionsAll = normalized.contains("all") || normalized.contains("every"),
            number = words.firstNotNullOfOrNull { it.toIntOrNull() }
        )
    }
}
