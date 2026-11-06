package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal interface GatewayDiagnosticTransport {
    suspend fun post(batch: JsonObject): JsonObject
    suspend fun read(session: String, after: Long, excludeOrigin: String): JsonObject
}

internal class DiagnosticSyncFailure(val code: String) : IOException(code)

/** One bounded page per turn. Failed/uncertain uploads are replayed with exactly the same identities. */
internal class GatewayDiagnosticSync(
    private val spool: GatewayDiagnosticSpool,
    private val transport: GatewayDiagnosticTransport,
    private val allowed: () -> Boolean,
    private val commit: ((() -> Unit) -> Unit) = { it() }
) {
    suspend fun once() {
        if (!allowed()) return
        val batch = spool.batch()
        if (!allowed()) return
        if ((batch["events"] as JsonArray).isNotEmpty() || (batch["gaps"] as JsonArray).isNotEmpty() || spool.status().lastSyncAt == null) {
            val receipt = transport.post(batch)
            // A privacy/clear transition must not recreate deleted capture storage.
            if (!allowed()) return
            commit { if (allowed()) spool.acknowledge(receipt) }
        }
        if (!allowed()) return
        val page = transport.read(spool.sessionId, spool.cursor, spool.originId)
        if (allowed()) commit { if (allowed()) spool.importPage(page) }
    }
}

/** No logger plugin: diagnostic uploads must not recursively generate transport diagnostics. */
internal class GatewayDiagnosticHttpTransport(
    private val http: HttpClient,
    private val config: ProviderRequestConfig,
    private val allowed: () -> Boolean
) : GatewayDiagnosticTransport {
    private fun checkAllowed() {
        if (!allowed()) throw CancellationException("Diagnostic capture paused")
    }

    override suspend fun post(batch: JsonObject): JsonObject {
        checkAllowed()
        val content = batch.toString()
        if (content.toByteArray(Charsets.UTF_8).size > GatewayDiagnosticEvent.MAX_BATCH_BYTES) throw DiagnosticSyncFailure("DIAGNOSTIC_BATCH_LIMIT")
        return http.preparePost(config.buildEndpoint("gateway/debug/events")) {
            checkAllowed()
            config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
            authenticationHeaders().forEach { (key, value) -> header(key, value) }
            header("Content-Type", "application/json")
            timeout {
                connectTimeoutMillis = 5000
                requestTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
            setBody(content)
        }.execute { response ->
            if (response.status.value !in 200..299) throw DiagnosticSyncFailure(responseCode(response.status.value))
            Json.parseToJsonElement(response.boundedMetadata(128 * 1024)) as? JsonObject ?: throw DiagnosticSyncFailure("INVALID_DIAGNOSTIC_ACK")
        }
    }

    override suspend fun read(session: String, after: Long, excludeOrigin: String): JsonObject {
        checkAllowed()
        return http.prepareGet(config.buildEndpoint("gateway/debug/events")) {
            checkAllowed()
            config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
            authenticationHeaders().forEach { (key, value) -> header(key, value) }
            parameter("session_id", session)
            parameter("after", after)
            parameter("limit", 64)
            parameter("exclude_origin", excludeOrigin)
            timeout {
                connectTimeoutMillis = 5000
                requestTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
        }.execute { response ->
            if (response.status.value !in 200..299) throw DiagnosticSyncFailure(responseCode(response.status.value))
            Json.parseToJsonElement(response.boundedMetadata(128 * 1024)) as? JsonObject ?: throw DiagnosticSyncFailure("INVALID_DIAGNOSTIC_PAGE")
        }
    }

    private fun authenticationHeaders() = config.extraHeaders.filterKeys {
        !it.startsWith("X-Gateway-", true) && !it.equals("traceparent", true) && !it.equals("tracestate", true) && !it.equals("baggage", true)
    }

    private fun responseCode(status: Int) = when (status) {
        401 -> "DIAGNOSTIC_AUTHENTICATION_REQUIRED"
        403 -> "DIAGNOSTIC_ACCESS_DENIED"
        404, 405 -> "DIAGNOSTIC_SOURCE_UNAVAILABLE"
        409 -> "DIAGNOSTIC_CAPTURE_BLOCKED"
        429 -> "DIAGNOSTIC_BACKPRESSURE"
        503, 507 -> "DEBUG_STORAGE_UNAVAILABLE"
        else -> "DIAGNOSTIC_TRANSPORT_FAILURE"
    }
}
