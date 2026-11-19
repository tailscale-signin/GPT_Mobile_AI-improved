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
    enum class Status { AVAILABLE, CHECKING, NOT_VERIFIED, INCOMPATIBLE }

    data class Decision(
        val eligible: Boolean,
        val explanation: String,
        val policyUrl: String? = null,
        val status: Status = if (eligible) Status.AVAILABLE else Status.NOT_VERIFIED,
        val code: String? = null
    )

    const val OPENROUTER_TERMS = "https://openrouter.ai/docs/guides/features/zdr"

    /** Bounded diagnostics: never interpolate credential, prompt, provider response or account identifiers. */
    enum class Reason(val message: String, val incompatible: Boolean = false) {
        BATCH_UNSUPPORTED("Privacy Mode supports direct text requests. Turn off batch mode for this profile.", true),
        MODEL_ROUTER_UNSUPPORTED("Choose a named text model. Automatic model routers, online variants and presets cannot be verified for this private route.", true),
        ENDPOINT_UNSUPPORTED("This endpoint cannot enforce the private route. Use an official OpenRouter API endpoint.", true),
        CREDENTIAL_MISSING("Add a valid inference API key to this profile before checking privacy."),
        ROUTING_UNSUPPORTED("This profile contains routing, model fallbacks or plugin options that the private text route cannot enforce. Review its advanced options.", true),
        ACCOUNT_PRIVACY_UNVERIFIED("Finish account setup in Privacy settings. For OpenRouter, use Check my account to apply protection automatically and review default plugins."),
        ACCOUNT_APPROVAL_EXPIRED("Account verification needs a refresh. Open Privacy settings to renew quick setup, or import current organization evidence in Advanced."),
        ACCOUNT_APPROVAL_INVALID("The saved privacy setup does not match this account, key or endpoint. Open Privacy settings and check the account again."),
        ACCOUNT_APPROVAL_AMBIGUOUS("More than one account approval matches this route. Ask the issuer for one unambiguous approval."),
        PUBLISHED_POLICY_CHANGED("The provider's privacy policy changed. Review it and refresh account setup in Privacy settings."),
        MODEL_RESOLUTION_UNVERIFIED("The selected model could not be resolved to a verified version. Choose a specific version or recheck the provider catalog."),
        CATALOG_MALFORMED("The provider returned incomplete or conflicting endpoint metadata. Recheck later; no private prompt was sent."),
        MODEL_NOT_LISTED("No verified endpoint is listed for this selected model. Choose another model or recheck later."),
        NO_STRICT_ZDR_ENDPOINT("No endpoint for this model currently proves strict ZDR with Safety Retention excluded. Recheck later or choose another model."),
        PROVIDER_RESTRICTIONS("The profile's provider restrictions exclude every verified private endpoint. Review its routing options.", true),
        AMBIGUOUS_ENDPOINT("The available provider selector can include an unverified endpoint variant. Choose another model or recheck later."),
        MANAGEMENT_KEY_MISSING("Add an OpenRouter management key in Privacy settings so the app can verify the assigned privacy guardrail."),
        GUARDRAIL_UNVERIFIED("The assigned guardrail does not verify ZDR for this model group with both Safety Retention choices excluded. Review OpenRouter privacy setup."),
        GUARDRAIL_UPDATE_UNCONFIRMED("A privacy guardrail update has an unknown outcome. Confirm or apply the strict settings in OpenRouter, then recheck; the app will not repeat the update."),
        GUARDRAIL_ASSIGNMENT_UNVERIFIED("The approved guardrail is not confirmed on this inference key. Review its assignment in OpenRouter."),
        KEY_UNAVAILABLE("The inference key is invalid, expired or has the wrong role. Update the profile's API key."),
        VERIFICATION_RATE_LIMITED("OpenRouter temporarily limited privacy checks. Wait and recheck; Privacy Mode stays on."),
        VERIFICATION_UNAVAILABLE("Privacy verification could not be completed. Check the connection and recheck; Privacy Mode stays on.");

        fun decision(policyUrl: String? = null): Decision = Decision(
            false,
            message,
            policyUrl,
            if (incompatible) Status.INCOMPATIBLE else Status.NOT_VERIFIED,
            name
        )
    }

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
            if (VerifiedPrivateRoute.providerEndpoint(profile) != null) {
                return Decision(false, "Vertex requires verified project-specific ZDR, disabled content logging and eligible inference features.", VerifiedPrivateRoute.VERTEX_TERMS)
            }
            return Decision(false, "Gemini free usage can be used for product improvement; a verified ZDR account and endpoint are required", "https://ai.google.dev/gemini-api/docs/zdr")
        }
        if (profile.compatibleType == ClientType.GROQ) {
            return Decision(false, "Groq requires verification of account-level ZDR Data Controls", "https://console.groq.com/docs/your-data")
        }
        if (profile.compatibleType == ClientType.OPENROUTER) {
            val reason = when {
                profile.batchMode || !profile.batchApiUrl.isNullOrBlank() -> Reason.BATCH_UNSUPPORTED
                !PrivateOpenRouterRouting.selectedModel(profile.model) -> Reason.MODEL_ROUTER_UNSUPPORTED
                profile.apiUrl.trimEnd('/') !in dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity.endpoints -> Reason.ENDPOINT_UNSUPPORTED
                profile.token.isNullOrBlank() || profile.token.any { it.isWhitespace() || it.isISOControl() || it == ',' || it == ';' } -> Reason.CREDENTIAL_MISSING
                PrivateOpenRouterRouting.options(profile.openRouterRouting) == null -> Reason.ROUTING_UNSUPPORTED
                else -> Reason.ACCOUNT_PRIVACY_UNVERIFIED
            }
            return reason.decision(OPENROUTER_TERMS)
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
