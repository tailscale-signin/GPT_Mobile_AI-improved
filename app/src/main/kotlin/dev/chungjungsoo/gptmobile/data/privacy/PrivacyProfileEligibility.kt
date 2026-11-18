package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import java.net.URI

/**
 * Conservative privacy-mode admission for the initial implementation.
 *
 * A provider's marketing claim or a model's name is never proof of endpoint ZDR.
 * Remote routes remain disabled until the actual account, endpoint, router, all
 * fallbacks, and outbound sanitization path can be verified before each request.
 * The local gateway is exempt from third-party retention policy checks only when
 * it is a private, locally hosted inference route, NOT when it relays to cloud AI.
 */
object PrivacyProfileEligibility {
    data class Decision(val eligible: Boolean, val explanation: String, val policyUrl: String? = null)

    private val local = Decision(true, "On-device inference")

    fun evaluate(profile: PlatformV2, privacyEnabled: Boolean): Decision {
        if (!privacyEnabled) return Decision(true, "Privacy Mode is off")
        if (profile.compatibleType == ClientType.LITERT_LM) return local
        if (profile.compatibleType in setOf(ClientType.LLAMA, ClientType.OLLAMA)) {
            return Decision(false, "Verify the private Gateway’s installed model and tool-free inference route.")
        }
        val free = FreeAiProvider.fromApiUrl(profile.apiUrl)
        if (free != null || profile.compatibleType == ClientType.FREE) return freeDecision(free, profile.model)
        if (profile.compatibleType == ClientType.GOOGLE) {
            return Decision(false, "Gemini free usage can be used for product improvement; a verified ZDR account and endpoint are required", "https://ai.google.dev/gemini-api/docs/zdr")
        }
        if (profile.compatibleType == ClientType.GROQ) {
            return Decision(false, "Groq requires verification of account-level ZDR Data Controls", "https://console.groq.com/docs/your-data")
        }
        if (profile.compatibleType == ClientType.OPENROUTER) {
            return Decision(false, "Verify the selected model's exact ZDR endpoint, account policy, and Safety Retention exclusion; automatic model routing is not eligible", "https://openrouter.ai/docs/guides/features/zdr")
        }
        if (profile.compatibleType == ClientType.OPENAI) {
            return Decision(false, "An approved project, eligible model and stateless endpoint are required.", "https://developers.openai.com/api/docs/guides/your-data")
        }
        if (profile.compatibleType == ClientType.ANTHROPIC) {
            return Decision(false, "The organization’s ZDR agreement must cover this workspace, model and Messages endpoint.", "https://platform.claude.com/docs/en/build-with-claude/zero-data-retention")
        }
        return Decision(false, "Endpoint and account ZDR eligibility have not been verified")
    }

    fun freeDecision(provider: FreeAiProvider?, model: String): Decision = when (provider) {
        FreeAiProvider.KILO -> Decision(
            false,
            if (model == "kilo-auto/free") "Kilo Auto Free can route to logging or training-enabled hosts" else "Kilo's upstream free-model data policy is not verified for this endpoint",
            "https://kilo.ai/docs/getting-started/using-kilo-for-free"
        )
        FreeAiProvider.OVHCLOUD -> Decision(
            false,
            "OVHcloud documents zero prompt retention, but the anonymous endpoint and model need current verification",
            "https://docs.ovhcloud.com/en/guides/public-cloud/ai-machine-learning/ai-endpoints-getting-started"
        )
        FreeAiProvider.BLOCKRUN -> Decision(
            false,
            "BlockRun states no prompt retention; the selected upstream inference endpoint still requires verification",
            "https://blockrun.ai/privacy"
        )
        FreeAiProvider.AI_HORDE -> Decision(false, "Distributed volunteer workers can receive the prompt", "https://aihorde.net/terms")
        FreeAiProvider.VIREONIX -> Decision(false, "Provider terms allow storing or training on prompts", "https://vireonix.ai/privacy")
        FreeAiProvider.OPENCODE -> Decision(false, "Retired app route with no eligible execution", "https://opencode.ai/v2/docs/console/models/")
        FreeAiProvider.HUGGING_FACE -> Decision(false, "Public third-party Space and upstream data retention are unverified", "https://huggingface.co/docs/hub/spaces-overview")
        FreeAiProvider.POLLINATIONS -> Decision(false, "Classic forwards requests to upstream models. Their content-retention terms are unverified.", "https://pollinations.ai/privacy")
        FreeAiProvider.BRIDGENODE -> Decision(false, "The public gateway's upstream data handling varies")
        FreeAiProvider.LLM7 -> Decision(false, "LLM7 retains usage and security logs. The selected upstream lacks verified prompt and response ZDR.", "https://llm7.io/privacy")
        FreeAiProvider.CEHPOINT -> Decision(false, "No verified ZDR commitment for the free inference endpoint", "https://ai-api.cehpoint.co.in")
        FreeAiProvider.PORCHLM -> Decision(false, "Retired public demonstration endpoint", "https://guythatlives.net/terms")
        null -> Decision(false, "Free route is unidentified or the model's retention policy is unverified")
    }

    /** Never resolve DNS to decide that a user-supplied hostname is private. */
    internal fun privateGateway(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.scheme !in setOf("http", "https") || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.host.isNullOrBlank()) return@runCatching false
        val host = uri.host.lowercase().removePrefix("[").removeSuffix("]")
        if (host in setOf("localhost", "127.0.0.1", "::1")) return@runCatching true
        if (uri.scheme == "https" && host.endsWith(".ts.net")) return@runCatching true
        val bytes = host.split(".").map { it.toIntOrNull() }
        if (bytes.size != 4 || bytes.any { it == null || it !in 0..255 }) return@runCatching false
        val a = bytes[0] ?: return@runCatching false
        val b = bytes[1] ?: return@runCatching false
        a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
    }.getOrDefault(false)
}
