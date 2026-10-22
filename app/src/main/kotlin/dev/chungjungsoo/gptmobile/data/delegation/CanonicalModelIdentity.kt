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
                "openrouter.ai", "eu.openrouter.ai", "us.openrouter.ai", "api.groq.com", "integrate.api.nvidia.com" -> "router"
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
            val cleaned = raw.removeSuffix(":free").removeSuffix(":thinking")
            val aliased = cleaned.replace(Regex("^meta/"), "meta-llama/")
            val namedProvider = aliased.substringBefore('/').takeIf { it in setOf("openai", "anthropic", "google", "meta-llama", "qwen", "deepseek", "mistralai") }
            val canonical = when {
                raw.isBlank() -> null
                provider == "router" && namedProvider != null -> aliased
                provider == "router" -> when {
                    cleaned.startsWith("gpt-") || cleaned.startsWith("chatgpt-") || cleaned.matches(Regex("o[1-9](?:-.*)?")) -> "openai/$cleaned"
                    cleaned.startsWith("claude-") -> "anthropic/$cleaned"
                    cleaned.startsWith("gemini-") || cleaned.startsWith("gemma-") -> "google/$cleaned"
                    cleaned.startsWith("llama-") || cleaned.startsWith("llama3") -> "meta-llama/$cleaned"
                    cleaned.startsWith("qwen") -> "qwen/$cleaned"
                    cleaned.startsWith("deepseek-") -> "deepseek/$cleaned"
                    cleaned.startsWith("mistral-") || cleaned.startsWith("mixtral-") -> "mistralai/$cleaned"
                    else -> null
                }
                provider != null && provider != "router" -> "$provider/${raw.removePrefix("$provider/")}"
                else -> null
            }
            val known = canonical?.takeIf { id ->
                val model = id.substringAfter('/')
                when (id.substringBefore('/')) {
                    "openai" -> model.matches(Regex("(?:gpt-[0-9].*|gpt-oss-.*|chatgpt-[0-9].*|o[1-9](?:-.*)?)"))
                    "anthropic" -> model.startsWith("claude-")
                    "google" -> model.startsWith("gemini-") || model.startsWith("gemma-")
                    "meta-llama" -> model.startsWith("llama-", true) || model.startsWith("llama3", true)
                    "qwen" -> model.startsWith("qwen", true)
                    "deepseek" -> model.startsWith("deepseek", true)
                    "mistralai" -> model.startsWith("mistral", true) || model.startsWith("mixtral", true)
                    else -> false
                }
            }?.let { if (it.startsWith("anthropic/")) it.replace('.', '-') else it }
            val family = known?.replace(Regex("-(?:20[0-9]{2}-[0-9]{2}-[0-9]{2}|20[0-9]{6})$"), "")?.removeSuffix("-latest")
                ?.replace(Regex("^openai/chatgpt-"), "openai/gpt-")
                ?.replace(Regex("^anthropic/claude-([0-9]+(?:-[0-9]+)?)-(sonnet|opus|haiku)$"), "anthropic/claude-$2-$1")
            val deployment = dev.chungjungsoo.gptmobile.data.research.researchHash("${profile.compatibleType}|${profile.apiUrl}|${profile.model}|${profile.accelerator}|${profile.providerConnectionUid}|${profile.secretRef}")
            return CanonicalModelIdentity(family, deployment, if (family == null) "Unresolved deployment alias" else "Configured official-provider identifier; known family aliases normalized")
        }
        fun independence(a: PlatformV2, b: PlatformV2): ModelIndependence {
            if (a.uid == b.uid) return ModelIndependence.SAME
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

enum class ReadinessCheck { PROFILE_ENABLED, MODEL_SELECTED, LOCAL_ARTIFACT, CREDENTIALS, PROVIDER_REACHABILITY, BACKEND_QUALIFICATION }

data class DelegationReadiness(val profileUid: String, val identity: CanonicalModelIdentity, val checks: List<Pair<ReadinessCheck, Boolean?>>, val blockers: List<DelegationBlockReason>)

object DelegationReadinessInspector {
    fun inspect(profile: PlatformV2, config: dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings, primary: PlatformV2? = null): DelegationReadiness {
        val local = profile.compatibleType == ClientType.LITERT_LM
        return DelegationReadiness(
            profile.uid,
            CanonicalModelIdentity.resolve(profile),
            listOf(
                ReadinessCheck.PROFILE_ENABLED to profile.enabled,
                ReadinessCheck.MODEL_SELECTED to profile.model.isNotBlank(),
                ReadinessCheck.LOCAL_ARTIFACT to if (local && java.io.File(profile.model).isAbsolute) java.io.File(profile.model).isFile else null,
                ReadinessCheck.CREDENTIALS to if (local || profile.compatibleType == ClientType.OLLAMA) null else (!profile.token.isNullOrBlank() || !profile.secretRef.isNullOrBlank()),
                ReadinessCheck.PROVIDER_REACHABILITY to null,
                ReadinessCheck.BACKEND_QUALIFICATION to null
            ),
            DelegationPolicyResolver.reasons(profile, config, primary)
        )
    }
}
