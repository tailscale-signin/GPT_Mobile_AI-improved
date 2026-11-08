package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayClientExchange
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** Catalog and transcript reads have no admission, resume, or cancellation side effects. */
@Singleton
class GatewayJobClient internal constructor(private val http: HttpClient) {
    @Inject constructor(network: NetworkClient) : this(network())
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun read(config: ProviderRequestConfig, path: String, maxBytes: Int = 2_000_000): String {
        val diagnostic = GatewayClientDiagnostics.withHeaders(config, model = "", knownGateway = true)
        return gatewayClientExchange(diagnostic, "job_history", safeRead = true) { headers ->
            http.prepareRequest(config.buildEndpoint("gateway/$path")) {
                method = HttpMethod.Get
                config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                headers.forEach { (key, value) -> header(key, value) }
                timeout {
                    connectTimeoutMillis = 10_000
                    requestTimeoutMillis = if (maxBytes > 2_000_000) 120_000 else 30_000
                    socketTimeoutMillis = 30_000
                }
            }.execute { response ->
                GatewayClientDiagnostics.observe(diagnostic, response.status.value) { response.headers[it] }
                if (response.status.value in setOf(404, 501)) throw GatewayHistoryUnsupported()
                if (response.status.value in setOf(409, 410)) throw GatewayCursorExpired()
                check(response.status.isSuccess()) {
                    when (response.status.value) {
                        401, 403 -> "Saved gateway conversations require the original account and connection."
                        409, 410 -> "The saved history cursor expired. Refresh the conversation to reconcile its current snapshot."
                        else -> "Gateway history is unavailable (HTTP ${response.status.value}). Cached jobs are retained."
                    }
                }
                try {
                    response.boundedMetadata(maxBytes)
                } catch (oversized: IllegalStateException) {
                    if (maxBytes > 2_000_000 && oversized.message == "Catalog response exceeds its size limit.") throw GatewayTranscriptTooLarge()
                    throw oversized
                }
            }
        }
    }

    /** Captures the authenticated destination before a new durable ordinary admission. */
    internal suspend fun scope(config: ProviderRequestConfig): GatewayScopeIdentity =
        json.decodeFromString<GatewayScopeIdentity>(read(config, "conversations?limit=1")).also { requireGatewayIdentity(it.gatewayId, it.principalId) }

    /** Canonical journal identity; the inference installation identifier may be different. */
    internal suspend fun identity(config: ProviderRequestConfig): GatewayScopeIdentity =
        json.decodeFromString<GatewayScopeIdentity>(read(config, "identity")).also { requireGatewayIdentity(it.gatewayId, it.principalId) }

    suspend fun command(config: ProviderRequestConfig, commandId: String): GatewayCommandReceipt? {
        val payload = try {
            read(config, "commands/${pathId(commandId)}")
        } catch (_: GatewayHistoryUnsupported) {
            return null
        }
        return json.decodeFromString<GatewayCommandReceipt>(payload).also {
            require(it.schemaVersion == 1 && it.commandId == commandId && it.jobKind in setOf("engineering", "generation"))
            requireGatewayIdentity(it.gatewayId, it.principalId)
        }
    }

    suspend fun catalog(config: ProviderRequestConfig, cursor: String? = null): GatewayJobCatalog =
        json.decodeFromString<GatewayJobCatalog>(read(config, "job-catalog?limit=50${cursor.query("cursor")}")).also {
            require(it.schemaVersion == 1 && it.items.size <= 100)
            requireGatewayIdentity(it.gatewayId, it.principalId)
            require(!it.hasMore || !it.nextCursor.isNullOrBlank())
        }

    suspend fun conversations(config: ProviderRequestConfig, cursor: String? = null): GatewayConversationPage =
        json.decodeFromString<GatewayConversationPage>(read(config, "conversations?limit=50${cursor.query("cursor")}")).also {
            require(it.schemaVersion == 1 && it.items.size <= 100)
            requireGatewayIdentity(it.gatewayId, it.principalId)
            require(!it.hasMore || !it.nextCursor.isNullOrBlank())
            require(it.items.all { item -> item.gatewayId == it.gatewayId && item.principalId == it.principalId })
        }

    suspend fun conversation(config: ProviderRequestConfig, conversationId: String): GatewayConversationSnapshot =
        json.decodeFromString<GatewayConversationSnapshot>(read(config, "conversations/${pathId(conversationId)}")).also {
            require(it.schemaVersion == 1 && it.conversationId == conversationId)
            requireGatewayIdentity(it.gatewayId, it.principalId)
        }

    suspend fun messages(config: ProviderRequestConfig, conversationId: String, cursor: String?): GatewayTranscriptPage =
        json.decodeFromString<GatewayTranscriptPage>(read(config, "conversations/${pathId(conversationId)}/messages?limit=100${cursor.query("after_cursor")}", maxBytes = 65 * 1024 * 1024)).also {
            require(it.schemaVersion == 1 && it.conversationId == conversationId && it.messages.size <= 200)
            requireGatewayIdentity(it.gatewayId, it.principalId)
            require(!it.hasMore || !it.nextCursor.isNullOrBlank() && it.nextCursor != cursor)
            require(it.messages.all { message -> message.messageId.isNotBlank() && message.turnId.isNotBlank() && message.role in setOf("user", "assistant", "tool", "system") && message.revision >= 0 })
        }

    private fun pathId(id: String): String {
        require(id.isNotBlank() && id.length <= 256 && id.none(Char::isISOControl))
        return id.encodeURLParameter()
    }

    private fun String?.query(name: String): String {
        if (this == null) return ""
        require(length in 1..4096 && none(Char::isISOControl))
        return "&$name=${encodeURLParameter()}"
    }
}
