package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlRepository
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayPairedRequestSource
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
internal data class GatewayInstructionCommand(
    @SerialName("command_id") val commandId: String,
    val content: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    val kind: String = "instruction"
)

/** Explicit user commands only; reconnect and transcript observers never call this client. */
@Singleton
class GatewayConversationCommandClient internal constructor(private val http: HttpClient, private val pairedRequests: GatewayPairedRequestSource? = null) {
    @Inject internal constructor(network: NetworkClient, control: GatewayControlRepository) : this(network(), control)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    internal suspend fun instruction(config: ProviderRequestConfig, conversationId: String, command: GatewayInstructionCommand): GatewayCommandReceipt {
        require(conversationId.isNotBlank() && conversationId.length <= 256 && conversationId.none(Char::isISOControl))
        require(command.commandId.length in 1..128 && command.content.isNotBlank() && command.content.length <= 16000 && command.expectedRevision >= 0)
        val diagnostic = GatewayClientDiagnostics.withHeaders(config, model = "", knownGateway = true)
        val path = "gateway/conversations/${conversationId.encodeURLParameter()}/messages"
        val body = json.encodeToString(command)
        return gatewayClientExchange(diagnostic, "commands", safeRead = false, method = "POST") { headers ->
            val paired = pairedRequests?.prepare(config, "POST", "/$path", body)
            (paired?.http ?: http).prepareRequest(paired?.endpoint ?: config.buildEndpoint(path)) {
                method = HttpMethod.Post
                (paired?.token ?: config.token)?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                (paired?.requestHeaders(headers) ?: headers).forEach { (key, value) -> header(key, value) }
                contentType(ContentType.Application.Json)
                setBody(body)
                timeout {
                    connectTimeoutMillis = 10_000
                    requestTimeoutMillis = 30_000
                    socketTimeoutMillis = 30_000
                }
            }.execute { response ->
                GatewayClientDiagnostics.observe(diagnostic, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                if (response.status.value == 409) throw GatewayConversationConflict.fromBody(response.boundedMetadata(128 * 1024), conversationId)
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

class GatewayConversationConflict(
    val recoveryAction: String? = null,
    val activeJobId: String? = null,
    val conversationRevision: Long? = null
) : IllegalStateException(
    when (recoveryAction) {
        "return_client_tool_results" -> "The existing job is waiting for saved tool results. Open that job and return its original results before continuing; completed tools must not run again."
        "wait_for_active_job" -> "The existing job is still running. Keep this instruction queued and refresh that job's saved result."
        "queue_instruction" -> "An engineering job is active. Review its refreshed state and queue this instruction for that job."
        "resolve_active_job" -> "The existing job needs attention. Open it and resolve the pending state before sending this instruction."
        else -> "The conversation changed or its job finished. Refresh it and review the instruction before sending again."
    }
) {
    companion object {
        internal fun fromBody(body: String, conversationId: String): GatewayConversationConflict {
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val error = root?.get("error") as? JsonObject ?: return GatewayConversationConflict()
            fun text(key: String) = (error[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (text("conversation_id")?.let { it != conversationId } == true) return GatewayConversationConflict()
            val action = text("recovery_action")?.takeIf { it in setOf("open_active_job", "return_client_tool_results", "wait_for_active_job", "queue_instruction", "resolve_active_job") }
            val job = text("active_job_id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            val revision = (error["conversation_revision"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
            return GatewayConversationConflict(action, job, revision)
        }
    }
}
