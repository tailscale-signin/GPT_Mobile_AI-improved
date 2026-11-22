package dev.chungjungsoo.gptmobile.data.readiness

import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.net.URI
import java.util.Locale

/** A presentation of the saved route, never a privacy, availability or dispatch decision. */
internal enum class ConfiguredInferenceRoute(val label: String, val detail: String) {
    PHONE("Phone inference configured", "The selected model runs on this phone. Tools, research and helper profiles can use separate network routes."),
    GATEWAY("Gateway route observed", "The configured endpoint identified itself as a Gateway. Its selected model and any upstream provider still need their own execution evidence."),
    CLOUD("Cloud API configured", "The saved endpoint is a recognized cloud API. The selected provider receives inference requests; routing and retention depend on that provider and account."),
    NETWORK("Network API configured", "Requests go to the configured service. Its address alone does not verify whether inference runs on a PC, another phone or a cloud provider."),
    MISSING("Endpoint setup required", "Add a valid HTTP or HTTPS endpoint in this profile's connection settings.")
}

/** Only exact recognized hosts identify a configured cloud API; provider type alone does not. */
internal fun configuredInferenceRoute(
    clientType: ClientType,
    apiUrl: String,
    gatewayIdentityObserved: Boolean = false
): ConfiguredInferenceRoute {
    if (clientType == ClientType.LITERT_LM) return ConfiguredInferenceRoute.PHONE
    val endpoint = runCatching { URI(apiUrl.trim()) }.getOrNull() ?: return ConfiguredInferenceRoute.MISSING
    if (endpoint.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || endpoint.host.isNullOrBlank() || endpoint.userInfo != null) {
        return ConfiguredInferenceRoute.MISSING
    }
    if (gatewayIdentityObserved) return ConfiguredInferenceRoute.GATEWAY
    return if (endpoint.host.lowercase(Locale.ROOT).removeSuffix(".") in CLOUD_API_HOSTS) {
        ConfiguredInferenceRoute.CLOUD
    } else {
        ConfiguredInferenceRoute.NETWORK
    }
}

private val CLOUD_API_HOSTS = setOf(
    "api.openai.com",
    "api.anthropic.com",
    "generativelanguage.googleapis.com",
    "api.groq.com",
    "openrouter.ai",
    "integrate.api.nvidia.com"
)
