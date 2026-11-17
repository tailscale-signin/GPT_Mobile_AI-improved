package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterProviderRouting
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** An upstream 429 must not quarantine all models on an OpenRouter key. */
internal object OpenRouterRateLimitScopes {
    private val freeTierSources = setOf("openrouter_free_tier_per_minute", "openrouter_free_tier_daily")
    private val providerSlug = Regex("[a-z0-9][a-z0-9_.-]{0,79}(/[a-z0-9][a-z0-9_.-]{0,79})?")
    private val recognizedSources = freeTierSources + "upstream_provider_shared_pool"

    fun validLimitSource(value: String?): String? = value?.takeIf { it in recognizedSources }

    fun sourceFromBody(body: String): String? {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val error = root["error"] as? JsonObject ?: root
        val metadata = error["metadata"] as? JsonObject ?: return null
        return validLimitSource((metadata["limit_source"] as? JsonPrimitive)?.contentOrNull)
    }

    fun isOpenRouter(config: ProviderRequestConfig): Boolean =
        config.providerType == "OPENROUTER" ||
            runCatching { URI(config.apiUrl).host.equals("openrouter.ai", ignoreCase = true) }.getOrDefault(false)

    fun isFreeModel(config: ProviderRequestConfig): Boolean {
        val model = config.selectedModel ?: config.lockedModel ?: return false
        return model.endsWith(":free", ignoreCase = true) || model.equals("openrouter/free", ignoreCase = true)
    }

    fun pinnedProvider(routing: OpenRouterProviderRouting?, hasModelFallbacks: Boolean): String? =
        routing?.only?.singleOrNull()?.takeIf { !hasModelFallbacks && providerSlug.matches(it) }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    fun identity(config: ProviderRequestConfig, limitSource: String? = null): String {
        val credential = digest(config.apiUrl.trimEnd('/') + "\u0000" + config.token.orEmpty())
        if (!isOpenRouter(config)) return credential
        val resource = if (validLimitSource(limitSource) in freeTierSources) {
            "shared-free-tier"
        } else {
            val model = config.selectedModel ?: config.lockedModel ?: config.profileUid ?: "unbound"
            val pinned = config.openRouterPinnedProvider?.takeIf(providerSlug::matches)
            when {
                pinned == null -> "model:$model"
                limitSource == "upstream_provider_shared_pool" -> "provider-pool:$pinned"
                else -> "model:$model\u0000provider:$pinned"
            }
        }
        return digest("openrouter-v2\u0000" + credential + "\u0000" + resource)
    }

    fun admissionIdentities(config: ProviderRequestConfig): List<String> {
        val model = identity(config)
        if (!isOpenRouter(config)) return listOf(model)
        return buildList {
            add(model)
            if (isFreeModel(config)) add(identity(config, "openrouter_free_tier_per_minute"))
            if (config.openRouterPinnedProvider?.let(providerSlug::matches) == true) {
                add(identity(config, "upstream_provider_shared_pool"))
            }
        }.distinct()
    }
}
