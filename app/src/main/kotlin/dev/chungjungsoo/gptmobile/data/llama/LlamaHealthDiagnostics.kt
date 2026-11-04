package dev.chungjungsoo.gptmobile.data.llama

import dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot
import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class LlamaHealthReport(
    val healthHttpStatus: Int? = null,
    val serverStatus: String? = null,
    val modelsHttpStatus: Int? = null,
    val selectedModelListed: Boolean? = null,
    val contextTokens: Long? = null,
    val templateRevision: String? = null
) {
    fun summary(): String = "healthHttp=${healthHttpStatus ?: "unknown"}; serverStatus=${serverStatus ?: "unknown"}; " +
        "modelsHttp=${modelsHttpStatus ?: "unknown"}; selectedModelListed=${selectedModelListed ?: "unknown"}; " +
        "servingContext=${contextTokens ?: "unknown"}; templateRevision=${templateRevision ?: "unknown"}; inferenceTested=false"
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
                val healthResult = health.await()
                val modelResult = models.await()
                val status = (healthResult?.second?.get("status") as? JsonPrimitive)?.contentOrNull
                    ?.lowercase()?.takeIf { it in setOf("ok", "loading model", "no slot available", "error", "ready", "loading") }
                val data = modelResult?.second?.get("data") as? JsonArray
                val ids = data?.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
                LlamaHealthReport(
                    healthHttpStatus = healthResult?.first,
                    serverStatus = status,
                    modelsHttpStatus = modelResult?.first,
                    selectedModelListed = ids?.takeIf { modelResult?.first == 200 && it.size == data?.size }?.contains(model)
                )
            }
        } ?: LlamaHealthReport()
        return report.copy(contextTokens = capacity?.contextTokens, templateRevision = capacity?.fingerprint?.templateRevision)
    }

    private suspend fun read(endpoint: String, config: ProviderRequestConfig, model: String): Pair<Int, JsonObject?>? = try {
        withTimeoutOrNull(1_500) {
            val response = client().get(endpoint) {
                config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                parameter("model", model)
                parameter("autoload", "false")
            }
            val body = response.boundedMetadata(64 * 1024)
            response.status.value to (runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
