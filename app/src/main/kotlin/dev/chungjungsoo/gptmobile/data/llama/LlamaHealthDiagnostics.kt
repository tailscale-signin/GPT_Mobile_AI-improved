package dev.chungjungsoo.gptmobile.data.llama

import dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot
import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.network.LocalEndpointConnectionPolicy
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewaySurfacePolicy
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayClientExchange
import dev.chungjungsoo.gptmobile.data.network.gateway.parseGatewayReadiness
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal enum class LlamaConnectionPhase { DISCONNECTED, REACHABLE, MODEL_LOADED, INFERENCE_READY, GENERATING, DEGRADED }

internal data class LlamaHealthReport(
    val healthHttpStatus: Int? = null,
    val serverStatus: String? = null,
    val modelsHttpStatus: Int? = null,
    val selectedModelListed: Boolean? = null,
    val contextTokens: Long? = null,
    val templateRevision: String? = null,
    val propsHttpStatus: Int? = null,
    val gatewayHttpStatus: Int? = null,
    val gatewayReady: Boolean? = null,
    val metadataRestricted: Boolean = false,
    val serverGeneration: String? = null,
    val modelIdentity: String? = null,
    val healthFailure: String? = null,
    val modelsFailure: String? = null,
    val propsFailure: String? = null,
    val inferenceHttpStatus: Int? = null,
    val inferenceTested: Boolean = false,
    val inferenceReady: Boolean? = null,
    val inferenceFailure: String? = null,
    val activeGenerations: Int = 0,
    val activeRequestId: String? = null,
    val inferenceLatencyMs: Long? = null,
    val timeToFirstTokenMs: Long? = null,
    val gatewayToolsReady: Boolean? = null,
    val cachedRestrictedSurfaces: List<String> = emptyList(),
    val metadataSource: String = "DIRECT_METADATA",
    val skippedMetadataSurfaces: List<String> = emptyList(),
    val gatewayFailure: String? = null,
    val gatewayReasonCode: String? = null,
    val gatewayRetryable: Boolean? = null,
    val propsRejection: LlamaMetadataFailure? = null
) {
    val phase: LlamaConnectionPhase get() = when {
        activeGenerations > 0 -> LlamaConnectionPhase.GENERATING
        inferenceReady == true -> LlamaConnectionPhase.INFERENCE_READY
        inferenceTested && inferenceReady == false -> LlamaConnectionPhase.DEGRADED
        selectedModelListed == true && gatewayReady == true -> LlamaConnectionPhase.MODEL_LOADED
        selectedModelListed == true && healthHttpStatus == 200 && serverStatus in setOf("ok", "ready") -> LlamaConnectionPhase.MODEL_LOADED
        gatewayReady == false -> LlamaConnectionPhase.DEGRADED
        listOfNotNull(healthHttpStatus, modelsHttpStatus, propsHttpStatus, gatewayHttpStatus).isEmpty() -> LlamaConnectionPhase.DISCONNECTED
        healthFailure != null || propsFailure != null || modelsFailure != null || gatewayFailure != null -> LlamaConnectionPhase.DEGRADED
        else -> LlamaConnectionPhase.REACHABLE
    }

    fun summary(): String = "healthHttp=${healthHttpStatus ?: "unknown"}; serverStatus=${serverStatus ?: "unknown"}; " +
        "modelsHttp=${modelsHttpStatus ?: "unknown"}; selectedModelListed=${selectedModelListed ?: "unknown"}; " +
        "propsHttp=${propsHttpStatus ?: "unknown"}; servingContext=${contextTokens ?: "unknown"}; templateRevision=${templateRevision ?: "unknown"}; " +
        "gatewayHttp=${gatewayHttpStatus ?: "unknown"}; gatewayReady=${gatewayReady ?: "Not measured"}; gatewayReasonCode=${gatewayReasonCode ?: "Not reported"}; gatewayFailure=${gatewayFailure ?: "none"}; metadataSource=$metadataSource; metadataRestricted=$metadataRestricted; cachedRestrictedSurfaces=$cachedRestrictedSurfaces; " +
        "phase=$phase; serverGeneration=${serverGeneration ?: "Not reported"}; modelIdentity=${modelIdentity ?: "Not reported"}; " +
        "healthFailure=${healthFailure ?: "none"}; propsFailure=${propsFailure ?: "none"}; propsErrorCode=${propsRejection?.serverCode ?: "Not reported"}; propsErrorType=${propsRejection?.serverType ?: "Not reported"}; propsErrorParameter=${propsRejection?.parameter ?: "Not reported"}; propsErrorMessage=${propsRejection?.message ?: "Not reported"}; propsRequestId=${propsRejection?.requestId ?: "Not reported"}; modelsFailure=${modelsFailure ?: "none"}; " +
        "activeGenerations=$activeGenerations; requestId=${activeRequestId ?: "Not reported"}; inferenceTested=$inferenceTested; inferenceReady=${inferenceReady ?: "Not measured"}; inferenceFailure=${inferenceFailure ?: "none"}; inferenceLatencyMs=${inferenceLatencyMs ?: "Not measured"}; timeToFirstTokenMs=${timeToFirstTokenMs ?: "Not measured"}; gatewayToolsReady=${gatewayToolsReady ?: "Not measured"}"

    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "healthHttp" to healthHttpStatus,
        "serverStatus" to serverStatus,
        "modelsHttp" to modelsHttpStatus,
        "selectedModelListed" to selectedModelListed,
        "propsHttp" to propsHttpStatus,
        "servingContext" to contextTokens,
        "templateRevision" to templateRevision,
        "gatewayHttp" to gatewayHttpStatus,
        "gatewayReady" to gatewayReady,
        "gatewayFailure" to gatewayFailure,
        "gatewayReasonCode" to gatewayReasonCode,
        "gatewayRetryable" to gatewayRetryable,
        "metadataSource" to metadataSource,
        "metadataRestricted" to metadataRestricted,
        "cachedRestrictedSurfaces" to cachedRestrictedSurfaces,
        "skippedMetadataSurfaces" to skippedMetadataSurfaces,
        "phase" to phase.name,
        "serverGeneration" to serverGeneration,
        "modelIdentity" to modelIdentity,
        "healthFailure" to healthFailure,
        "propsFailure" to propsFailure,
        "propsErrorCode" to propsRejection?.serverCode,
        "propsErrorType" to propsRejection?.serverType,
        "propsErrorParameter" to propsRejection?.parameter,
        "propsErrorMessage" to propsRejection?.message,
        "propsRequestId" to propsRejection?.requestId,
        "modelsFailure" to modelsFailure,
        "activeGenerations" to activeGenerations,
        "requestId" to activeRequestId,
        "inferenceTested" to inferenceTested,
        "inferenceReady" to inferenceReady,
        "inferenceFailure" to inferenceFailure,
        "inferenceLatencyMs" to inferenceLatencyMs,
        "timeToFirstTokenMs" to timeToFirstTokenMs,
        "gatewayToolsReady" to gatewayToolsReady
    )
}

