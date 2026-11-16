package com.universalbrain.ai.integrations.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Optional bridge to an OpenAI-compatible chat-completions endpoint. The user
 * configures base URL + API key (stored in the Keystore-backed SecureStore) +
 * model in Settings. When not configured, the agent runs fully on the local
 * deterministic brain — no key, no network call.
 */
class LlmClient(private val secureStore: com.universalbrain.ai.core.security.SecureStore) {

    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** @return assistant reply text or null (unconfigured/failed). */
    suspend fun complete(config: Config, systemPrompt: String, history: List<Pair<String, String>>): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val messages = buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    history.forEach { (role, content) ->
                        add(buildJsonObject {
                            put("role", role)
                            put("content", content)
                        })
                    }
                }
                val payload = buildJsonObject {
                    put("model", config.model)
                    put("messages", messages)
                    put("max_tokens", 700)
                }
                val request = Request.Builder()
                    .url(config.baseUrl.trimEnd('/') + "/chat/completions")
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    val root = json.parseToJsonElement(body).jsonObject
                    root["choices"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("message")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.content
                }
            }.getOrNull()
        }
}
