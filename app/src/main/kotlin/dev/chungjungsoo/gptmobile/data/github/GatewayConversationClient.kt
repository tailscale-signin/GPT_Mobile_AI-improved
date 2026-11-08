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
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Catalog and replay calls cannot create work. Command submission has no automatic retry. */
@Singleton
class GatewayConversationClient internal constructor(private val http: HttpClient) {
    @Inject constructor(network: NetworkClient) : this(network())
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun request(config: ProviderRequestConfig, path: String, cursor: String? = null, limit: Int? = null, body: JsonObject? = null, post: Boolean = body != null): String {
        require(cursor == null || cursor.length in 1..2048 && cursor.none(Char::isISOControl))
        val diagnostic = GatewayClientDiagnostics.withHeaders(config, model = "", knownGateway = true)
        return gatewayClientExchange(diagnostic, "continuity", safeRead = !post, method = if (post) "POST" else "GET") { headers ->
            http.prepareRequest(config.buildEndpoint("gateway/$path")) {
                method = if (post) HttpMethod.Post else HttpMethod.Get
                config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                headers.forEach { (key, value) -> header(key, value) }
                cursor?.let { parameter("cursor", it) }
                limit?.let { parameter("limit", it) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                timeout {
                    connectTimeoutMillis = 5_000
                    requestTimeoutMillis = 30_000
                    socketTimeoutMillis = 30_000
                }
            }.execute { response ->
                GatewayClientDiagnostics.observe(diagnostic, response.status.value) { response.headers[it] }
                val payload = response.boundedMetadata(2_000_000)
                if (!response.status.isSuccess()) {
                    val details = gatewayErrorDetails(payload)
                    throw GatewayContinuityFailure(response.status.value, details.first, details.second)
                }
                payload
            }
        }
    }

    suspend fun identity(config: ProviderRequestConfig): GatewayIdentity = json.decodeFromString<GatewayIdentity>(request(config, "identity")).also { it.validate() }

    suspend fun catalog(config: ProviderRequestConfig, cursor: String? = null): GatewayJobCatalog = json.decodeFromString(request(config, "job-catalog", cursor, 50))

    suspend fun conversation(config: ProviderRequestConfig, id: String): GatewayConversationSnapshot = json.decodeFromString(request(config, "conversations/${identifier(id)}"))

    suspend fun messages(config: ProviderRequestConfig, id: String, cursor: String? = null): GatewayMessagePage = json.decodeFromString(request(config, "conversations/${identifier(id)}/messages", cursor, 100))

    suspend fun command(config: ProviderRequestConfig, id: String): GatewayCommandReceipt? = try {
        json.decodeFromString(request(config, "commands/${identifier(id)}"))
    } catch (failure: GatewayContinuityFailure) {
        if (failure.status == 404) null else throw failure
    }

    suspend fun cancelOrdinary(config: ProviderRequestConfig, id: String): JsonObject = json.parseToJsonElement(request(config, "jobs/${identifier(id)}/cancel", post = true)) as? JsonObject ?: error("Invalid gateway cancellation receipt.")

    suspend fun followUp(config: ProviderRequestConfig, conversationId: String, commandId: String, expectedRevision: Long, content: String, scope: String): GatewayCommandReceipt {
        require(scope in setOf("read", "plan", "implement") && expectedRevision >= 0 && content.length in 1..16000)
        val body = buildJsonObject {
            put("command_id", identifier(commandId))
            put("expected_revision", expectedRevision)
            put("content", content)
            put("scope", scope)
        }
        return json.decodeFromString(request(config, "conversations/${identifier(conversationId)}/messages", body = body))
    }

    private fun identifier(value: String): String = value.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "Invalid saved gateway identifier." } }
}

class GatewayContinuityFailure(val status: Int, val code: String? = null, detail: String? = null) :
    IllegalStateException(
        (code?.let { "$it: " }.orEmpty()) + (
            detail ?: when (status) {
                401, 403 -> "The gateway account is not authorized for this saved conversation."
                404 -> "The selected gateway does not expose this retained conversation or command."
                409 -> "The gateway refused this request. Read its saved command and conversation state before retrying."
                else -> "Gateway observation failed (HTTP $status). Saved work is retained; its remote outcome is not changed."
            }
            )
    )

internal fun gatewayErrorDetails(payload: String): Pair<String?, String?> {
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null to null
    val details = root["error"] as? JsonObject ?: root["detail"] as? JsonObject ?: root
    val code = (details["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) }
    val message = (details["message"] as? JsonPrimitive ?: root["detail"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.filter { !it.isISOControl() || it == '\n' || it == '\t' }?.trim()?.take(500)?.takeIf(String::isNotBlank)
    return code to message
}
