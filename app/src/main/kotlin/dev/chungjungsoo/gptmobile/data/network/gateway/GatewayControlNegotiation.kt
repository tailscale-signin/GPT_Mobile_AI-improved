package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class GatewayControlNegotiation(private val api: GatewayAPI, now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class CapabilityAttempt(val capabilities: GatewayCapabilities? = null, val failure: GatewayCapabilityDiscoveryException? = null)
    private val cache = RouteCapabilityCache<CapabilityAttempt>(now, negativeTtl = 15_000, valueTtl = { if (it.failure != null) 15_000 else 300_000 })
    suspend fun prepare(config: ProviderRequestConfig): ProviderRequestConfig = try {
        prepareVerified(config)
    } catch (failure: GatewayCapabilityDiscoveryException) {
        val reason = if (failure.code == "GATEWAY_ISOLATION_UNAVAILABLE") dev.chungjungsoo.gptmobile.data.context.InferencePreflightReason.GATEWAY_ISOLATION_UNAVAILABLE else dev.chungjungsoo.gptmobile.data.context.InferencePreflightReason.GATEWAY_CAPABILITY_UNAVAILABLE
        currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.rejectedBeforeInference(reason)
        throw failure
    }

    private suspend fun prepareVerified(config: ProviderRequestConfig): ProviderRequestConfig {
        // Captured display metadata and retention cannot bypass negotiation.
        val permittedHeaders = GatewayClientDiagnostics.requestHeaders(config).filterKeys { it.lowercase() !in profileHeaders }
        val permitted = config.copy(extraHeaders = permittedHeaders, gatewayConversationSync = false)
        val requested = permittedHeaders.filterKeys { it.startsWith("X-Gateway-", true) }
        val knownGateway = GatewayClientDiagnostics.runtimeEpoch(config) != "unobserved"
        val discoverLocalHistory = config.providerType in setOf("LLAMA", "OLLAMA")
        // A gateway remains a gateway when diagnostics and optional request controls are disabled.
        if (requested.isEmpty() && !knownGateway && !discoverLocalHistory) return permitted
        val identity = researchHash("${GatewayClientDiagnostics.routeIdentity(config)}|${LocalServiceHealth.networkEpoch}|${GatewayClientDiagnostics.capabilityEpoch(config)}")
        val capability = try {
            val attempt = cache.getOrLoad(identity) {
                try {
                    api.getCapabilities(permitted.copy(extraHeaders = permittedHeaders - requested.keys))?.also {
                        GatewayClientDiagnostics.observeInstance(config, it.instanceId)
                    }?.let { CapabilityAttempt(capabilities = it) }
                } catch (failure: GatewayCapabilityDiscoveryException) {
                    CapabilityAttempt(failure = failure)
                }
            }
            attempt?.failure?.let { throw it }
            attempt?.capabilities
        } catch (failure: GatewayCapabilityDiscoveryException) {
            // Optional debug metadata must not introduce a new availability gate for an ordinary inference route.
            if (discoverLocalHistory || knownGateway || GatewayClientDiagnostics.runtimeEpoch(config) != "unobserved" || requested.keys.any { !it.equals(GatewayClientDiagnostics.CLIENT_HEADER, true) }) throw failure
            return permitted.copy(extraHeaders = permittedHeaders - requested.keys)
        }
        // Discovery can observe gateway identity even when its capability endpoint rejects the request.
        if (capability == null && (knownGateway || GatewayClientDiagnostics.runtimeEpoch(config) != "unobserved")) {
            throw GatewayCapabilityDiscoveryException("GATEWAY_CAPABILITY_UNAVAILABLE: The selected gateway no longer confirms its capabilities. Reconnect to the gateway software and retry; no model request was sent.")
        }
        val isolation = requested.filterKeys {
            it.lowercase() in setOf("x-gateway-allow-local-tools", "x-gateway-delegated-worker", "x-gateway-request-role")
        }
        val accepted = capability?.acceptedRequestHeaders.orEmpty().map(String::lowercase).toSet()
        val transmitted = if (capability == null) {
            // A definite 404/501 identifies a direct route. Preserve tool isolation if an older proxy understands it.
            isolation
        } else {
            if (isolation.keys.any { it.lowercase() !in accepted }) {
                throw GatewayCapabilityDiscoveryException("GATEWAY_ISOLATION_UNAVAILABLE: The selected gateway did not confirm the required tool isolation controls. Check or update that gateway and retry; no model request was sent.", code = "GATEWAY_ISOLATION_UNAVAILABLE")
            }
            requested.filterKeys { it.lowercase() in accepted } +
                profileDisplayHeaders(config.copy(gatewayConversationSync = capability.supportsFeature("conversation_sync_v1")), accepted)
        }
        val sourceCommit = (capability?.build?.get("sourceCommit") as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9]{40}")) }
        AppLogRecorder.record("Gateway", "CONTROL_NEGOTIATION · route=$identity · requested=${requested.keys.sorted()} · accepted=${transmitted.keys.sorted()} · serverCommit=${sourceCommit ?: "Not measured"} · effective=Not measured · gatewayVersion=${capability?.gatewayVersion ?: capability?.version ?: "unavailable"}")
        return permitted.copy(
            extraHeaders = permittedHeaders - requested.keys + transmitted,
            gatewayConversationSync = capability?.supportsFeature("conversation_sync_v1") == true
        )
    }

    private fun profileDisplayHeaders(config: ProviderRequestConfig, accepted: Set<String>): Map<String, String> = buildMap {
        if (CONVERSATION_RETENTION.lowercase() in accepted) {
            val callerRole = config.extraHeaders.entries.firstOrNull { it.key.equals("X-Gateway-Request-Role", true) }?.value
            val delegated = config.extraHeaders.entries.any { it.key.equals("X-Gateway-Delegated-Worker", true) && it.value.equals("true", true) }
            val retain = if (config.gatewayConversationSync) {
                gatewayStoresVisibleTurn(config)
            } else {
                PROFILE_ID.lowercase() in accepted &&
                    config.queuePersistent &&
                    config.requestRole == "primary" &&
                    (callerRole == null || callerRole == "primary") &&
                    !delegated &&
                    config.profileUid?.matches(profileUidPattern) == true
            }
            put(CONVERSATION_RETENTION, if (retain) "full" else "none")
        }
        if (PROFILE_ID.lowercase() in accepted) {
            config.profileUid?.takeIf { it.matches(profileUidPattern) }?.let { put(PROFILE_ID, it) }
        }
        if (PROFILE_NAME.lowercase() in accepted) {
            config.profileName?.takeIf { it.isNotBlank() && it.length <= 1024 && it.none(Char::isISOControl) }?.let { name ->
                val bytes = name.toByteArray(Charsets.UTF_8)
                if (bytes.size <= 1024 && bytes.toString(Charsets.UTF_8) == name) {
                    put(PROFILE_NAME, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
                }
            }
        }
    }

    private companion object {
        const val PROFILE_ID = "X-Gateway-Profile-ID"
        const val PROFILE_NAME = "X-Gateway-Profile-Name-Base64"
        const val CONVERSATION_RETENTION = "X-Gateway-Conversation-Retention"
        val profileHeaders = setOf(PROFILE_ID.lowercase(), PROFILE_NAME.lowercase(), CONVERSATION_RETENTION.lowercase())
        val profileUidPattern = Regex("[A-Za-z0-9_-]{1,160}")
    }
}
