package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
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
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Singleton
class GatewayEngineeringClient internal constructor(private val http: HttpClient) {
    @Inject constructor(network: NetworkClient) : this(network())
    private val json = Json { ignoreUnknownKeys = true }
    private val capabilityCache = RouteCapabilityCache<GatewayEngineeringCapabilities>({ System.nanoTime() / 1_000_000 }, positiveTtl = 10_000, negativeTtl = 60_000)
    private fun capabilityKey(config: ProviderRequestConfig) = "${GatewayClientDiagnostics.routeIdentity(config)}|${LocalServiceHealth.networkEpoch}|${GatewayClientDiagnostics.capabilityEpoch(config)}"

    private suspend fun request(config: ProviderRequestConfig, path: String, body: String? = null, approval: Boolean = false): String {
        val diagnostic = GatewayClientDiagnostics.withHeaders(config, model = "", knownGateway = true)
        return gatewayClientExchange(diagnostic, "engineering", safeRead = body == null, method = if (body == null) "GET" else "POST") { headers ->
            http.prepareRequest(config.buildEndpoint("gateway/engineering/$path")) {
                method = if (body == null) HttpMethod.Get else HttpMethod.Post
                config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                headers.forEach { (key, value) -> header(key, value) }
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
                    val error = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
                    val detail = error?.get("error") as? JsonObject ?: error?.get("detail") as? JsonObject ?: error
                    val code = detail?.get("code")?.jsonPrimitive?.content
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

    suspend fun capabilities(config: ProviderRequestConfig): GatewayEngineeringCapabilities =
        capabilityCache.getOrLoad(capabilityKey(config)) {
            if (GatewaySurfacePolicy.shared.rememberedStatus(GatewaySurfacePolicy.identity(config), GatewaySurfacePolicy.Surface.CAPABILITIES) != null) return@getOrLoad null
            val payload = try {
                request(config, "capabilities")
            } catch (_: UnsupportedEngineeringGateway) {
                return@getOrLoad null
            }
            val capability = try {
                json.decodeFromString<GatewayEngineeringCapabilities>(payload)
            } catch (_: kotlinx.serialization.SerializationException) {
                error("Engineering capability response is invalid. Check the selected gateway address and version.")
            }
            check(capability.service == "engineering-gateway" && capability.protocolVersion == 1 && capability.readiness?.schemaVersion in setOf(null, 1)) { "Engineering endpoint identity or protocol is unsupported. Update the desktop gateway and check its address; inference connectivity is independent." }
            check(!capability.principalId.isNullOrBlank() && capability.principalId.length <= 256 && capability.principalId.none(Char::isISOControl)) { "Gateway engineering identity is unavailable." }
            val version = capability.gatewayVersion?.takeIf { it.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) }
            val build = capability.serverBuild?.takeIf { it.matches(Regex("sha256:[a-f0-9]{64}")) }
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Gateway", "ENGINEERING_HANDSHAKE · route=${capabilityKey(config)} · gatewayVersion=${version ?: "Not measured"} · serverBuild=${build ?: "Not measured"} · protocol=1")
            capability
        } ?: throw UnsupportedEngineeringGateway()

    suspend fun principal(config: ProviderRequestConfig, requireReady: Boolean = true): String {
        val capability = capabilities(config)
        if (requireReady) capability.unavailableReason()?.let { error(it) }
        if (!requireReady && capability.readiness?.savedResults == false) error("The engineering journal is unavailable. Restore the selected gateway storage to retrieve its saved jobs.")
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
        capability.unavailableReason(graph = true)?.let { throw GatewayGraphUnavailableException(it) }
        check(capability.codeGraph?.schemaVersion == 1) { "The selected gateway repository graph protocol is unsupported. Update the desktop gateway." }
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

    suspend fun create(config: ProviderRequestConfig, payload: GatewayEngineeringRequest): GatewayEngineeringJob =
        json.decodeFromString(request(config, "jobs", json.encodeToString(payload)))

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

/** Availability only: policy, identity, TLS and authorization failures must not use this type. */
internal open class GatewayGraphUnavailableException(message: String) : IllegalStateException(message)

private class UnsupportedEngineeringGateway : GatewayGraphUnavailableException("This inference endpoint does not expose engineering jobs. Select an engineering gateway endpoint; ordinary chat remains available.")

class GatewayExecutionRejected(val code: String, val httpStatus: Int) :
    IllegalStateException(
        "The selected execution profile could not be verified ($code). Saved work is retained; review the selected profile and gateway status."
    )
