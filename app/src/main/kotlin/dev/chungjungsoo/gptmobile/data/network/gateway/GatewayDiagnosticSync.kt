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
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal interface GatewayDiagnosticTransport {
    suspend fun post(batch: JsonObject): JsonObject
    suspend fun read(session: String, after: Long, excludeOrigin: String): JsonObject
}

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
            commit { if (allowed()) spool.acknowledge(receipt, batch) }
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
            decode(response, "INVALID_DIAGNOSTIC_ACK")
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
            decode(response, "INVALID_DIAGNOSTIC_PAGE")
        }
    }

    suspend fun exportSession(session: String, destination: java.io.File): java.io.File {
        checkAllowed()
        require(GatewayDiagnosticEvent.hex32.matches(session))
        try {
            http.prepareGet(config.buildEndpoint("gateway/debug/export")) {
                checkAllowed()
                config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                authenticationHeaders().forEach { (key, value) -> header(key, value) }
                parameter("session_id", session)
                timeout {
                    connectTimeoutMillis = 5000
                    requestTimeoutMillis = 15_000
                    socketTimeoutMillis = 10_000
                }
            }.execute { response ->
                if (response.status.value != 200) throw DiagnosticSyncFailure(responseCode(response.status.value))
                val channel = response.bodyAsChannel()
                try {
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            checkAllowed()
                            val count = channel.readAvailable(buffer, 0, buffer.size)
                            if (count < 0) break
                            if (count == 0) {
                                kotlinx.coroutines.yield()
                                continue
                            }
                            total += count
                            if (total > 12 * 1024 * 1024) throw DiagnosticSyncFailure("DIAGNOSTIC_RESPONSE_LIMIT")
                            output.write(buffer, 0, count)
                        }
                    }
                } finally {
                    channel.cancel(null)
                }
            }
            checkAllowed()
            return destination
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

    private suspend fun decode(response: io.ktor.client.statement.HttpResponse, invalidCode: String): JsonObject {
        checkAllowed()
        val body = try {
            response.boundedMetadata(128 * 1024)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            throw DiagnosticSyncFailure("DIAGNOSTIC_RESPONSE_LIMIT")
        }
        checkAllowed()
        return try {
            Json.parseToJsonElement(body) as? JsonObject ?: throw DiagnosticSyncFailure(invalidCode)
        } catch (_: SerializationException) {
            throw DiagnosticSyncFailure(invalidCode)
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
        410 -> "DIAGNOSTIC_CAPTURE_EXPIRED"
        429 -> "DIAGNOSTIC_BACKPRESSURE"
        503, 507 -> "DEBUG_STORAGE_UNAVAILABLE"
        408, 500, 502, 504 -> "DIAGNOSTIC_TRANSPORT_FAILURE"
        in 300..399 -> "DIAGNOSTIC_REDIRECT_REJECTED"
        in 400..499 -> "DIAGNOSTIC_REQUEST_REJECTED"
        else -> "DIAGNOSTIC_PROTOCOL_FAILURE"
    }
}
