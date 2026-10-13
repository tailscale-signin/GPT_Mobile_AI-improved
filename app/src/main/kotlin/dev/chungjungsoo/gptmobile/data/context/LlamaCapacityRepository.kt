package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Metadata stays on the configured route, with autoload disabled. No slot contents are requested. */
internal class LlamaCapacityRepository(private val client: NetworkClient, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val cache = RouteCapabilityCache<CapacitySnapshot>(now)

    suspend fun discover(config: ProviderRequestConfig, model: String): CapacitySnapshot? {
        val route = config.apiUrl.trim().trimEnd('/')
        val identity = researchHash("${config.profileUid}|$route|${researchHash(config.token.orEmpty())}|$model")
        return cache.getOrLoad(identity) {
            try {
                withTimeoutOrNull(5000L) {
                    val uri = URI(route)
                    require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null)
                    val endpoint = route.removeSuffix("/v1") + "/props"
                    val response = client().get(endpoint) {
                        config.token?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
                        parameter("model", model)
                        parameter("autoload", "false")
                    }
                    if (response.status.value != 200) return@withTimeoutOrNull null
                    parseLlamaCapacity(response.body(), config, model, now())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    fun invalidate() = cache.invalidate()
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
    val template = (root["chat_template"] as? JsonPrimitive)?.contentOrNull ?: return null
    val revision = researchHash(listOf(root["model_path"], root["build_info"], root["runtime_epoch"]).joinToString("|"))
    return CapacitySnapshot(
        RuntimeFingerprint(config.profileUid ?: "unbound", config.apiUrl.trim().trimEnd('/'), researchHash(config.token.orEmpty()), model, resolved ?: model, revision, researchHash(template), "n_ctx=$allocation;slots=${root["total_slots"]}"),
        allocation,
        provenance = CapacityProvenance.VERIFIED_ROUTE,
        observedAtMillis = now
    )
}
