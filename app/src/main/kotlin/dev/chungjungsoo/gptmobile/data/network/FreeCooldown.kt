package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.serialization.Serializable

@Serializable
data class FreeCooldown(
    val retryAt: Long,
    val observedAt: Long? = null,
    val status: Int? = null,
    val providerCode: String? = null,
    val category: String = "unknown",
    val message: String? = null,
    val deadlineSource: String = "legacy_unknown",
    val credentialIdentity: String = "unknown",
    val quotaScope: String = "unknown",
    val originatingFailureId: String? = null
) {
    fun blockedMessage(provider: FreeAiProvider): String = "Blocked locally by the saved ${provider.displayName} cooldown. Next eligible retry: ${java.time.Instant.ofEpochMilli(retryAt)}. This does not guarantee the allowance has reset." + (message?.let { " Original cause: $it" } ?: " Original cause was not retained.")
}
