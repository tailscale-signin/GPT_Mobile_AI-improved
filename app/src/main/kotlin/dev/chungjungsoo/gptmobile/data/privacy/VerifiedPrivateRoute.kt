package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Issued by the organization's device-policy controller after checking the provider account.
 * User-editable profile settings, imports and chat payloads cannot issue an approval.
 */
@Serializable
internal data class ProviderApproval(
    val endpoint: String,
    val model: String,
    val credentialSha256: String,
    val upstream: String,
    val policyUrl: String,
    val verifiedAtMillis: Long,
    val expiresAtMillis: Long,
    val feature: String,
    val retentionTier: String,
    val noTraining: Boolean,
    val loggingDisabled: Boolean,
    val externalToolsDisabled: Boolean,
    val providerDefaultsDisabled: Boolean,
    val safetyRetentionExcluded: Boolean,
    val guardrailId: String? = null,
    val providerKeyHash: String? = null,
    val allowGuardrailTightening: Boolean = false
) {
    fun covers(profile: PlatformV2, url: String, terms: String, now: Long): Boolean =
        feature == "stateless-text-v1" &&
            retentionTier == "zdr" &&
            noTraining &&
            loggingDisabled &&
            externalToolsDisabled &&
            providerDefaultsDisabled &&
            safetyRetentionExcluded &&
            verifiedAtMillis in (now - 86_400_000L)..now &&
            expiresAtMillis in (now + 1)..(verifiedAtMillis + 86_400_000L) &&
            endpoint == url &&
            model == profile.model &&
            policyUrl == terms &&
            upstream.matches(Regex("[a-z0-9][a-z0-9._/-]{0,127}")) &&
            credentialSha256 == OpenRouterIdentity.digest(profile.token.orEmpty())
}

internal data class VerifiedPrivateRoute(
    val url: String,
    val kind: ClientType,
    val approval: ProviderApproval? = null
) {
    companion object {
        const val GATEWAY_PATH = "/gateway/privacy/inference"

        fun gateway(profile: PlatformV2): String? {
            if (profile.compatibleType !in setOf(ClientType.LLAMA, ClientType.OLLAMA)) return null
            val base = profile.apiUrl.trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/v1")
            if (!PrivacyProfileEligibility.privateGateway(base) || java.net.URI(base).rawPath.orEmpty().isNotEmpty()) return null
            return base + GATEWAY_PATH
        }

        fun providerEndpoint(profile: PlatformV2): String? {
            if (profile.batchMode ||
                !profile.batchApiUrl.isNullOrBlank() ||
                !profile.openRouterRouting.isNullOrBlank() ||
                !profile.model.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,190}")) ||
                profile.model.startsWith("openrouter/") ||
                profile.model.endsWith(":online") ||
                profile.token.orEmpty().any { it.isWhitespace() || it == ',' || it == ';' }
            ) {
                return null
            }
            val base = profile.apiUrl.trimEnd('/')
            return when (profile.compatibleType) {
                ClientType.OPENROUTER -> if (base in OpenRouterIdentity.endpoints && !profile.token.isNullOrBlank() && profile.model.count { it == '/' } == 1) "$base/chat/completions" else null
                ClientType.OPENAI -> if (base in setOf("", "https://api.openai.com/v1") && !profile.token.isNullOrBlank()) "https://api.openai.com/v1/chat/completions" else null
                ClientType.GOOGLE -> if (!profile.token.isNullOrBlank() && base.matches(Regex("""https://(?:[a-z0-9-]+-)?aiplatform\.googleapis\.com/v1(?:beta1)?/projects/[a-z0-9-]+/locations/[a-z0-9-]+/endpoints/openapi"""))) "$base/chat/completions" else null
                ClientType.ANTHROPIC -> if (base in setOf("", "https://api.anthropic.com", "https://api.anthropic.com/v1") && !profile.token.isNullOrBlank()) "https://api.anthropic.com/v1/messages" else null
                ClientType.FREE -> if (base == FreeAiProvider.OVHCLOUD.apiUrl && profile.token.isNullOrEmpty()) FreeAiProvider.OVHCLOUD.chatCompletionsUrl else null
                else -> null
            }
        }

        fun strictCatalogMatch(rows: JsonArray, proof: ProviderApproval): Boolean {
            val matches = rows.filterIsInstance<JsonObject>().filter {
                it["model_id"] == JsonPrimitive(proof.model) && it["tag"] == JsonPrimitive(proof.upstream)
            }
            // Some catalog versions omit the tier. In those versions the current account-bound
            // approval supplies it. Any contradictory provider observation vetoes admission.
            return matches.size == 1 &&
                matches.single().let { row ->
                    listOf("retention_tier", "data_policy_tier").all { row[it] == null || row[it] == JsonPrimitive("zdr") } &&
                        listOf("safety_retention", "requires_safety_retention").all { row[it] == null || row[it] == JsonPrimitive(false) }
                }
        }
    }
}
