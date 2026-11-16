package com.universalbrain.ai.domain.mining

import kotlinx.serialization.json.JsonObject

/**
 * Tolerant pool-stats parser: walks the JSON tree (handles result/data
 * wrappers) and extracts the first recognizable value for each metric.
 * Pure — unit tested against real pool response shapes.
 */
object MiningStatsParser {

    fun parse(root: JsonObject): PoolStats {
        val flat = mutableMapOf<String, String>()
        walk(root, "", flat)

        fun find(vararg keys: String): Double? =
            keys.firstNotNullOfOrNull { key -> flat.entries.firstOrNull {
                it.key.endsWith(key, ignoreCase = true)
            }?.value?.toDoubleOrNull() }

        fun findExact(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key -> flat.entries.firstOrNull {
                it.key.equals(key, ignoreCase = true)
            }?.value }

        return PoolStats(
            hashrate = find("hashrate", "hashRate", "currentHashrate", "reportedHashrate"),
            workersOnline = find("workersOnline", "workers", "activeWorkers")?.toInt(),
            workersTotal = find("workersTotal")?.toInt(),
            unpaid = find("unpaid", "unpaidBalance", "balance"),
            coinsPerDay = find("coinsPerDay", "ethPerDay", "btcPerDay", "rewardsPerDay"),
            validShares = find("validShares")?.toLong(),
            invalidShares = find("invalidShares", "invalidSharesRate")?.toLong(),
            lastSeen = findExact("lastSeen")
        )
    }

    private fun walk(node: Any?, prefix: String, out: MutableMap<String, String>) {
        when (node) {
            is JsonObject -> node.forEach { (key, value) ->
                walk(value, if (prefix.isEmpty()) key else "$prefix.$key", out)
            }
            else -> if (prefix.isNotEmpty() && node != null) {
                out[prefix] = node.toString()
            }
        }
    }
}