/** Read-only checks stay on the configured authenticated route and never request model autoload. */
internal class LlamaHealthDiagnostics(
    private val client: () -> HttpClient,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val readClient: () -> HttpClient = client,
    private val evictIdleConnections: suspend () -> Unit = {}
) {
    constructor(client: NetworkClient) : this({ client() }, readClient = client::gatewayReadClient, evictIdleConnections = client::evictIdleConnections)
    private val nowMs = now
    private val cache = RouteCapabilityCache<LlamaHealthReport>(now, positiveTtl = 30_000)

    suspend fun inspect(config: ProviderRequestConfig, model: String, capacity: CapacitySnapshot? = null): LlamaHealthReport {
        val route = config.apiUrl.trim().trimEnd('/')
        val uri = runCatching { URI(route) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host == null || uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null) return LlamaHealthReport()
        val key = researchHash("${GatewayClientDiagnostics.routeIdentity(config)}|$model|${LocalServiceHealth.networkEpoch}|${GatewayClientDiagnostics.capabilityEpoch(config)}")
        val report = cache.getOrLoad(key) {
            if (GatewayClientDiagnostics.runtimeEpoch(config) != "unobserved") return@getOrLoad inspectGateway(config, model)
            coroutineScope {
                val health = async { readMetadata(route.removeSuffix("/v1") + "/health", config, model, GatewaySurfacePolicy.Surface.HEALTH) }
                val models = async { read(config.buildEndpoint("models"), config, model) }
                val props = async { readMetadata(route.removeSuffix("/v1") + "/props", config, model, GatewaySurfacePolicy.Surface.PROPS) }
                val healthResult = health.await()
                val modelResult = models.await()
                val propsResult = props.await()
                val restricted = healthResult.status in setOf(401, 403) || propsResult.status in setOf(401, 403)
                val gateway = if (restricted || GatewayClientDiagnostics.runtimeEpoch(config) != "unobserved") read(config.buildEndpoint("gateway/ready"), config, model, timeoutMillis = 3_500) else null
                val gatewayBody = gateway?.body?.takeIf {
                    (it["schemaVersion"] as? JsonPrimitive)?.longOrNull == 1L &&
                        (it["instanceId"] as? JsonPrimitive)?.contentOrNull?.matches(Regex("[a-f0-9]{32}")) == true &&
                        ((it["capabilities"] as? JsonObject)?.get("selectedModelDigest") as? JsonPrimitive)?.contentOrNull == researchHash(model)
                }
                val capability = gatewayBody?.get("capabilities") as? JsonObject
                val gatewayModel = (capability?.get("modelAvailable") as? JsonPrimitive)?.booleanOrNull
                val gatewayReady = (gatewayBody?.get("ready") as? JsonPrimitive)?.booleanOrNull?.let {
                    it && gateway?.status == 200 && gatewayModel == true && (capability?.get("generationReady") as? JsonPrimitive)?.booleanOrNull == true
                }
                val instance = (gatewayBody?.get("instanceId") as? JsonPrimitive)?.contentOrNull
                GatewayClientDiagnostics.observeInstance(config, instance)
                val status = (healthResult.body?.get("status") as? JsonPrimitive)?.contentOrNull
                    ?.lowercase()?.takeIf { it in setOf("ok", "loading model", "no slot available", "error", "ready", "loading") }
                val data = modelResult.body?.get("data") as? JsonArray
                val ids = data?.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
                LlamaHealthReport(
                    healthHttpStatus = healthResult.status,
                    serverStatus = status,
                    modelsHttpStatus = modelResult.status,
                    selectedModelListed = ids?.takeIf { modelResult.status == 200 && it.size == data?.size }?.contains(model) ?: gatewayModel,
                    propsHttpStatus = propsResult.status,
                    gatewayHttpStatus = gateway?.status,
                    gatewayReady = gatewayReady,
                    gatewayToolsReady = (capability?.get("toolsReady") as? JsonPrimitive)?.booleanOrNull?.takeIf { gateway?.status == 200 },
                    metadataRestricted = restricted,
                    contextTokens = ((propsResult.body?.get("default_generation_settings") as? JsonObject)?.get("n_ctx") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
                    serverGeneration = instance ?: listOf("server_generation", "process_start_time", "started_at").firstNotNullOfOrNull { key ->
                        (propsResult.body?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let { researchHash(it).take(16) }
                    },
                    modelIdentity = ids?.takeIf { model in it }?.let { researchHash(model).take(16) },
                    healthFailure = healthResult.failure,
                    modelsFailure = modelResult.failure,
                    propsFailure = propsResult.failure,
                    propsRejection = propsResult.rejection,
                    cachedRestrictedSurfaces = listOfNotNull("health".takeIf { healthResult.cached }, "props".takeIf { propsResult.cached })
                )
            }
        } ?: LlamaHealthReport()
        val active = LlamaGenerationActivity.snapshot(config, model)
        val exactCapacity = capacity?.takeIf { it.fingerprint.requestedModel == model && it.fingerprint.resolvedModel == model }
        return report.copy(contextTokens = exactCapacity?.contextTokens ?: report.contextTokens, templateRevision = exactCapacity?.fingerprint?.templateRevision ?: report.templateRevision, activeGenerations = active.count, activeRequestId = active.requestId)
    }

    /** Known gateways own model discovery; their intentionally private /props and /health are not probes. */
    private suspend fun inspectGateway(config: ProviderRequestConfig, model: String): LlamaHealthReport {
        val gateway = read(config.buildEndpoint("gateway/ready"), config, model, timeoutMillis = 5_500)
        val readiness = gateway.body?.let { body ->
            runCatching { parseGatewayReadiness(body, gateway.status ?: 0, model, System.currentTimeMillis(), nowMs()) }.getOrNull()
        }
        val capability = gateway.body?.get("capabilities") as? JsonObject
        val stale = readiness?.observationAgeMillis?.let { it >= 20_000L } == true
        readiness?.let { GatewayClientDiagnostics.observeInstance(config, it.instanceId) }
        return LlamaHealthReport(
            selectedModelListed = readiness?.selectedModelAvailable,
            gatewayHttpStatus = gateway.status,
            gatewayReady = readiness?.ready?.takeUnless { stale },
            serverGeneration = readiness?.instanceId,
            modelIdentity = researchHash(model).take(16).takeIf { readiness?.selectedModelAvailable == true },
            gatewayToolsReady = (capability?.get("toolsReady") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.takeIf { readiness != null && !stale },
            metadataSource = "GATEWAY_CONTRACT",
            skippedMetadataSurfaces = listOf("health", "props", "models"),
            gatewayFailure = gateway.failure ?: when {
                readiness == null -> "GATEWAY_READINESS_UNVERIFIED"
                stale -> "GATEWAY_READINESS_STALE"
                else -> null
            },
            gatewayReasonCode = readiness?.reasonCode,
            gatewayRetryable = readiness?.retryable
        )
    }

    /** Explicit diagnostic action only. The fixed prompt contains no user data or tool definitions. */
    suspend fun testInference(config: ProviderRequestConfig, model: String): LlamaHealthReport {
        cache.invalidate()
        val metadata = inspect(config, model)
        if (metadata.phase != LlamaConnectionPhase.MODEL_LOADED) {
            return metadata.copy(inferenceFailure = "Confirm healthy server and selected loaded model before testing inference.")
        }
        val started = nowMs()
        var firstTokenMs: Long? = null
        return try {
            val result = withTimeoutOrNull(15_000L) {
                gatewayClientExchange(config, "chat", safeRead = false, method = "POST", knownGateway = false) { headers ->
                    client().preparePost(config.buildEndpoint("chat/completions")) {
                        config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                        headers.filterKeys { !it.equals("X-Gateway-Allow-Local-Tools", ignoreCase = true) }.forEach { (key, value) -> header(key, value) }
                        header("X-Gateway-Allow-Local-Tools", "false")
                        header(HttpHeaders.Accept, "text/event-stream, application/json")
                        parameter("autoload", "false")
                        contentType(ContentType.Application.Json)
                        timeout {
                            connectTimeoutMillis = 3_000
                            requestTimeoutMillis = 15_000
                            socketTimeoutMillis = 10_000
                        }
                        setBody(
                            buildJsonObject {
                                put("model", model)
                                put("stream", true)
                                put("max_tokens", 1)
                                put("tool_choice", "none")
                                put(
                                    "messages",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("role", "user")
                                                put("content", "Reply OK.")
                                            }
                                        )
                                    )
                                )
                            }.toString()
                        )
                    }.execute { response ->
                        GatewayClientDiagnostics.observe(config, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                        val evidence = LlamaInferenceProbeEvidence(model)
                        val streaming = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').trim().equals("text/event-stream", true)
                        if (streaming) {
                            val channel = response.bodyAsChannel()
                            var bytes = 0
                            try {
                                while (true) {
                                    val line = channel.readUTF8Line(16 * 1024) ?: break
                                    bytes += line.toByteArray(Charsets.UTF_8).size
                                    check(bytes <= 64 * 1024) { "INFERENCE_PROBE_RESPONSE_TOO_LARGE" }
                                    val payload = dev.chungjungsoo.gptmobile.data.network.SseUtils.extractSseData(line) ?: continue
                                    if (dev.chungjungsoo.gptmobile.data.network.SseUtils.isDone(payload)) {
                                        evidence.completed = true
                                        break
                                    }
                                    evidence.observe(payload)
                                    if (evidence.hasPayload && firstTokenMs == null) firstTokenMs = (nowMs() - started).coerceAtLeast(0)
                                    if (evidence.completed || evidence.invalid) break
                                }
                            } finally {
                                channel.cancel(null)
                            }
                        } else {
                            // Some direct endpoints ignore stream=true. Their full response proves
                            // generation, but it cannot establish time to the first token.
                            evidence.observe(response.boundedMetadata(64 * 1024))
                            evidence.completed = true
                        }
                        val ready = response.status.value in 200..299 && evidence.ready
                        metadata.copy(
                            inferenceHttpStatus = response.status.value,
                            inferenceTested = true,
                            inferenceReady = ready,
                            inferenceFailure = if (ready) null else "Inference returned no complete verified generated output (HTTP ${response.status.value}).",
                            inferenceLatencyMs = (nowMs() - started).coerceAtLeast(0),
                            timeToFirstTokenMs = firstTokenMs
                        )
                    }
                }
            }
            result ?: metadata.copy(inferenceTested = true, inferenceReady = false, inferenceFailure = "INFERENCE_PROBE_TIMEOUT", inferenceLatencyMs = (nowMs() - started).coerceAtLeast(0), timeToFirstTokenMs = firstTokenMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            metadata.copy(inferenceTested = true, inferenceReady = false, inferenceFailure = LocalServiceHealth.classify(failure).name, inferenceLatencyMs = (nowMs() - started).coerceAtLeast(0), timeToFirstTokenMs = firstTokenMs)
        }
    }

    private data class Probe(val status: Int? = null, val body: JsonObject? = null, val failure: String? = null, val cached: Boolean = false, val rejection: LlamaMetadataFailure? = null)

    private suspend fun readMetadata(endpoint: String, config: ProviderRequestConfig, model: String, surface: GatewaySurfacePolicy.Surface): Probe {
        GatewaySurfacePolicy.shared.rememberedStatus(GatewaySurfacePolicy.identity(config), surface)?.let {
            val rejection = llamaMetadataFailure(it, null)
            return Probe(status = it, failure = rejection.code, cached = true, rejection = rejection)
        }
        val result = read(endpoint, config, model)
        val code = ((result.body?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
        result.status?.let { GatewaySurfacePolicy.shared.observe(GatewaySurfacePolicy.identity(config), surface, it, code) }
        return result
    }

    private suspend fun read(endpoint: String, config: ProviderRequestConfig, model: String, timeoutMillis: Long = 1_500): Probe = try {
        withTimeoutOrNull(timeoutMillis) {
            val diagnostic = GatewayClientDiagnostics.withHeaders(config.copy(providerType = config.providerType ?: "LLAMA"), model)
            LocalEndpointConnectionPolicy.probe(endpoint, evictIdleConnections) {
                gatewayClientExchange(diagnostic, if (endpoint.substringBefore('?').endsWith("/gateway/ready")) "readiness" else "capabilities", safeRead = true) { headers ->
                    val response = readClient().get(endpoint) {
                        config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                        headers.forEach { (key, value) -> header(key, value) }
                        parameter("model", model)
                        parameter("autoload", "false")
                        if (LocalEndpointConnectionPolicy.freshConnection(endpoint)) header(HttpHeaders.Connection, "close")
                    }
                    GatewayClientDiagnostics.observe(config, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                    val status = response.status.value
                    val body = try {
                        response.boundedMetadata(64 * 1024)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // A rejected response remains HTTP evidence even if its optional body is unreadable.
                        if (status in 200..299) throw failure
                        null
                    }
                    val document = body?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                    val rejection = if (status !in 200..299) llamaMetadataFailure(status, document, headers["X-Request-ID"]) else null
                    Probe(status, document, rejection?.code ?: if (document == null) "METADATA_INVALID_JSON" else null, rejection = rejection)
                }
            }
        } ?: Probe(failure = "PROBE_TIMEOUT")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Probe(failure = LocalServiceHealth.classify(failure).name)
    }
}
