package dev.chungjungsoo.gptmobile.data.model

import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.net.URI

enum class ProviderAuthenticationMode { ANONYMOUS, FREE_USER_TOKEN, PAID_API }

/** App-owned free routes; never fall back to a billing-capable route. */
enum class FreeAiProvider(
    val displayName: String,
    val apiUrl: String,
    val model: String,
    val supportsTools: Boolean,
    val requestsPerMinute: Int,
    val requestsPerHour: Int,
    val maxOutputTokens: Int = 2048
) {
    KILO("Kilo", "https://ai-gateway.kilo.ai/api/v1", "kilo-auto/free", true, 0, 200),
    POLLINATIONS("Pollinations", "https://text.pollinations.ai", "openai-fast", false, 0, 0),
    OVHCLOUD("OVHcloud", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1", "Mistral-Small-3.2-24B-Instruct-2506", true, 2, 0),
    LLM7("LLM7", "https://api.llm7.io/v1", "mistral-Nemo-Instruct-2407", false, 60, 250),
    BLOCKRUN("BlockRun", "https://blockrun.ai/api/v1", "nvidia/nemotron-3.5-lightning", false, 30, 300, 8192),
    AI_HORDE("AI Horde", "https://aihorde.net/api/v2", "automatic", false, 0, 0, 256),
    VIREONIX("Vireonix", "https://vireonix.ai/v1", "auto", false, 0, 0, 8192),
    OPENCODE("OpenCode", "https://opencode.ai/inference/openai/v1", "glm-5.3-flash-free", false, 0, 0, 8192),
    HUGGING_FACE("Hugging Face Spaces", "https://qwen-qwen3-demo.hf.space", "qwen3-235b-a22b", false, 0, 0, 2048),
    BRIDGENODE("BridgeNode", "https://bridgenode.cc/v1", "gpt-oss-20b", false, 0, 0, 8192),
    CEHPOINT("Cehpoint", "https://ai-api.cehpoint.co.in/v1", "cehpoint-ai", false, 0, 0, 4096),
    PORCHLM("PorchLM", "https://guythatlives.net/v1", "automatic", false, 0, 0);

    // Preset values are defaults, not verified per-model server ceilings.
    val defaultOutputTokens: Int get() = maxOutputTokens
    val enforcedOutputCeiling: Int? get() = if (this == AI_HORDE) 256 else null
    fun outputAllowance(requested: Int?): Int = minOf(requested?.takeIf { it > 0 } ?: defaultOutputTokens, enforcedOutputCeiling ?: Int.MAX_VALUE)

    val authenticationMode: ProviderAuthenticationMode
        get() = if (this == LLM7) ProviderAuthenticationMode.FREE_USER_TOKEN else ProviderAuthenticationMode.ANONYMOUS
    val requiresUserToken: Boolean get() = authenticationMode == ProviderAuthenticationMode.FREE_USER_TOKEN

    val isDemo: Boolean get() = this == PORCHLM

    val isAvailable: Boolean
        get() = unavailableReason == null

    val unavailableReason: String?
        get() = when {
            this == OPENCODE -> "OpenCode’s free models are restricted to OpenCode’s own apps. Choose another provider."
            this == LLM7 && !BuildConfig.FREE_LLM7_APPROVED -> "LLM7 is unavailable pending provider approval."
            else -> null
        }

    val chatCompletionsUrl: String
        get() = if (this == POLLINATIONS) "$apiUrl/openai" else "$apiUrl/chat/completions"

    fun applyTo(platform: PlatformV2): PlatformV2 = platform.copy(
        name = platform.name.takeUnless { it.isBlank() || it == "Free Models" || it == fromApiUrl(platform.apiUrl)?.displayName } ?: displayName,
        compatibleType = ClientType.FREE,
        apiUrl = apiUrl,
        token = platform.token.takeIf { requiresUserToken && fromApiUrl(platform.apiUrl) == this },
        secretRef = platform.secretRef.takeIf { requiresUserToken && fromApiUrl(platform.apiUrl) == this },
        model = platform.model.takeIf { dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.models(this).any { option -> option.id == it } } ?: model,
        maxTokens = platform.maxTokens?.takeIf { it > 0 }?.let { minOf(it, enforcedOutputCeiling ?: Int.MAX_VALUE) },
        batchMode = false,
        batchApiUrl = null,
        openRouterRouting = null,
        ollamaOptions = null,
        topK = null
    )

    companion object {
        val default = KILO

        fun fromApiUrl(url: String): FreeAiProvider? {
            val normalized = url.trim().trimEnd('/')
            if (normalized in setOf("https://api.kilo.ai/api/gateway", "https://api.kilo.ai/api/gateway/chat/completions")) return KILO
            return entries.firstOrNull { it.apiUrl == normalized || it.chatCompletionsUrl == normalized }
        }

        fun requireFor(platform: PlatformV2): FreeAiProvider = requireNotNull(fromApiUrl(platform.apiUrl)) {
            "Choose a Free provider in the AI profile settings."
        }
    }
}

/** Also protects imported/manual profiles that use known free routes. */
fun PlatformV2.excludesMemory(): Boolean {
    if (compatibleType == ClientType.FREE) return true
    if (model.endsWith(":free") || model == "openrouter/free" || model == "kilo-auto/free") return true
    val host = runCatching { URI(apiUrl.trim()).host?.lowercase() }.getOrNull()
    return host in FreeAiProvider.entries.mapNotNull { runCatching { URI(it.apiUrl).host }.getOrNull() }.toSet() ||
        (host == "oai.endpoints.kepler.ai.cloud.ovh.net" && token.isNullOrBlank())
}
