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
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class GatewayInstructionCommand(
    @SerialName("command_id") val commandId: String,
    val content: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    val kind: String = "instruction"
)

/** Explicit user commands only; reconnect and transcript observers never call this client. */
@Singleton
class GatewayConversationCommandClient internal constructor(private val http: HttpClient) {
    @Inject constructor(network: NetworkClient) : this(network())
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    internal suspend fun instruction(config: ProviderRequestConfig, conversationId: String, command: GatewayInstructionCommand): GatewayCommandReceipt {
        require(conversationId.isNotBlank() && conversationId.length <= 256 && conversationId.none(Char::isISOControl))
        require(command.commandId.length in 1..128 && command.content.isNotBlank() && command.content.length <= 16000 && command.expectedRevision >= 0)
        val diagnostic = GatewayClientDiagnostics.withHeaders(config, model = "", knownGateway = true)
        return gatewayClientExchange(diagnostic, "commands", safeRead = false, method = "POST") { headers ->
            http.prepareRequest(config.buildEndpoint("gateway/conversations/${conversationId.encodeURLParameter()}/messages")) {
                method = HttpMethod.Post
                config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                headers.forEach { (key, value) -> header(key, value) }
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(command))
                timeout {
                    connectTimeoutMillis = 10_000
                    requestTimeoutMillis = 30_000
                    socketTimeoutMillis = 30_000
                }
            }.execute { response ->
                GatewayClientDiagnostics.observe(diagnostic, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                if (response.status.value == 409) throw GatewayConversationConflict()
                check(response.status.isSuccess()) {
                    when (response.status.value) {
                        401, 403 -> "The original gateway account cannot accept this instruction. The saved command is retained."
                        else -> "The instruction reply was not confirmed (HTTP ${response.status.value}). Reconnect to check its saved command."
                    }
                }
                json.decodeFromString<GatewayCommandReceipt>(response.boundedMetadata(128 * 1024)).also {
                    requireGatewayIdentity(it.gatewayId, it.principalId)
                    check(it.schemaVersion == 1 && it.commandId == command.commandId && it.conversationId == conversationId && it.jobKind == "engineering") { "The instruction receipt does not match the saved command." }
                }
            }
        }
    }
}

class GatewayConversationConflict : IllegalStateException("The conversation changed or its job finished. Refresh it and review the instruction before sending again.")
