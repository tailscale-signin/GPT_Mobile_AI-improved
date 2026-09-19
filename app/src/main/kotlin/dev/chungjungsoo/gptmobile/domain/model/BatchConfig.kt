package dev.chungjungsoo.gptmobile.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class BatchRequest(
    val prompt: String,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f
)

@Serializable
data class BatchConfig(
    val platform: PlatformType,
    val apiKey: String,
    val batchKey: String? = null, // NEW field for batch operations
    
    // OpenRouter parallel batching settings
    val openRouter: OpenRouterBatchSettings = OpenRouterBatchSettings(),
    
    // Llama.cpp batch endpoint settings
    val llama: LlamaBatchSettings = LlamaBatchSettings()
)

@Serializable
data class OpenRouterBatchSettings(
    val maxConcurrentRequests: Int = 10,
    val retryDelayMs: Long = 1000,
    val timeoutMs: Long = 30000
)

@Serializable
data class LlamaBatchSettings(
    val serverUrl: String = "http://localhost:8080",
    val batchSize: Int = 50,
    val jsonlPath: String? = null // Optional temp file path
)

enum class PlatformType {
    OPENROUTER,
    LLAMA_CPP,
    ANTHROPIC,
    GOOGLE,
    AWS
}
