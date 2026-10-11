package dev.chungjungsoo.gptmobile.data.marketplace

import kotlinx.serialization.Serializable

@Serializable
enum class EndpointAuthType { NONE, BEARER, API_KEY }

@Serializable
data class NativeEndpointConfig(
    val url: String,
    val authType: EndpointAuthType = EndpointAuthType.NONE,
    val secretRef: String? = null,
    val allowCustomHttpsPort: Boolean = false
) {
    fun ready() = NativeMarketplaceCatalog.validEndpoint(url, allowCustomHttpsPort) &&
        (authType == EndpointAuthType.NONE || secretRef != null)
}
