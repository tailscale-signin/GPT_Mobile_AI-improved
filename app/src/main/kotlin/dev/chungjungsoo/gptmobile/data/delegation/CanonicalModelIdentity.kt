package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.net.URI
import java.util.Locale

/** Profile names and transport endpoints are not model identities. Unknown aliases remain unknown. */
data class CanonicalModelIdentity(val canonical: String?, val deployment: String, val provenance: String) {
    companion object {
        fun resolve(profile: PlatformV2): CanonicalModelIdentity {
            val raw = profile.model.trim().lowercase(Locale.ROOT).removePrefix("models/")
            val host = runCatching { URI(profile.apiUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
            val provider = when (host) {
                "api.openai.com" -> "openai"
                "api.anthropic.com" -> "anthropic"
                "generativelanguage.googleapis.com" -> "google"
                "openrouter.ai", "api.groq.com", "integrate.api.nvidia.com" -> "router"
                else -> if (profile.apiUrl.isBlank()) {
                    when (profile.compatibleType) {
                        ClientType.OPENAI -> "openai"
                        ClientType.ANTHROPIC -> "anthropic"
                        ClientType.GOOGLE -> "google"
                        else -> null
                    }
                } else {
                    null
                }
            }
            val namedProvider = raw.substringBefore('/').takeIf { it in setOf("openai", "anthropic", "google", "meta-llama", "qwen", "deepseek", "mistralai") }
            val canonical = when {
                raw.isBlank() -> null
                provider == "router" && namedProvider != null -> raw.removeSuffix(":free")
                provider != null && provider != "router" -> "$provider/${raw.removePrefix("$provider/")}"
                else -> null
            }
            val family = canonical?.replace(Regex("-(?:20[0-9]{2}-[0-9]{2}-[0-9]{2}|20[0-9]{6})$"), "")?.removeSuffix("-latest")
            val deployment = dev.chungjungsoo.gptmobile.data.research.researchHash("${profile.compatibleType}|${profile.apiUrl}|${profile.model}|${profile.accelerator}|${profile.providerConnectionUid}|${profile.secretRef}")
            return CanonicalModelIdentity(family, deployment, if (family == null) "Unresolved deployment alias" else "Provider model identifier; family aliases collapsed")
        }
        fun independence(a: PlatformV2, b: PlatformV2): ModelIndependence {
            if (a.uid == b.uid || a.model.isNotBlank() && a.model.trim().equals(b.model.trim(), true)) return ModelIndependence.SAME
            val first = resolve(a).canonical
            val second = resolve(b).canonical
            return if (first == null || second == null) {
                ModelIndependence.UNKNOWN
            } else if (first == second) {
                ModelIndependence.SAME
            } else {
                ModelIndependence.DIFFERENT
            }
        }
    }
}

enum class ModelIndependence { SAME, DIFFERENT, UNKNOWN }

data class DelegationReadiness(val profileUid: String, val identity: CanonicalModelIdentity, val checks: List<Pair<String, Boolean?>>, val blockers: List<DelegationBlockReason>)

object DelegationReadinessInspector {
    fun inspect(profile: PlatformV2, config: dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings, primary: PlatformV2? = null): DelegationReadiness {
        val local = profile.compatibleType == ClientType.LITERT_LM
        return DelegationReadiness(
            profile.uid,
            CanonicalModelIdentity.resolve(profile),
            listOf(
                "Profile enabled" to profile.enabled,
                "Model selected" to profile.model.isNotBlank(),
                "Local artifact installed" to if (local && java.io.File(profile.model).isAbsolute) java.io.File(profile.model).isFile else null,
                "Credentials configured" to if (local || profile.compatibleType == ClientType.OLLAMA) null else (!profile.token.isNullOrBlank() || !profile.secretRef.isNullOrBlank()),
                "Provider reachability (explicit test required)" to null,
                "Backend execution qualification (device test required)" to null
            ),
            DelegationPolicyResolver.reasons(profile, config, primary)
        )
    }
}
