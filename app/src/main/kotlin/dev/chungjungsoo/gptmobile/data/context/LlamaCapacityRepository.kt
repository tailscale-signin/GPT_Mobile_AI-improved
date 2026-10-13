package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.net.URI
import kotlinx.coroutines.CancellationException
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

/** Metadata stays on the configured route, with autoload disabled. No slot contents are requested. */
internal class LlamaCapacityRepository(private val client: NetworkClient, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Metadata(val capacity: CapacitySnapshot, val template: String, val buildKnown: Boolean)
    private val cache = RouteCapabilityCache<Metadata>(now)
    private val reasoningCache = RouteCapabilityCache<ReasoningTemplateControl>(now)

    suspend fun discover(config: ProviderRequestConfig, model: String): CapacitySnapshot? = metadata(config, model)?.capacity

    private suspend fun metadata(config: ProviderRequestConfig, model: String): Metadata? {
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
                    val body = response.body<String>()
                    val capacity = parseLlamaCapacity(body, config, model, now()) ?: return@withTimeoutOrNull null
                    val root = Json.parseToJsonElement(body) as JsonObject
                    Metadata(capacity, (root["chat_template"] as JsonPrimitive).content, (root["build_info"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun reasoningControl(config: ProviderRequestConfig, model: String, capacity: CapacitySnapshot): ReasoningTemplateControl? {
        val metadata = metadata(config, model) ?: return null
        if (metadata.capacity.fingerprint != capacity.fingerprint || !metadata.buildKnown) return null
        return reasoningCache.getOrLoad(researchHash(capacity.fingerprint.toString())) {
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

    private suspend fun applyTemplate(config: ProviderRequestConfig, model: String, control: ReasoningTemplateControl, enabled: Boolean): String? {
        val endpoint = config.apiUrl.trim().trimEnd('/').removeSuffix("/v1") + "/apply-template"
        val response = client().post(endpoint) {
            config.token?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
            parameter("model", model)
            parameter("autoload", "false")
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
        }
        if (response.status.value != 200) return null
        val body = response.body<String>()
        if (body.length > 1_000_000) return null
        return ((Json.parseToJsonElement(body) as? JsonObject)?.get("prompt") as? JsonPrimitive)?.contentOrNull
    }

    fun invalidate() {
        cache.invalidate()
        reasoningCache.invalidate()
    }
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
        RuntimeFingerprint(config.profileUid ?: "unbound", "sha256:${researchHash(config.apiUrl.trim().trimEnd('/'))}", researchHash(config.token.orEmpty()), model, resolved ?: model, revision, researchHash(template), "n_ctx=$allocation;slots=${root["total_slots"]}"),
        allocation,
        provenance = CapacityProvenance.VERIFIED_ROUTE,
        observedAtMillis = now
    )
}
