package com.universalbrain.ai.domain.mining

/**
 * Normalized mining pool stats. All fields nullable — pools expose different
 * subsets; the UI shows N/A honestly for anything the API did not provide.
 */
data class PoolStats(
    val hashrate: Double?,
    val workersOnline: Int?,
    val workersTotal: Int?,
    val unpaid: Double?,
    val coinsPerDay: Double?,
    val validShares: Long?,
    val invalidShares: Long?,
    val lastSeen: String?
) {
    val isEmpty: Boolean get() = hashrate == null && workersOnline == null && unpaid == null
}
