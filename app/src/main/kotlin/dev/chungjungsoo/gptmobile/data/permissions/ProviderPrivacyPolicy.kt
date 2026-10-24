package dev.chungjungsoo.gptmobile.data.permissions

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.free.FreeModelCatalog
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.research.researchHash

enum class RetentionPolicy { VERIFIED_NO_RETENTION, COLLECTING, UNKNOWN }

/** Bundled, reviewed policy; remote model descriptions cannot grant data permissions. */
data class ProviderPrivacyPolicy(
    val provider: FreeAiProvider,
    val retention: RetentionPolicy,
    val disclosure: String,
    val policyUrl: String,
    val recipients: Set<String>,
    val personalDataAllowed: Boolean = false,
    val version: Int = 1
) {
    val enabledByDefault: Boolean get() = !provider.isRetired && retention == RetentionPolicy.VERIFIED_NO_RETENTION
    val digest: String get() = researchHash("${provider.name}|$retention|${recipients.sorted()}|$personalDataAllowed|$version")

    companion object {
        fun forProfile(profile: PlatformV2): ProviderPrivacyPolicy? = FreeAiProvider.fromApiUrl(profile.apiUrl)?.let(::forProvider)

        fun forProvider(provider: FreeAiProvider): ProviderPrivacyPolicy = when (provider) {
            FreeAiProvider.KILO -> ProviderPrivacyPolicy(provider, RetentionPolicy.COLLECTING, "Free routing may send prompts and responses to providers that log them or use them to improve their services. Personal and confidential data are not permitted.", "https://kilo.ai/docs/getting-started/using-kilo-for-free", setOf("Kilo", "Free route partners (including NVIDIA)"))
            FreeAiProvider.POLLINATIONS -> ProviderPrivacyPolicy(provider, RetentionPolicy.COLLECTING, "The Classic route processes IP information for rate limits. Downstream model providers may have separate data practices. Personal tools remain unavailable until those recipients are verified.", "https://pollinations.ai/privacy", setOf("Pollinations", "Model route partners"))
            FreeAiProvider.LLM7 -> ProviderPrivacyPolicy(provider, RetentionPolicy.COLLECTING, "Account, usage and security information may be collected. Prompt and tool-result retention across downstream routes has not been verified.", "https://llm7.io/privacy.html", setOf("LLM7", "Model route partners"))
            FreeAiProvider.OVHCLOUD -> ProviderPrivacyPolicy(provider, RetentionPolicy.UNKNOWN, "OVHcloud advertises zero content retention and retains billing data. Identifying request-log practices for this anonymous route have not been fully verified.", "https://www.ovhcloud.com/en/public-cloud/ai-endpoints/", setOf("OVHcloud"), personalDataAllowed = true)
            FreeAiProvider.HUGGING_FACE -> ProviderPrivacyPolicy(provider, RetentionPolicy.UNKNOWN, "Retention of prompts and tool results by this hosted Space and its upstream service has not been verified.", "https://huggingface.co/spaces/Qwen/Qwen3-Demo", setOf("Hugging Face", "Qwen Space", "DashScope"))
            else -> ProviderPrivacyPolicy(provider, RetentionPolicy.UNKNOWN, "We could not verify whether this route stores prompts or tool results, or all downstream recipients. Personal tools remain unavailable until the route policy is verified.", FreeModelCatalog.details(provider).documentation, setOf(provider.displayName))
        }

        /** Changing a model, credential, endpoint or policy never inherits a prior grant. */
        fun scope(profile: PlatformV2): String = researchHash("${profile.uid}|${FreeAiProvider.fromApiUrl(profile.apiUrl)?.apiUrl ?: profile.apiUrl}|${profile.model}|${researchHash(profile.token.orEmpty())}|${forProfile(profile)?.digest}")
    }
}
