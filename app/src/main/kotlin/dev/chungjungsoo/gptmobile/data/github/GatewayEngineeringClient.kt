package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Singleton
class GatewayEngineeringClient internal constructor(private val http: HttpClient) {
    @Inject constructor(network: NetworkClient) : this(network())
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun request(config: ProviderRequestConfig, path: String, body: String? = null, approval: Boolean = false): String = http.prepareRequest(config.buildEndpoint("gateway/engineering/$path")) {
        method = if (body == null) HttpMethod.Get else HttpMethod.Post
        config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
        config.extraHeaders.forEach { (key, value) -> header(key, value) }
        if (approval) header("X-Gateway-User-Action", "approve-and-merge")
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        timeout {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }.execute { response ->
        check(response.status.isSuccess()) {
            when (response.status.value) {
                401, 403 -> "Gateway engineering access is not authorized. Check the gateway connection and engineering scope."
                404 -> "This gateway does not expose the saved engineering job. Check the selected gateway and account."
                409 -> "The job or PR changed. Refresh and review its current commit before confirming."
                503 -> "The gateway needs its host GitHub connection configured, or is at capacity. Your request is retained."
                else -> "Gateway engineering request failed (HTTP ${response.status.value}). The job may still be running; reconnect to check."
            }
        }
        response.boundedMetadata()
    }

    suspend fun principal(config: ProviderRequestConfig): String =
        requireNotNull(json.parseToJsonElement(request(config, "capabilities")).let { it as? kotlinx.serialization.json.JsonObject }?.get("principal_id")?.jsonPrimitive?.contentOrNull) { "Gateway engineering identity is unavailable." }

    suspend fun create(config: ProviderRequestConfig, payload: GatewayEngineeringRequest): GatewayEngineeringJob =
        json.decodeFromString(request(config, "jobs", json.encodeToString(payload)))

    suspend fun get(config: ProviderRequestConfig, id: String, afterRevision: Int): GatewayEngineeringJob {
        require(id.matches(Regex("eng_[a-f0-9]{32}")))
        return json.decodeFromString(request(config, "jobs/$id?after_revision=${afterRevision.coerceAtLeast(-1)}"))
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
