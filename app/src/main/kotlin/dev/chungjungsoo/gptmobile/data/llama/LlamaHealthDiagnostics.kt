package dev.chungjungsoo.gptmobile.data.llama

import dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot
import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.network.LocalEndpointConnectionPolicy
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    val activeRequestId: String? = null
) {
    val phase: LlamaConnectionPhase get() = when {
        activeGenerations > 0 -> LlamaConnectionPhase.GENERATING
        inferenceReady == true -> LlamaConnectionPhase.INFERENCE_READY
        inferenceTested && inferenceReady == false -> LlamaConnectionPhase.DEGRADED
        selectedModelListed == true && healthHttpStatus == 200 && serverStatus in setOf("ok", "ready") -> LlamaConnectionPhase.MODEL_LOADED
        listOfNotNull(healthHttpStatus, modelsHttpStatus, propsHttpStatus).isEmpty() -> LlamaConnectionPhase.DISCONNECTED
        healthFailure != null || propsFailure != null || modelsFailure != null -> LlamaConnectionPhase.DEGRADED
        else -> LlamaConnectionPhase.REACHABLE
    }

    fun summary(): String = "healthHttp=${healthHttpStatus ?: "unknown"}; serverStatus=${serverStatus ?: "unknown"}; " +
        "modelsHttp=${modelsHttpStatus ?: "unknown"}; selectedModelListed=${selectedModelListed ?: "unknown"}; " +
        "propsHttp=${propsHttpStatus ?: "unknown"}; servingContext=${contextTokens ?: "unknown"}; templateRevision=${templateRevision ?: "unknown"}; " +
        "phase=$phase; serverGeneration=${serverGeneration ?: "Not reported"}; modelIdentity=${modelIdentity ?: "Not reported"}; " +
        "healthFailure=${healthFailure ?: "none"}; propsFailure=${propsFailure ?: "none"}; modelsFailure=${modelsFailure ?: "none"}; " +
        "activeGenerations=$activeGenerations; requestId=${activeRequestId ?: "Not reported"}; inferenceTested=$inferenceTested; inferenceReady=${inferenceReady ?: "Not measured"}; inferenceFailure=${inferenceFailure ?: "none"}"
}

