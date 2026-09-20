package dev.chungjungsoo.gptmobile.domain.model

data class OpenRouterSettings(
    val apiKey: String,
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val batchingEnabled: Boolean = true,
    val batchSize: Int = 10,
    val flushTimeoutMs: Long = 5000,
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 1000,
    val cacheEnabled: Boolean = true,
    val cacheTtlSeconds: Int = 300
)

data class CacheKey(
    val model: String,
    val promptHash: String,
    val temperature: Double,
    val maxTokens: Int?
)

data class OpenRouterBatchRequestItem(
    val id: String,
    val model: String,
    val messages: List<OpenRouterMessageItem>,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null
)

data class OpenRouterMessageItem(
    val role: String,
    val content: String
)

data class OpenRouterBatchItemResult(
    val requestId: String,
    val content: String?,
    val error: String?,
    val usage: Usage? = null
)

data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)
