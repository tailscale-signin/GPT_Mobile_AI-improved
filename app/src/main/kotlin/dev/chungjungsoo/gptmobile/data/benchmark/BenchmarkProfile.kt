package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterOptions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Apply bounded fixture settings to a copy, including provider-specific overrides. */
internal fun benchmarkCompletionBudget(profile: PlatformV2, category: String): Int = when {
    category == "performance_sustained_throughput" -> 2048
    category == "performance_decode" -> 768
    category.startsWith("performance_") -> 64
    category == "speed" && (profile.model.contains("gpt-oss", true) || profile.model.contains("nemotron", true)) -> 4096
    category == "speed" -> 768
    category.startsWith(IntelligenceBenchmark.CATEGORY) && profile.compatibleType == ClientType.LITERT_LM -> if (profile.reasoning) 2048 else 512
    category.startsWith(IntelligenceBenchmark.CATEGORY) -> 4096
    profile.compatibleType == ClientType.LITERT_LM -> 256
    profile.model.contains("gpt-oss", true) -> 8192
    else -> 2048
}

internal fun benchmarkProfile(profile: PlatformV2, allowTools: Boolean, category: String = "speed", executionPolicy: BenchmarkExecutionPolicy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1): PlatformV2 {
    if (executionPolicy == BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1) return profile.copy(batchMode = false)
    val outputBudget = benchmarkCompletionBudget(profile, category)
    fun objectOrNull(value: String?): JsonObject? = value?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    val openRouter = if (profile.compatibleType == ClientType.OPENROUTER) {
        val saved = objectOrNull(profile.openRouterRouting) ?: objectOrNull(Json.encodeToString(OpenRouterOptions.createDefault()))!!
        val legacyRouting = saved.isNotEmpty() && saved.keys.none { it in setOf("provider", "stream", "max_tokens", "temperature", "top_p", "top_k", "seed", "repetition_penalty", "frequency_penalty", "presence_penalty") }
        val options = if (legacyRouting) mapOf("provider" to saved) else saved
        JsonObject(options + mapOf("temperature" to JsonPrimitive(0f), "stream" to JsonPrimitive(true), "max_tokens" to JsonPrimitive(outputBudget))).toString()
    } else {
        profile.openRouterRouting
    }
    val ollama = if (profile.compatibleType == ClientType.OLLAMA) {
        JsonObject(objectOrNull(profile.ollamaOptions).orEmpty() + mapOf("temperature" to JsonPrimitive(0f), "auto_continue" to JsonPrimitive(false))).toString()
    } else {
        profile.ollamaOptions
    }
    // Benchmarks are synchronous/interactive requests. Some OpenRouter profiles persist
    // a batch-only model variant in the model slug (for example ":batch"). Reusing that
    // slug with batchMode disabled produces a provider 404 for models that do not expose
    // a batch endpoint. Normalize only the benchmark copy so the user's saved profile is
    // never modified.
    val benchmarkModel = if (profile.compatibleType == ClientType.OPENROUTER) {
        profile.model.removeSuffix(":batch")
    } else {
        profile.model
    }
    return profile.copy(
        model = benchmarkModel,
        // Throughput has a fixed window; correctness reserves enough space for a final answer.
        // Mandatory-reasoning GPT-OSS uses supported low effort, recorded with the sample.
        reasoning = category.startsWith(IntelligenceBenchmark.CATEGORY) && profile.reasoning,
        disableAllTools = !allowTools, temperature = 0f, maxTokens = outputBudget, stream = true,
        openRouterRouting = openRouter, ollamaOptions = ollama, batchMode = false,
        systemPrompt = "Follow the benchmark instruction exactly. Use only the provided fixture tool when requested."
    )
}
