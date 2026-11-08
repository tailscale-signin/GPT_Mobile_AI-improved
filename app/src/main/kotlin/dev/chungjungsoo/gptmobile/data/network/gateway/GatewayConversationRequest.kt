package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The command ID is frozen together with the metadata that will be sent on this attempt. */
internal data class GatewayConversationRequest(val payload: String, val commandId: String? = null) {
    suspend fun beforeDispatch(config: ProviderRequestConfig) {
        if (commandId == null) return
        currentCoroutineContext().ensureActive()
        val reserve = checkNotNull(config.onGatewayCommandPrepared) {
            "The gateway request could not be saved locally. Reopen the chat and retry; no model request was sent."
        }
        reserve(commandId)
        // A local reservation may finish under a non-cancellable database transaction.
        // Cancellation after that commit still must not start the HTTP request.
        currentCoroutineContext().ensureActive()
    }
}

/** Add history metadata only to a gateway that explicitly supports this contract. */
internal fun gatewayConversationRequest(serialized: String, config: ProviderRequestConfig): GatewayConversationRequest {
    if (!config.gatewayConversationSync) return GatewayConversationRequest(serialized)
    val payload = Json.parseToJsonElement(serialized) as? JsonObject ?: return GatewayConversationRequest(serialized)
    val key = config.gatewayConversationKey
    val persistent = gatewayStoresVisibleTurn(config)
    // A changed tool-stage payload gets its own command; transport retries of
    // the same accepted payload retain the exact command identity.
    val commandId = if (persistent) researchHash("${config.queueScope}|${config.requestRole}|$serialized") else null
    val metadata = buildJsonObject {
        put("store_conversation", persistent)
        config.profileUid?.let { put("profile_id", it) }
        if (persistent) {
            put("client_conversation_id", requireNotNull(key))
            config.gatewayConversationId?.let { put("conversation_id", it) }
            put("command_id", requireNotNull(commandId))
        }
    }
    return GatewayConversationRequest(JsonObject(payload + ("gateway" to metadata)).toString(), commandId)
}

/** Header negotiation and the serialized command must apply the same privacy policy. */
internal fun gatewayStoresVisibleTurn(config: ProviderRequestConfig): Boolean {
    val callerRole = config.extraHeaders.entries.firstOrNull { it.key.equals("X-Gateway-Request-Role", true) }?.value
    val delegated = config.extraHeaders.entries.any { it.key.equals("X-Gateway-Delegated-Worker", true) && it.value.equals("true", true) }
    return config.queuePersistent &&
        !config.gatewayConversationKey.isNullOrBlank() &&
        config.requestRole in setOf("primary", "synthesis") &&
        (callerRole == null || callerRole == config.requestRole) &&
        !delegated &&
        config.profileUid?.matches(Regex("[A-Za-z0-9_-]{1,160}")) == true
}

internal fun gatewayConversationPayload(serialized: String, config: ProviderRequestConfig): String = gatewayConversationRequest(serialized, config).payload
