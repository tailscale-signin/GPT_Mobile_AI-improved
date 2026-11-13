package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.network.LocalEndpointConnectionPolicy
import dev.chungjungsoo.gptmobile.data.network.LocalServiceUnavailableException
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayJobPollCoordinator
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewaySurfacePolicy
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayClientExchange
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayJobObservationIdentity
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Catalog and transcript reads have no admission, resume, or cancellation side effects. */
@Singleton
class GatewayJobClient internal constructor(
    private val http: HttpClient,
    private val evictIdleConnections: suspend () -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    @Inject constructor(network: NetworkClient) : this(network.gatewayReadClient(), network::evictIdleConnections)
    private val json = Json { ignoreUnknownKeys = true }
    private data class CommandObservation(val receipt: GatewayCommandReceipt?, val interval: Long, val runtime: String)
    private val commandMisses = linkedMapOf<String, Int>()
    private val commandObservations = RouteCapabilityCache<CommandObservation>(now, valueTtl = { it.interval })
    private data class IdentityObservation(val identity: GatewayScopeIdentity, val runtime: String)
    private val identityReads = RouteCapabilityCache<IdentityObservation>(now, positiveTtl = 1_000)

    private suspend fun read(config: ProviderRequestConfig, path: String, maxBytes: Int = 2_000_000): String {
        val diagnostic = GatewayClientDiagnostics.withHeaders(config, model = "", knownGateway = true)
        val endpoint = config.buildEndpoint("gateway/$path")
        val surface = surface(path)
        if (surface == GatewayAdmissionSurface.CAPABILITIES) {
            GatewaySurfacePolicy.shared.rememberedStatus(GatewaySurfacePolicy.identity(config), GatewaySurfacePolicy.Surface.CAPABILITIES)?.let { status ->
                throw GatewayHistoryUnsupported(surface, status)
            }
        }
        val deadline = if (maxBytes > 2_000_000) 120_000L else 30_000L
        return try {
            // One retry owner and one total deadline, for these GETs only.
            withTimeout(deadline) {
                LocalEndpointConnectionPolicy.probe(endpoint, evictIdleConnections) {
                    gatewayClientExchange(diagnostic, diagnosticRoute(surface), safeRead = true) { headers ->
                        http.prepareRequest(endpoint) {
                            method = HttpMethod.Get
                            config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                            headers.forEach { (key, value) -> header(key, value) }
                            if (LocalEndpointConnectionPolicy.freshConnection(endpoint)) header(HttpHeaders.Connection, "close")
                            timeout {
                                connectTimeoutMillis = 10_000
                                requestTimeoutMillis = deadline
                                socketTimeoutMillis = 30_000
                            }
                        }.execute { response ->
                            GatewayClientDiagnostics.observe(diagnostic, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                            if (surface == GatewayAdmissionSurface.CAPABILITIES) {
                                GatewaySurfacePolicy.shared.observe(GatewaySurfacePolicy.identity(config), GatewaySurfacePolicy.Surface.CAPABILITIES, response.status.value)
                            }
                            if (!response.status.isSuccess()) {
                                val code = safeGatewayAdmissionCode(response.headers["X-Gateway-Admission-Error"])
                                val body = if (code == null) {
                                    try {
                                        response.boundedMetadata(16 * 1024)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        // An HTTP rejection remains confirmed even if its optional body is invalid or incomplete.
                                        null
                                    }
                                } else {
                                    null
                                }
                                val directModelServer = response.headers[HttpHeaders.Server]?.substringBefore('/')?.equals("llama.cpp", ignoreCase = true) == true &&
                                    response.headers["X-Gateway-Instance-ID"].isNullOrBlank() &&
                                    GatewayClientDiagnostics.runtimeEpoch(config) == "unobserved"
                                val failure = gatewayAdmissionFailure(response.status.value, surface, code, body, directModelServer)
                                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                                    "Gateway",
                                    "ADMISSION_READ · phase=${failure.phase} · surface=$surface · method=GET · responseHeadersReceived=true · httpStatus=${failure.httpStatus} · responseService=${if (directModelServer) "LLAMA_CPP" else "UNVERIFIED"} · code=${failure.wireCode ?: failure.reason.name} · dependentDispatch=${failure.dispatchState}",
                                    "W"
                                )
                                throw failure
                            }
                            if (response.status.value != 200) throw GatewayAdmissionException(GatewayAdmissionReason.INVALID_RESPONSE, response.status.value, null, surface)
                            try {
                                response.boundedMetadata(maxBytes)
                            } catch (oversized: IllegalStateException) {
                                if (oversized.message != "Catalog response exceeds its size limit.") throw oversized
                                if (maxBytes > 2_000_000) throw GatewayTranscriptTooLarge()
                                throw GatewayAdmissionException(GatewayAdmissionReason.INVALID_RESPONSE, response.status.value, null, surface)
                            }
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw GatewayAdmissionException(GatewayAdmissionReason.TRANSPORT_INTERRUPTED, null, null, surface)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GatewayAdmissionException) {
            throw failure
        } catch (failure: Exception) {
            val causes = generateSequence<Throwable>(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
            val reason = when {
                causes.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException } -> GatewayAdmissionReason.INVALID_CONNECTION
                causes.any { it is java.io.IOException || it is LocalServiceUnavailableException || it.javaClass.simpleName in setOf("HttpRequestTimeoutException", "ConnectTimeoutException", "SocketTimeoutException") } -> GatewayAdmissionReason.TRANSPORT_INTERRUPTED
                else -> throw failure
            }
            throw GatewayAdmissionException(reason, null, null, surface)
        }
    }

    private fun surface(path: String): GatewayAdmissionSurface = when {
        path == "identity" -> GatewayAdmissionSurface.IDENTITY
        path == "capabilities" -> GatewayAdmissionSurface.CAPABILITIES
        path.startsWith("job-catalog") -> GatewayAdmissionSurface.JOB_CATALOG
        path.startsWith("commands/") -> GatewayAdmissionSurface.COMMAND
        path.substringBefore('?').endsWith("/messages") -> GatewayAdmissionSurface.TRANSCRIPT
        else -> GatewayAdmissionSurface.CONVERSATIONS
    }

    private fun diagnosticRoute(surface: GatewayAdmissionSurface): String = when (surface) {
        GatewayAdmissionSurface.IDENTITY -> "identity"
        GatewayAdmissionSurface.CAPABILITIES -> "capabilities"
        GatewayAdmissionSurface.COMMAND -> "commands"
        GatewayAdmissionSurface.TRANSCRIPT -> "messages"
        GatewayAdmissionSurface.CONVERSATIONS -> "conversations"
        else -> "job_catalog"
    }

    private inline fun <T> decode(surface: GatewayAdmissionSurface, block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: GatewayAdmissionException) {
        throw failure
    } catch (failure: GatewayCommandRejected) {
        throw failure
    } catch (_: Exception) {
        // Decoder causes can embed the response body. Retain only the safe contract category.
        throw GatewayAdmissionException(GatewayAdmissionReason.INVALID_RESPONSE, 200, null, surface)
    }

    /** Captures the authenticated destination before a new durable ordinary admission. */
    internal suspend fun scope(config: ProviderRequestConfig): GatewayScopeIdentity {
        val body = read(config, "conversations?limit=1")
        return decode(GatewayAdmissionSurface.CONVERSATIONS) {
            require((json.parseToJsonElement(body) as? JsonObject)?.integer("schema_version") == 1)
            json.decodeFromString<GatewayScopeIdentity>(body).also { requireGatewayIdentity(it.gatewayId, it.principalId) }
        }
    }

    /** Canonical journal identity; the inference installation identifier may be different. */
    internal suspend fun identity(config: ProviderRequestConfig): GatewayScopeIdentity {
        val key = gatewayJobObservationIdentity(config)
        // Each sequential admission still observes the server. Only overlapping
        // reads of the same authenticated route share their in-flight response.
        return requireNotNull(
            identityReads.getOrLoad(key, forceRefresh = true, isCurrent = { it?.runtime == GatewaySurfacePolicy.identity(config) }) {
                val body = read(config, "identity")
                val identity = decode(GatewayAdmissionSurface.IDENTITY) {
                    val document = json.parseToJsonElement(body) as? JsonObject ?: error("Invalid identity")
                    require(document.integer("schema_version") == 1 && document.integer("protocol_version") == 1)
                    json.decodeFromString<GatewayScopeIdentity>(body).also { requireGatewayIdentity(it.gatewayId, it.principalId) }
                }
                IdentityObservation(identity, GatewaySurfacePolicy.identity(config))
            }
        ).identity
    }

    /** Explicit reconciliation always reads live; a prior missing receipt never authorizes a write. */
    suspend fun command(config: ProviderRequestConfig, commandId: String): GatewayCommandReceipt? =
        lookupCommand(config, commandId, forceRefresh = true)

    /** Passive observers back off absent receipts without retiring or replaying the saved command. */
    internal suspend fun observeCommand(config: ProviderRequestConfig, commandId: String): GatewayCommandReceipt? =
        lookupCommand(config, commandId, forceRefresh = false)

    private suspend fun lookupCommand(config: ProviderRequestConfig, commandId: String, forceRefresh: Boolean): GatewayCommandReceipt? {
        pathId(commandId)
        val key = gatewayIdentityKey(gatewayJobObservationIdentity(config), commandId)
        return commandObservations.getOrLoad(key, forceRefresh = forceRefresh, isCurrent = { it?.runtime == GatewaySurfacePolicy.identity(config) }) {
            val receipt = readCommand(config, commandId)
            val misses = synchronized(commandMisses) {
                if (receipt == null) {
                    if (commandMisses.size >= 128 && key !in commandMisses) commandMisses.remove(commandMisses.keys.first())
                    ((commandMisses[key] ?: 0) + 1).coerceAtMost(5).also { commandMisses[key] = it }
                } else {
                    commandMisses.remove(key)
                    0
                }
            }
            // Cache only the immutable accepted binding. Active job/queue state
            // remains in result reads, and explicit reconciliation stays fresh.
            val interval = if (receipt == null) (5_000L shl (misses - 1).coerceIn(0, 4)).coerceAtMost(60_000L) else maxOf(30_000L, GatewayJobPollCoordinator.remaining(config, receipt.jobId))
            CommandObservation(receipt, interval, GatewaySurfacePolicy.identity(config))
        }?.receipt
    }

    private suspend fun readCommand(config: ProviderRequestConfig, commandId: String): GatewayCommandReceipt? {
        val payload = try {
            read(config, "commands/${pathId(commandId)}?include_outcome=true")
        } catch (failure: GatewayAdmissionException) {
            if (failure.reason == GatewayAdmissionReason.COMMAND_RECEIPT_UNAVAILABLE) return null
            throw failure
        }
        return decode(GatewayAdmissionSurface.COMMAND) {
            val document = json.parseToJsonElement(payload) as? JsonObject ?: error("Invalid command receipt")
            if (document.text("admission_state") == "rejected") {
                val rejection = json.decodeFromString<GatewayCommandRejection>(payload)
                rejection.verify(commandId)
                throw GatewayCommandRejected(rejection)
            }
            json.decodeFromString<GatewayCommandReceipt>(payload).also {
                require(it.schemaVersion == 1 && it.commandId == commandId && it.jobKind in setOf("engineering", "generation"))
                requireGatewayIdentity(it.gatewayId, it.principalId)
            }
        }
    }

    suspend fun catalog(config: ProviderRequestConfig, cursor: String? = null): GatewayJobCatalog {
        val body = read(config, "job-catalog?limit=50${cursor.query("cursor")}")
        return decode(GatewayAdmissionSurface.JOB_CATALOG) {
            json.decodeFromString<GatewayJobCatalog>(body).also {
                require(it.schemaVersion == 1 && it.items.size <= 100)
                requireGatewayIdentity(it.gatewayId, it.principalId)
                require(!it.hasMore || !it.nextCursor.isNullOrBlank())
            }
        }
    }

    suspend fun conversations(config: ProviderRequestConfig, cursor: String? = null): GatewayConversationPage {
        val body = read(config, "conversations?limit=50${cursor.query("cursor")}")
        return decode(GatewayAdmissionSurface.CONVERSATIONS) {
            json.decodeFromString<GatewayConversationPage>(body).also {
                require(it.schemaVersion == 1 && it.items.size <= 100)
                requireGatewayIdentity(it.gatewayId, it.principalId)
                require(!it.hasMore || !it.nextCursor.isNullOrBlank())
                require(it.items.all { item -> item.gatewayId == it.gatewayId && item.principalId == it.principalId })
            }
        }
    }

    suspend fun conversation(config: ProviderRequestConfig, conversationId: String): GatewayConversationSnapshot {
        val body = read(config, "conversations/${pathId(conversationId)}")
        return decode(GatewayAdmissionSurface.CONVERSATIONS) {
            json.decodeFromString<GatewayConversationSnapshot>(body).also {
                require(it.schemaVersion == 1 && it.conversationId == conversationId)
                requireGatewayIdentity(it.gatewayId, it.principalId)
            }
        }
    }

    suspend fun messages(config: ProviderRequestConfig, conversationId: String, cursor: String?): GatewayTranscriptPage {
        val body = read(config, "conversations/${pathId(conversationId)}/messages?limit=100${cursor.query("after_cursor")}", maxBytes = 65 * 1024 * 1024)
        return decode(GatewayAdmissionSurface.TRANSCRIPT) {
            json.decodeFromString<GatewayTranscriptPage>(body).also {
                require(it.schemaVersion == 1 && it.conversationId == conversationId && it.messages.size <= 200)
                requireGatewayIdentity(it.gatewayId, it.principalId)
                require(!it.hasMore || !it.nextCursor.isNullOrBlank() && it.nextCursor != cursor)
                require(it.messages.all { message -> message.messageId.isNotBlank() && message.turnId.isNotBlank() && message.role in setOf("user", "assistant", "tool", "system") && message.revision >= 0 })
            }
        }
    }

    /** Identity/capability preflight, optionally followed by each history surface for explicit diagnostics. */
    internal suspend fun verifySavedWorkContract(config: ProviderRequestConfig, expectedSourceCommit: String? = null, verifyHistorySurfaces: Boolean = true): GatewaySavedWorkContract {
        require(expectedSourceCommit == null || expectedSourceCommit.matches(Regex("[a-f0-9]{40}")))
        var checkedSurface = GatewayAdmissionSurface.CAPABILITIES
        var source: GatewayAdmissionSource? = null
        return try {
            withTimeout(30_000) {
                val payload = read(config, "capabilities", 256 * 1024)
                val document = decode(checkedSurface) { json.parseToJsonElement(payload) as? JsonObject ?: error("Invalid capability") }
                val build = document["build"] as? JsonObject
                val observedSource = GatewayAdmissionSource(
                    document.text("gatewayVersion")?.takeIf { it.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) },
                    build?.text("sourceCommit")?.takeIf { it.matches(Regex("[a-f0-9]{40}")) }
                )
                source = observedSource
                val features = document["features"] as? JsonObject
                val contracts = document["contracts"] as? JsonObject
                val supported = document.integer("contractVersion") == 1 &&
                    features?.get("conversation_sync_v1") == JsonPrimitive(true) &&
                    features["job_catalog_v1"] == JsonPrimitive(true) &&
                    contracts?.supports("gatewayConversations", "gptmobile.gateway.conversations.v1") == true &&
                    contracts.supports("gatewayJobCatalog", "gptmobile.gateway.job-catalog.v1")
                if (!supported) throw GatewayHistoryUnsupported(checkedSurface, 200)
                if (expectedSourceCommit != null && observedSource.sourceCommit != expectedSourceCommit) throw GatewayAdmissionException(GatewayAdmissionReason.SOURCE_MISMATCH, 200, null, checkedSurface)
                checkedSurface = GatewayAdmissionSurface.IDENTITY
                val identity = identity(config)
                val declaredGateway = document.text("gateway_id")
                val declaredPrincipal = document.text("principal_id")
                val declaredInstance = document.text("instanceId")
                decode(GatewayAdmissionSurface.CAPABILITIES) {
                    requireGatewayIdentity(declaredGateway.orEmpty(), declaredPrincipal.orEmpty())
                    require(declaredInstance?.matches(Regex("[a-f0-9]{32}")) == true)
                }
                if (identity.gatewayId != declaredGateway || identity.principalId != declaredPrincipal || identity.instanceId != declaredInstance) {
                    throw GatewayAdmissionException(GatewayAdmissionReason.IDENTITY_MISMATCH, 200, null, checkedSurface)
                }
                if (!verifyHistorySurfaces) {
                    return@withTimeout GatewaySavedWorkContract(identity, observedSource, expectedSourceCommit?.let { it == observedSource.sourceCommit }, readiness = GatewayActionReadinessEvidence(identity = GatewayActionReadiness.VERIFIED))
                }
                checkedSurface = GatewayAdmissionSurface.CONVERSATIONS
                val conversationBody = read(config, "conversations?limit=1")
                val conversation = decode(checkedSurface) {
                    require((json.parseToJsonElement(conversationBody) as? JsonObject)?.integer("schema_version") == 1)
                    json.decodeFromString<GatewayConversationPage>(conversationBody).also { require(it.items.size <= 1 && it.items.all { item -> item.gatewayId == identity.gatewayId && item.principalId == identity.principalId }) }
                }
                if (conversation.gatewayId != identity.gatewayId || conversation.principalId != identity.principalId) throw GatewayAdmissionException(GatewayAdmissionReason.IDENTITY_MISMATCH, 200, null, checkedSurface)
                checkedSurface = GatewayAdmissionSurface.JOB_CATALOG
                val catalogBody = read(config, "job-catalog?limit=1")
                val catalog = decode(checkedSurface) {
                    require((json.parseToJsonElement(catalogBody) as? JsonObject)?.integer("schema_version") == 1)
                    json.decodeFromString<GatewayJobCatalog>(catalogBody).also { require(it.items.size <= 1) }
                }
                if (catalog.gatewayId != identity.gatewayId || catalog.principalId != identity.principalId) throw GatewayAdmissionException(GatewayAdmissionReason.IDENTITY_MISMATCH, 200, null, checkedSurface)
                GatewaySavedWorkContract(identity, observedSource, expectedSourceCommit?.let { it == observedSource.sourceCommit })
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw GatewayAdmissionException(GatewayAdmissionReason.TRANSPORT_INTERRUPTED, null, null, checkedSurface, source = source)
        } catch (failure: GatewayAdmissionException) {
            throw source?.let(failure::withSource) ?: failure
        }
    }

    private fun JsonObject.supports(key: String, contract: String): Boolean = (this[key] as? kotlinx.serialization.json.JsonArray).orEmpty().any { it == JsonPrimitive(contract) }
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.integer(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private fun pathId(id: String): String {
        require(id.isNotBlank() && id.length <= 256 && id.none(Char::isISOControl))
        return id.encodeURLParameter()
    }

    private fun String?.query(name: String): String {
        if (this == null) return ""
        require(length in 1..4096 && none(Char::isISOControl))
        return "&$name=${encodeURLParameter()}"
    }
}
