package dev.chungjungsoo.gptmobile.data.free

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider

/** Reviewed free routes. Discovery can never authorize a paid route or a new host. */
@kotlinx.serialization.Serializable
data class FreeModelOption(val id: String, val label: String = id, val tools: Boolean = false, val streaming: Boolean = true, val privacy: String? = null, val contextTokens: Int? = null, val outputTokens: Int? = null, val freeAllowance: Boolean = false, val reasoning: Boolean? = null)

data class FreeServiceDetails(
    val authentication: String = "Anonymous · no account or personal API key",
    val limits: String,
    val lifecycle: String = "Experimental",
    val privacy: String,
    val documentation: String,
    val transport: String = "OpenAI-compatible text",
    val structuredOutput: String = "Not verified",
    val notice: String = "Availability may change. No paid fallback."
)

object FreeModelCatalog {
    const val CONNECTION_UID = "builtin-free-models"
    const val VERSION = 5
    const val NAME = "Free Models"
    const val AUTOMATIC = "automatic"

    fun defaults(provider: FreeAiProvider): List<FreeModelOption> = if (provider.isRetired) {
        emptyList()
    } else {
        when (provider) {
            FreeAiProvider.KILO -> listOf(FreeModelOption("kilo-auto/free", tools = true), FreeModelOption("minimax/minimax-m3.1-flash:free", tools = true), FreeModelOption("nvidia/nemotron-3.5-super-vl:free", tools = true), FreeModelOption("recursive/flint-v1.0:free", tools = true), FreeModelOption("xingchen-agi/xing4.0-29b-a4b:free", tools = true), FreeModelOption("dots-studio/dots-3-note-preview:free", tools = true), FreeModelOption("liquid/lfm-2.5-2.6b:free", tools = true), FreeModelOption("nvidia/nemotron-3.5-lightning:free", tools = true), FreeModelOption("poolside/laguna-s-2.1:free", tools = true), FreeModelOption("poolside/laguna-xs-2.1:free", tools = true), FreeModelOption("cohere/north-mini-code:free", tools = true), FreeModelOption("nvidia/nemotron-3.5-content-safety:free", tools = false), FreeModelOption("nvidia/nemotron-3-ultra-550b-a55b:free", tools = true), FreeModelOption("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", tools = true), FreeModelOption("nvidia/nemotron-3-super-120b-a12b:free", tools = true))
            FreeAiProvider.OVHCLOUD -> listOf(FreeModelOption("Qwen3.5-397B-A17B", tools = false), FreeModelOption("Qwen3.5-9B", tools = false), FreeModelOption("gpt-oss-120b", tools = false), FreeModelOption("Mistral-Small-3.2-24B-Instruct-2506", tools = true), FreeModelOption("Qwen3.6-27B", tools = false), FreeModelOption("Qwen3.8-27B", tools = false), FreeModelOption("gpt-oss-20b", tools = false), FreeModelOption("Qwen3Guard-Gen-8B", tools = false), FreeModelOption("Qwen3Guard-Gen-0.6B", tools = false), FreeModelOption("Qwen2.5-VL-72B-Instruct", tools = false), FreeModelOption("Meta-Llama-3_3-70B-Instruct", tools = true), FreeModelOption("Mistral-Nemo-Instruct-2407", tools = false))
            FreeAiProvider.BLOCKRUN -> listOf("nvidia/nemotron-3.5-lightning", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning", "nvidia/llama-3.2-11b-vision", "nvidia/nemotron-3-ultra-550b", "cohere/north-mini-code", "poolside/laguna-xs-2.1", "nvidia/muse-glimmer-30b").map(::FreeModelOption)
            FreeAiProvider.AI_HORDE -> listOf(FreeModelOption(AUTOMATIC, "Available text workers", streaming = false), FreeModelOption("horde-image", "Image generation · available workers", streaming = false))
            FreeAiProvider.OPENCODE -> listOf("glm-5.3-flash-free", "deepseek-v4.1-flash-free", "minimax-m3.1-flash-free", "beam-free", "flint-40b-free", "longcat-2.5-preview-free", "exo-free", "mimo-v2.6-flash-free", "mimo-v2.5-free", "nemotron-3-ultra-free", "nemotron-3.5-lightning-free", "nemotron-3.5-super-vl-free", "big-pickle").map {
                FreeModelOption(
                    it,
                    privacy = when {
                        it.startsWith("glm-") || it.startsWith("deepseek-") || it.startsWith("minimax-") || it.startsWith("longcat-") -> "Provider documents no training and zero retention for this route."
                        it == "beam-free" -> "Security retention up to 30 days; regional restrictions apply."
                        it.startsWith("nemotron-") -> "NVIDIA trial access; prompts may be logged. Do not send confidential information."
                        else -> "Prompts and outputs may be used to improve or train models."
                    }
                )
            }
            // Observed route capacity, not the upstream model's advertised training window.
            FreeAiProvider.BRIDGENODE -> listOf("gpt-oss-20b", "gpt-oss-120b", "glm-4.7-flash", "glm-4.5-flash", "glm-4.6v-flash").map { FreeModelOption(it, contextTokens = if (it == "gpt-oss-20b") 8000 else null) }
            // This is an app-side choice, never a model ID sent to LLM7. Live free-allowance
            // metadata must resolve it before generation; no historical model is trusted.
            FreeAiProvider.LLM7 -> listOf(FreeModelOption(AUTOMATIC, "Automatic · current free-allowance model"))
            FreeAiProvider.POLLINATIONS -> listOf(FreeModelOption(provider.model, "GPT-OSS 20B · Pollinations Classic", outputTokens = provider.fixedOutputTokens))
            FreeAiProvider.HUGGING_FACE -> listOf(FreeModelOption("qwen3-235b-a22b", "Qwen3 · Qwen/Qwen3-Demo", streaming = false))
            FreeAiProvider.PORCHLM -> listOf(FreeModelOption(AUTOMATIC, "Discover the service's current model"))
            else -> listOf(FreeModelOption(provider.model))
        }
    }

    fun details(provider: FreeAiProvider): FreeServiceDetails = when (provider) {
        FreeAiProvider.KILO -> FreeServiceDetails(limits = "200 requests/hour/IP · shared across profiles", lifecycle = "Supported", privacy = "Public cloud. Free-model data policies vary; NVIDIA trial routes can log prompts.", documentation = "https://kilo.ai/docs/gateway", notice = "Automatic routing can change the served model. Tool support varies by model.")
        FreeAiProvider.OVHCLOUD -> FreeServiceDetails(limits = "2 requests/minute/IP/model", lifecycle = "Supported", privacy = "Public cloud processing under OVHcloud's terms.", documentation = "https://docs.ovhcloud.com/en/guides/public-cloud/ai-machine-learning/ai-endpoints-getting-started")
        FreeAiProvider.BLOCKRUN -> FreeServiceDetails(limits = "30 requests/minute and 300/hour/IP", privacy = "Free infrastructure policies vary. No blanket no-training guarantee.", documentation = "https://blockrun.ai/free", notice = "Only free routes are admitted. The service may return another free model; its identity is recorded.")
        FreeAiProvider.AI_HORDE -> FreeServiceDetails(authentication = "Anonymous · published public token 0000000000", limits = "Volunteer capacity · anonymous jobs have lower priority", privacy = "Workers can see submitted content. Commercial-service terms apply.", documentation = "https://aihorde.net/terms", transport = "Queued text and image jobs", notice = "Queue position and wait estimates are supplied by workers; completion time is not guaranteed.")
        FreeAiProvider.VIREONIX -> FreeServiceDetails(limits = "Shared hourly limits · published 20M input / 200K output tokens per IP; subject to change", privacy = "Prompts, outputs and tool traces may be stored and used for training.", documentation = "https://vireonix.ai/privacy", notice = "Auto routing; client tools remain disabled until verified.")
        FreeAiProvider.OPENCODE -> FreeServiceDetails(limits = "Promotional free models · variable shared limits", privacy = "Model-specific retention and training policies are shown below.", documentation = "https://opencode.ai/v2/docs/console/models/", notice = "Free chat models are restricted to OpenCode’s own apps, including when a third-party app supplies a key. Choose another provider.")
        FreeAiProvider.HUGGING_FACE -> FreeServiceDetails(limits = "Space queue and upstream-service limits apply; this endpoint does not expose a hard output-token cap", privacy = "Public third-party Space. Its maintainer and upstream model service process prompts.", documentation = "https://huggingface.co/spaces/Qwen/Qwen3-Demo", transport = "Gradio queue · Qwen/Qwen3-Demo", notice = "The Space controls generation length and reasoning. Queue acceptance is not a completed answer. A failed job cannot resume; retry later or choose another provider.")
        FreeAiProvider.POLLINATIONS -> FreeServiceDetails(limits = "One active text request/IP · 30 seconds between completed requests · fixed 1,500 generation tokens", lifecycle = "Classic", privacy = "Public free endpoint. Use non-sensitive prompts only.", documentation = "https://github.com/pollinations/pollinations/blob/master/APIDOCS.md", transport = "OpenAI-compatible POST · GPT-OSS 20B · streaming", notice = "Supports Combined contributions within its fixed output allowance. A smaller hard output budget cannot be enforced. Free-slot exhaustion stops until Retry-After; no paid route or fallback.")
        FreeAiProvider.BRIDGENODE -> FreeServiceDetails(limits = "Shared capacity · published free routes only", privacy = "Public gateway; upstream data handling varies.", documentation = "https://bridgenode.cc", notice = "Uses the documented direct chat API. Payment-required responses stop immediately.")
        FreeAiProvider.LLM7 -> FreeServiceDetails(authentication = "Anonymous · optional personal free API token", limits = "Shared anonymous capacity; free-token allowance up to 100,000 input + output tokens per rolling 24 hours, 1/second, 60/minute and 250/hour", lifecycle = "Supported", privacy = "Public cloud processing under LLM7's terms and privacy policy.", documentation = "https://docs.llm7.io/guides/models-api", notice = "Automatic selection uses current free-allowance chat models, with no paid-only models or app fallback. If anonymous access is declined, add your own free token from dash.llm7.io or retry later. LLM7 may bill topped-up accounts even for these models.")
        FreeAiProvider.CEHPOINT -> FreeServiceDetails(limits = "Free noncommercial use · shared limits", lifecycle = "Noncommercial", privacy = "Public service. Commercial production requires a separate keyed plan.", documentation = "https://ai-api.cehpoint.co.in", notice = "Token usage can be unavailable. Provider-side search does not imply client tool support.")
        FreeAiProvider.PORCHLM -> FreeServiceDetails(limits = "Home-hosted educational model · no uptime guarantee", lifecycle = "Demonstration", privacy = "Public educational service; unreliable factual answers.", documentation = "https://guythatlives.net/terms", notice = "Not suitable for research or important decisions. Model discovery must succeed before generation.")
    }

    fun acceptsDiscovered(provider: FreeAiProvider, id: String, explicitlyFree: Boolean): Boolean = !provider.isRetired &&
        when (provider) {
            FreeAiProvider.KILO -> id == provider.model || id.endsWith(":free") && explicitlyFree
            FreeAiProvider.OPENCODE -> defaults(provider).any { it.id == id } || id.endsWith("-free") && explicitlyFree && !id.startsWith("jev-")
            FreeAiProvider.BLOCKRUN, FreeAiProvider.BRIDGENODE -> defaults(provider).any { it.id == id } && explicitlyFree
            FreeAiProvider.LLM7 -> explicitlyFree && id.isNotBlank() && id.length <= 256
            FreeAiProvider.OVHCLOUD, FreeAiProvider.AI_HORDE, FreeAiProvider.PORCHLM -> id.isNotBlank() && id.length <= 256
            else -> defaults(provider).any { it.id == id }
        }
}
