package com.universalbrain.ai.core.networking

import com.universalbrain.ai.domain.mining.MiningStatsParser
import com.universalbrain.ai.domain.mining.PoolStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Minimal documented-protocol HTTP client for mining pool stats APIs. The
 * response shape varies per pool; [MiningStatsParser] walks the JSON tree for
 * well-known keys, so any pool exposing standard stats JSON works
 * (ethermine/nanopool/f2pool style, both `result` and `data` wrappers).
 */
class PoolApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Fetches and parses pool stats; returns null when unreachable/bad JSON. */
    suspend fun fetchStats(apiUrl: String, wallet: String): PoolStats? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = buildUrl(apiUrl, wallet)
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "UniversalBrainAI/1.0")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    val element = json.parseToJsonElement(body)
                    MiningStatsParser.parse(element.jsonObject)
                }
            }.getOrNull()
        }

    private fun buildUrl(apiUrl: String, wallet: String): String {
        val base = apiUrl.trim().trimEnd('/')
        // Supports URL templates containing {wallet} and plain endpoints.
        return if (base.contains("{wallet}")) {
            base.replace("{wallet}", wallet)
        } else if (base.contains(wallet)) {
            base
        } else {
            "$base/$wallet"
        }
    }
}
