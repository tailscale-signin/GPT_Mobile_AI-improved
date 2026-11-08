package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.network.LocalEndpointConnectionPolicy
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.LocalServiceUnavailableException
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewaySurfacePolicy
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayClientExchange
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Singleton
class GatewayEngineeringClient internal constructor(
    private val http: HttpClient,
    private val readHttp: HttpClient = http,
    private val evictIdleConnections: suspend () -> Unit = {}
) {
    @Inject constructor(network: NetworkClient) : this(network(), network.gatewayReadClient(), network::evictIdleConnections)
    private val json = Json { ignoreUnknownKeys = true }
    private data class CapabilityAttempt(val capability: GatewayEngineeringCapabilities? = null, val failure: GatewayAdmissionException? = null)
    private val capabilityCache = RouteCapabilityCache<CapabilityAttempt>({ System.nanoTime() / 1_000_000 }, positiveTtl = 10_000, negativeTtl = 60_000)
    private fun capabilityKey(config: ProviderRequestConfig) = "${GatewayClientDiagnostics.routeIdentity(config)}|${LocalServiceHealth.networkEpoch}|${GatewayClientDiagnostics.capabilityEpoch(config)}"

    private suspend fun request(config: ProviderRequestConfig, path: String, body: String? = null, approval: Boolean = false, root: String = "gateway/engineering"): String {
        val diagnostic = GatewayClientDiagnostics.withHeaders(config, model = "", knownGateway = true)
        val endpoint = config.buildEndpoint("$root/$path")
        val exchange: suspend () -> String = {
            gatewayClientExchange(diagnostic, "engineering", safeRead = body == null, method = if (body == null) "GET" else "POST") { headers ->
                (if (body == null) readHttp else http).prepareRequest(endpoint) {
                    method = if (body == null) HttpMethod.Get else HttpMethod.Post
                    config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                    headers.forEach { (key, value) -> header(key, value) }
                    if (body == null && LocalEndpointConnectionPolicy.freshConnection(endpoint)) header(HttpHeaders.Connection, "close")
                    if (approval) header("X-Gateway-User-Action", "approve-and-merge")
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    timeout {
                        connectTimeoutMillis = if (path == "capabilities") 1_500 else 10_000
                        requestTimeoutMillis = if (path == "capabilities") 4_000 else 120_000
                        socketTimeoutMillis = if (path == "capabilities") 4_000 else 120_000
                    }
                }.execute { response ->
                    GatewayClientDiagnostics.observe(diagnostic, response.status.value) { response.headers[it] }
                    if (path == "capabilities" && response.status.value in setOf(404, 501)) throw UnsupportedEngineeringGateway()
                    val payload = response.boundedMetadata(if (path == "capabilities") 128 * 1024 else 2_000_000)
                    if (!response.status.isSuccess()) {
                        if (path == "capabilities") throw gatewayAdmissionFailure(response.status.value, GatewayAdmissionSurface.CAPABILITIES, response.headers["X-Gateway-Admission-Error"], payload)
                        val error = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
                        val detail = error?.get("error") as? JsonObject ?: error?.get("detail") as? JsonObject ?: error
                        val code = detail?.get("code")?.jsonPrimitive?.content
                        if (path == "graph" && code == "graph_connection_required" && response.status.value in setOf(412, 503)) {
                            val readiness = runCatching { error?.get("readiness")?.let { json.decodeFromJsonElement(GatewayEngineeringReadiness.serializer(), it) } }.getOrNull()
                            val missing = GatewayGraphPrerequisite.missing(GatewayEngineeringCapabilities("engineering-gateway", 1, readiness = readiness))
                            throw GatewayGraphUnavailableException("The selected gateway graph prerequisites changed before this request.", missing)
                        }
                        if (body != null && response.status.value in 400..499 && response.status.value !in setOf(408, 499)) {
                            val message = when (response.status.value) {
                                401, 403 -> "Gateway engineering access was rejected. Restore the original account and engineering permission before submitting a new command."
                                409, 412 -> "The conversation or job changed. Refresh Jobs and review the original task before sending another command."
                                429 -> "The gateway did not admit this command because its queue or request limit is full."
                                else -> "The gateway rejected this command (HTTP ${response.status.value}). Review it before sending another command."
                            }
                            throw GatewayEngineeringRequestRejected(response.status.value, message)
                        }
                        if (code != null && code.matches(Regex("(?:EXECUTION|SELECTED_ONLY|INFERENCE)_[A-Z0-9_]{1,80}"))) {
                            throw GatewayExecutionRejected(code, response.status.value)
                        }
                    }
                    check(response.status.isSuccess()) {
                        when (response.status.value) {
                            401, 403 -> "Gateway engineering access is not authorized. Check the gateway connection and engineering scope."
                            404 -> "This gateway does not expose the saved engineering job. Check the selected gateway and account."
                            409 -> "The job or PR changed. Refresh and review its current commit before confirming."
                            503 -> "The gateway needs its host GitHub connection configured, or is at capacity. Your request is retained."
                            else -> "Gateway engineering request failed (HTTP ${response.status.value}). The job may still be running; reconnect to check."
                        }
                    }
                    payload
                }
            }
        }
        // Bounded GETs may recover a stale idle socket once. A job POST or approval
        // keeps its original request identity and is never replayed by this layer.
        return if (body == null) LocalEndpointConnectionPolicy.probe(endpoint, evictIdleConnections, exchange) else exchange()
    }

    suspend fun capabilities(config: ProviderRequestConfig): GatewayEngineeringCapabilities {
        val attempt = capabilityCache.getOrLoad(capabilityKey(config)) {
            if (GatewaySurfacePolicy.shared.rememberedStatus(GatewaySurfacePolicy.identity(config), GatewaySurfacePolicy.Surface.CAPABILITIES) != null) return@getOrLoad null
            val payload = try {
                request(config, "capabilities")
            } catch (_: UnsupportedEngineeringGateway) {
                return@getOrLoad null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: GatewayAdmissionException) {
                return@getOrLoad CapabilityAttempt(failure = failure)
            } catch (failure: Exception) {
                val causes = generateSequence<Throwable>(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
                val reason = when {
                    causes.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException } -> GatewayAdmissionReason.INVALID_CONNECTION
                    causes.any { it is java.io.IOException || it is LocalServiceUnavailableException || it.javaClass.simpleName in setOf("HttpRequestTimeoutException", "ConnectTimeoutException", "SocketTimeoutException") } -> GatewayAdmissionReason.TRANSPORT_INTERRUPTED
                    else -> GatewayAdmissionReason.INVALID_RESPONSE
                }
                return@getOrLoad CapabilityAttempt(failure = GatewayAdmissionException(reason, null, null, GatewayAdmissionSurface.CAPABILITIES))
            }
            val capability = try {
                json.decodeFromString<GatewayEngineeringCapabilities>(payload)
            } catch (_: kotlinx.serialization.SerializationException) {
                return@getOrLoad CapabilityAttempt(failure = GatewayAdmissionException(GatewayAdmissionReason.INVALID_RESPONSE, 200, null, GatewayAdmissionSurface.CAPABILITIES))
            }
            if (capability.service != "engineering-gateway" || capability.protocolVersion != 1 || capability.readiness?.schemaVersion !in setOf(null, 1)) {
                return@getOrLoad CapabilityAttempt(failure = GatewayAdmissionException(GatewayAdmissionReason.UNSUPPORTED_CONTRACT, 200, null, GatewayAdmissionSurface.CAPABILITIES))
            }
            if (capability.principalId.isNullOrBlank() || capability.principalId.length > 256 || capability.principalId.any(Char::isISOControl)) {
                return@getOrLoad CapabilityAttempt(failure = GatewayAdmissionException(GatewayAdmissionReason.INVALID_RESPONSE, 200, null, GatewayAdmissionSurface.CAPABILITIES))
            }
            val version = capability.gatewayVersion?.takeIf { it.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) }
            val build = capability.serverBuild?.takeIf { it.matches(Regex("sha256:[a-f0-9]{64}")) }
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Gateway", "ENGINEERING_HANDSHAKE · route=${capabilityKey(config)} · gatewayVersion=${version ?: "Not measured"} · serverBuild=${build ?: "Not measured"} · protocol=1")
            CapabilityAttempt(capability = capability)
        } ?: throw UnsupportedEngineeringGateway()
        attempt.failure?.let { throw it }
        return requireNotNull(attempt.capability)
    }

    suspend fun principal(config: ProviderRequestConfig, requireReady: Boolean = true): String {
        val capability = capabilities(config)
        capability.requireJobReadiness(savedResultsOnly = !requireReady)
        return requireNotNull(capability.principalId)
    }

    suspend fun prepareBinding(config: ProviderRequestConfig, selection: GatewayExecutionSelection): GatewayExecutionAdmission {
        val payload = buildJsonObject { put("selection", json.encodeToJsonElement(GatewayExecutionSelection.serializer(), selection)) }
        val result = json.decodeFromString<GatewayExecutionAdmission>(request(config, "bindings", payload.toString()))
        result.verify(selection)
        return result
    }

    suspend fun bindingCapabilities(config: ProviderRequestConfig, selection: GatewayExecutionSelection, bindingId: String): GatewayExecutionAdmission {
        require(bindingId.matches(Regex("bind_[a-f0-9]{32}")))
        return json.decodeFromString<GatewayExecutionAdmission>(request(config, "bindings/$bindingId/capabilities")).also {
            it.verify(selection)
            check(it.bindingId == bindingId) { "The gateway execution binding changed." }
        }
    }

    suspend fun requireGraphReady(config: ProviderRequestConfig): String {
        val capability = capabilities(config)
        capability.unavailableReason(graph = true)?.let { throw GatewayGraphUnavailableException(it, GatewayGraphPrerequisite.missing(capability)) }
        if (capability.codeGraph?.schemaVersion != 1) throw GatewayGraphUnavailableException("The selected gateway repository graph protocol is unsupported. Update the desktop gateway.", listOf(GatewayGraphPrerequisite.GRAPH_PROTOCOL_UNSUPPORTED))
        return requireNotNull(capability.principalId)
    }

    internal fun diagnosticEvents(config: ProviderRequestConfig, jobId: String, after: Int): kotlinx.coroutines.flow.Flow<JsonObject> = kotlinx.coroutines.flow.flow {
        require(jobId.matches(Regex("diag_[a-f0-9]{32}")) && after in 0..3)
        val diagnostic = GatewayClientDiagnostics.withHeaders(config, model = "", knownGateway = true)
        gatewayClientExchange(diagnostic, "engineering", safeRead = true) { headers ->
            http.prepareRequest(config.buildEndpoint("gateway/engineering/diagnostics/jobs/$jobId/events?after=$after")) {
                method = HttpMethod.Get
                config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                headers.forEach { (key, value) -> header(key, value) }
                header("Accept", "text/event-stream")
                timeout {
                    connectTimeoutMillis = 10_000
                    requestTimeoutMillis = 30_000
                    socketTimeoutMillis = 30_000
                }
            }.execute { response ->
                GatewayClientDiagnostics.observe(diagnostic, response.status.value) { response.headers[it] }
                check(response.status.isSuccess()) { "Gateway diagnostic stream failed (HTTP ${response.status.value})." }
                val channel = response.bodyAsChannel()
                var seen = after
                while (!channel.isClosedForRead) {
                    val line = channel.readLine() ?: break
                    if (line.length > 4096) error("Gateway diagnostic event exceeds its limit.")
                    val data = dev.chungjungsoo.gptmobile.data.network.SseUtils.extractSseData(line) ?: continue
                    if (data == "[DONE]") break
                    val event = json.parseToJsonElement(data) as? JsonObject ?: error("Invalid gateway diagnostic event.")
                    val sequence = event["sequence"]?.jsonPrimitive?.intOrNull ?: error("Missing diagnostic sequence.")
                    check(event["job_id"] == kotlinx.serialization.json.JsonPrimitive(jobId) && sequence == seen + 1 && sequence <= 3) { "Gateway event identity or cursor changed." }
                    seen = sequence
                    emit(event)
                }
            }
        }
    }

    internal suspend fun diagnostic(config: ProviderRequestConfig, path: String, body: JsonObject? = null): JsonObject =
        json.parseToJsonElement(request(config, "diagnostics/$path", body?.toString())) as? JsonObject
            ?: error("Invalid gateway diagnostic response.")

    suspend fun create(config: ProviderRequestConfig, payload: GatewayEngineeringRequest): GatewayEngineeringJob {
        val response = if (payload.conversationId == null) {
            request(config, "jobs", json.encodeToString(payload))
        } else {
            val command = buildJsonObject {
                put("kind", "message")
                put("command_id", payload.requestId)
                put("content", payload.objective)
                put("expected_revision", requireNotNull(payload.expectedRevision) { "Refresh the saved conversation before replying." })
                put("engineering_request", json.encodeToJsonElement(GatewayEngineeringRequest.serializer(), payload))
            }
            request(config, "conversations/${payload.conversationId.encodeURLParameter()}/messages", command.toString(), root = "gateway")
        }
        return json.decodeFromString(response)
    }

    suspend fun graph(config: ProviderRequestConfig, arguments: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject =
        json.parseToJsonElement(request(config, "graph", arguments.toString())) as? kotlinx.serialization.json.JsonObject
            ?: error("The gateway returned an invalid repository graph response.")

    suspend fun get(config: ProviderRequestConfig, id: String, afterRevision: Int): GatewayEngineeringJob {
        require(id.matches(Regex("eng_[a-f0-9]{32}")))
        return json.decodeFromString(request(config, "jobs/$id?after_revision=${afterRevision.coerceAtLeast(-1)}"))
    }

    suspend fun evidence(config: ProviderRequestConfig, jobId: String, receiptId: String): String {
        require(jobId.matches(Regex("eng_[a-f0-9]{32}")) && receiptId.matches(Regex("ev_[a-f0-9]{24}")))
        return request(config, "jobs/$jobId/evidence/$receiptId?preview=true")
    }

    suspend fun control(config: ProviderRequestConfig, job: GatewayEngineeringJob, action: String): GatewayEngineeringJob {
        require(job.id.matches(Regex("eng_[a-f0-9]{32}")) && action in setOf("approve", "cancel", "request_changes"))
        if (action == "approve") require(job.mergeReady && job.pr != null && job.headSha?.matches(Regex("[a-f0-9]{40}")) == true)
        val body = buildJsonObject {
            put("revision", job.revision)
            if (action == "approve") {
                put("pr", requireNotNull(job.pr))
                put("head_sha", requireNotNull(job.headSha))
            }
        }
        return json.decodeFromString(request(config, "jobs/${job.id}/$action", body.toString(), action == "approve"))
    }
}

internal class UnsupportedEngineeringGateway : GatewayGraphUnavailableException("This inference endpoint does not expose engineering jobs. Select an engineering gateway endpoint; ordinary chat remains available.", listOf(GatewayGraphPrerequisite.ENGINEERING_SURFACE_UNSUPPORTED))

class GatewayExecutionRejected(val code: String, val httpStatus: Int) :
    IllegalStateException(
        "The selected execution profile could not be verified ($code). Saved work is retained; review the selected profile and gateway status."
    )

/** A response rejected this POST; prior uncertain attempts still require command reconciliation. */
class GatewayEngineeringRequestRejected(val httpStatus: Int, message: String, val commandOutcomeUnknown: Boolean = false) : IllegalStateException(message)