/** Read-only checks stay on the configured authenticated route and never request model autoload. */
internal class LlamaHealthDiagnostics(
    private val client: () -> HttpClient,
    now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    constructor(client: NetworkClient) : this({ client() })
    private val cache = RouteCapabilityCache<LlamaHealthReport>(now, positiveTtl = 30_000)

    suspend fun inspect(config: ProviderRequestConfig, model: String, capacity: CapacitySnapshot? = null): LlamaHealthReport {
        val route = config.apiUrl.trim().trimEnd('/')
        val uri = runCatching { URI(route) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host == null || uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null) return LlamaHealthReport()
        val key = researchHash("$route|${researchHash(config.token.orEmpty())}|$model|${LocalServiceHealth.networkEpoch}")
        val report = cache.getOrLoad(key) {
            coroutineScope {
                val health = async { read(route.removeSuffix("/v1") + "/health", config, model) }
                val models = async { read(config.buildEndpoint("models"), config, model) }
                val props = async { read(route.removeSuffix("/v1") + "/props", config, model) }
                val healthResult = health.await()
                val modelResult = models.await()
                val propsResult = props.await()
                val status = (healthResult.body?.get("status") as? JsonPrimitive)?.contentOrNull
                    ?.lowercase()?.takeIf { it in setOf("ok", "loading model", "no slot available", "error", "ready", "loading") }
                val data = modelResult.body?.get("data") as? JsonArray
                val ids = data?.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
                LlamaHealthReport(
                    healthHttpStatus = healthResult.status,
                    serverStatus = status,
                    modelsHttpStatus = modelResult.status,
                    selectedModelListed = ids?.takeIf { modelResult.status == 200 && it.size == data?.size }?.contains(model),
                    propsHttpStatus = propsResult.status,
                    contextTokens = ((propsResult.body?.get("default_generation_settings") as? JsonObject)?.get("n_ctx") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
                    serverGeneration = listOf("server_generation", "process_start_time", "started_at").firstNotNullOfOrNull { key ->
                        (propsResult.body?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let { researchHash(it).take(16) }
                    },
                    modelIdentity = ids?.takeIf { model in it }?.let { researchHash(model).take(16) },
                    healthFailure = healthResult.failure,
                    modelsFailure = modelResult.failure,
                    propsFailure = propsResult.failure
                )
            }
        } ?: LlamaHealthReport()
        val active = LlamaGenerationActivity.snapshot(config, model)
        return report.copy(contextTokens = capacity?.contextTokens ?: report.contextTokens, templateRevision = capacity?.fingerprint?.templateRevision, activeGenerations = active.count, activeRequestId = active.requestId)
    }

    /** Explicit diagnostic action only. The fixed prompt contains no user data or tool definitions. */
    suspend fun testInference(config: ProviderRequestConfig, model: String): LlamaHealthReport {
        cache.invalidate()
        val metadata = inspect(config, model)
        if (metadata.phase != LlamaConnectionPhase.MODEL_LOADED) {
            return metadata.copy(inferenceFailure = "Confirm healthy server and selected loaded model before testing inference.")
        }
        return try {
            val result = withTimeoutOrNull(15_000L) {
                val response = client().post(config.buildEndpoint("chat/completions")) {
                    config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                    config.extraHeaders.filterKeys { !it.equals("X-Gateway-Allow-Local-Tools", ignoreCase = true) }.forEach { (key, value) -> header(key, value) }
                    header("X-Gateway-Allow-Local-Tools", "false")
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
                            put("stream", false)
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
                }
                val rawBody = response.boundedMetadata(64 * 1024)
                val body = runCatching { Json.parseToJsonElement(rawBody) as? JsonObject }.getOrNull()
                val choices = body?.get("choices") as? JsonArray
                val generated = ((body?.get("usage") as? JsonObject)?.get("completion_tokens") as? JsonPrimitive)?.longOrNull
                val payload = choices.orEmpty().any { choice ->
                    val message = (choice as? JsonObject)?.get("message") as? JsonObject
                    listOf("content", "reasoning_content", "reasoning").any { field ->
                        (message?.get(field) as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
                    }
                }
                val observedModel = (body?.get("model") as? JsonPrimitive)?.contentOrNull
                val ready = response.status.value in 200..299 && body?.get("error") == null && (observedModel == null || observedModel == model) && (payload || (generated ?: 0) > 0)
                metadata.copy(inferenceHttpStatus = response.status.value, inferenceTested = true, inferenceReady = ready, inferenceFailure = if (ready) null else "Inference returned no verified generated output (HTTP ${response.status.value}).")
            }
            result ?: metadata.copy(inferenceTested = true, inferenceReady = false, inferenceFailure = "INFERENCE_PROBE_TIMEOUT")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            metadata.copy(inferenceTested = true, inferenceReady = false, inferenceFailure = LocalServiceHealth.classify(failure).name)
        }
    }

    private data class Probe(val status: Int? = null, val body: JsonObject? = null, val failure: String? = null)

    private suspend fun read(endpoint: String, config: ProviderRequestConfig, model: String): Probe = try {
        withTimeoutOrNull(1_500) {
            LocalEndpointConnectionPolicy.probe(endpoint) {
                val response = client().get(endpoint) {
                    config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                    config.extraHeaders.forEach { (key, value) -> header(key, value) }
                    parameter("model", model)
                    parameter("autoload", "false")
                    if (LocalEndpointConnectionPolicy.freshConnection(endpoint)) header(HttpHeaders.Connection, "close")
                }
                val body = response.boundedMetadata(64 * 1024)
                Probe(response.status.value, runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull())
            }
        } ?: Probe(failure = "PROBE_TIMEOUT")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Probe(failure = LocalServiceHealth.classify(failure).name)
    }
}
