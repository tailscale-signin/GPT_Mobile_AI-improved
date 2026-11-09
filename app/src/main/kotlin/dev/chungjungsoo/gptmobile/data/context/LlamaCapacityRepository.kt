package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.llama.LlamaMetadataFailure
import dev.chungjungsoo.gptmobile.data.llama.llamaMetadataFailure
import dev.chungjungsoo.gptmobile.data.network.LocalEndpointConnectionPolicy
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewaySurfacePolicy
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayClientExchange
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Metadata stays on the configured route, with autoload disabled. No slot contents are requested. */
internal class LlamaCapacityRepository(
    private val client: () -> io.ktor.client.HttpClient,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val evictIdleConnections: suspend () -> Unit = {},
    private val readClient: () -> io.ktor.client.HttpClient = client
) {
    constructor(client: NetworkClient, now: () -> Long = { System.nanoTime() / 1_000_000 }) : this({ client() }, now, client::evictIdleConnections, client::gatewayReadClient)
    private data class Metadata(val capacity: CapacitySnapshot, val template: String, val buildKnown: Boolean, val reportedModel: String?)
    private val cache = RouteCapabilityCache<Metadata>(now)
    private val metadataFailures = linkedMapOf<String, LlamaMetadataFailure>()
    private val reasoningCache = RouteCapabilityCache<ReasoningTemplateControl>(now)
    private val schemaCache = RouteCapabilityCache<Boolean>(now, negativeTtl = 60_000L)
    private val gatewayCache = RouteCapabilityCache<GatewayModelCapabilityObservation>(now, positiveTtl = 15_000L, valueTtl = {
        if (it.capacity != null) (20_000L - (it.observationAgeMillis ?: 0)).coerceIn(1L, 15_000L) else 15_000L
    })

    private fun routeRevision(config: ProviderRequestConfig, model: String): String {
        // Registering a new profile can evict an old identity and advance the
        // capability epoch. Complete that memory-only step before caching a read.
        GatewayClientDiagnostics.observeInstance(config, null)
        return "${config.profileUid}|${GatewayClientDiagnostics.routeIdentity(config)}|$model|${LocalServiceHealth.networkEpoch}|${GatewayClientDiagnostics.capabilityEpoch(config)}"
    }

    suspend fun discover(config: ProviderRequestConfig, model: String): CapacitySnapshot? =
        if (GatewayClientDiagnostics.runtimeEpoch(config) != "unobserved") {
            // A known gateway owns its metadata contract. A failed qualification
            // must not fall through to /props, which it may deliberately forbid.
            discoverGateway(config, model)
        } else {
            metadata(config, model)?.capacity ?: discoverGateway(config, model)
        }

    /** Only an already observed gateway is asked to confirm its exact selected serving model. */
    suspend fun discoverGateway(config: ProviderRequestConfig, model: String): CapacitySnapshot? = gatewayMetadata(config, model)?.capacity

    suspend fun gatewayObservation(config: ProviderRequestConfig, model: String): GatewayModelCapabilityObservation? = gatewayMetadata(config, model)

    private suspend fun gatewayMetadata(config: ProviderRequestConfig, model: String): GatewayModelCapabilityObservation? {
        if (GatewayClientDiagnostics.runtimeEpoch(config) == "unobserved" || model.isBlank() || model.length > 512) return null
        if (GatewaySurfacePolicy.shared.rememberedStatus(GatewaySurfacePolicy.identity(config), GatewaySurfacePolicy.Surface.CAPABILITIES) != null) return null
        return gatewayCache.getOrLoad(researchHash(routeRevision(config, model))) {
            try {
                withTimeoutOrNull(6_000L) {
                    val endpoint = config.buildEndpoint("gateway/capabilities")
                    LocalEndpointConnectionPolicy.probe(endpoint, evictIdleConnections) {
                        gatewayClientExchange(config, "capabilities", safeRead = true) { requestHeaders ->
                            readClient().prepareGet(endpoint) {
                                config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                                requestHeaders.forEach { (name, value) -> header(name, value) }
                                if (LocalEndpointConnectionPolicy.freshConnection(endpoint)) header(HttpHeaders.Connection, "close")
                                parameter("model", model)
                                parameter("autoload", "false")
                                timeout {
                                    connectTimeoutMillis = 2_000
                                    requestTimeoutMillis = 5_000
                                    socketTimeoutMillis = 5_000
                                }
                            }.execute { response ->
                                GatewayClientDiagnostics.observe(config, response.status.value) { response.headers[it] }
                                GatewaySurfacePolicy.shared.observe(GatewaySurfacePolicy.identity(config), GatewaySurfacePolicy.Surface.CAPABILITIES, response.status.value)
                                if (response.status.value != 200) {
                                    return@execute GatewayModelCapabilityObservation(httpStatus = response.status.value, failureCode = "GATEWAY_CAPABILITY_HTTP_REJECTED", retryable = response.status.value in setOf(408, 429, 502, 503, 504))
                                }
                                val body = response.boundedMetadata(256 * 1024)
                                val observation = parseGatewayModelObservation(body, config, model, now())
                                val responseInstance = response.headers["X-Gateway-Instance-ID"]
                                if (observation.instanceId != null && responseInstance != null && observation.instanceId != responseInstance) {
                                    return@execute GatewayModelCapabilityObservation(httpStatus = 200, failureCode = "GATEWAY_CAPABILITY_IDENTITY_MISMATCH", retryable = false)
                                }
                                GatewayClientDiagnostics.observeInstance(config, observation.instanceId)
                                observation
                            }
                        }
                    }
                } ?: GatewayModelCapabilityObservation(failureCode = "GATEWAY_CAPABILITY_TIMEOUT", retryable = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val health = LocalServiceHealth.classify(failure)
                GatewayModelCapabilityObservation(failureCode = "GATEWAY_CAPABILITY_TRANSPORT_FAILURE", failureCategory = health.name, retryable = health in setOf(dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.DNS_FAILED, dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.ROUTE_UNAVAILABLE, dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.CONNECTION_REFUSED, dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.CONNECT_TIMEOUT, dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.STREAM_INTERRUPTED, dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.STREAM_IDLE_TIMEOUT, dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.REQUEST_TIMEOUT))
            }
        }?.fresh(now())
    }

    private suspend fun metadata(config: ProviderRequestConfig, model: String): Metadata? {
        if (GatewaySurfacePolicy.shared.rememberedStatus(GatewaySurfacePolicy.identity(config), GatewaySurfacePolicy.Surface.PROPS) != null) return null
        val route = config.apiUrl.trim().trimEnd('/')
        val identity = researchHash(routeRevision(config, model))
        return cache.getOrLoad(identity) {
            synchronized(metadataFailures) { metadataFailures.remove(identity) }
            try {
                withTimeoutOrNull(5000L) {
                    val uri = URI(route)
                    require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null)
                    val endpoint = route.removeSuffix("/v1") + "/props"
                    val diagnostic = GatewayClientDiagnostics.withHeaders(config.copy(providerType = config.providerType ?: "LLAMA"), model)
                    val (response, body, requestId) = LocalEndpointConnectionPolicy.probe(config.apiUrl, evictIdleConnections) {
                        gatewayClientExchange(diagnostic, "capabilities", safeRead = true) { headers ->
                            val response = readClient().get(endpoint) {
                                config.token?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
                                headers.forEach { (name, value) -> header(name, value) }
                                parameter("model", model)
                                parameter("autoload", "false")
                                if (LocalEndpointConnectionPolicy.freshConnection(config.apiUrl)) header(HttpHeaders.Connection, "close")
                            }
                            GatewayClientDiagnostics.observe(config, response.status.value) { response.headers[it] }
                            val body = try {
                                response.boundedMetadata(if (response.status.value == 200) 1_000_000 else 16 * 1024)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                if (response.status.value == 200) throw failure
                                ""
                            }
                            Triple(response, body, headers["X-Request-ID"])
                        }
                    }
                    val document = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                    val error = document?.get("error") as? JsonObject
                    GatewaySurfacePolicy.shared.observe(GatewaySurfacePolicy.identity(config), GatewaySurfacePolicy.Surface.PROPS, response.status.value, (error?.get("code") as? JsonPrimitive)?.contentOrNull)
                    if (response.status.value != 200) {
                        val failure = llamaMetadataFailure(response.status.value, document, requestId)
                        synchronized(metadataFailures) {
                            if (metadataFailures.size >= 128 && identity !in metadataFailures) metadataFailures.remove(metadataFailures.keys.first())
                            metadataFailures[identity] = failure
                        }
                        AppLogRecorder.recordEvent("LocalConnection", "LLAMA_METADATA_REJECTED", failure.diagnosticFields() + ("route" to identity), "W")
                        return@withTimeoutOrNull null
                    }
                    val capacity = parseLlamaCapacity(body, config, model, now()) ?: return@withTimeoutOrNull null
                    val root = Json.parseToJsonElement(body) as JsonObject
                    val reportedModel = (root["model_alias"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?: (root["model"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    Metadata(capacity, (root["chat_template"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (root["build_info"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true, reportedModel)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    fun metadataFailure(config: ProviderRequestConfig, model: String): LlamaMetadataFailure? =
        synchronized(metadataFailures) { metadataFailures[researchHash(routeRevision(config, model))] }

    /** Route allocation may omit model identity; a capability action requires the explicit serving-model proof. */
    suspend fun hasExactModelProof(config: ProviderRequestConfig, model: String, capacity: CapacitySnapshot): Boolean {
        if (capacity.provenance != CapacityProvenance.VERIFIED_ROUTE) return false
        return if (capacity.fingerprint.templateRevision.startsWith("gateway:")) {
            gatewayMetadata(config, model)?.capacity?.let { it.fingerprint == capacity.fingerprint && it.fingerprint.resolvedModel == model } == true
        } else {
            metadata(config, model)?.let { it.capacity.fingerprint == capacity.fingerprint && it.reportedModel == model } == true
        }
    }

    suspend fun reasoningControl(config: ProviderRequestConfig, model: String, capacity: CapacitySnapshot): ReasoningTemplateControl? {
        if (capacity.fingerprint.templateRevision.startsWith("gateway:")) {
            return gatewayMetadata(config, model)?.takeIf { it.capacity?.fingerprint == capacity.fingerprint }?.reasoningControl
        }
        val metadata = metadata(config, model) ?: return null
        if (metadata.capacity.fingerprint != capacity.fingerprint || !metadata.buildKnown) return null
        return reasoningCache.getOrLoad(researchHash("${routeRevision(config, model)}|${capacity.fingerprint}")) {
            try {
                withTimeoutOrNull(5000L) {
                    // Probe a synthetic prompt only, on the configured route, without loading a model.
                    // Merely mentioning a variable in a template is not evidence that it works.
                    val candidates = ReasoningTemplateControl.entries.filter { control ->
                        Regex("\\b${control.parameter}\\b").containsMatchIn(metadata.template)
                    }
                    for (control in candidates) {
                        val on = applyTemplate(config, model, control, true) ?: continue
                        val off = applyTemplate(config, model, control, false) ?: continue
                        if (ReasoningPolicy.verifiesThinkingToggle(on, off)) return@withTimeoutOrNull control
                    }
                    null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun supportsReviewSchema(config: ProviderRequestConfig, model: String, capacity: CapacitySnapshot? = null): Boolean =
        schemaCache.getOrLoad(researchHash("${routeRevision(config, model)}|${capacity?.fingerprint}|${dev.chungjungsoo.gptmobile.data.github.GitHubReviewSchema.VERSION}")) {
            try {
                withTimeoutOrNull(10_000L) {
                    val control = capacity?.let { reasoningControl(config, model, it) }
                    val response = client().post(config.apiUrl.trim().trimEnd('/').removeSuffix("/v1") + "/v1/chat/completions") {
                        config.token?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
                        // This is an independent synthetic inference, never a continuation
                        // of the user's retained gateway job or idempotency identity.
                        val headers = GatewayClientDiagnostics.requestHeaders(config)
                        val identities = setOf("x-gateway-job-id", "x-gateway-request-id", "x-gateway-attempt-id", "x-request-id", "idempotency-key", "x-idempotency-key")
                        headers.filterKeys { it.lowercase() !in identities }.forEach { (name, value) -> header(name, value) }
                        if (headers.keys.any { it.equals("X-Gateway-Attempt-ID", true) }) header("X-Gateway-Attempt-ID", java.util.UUID.randomUUID().toString())
                        parameter("autoload", "false")
                        contentType(ContentType.Application.Json)
                        if (LocalEndpointConnectionPolicy.freshConnection(config.apiUrl)) header(HttpHeaders.Connection, "close")
                        setBody(
                            buildJsonObject {
                                put("model", model)
                                put("stream", false)
                                put("max_tokens", 256)
                                put("tool_choice", "none")
                                put(
                                    "messages",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("role", "user")
                                                put("content", "Return only {\"ok\":false}.")
                                            }
                                        )
                                    )
                                )
                                put("response_format", dev.chungjungsoo.gptmobile.data.github.GitHubReviewSchema.probeFormat)
                                control?.let { put("chat_template_kwargs", buildJsonObject { put(it.parameter, false) }) }
                            }.toString()
                        )
                    }
                    GatewayClientDiagnostics.observe(config, response.status.value) { response.headers[it] }
                    val body = response.boundedMetadata(64 * 1024)
                    if (response.status.value != 200) return@withTimeoutOrNull null
                    val root = Json.parseToJsonElement(body) as? JsonObject ?: return@withTimeoutOrNull null
                    val first = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                    val message = first?.get("message") as? JsonObject
                    val text = (message?.get("content") as? JsonPrimitive)?.contentOrNull ?: return@withTimeoutOrNull null
                    // The prompt asks for false; only an enforced schema permits true.
                    if (Json.parseToJsonElement(text.trim()) == buildJsonObject { put("ok", true) }) true else null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } == true

    private suspend fun applyTemplate(config: ProviderRequestConfig, model: String, control: ReasoningTemplateControl, enabled: Boolean): String? {
        val endpoint = config.apiUrl.trim().trimEnd('/').removeSuffix("/v1") + "/apply-template"
        val response = LocalEndpointConnectionPolicy.probe(config.apiUrl, evictIdleConnections) {
            client().post(endpoint) {
                config.token?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
                GatewayClientDiagnostics.requestHeaders(config).forEach { (name, value) -> header(name, value) }
                parameter("model", model)
                parameter("autoload", "false")
                if (LocalEndpointConnectionPolicy.freshConnection(config.apiUrl)) header(HttpHeaders.Connection, "close")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("model", model)
                        put(
                            "messages",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("role", "user")
                                        put("content", "Template capability check.")
                                    }
                                )
                            )
                        )
                        put("chat_template_kwargs", buildJsonObject { put(control.parameter, enabled) })
                        put("add_generation_prompt", true)
                    }.toString()
                )
            }.also {
                GatewayClientDiagnostics.observe(config, it.status.value) { name -> it.headers[name] }
                it.body<String>()
            }
        }
        if (response.status.value != 200) return null
        val body = response.body<String>()
        if (body.length > 1_000_000) return null
        return ((Json.parseToJsonElement(body) as? JsonObject)?.get("prompt") as? JsonPrimitive)?.contentOrNull
    }

    fun invalidate() {
        cache.invalidate()
        reasoningCache.invalidate()
        schemaCache.invalidate()
        gatewayCache.invalidate()
        synchronized(metadataFailures) { metadataFailures.clear() }
    }
}

/** A gateway may relay measured template proof without exposing its administrative /props surface. */
internal fun parseGatewayReasoningControl(body: String, capacity: CapacitySnapshot): ReasoningTemplateControl? {
    if (body.length > 256 * 1024 || capacity.provenance != CapacityProvenance.VERIFIED_ROUTE) return null
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val selected = root["selectedModel"] as? JsonObject ?: return null
    val proof = selected["reasoningControl"] as? JsonObject ?: return null
    fun text(value: JsonObject, key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    fun number(key: String) = (proof[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    val revision = text(selected, "capabilityRevision") ?: return null
    val instance = text(root, "instanceId") ?: return null
    if (capacity.fingerprint.runtimeRevision != "$instance:$revision" ||
        text(selected, "requestedModel") != capacity.fingerprint.requestedModel ||
        text(selected, "reportedModel") != capacity.fingerprint.resolvedModel ||
        number("schemaVersion") != 1L ||
        text(proof, "capabilityRevision") != revision ||
        text(proof, "evidenceOrigin") != "GATEWAY_OBSERVED_TEMPLATE_PROBE" ||
        (proof["templateProbeVerified"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != true ||
        number("observedAtMillis")?.takeIf { it > 0 } == null
    ) {
        return null
    }
    val digests = listOf("templateDigest", "enabledPromptDigest", "disabledPromptDigest").map { text(proof, it) }
    if (digests.any { it == null || !it.matches(Regex("[a-f0-9]{64}")) } || digests[1] == digests[2]) return null
    return ReasoningTemplateControl.entries.singleOrNull { it.parameter == text(proof, "parameter") }
}

/** n_ctx is the generation allocation in /props; never divide it by total_slots. */
internal fun parseLlamaCapacity(body: String, config: ProviderRequestConfig, model: String, now: Long): CapacitySnapshot? {
    if (body.length > 1_000_000) return null
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    if ((root["is_sleeping"] as? JsonPrimitive)?.booleanOrNull == true) return null
    val resolved = (root["model_alias"] as? JsonPrimitive)?.contentOrNull ?: (root["model"] as? JsonPrimitive)?.contentOrNull
    if (resolved != null && resolved != model) return null
    val generation = root["default_generation_settings"] as? JsonObject ?: return null
    val allocation = (generation["n_ctx"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 256L..1_048_576L } ?: return null
    val template = (root["chat_template"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (template.isBlank() && resolved == null) return null
    val revision = researchHash(listOf(root["model_path"], root["build_info"], root["runtime_epoch"]).joinToString("|"))
    return CapacitySnapshot(
        RuntimeFingerprint(config.profileUid ?: "unbound", "sha256:${researchHash(config.apiUrl.trim().trimEnd('/'))}", researchHash(config.token.orEmpty()), model, resolved ?: model, revision, researchHash(template), "n_ctx=$allocation;slots=${root["total_slots"]}"),
        allocation,
        outputTokens = (root["max_output_tokens"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }?.coerceAtMost(allocation),
        provenance = CapacityProvenance.VERIFIED_ROUTE,
        observedAtMillis = now
    )
}

/** Catalog limits and a different model's allocation never become serving evidence. */
internal fun parseGatewayCapacity(body: String, config: ProviderRequestConfig, model: String, now: Long): CapacitySnapshot? {
    if (body.length > 256 * 1024) return null
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    if ((root["contractVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull != 1L) return null
    val instance = (root["instanceId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: return null
    val selected = root["selectedModel"] as? JsonObject ?: return null
    fun text(key: String) = (selected[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    fun number(key: String) = (selected[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    if (text("schema") != "gptmobile.gateway.selected-model.v1" ||
        number("schemaVersion") != 1L ||
        text("requestedModel") != model ||
        text("reportedModel") != model ||
        (selected["limitsVerified"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != true ||
        text("evidenceOrigin") != "GATEWAY_OBSERVED_PROVIDER_METADATA"
    ) {
        return null
    }
    val context = number("contextTokens")?.takeIf { it in 256L..2_097_152L } ?: return null
    val revision = text("capabilityRevision")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
    if (number("observedAtMillis")?.takeIf { it > 0 } == null) return null
    val output = when (text("outputLimitSource")) {
        "provider_reported" -> number("maxOutputTokens")?.takeIf { it in 1..context } ?: return null
        "context_capacity" -> if (selected["maxOutputTokens"] == null || selected["maxOutputTokens"] == JsonNull) null else return null
        else -> return null
    }
    return CapacitySnapshot(
        RuntimeFingerprint(config.profileUid ?: "unbound", "sha256:${researchHash(config.apiUrl.trim().trimEnd('/'))}", GatewayClientDiagnostics.routeIdentity(config), model, model, "$instance:$revision", "gateway:$revision", "context=$context;output=${output ?: "unreported"}"),
        context,
        outputTokens = output,
        provenance = CapacityProvenance.VERIFIED_ROUTE,
        observedAtMillis = now
    )
}
