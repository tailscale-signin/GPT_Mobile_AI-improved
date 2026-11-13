package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.diagnostics.GatewayReportDiagnostics
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportDiagnosticSnapshots
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GatewayModelPreparationReceipt(
    val schema: String,
    val operationId: String,
    val requestedModel: String,
    val state: String,
    val dispatchState: String,
    val loadAccepted: Boolean? = null,
    val loadRequests: Int = 0,
    val modelState: String? = null,
    val reasonCode: String? = null,
    val inferenceTested: Boolean = false,
    val automaticSubstitution: Boolean = false,
    val reasoningSettingsChanged: Boolean = false,
    val preparationAction: String? = null,
    val failure: GatewayModelPreparationFailure? = null,
    val reconciliationFailure: GatewayModelPreparationFailure? = null
) {
    internal fun sanitized(): GatewayModelPreparationReceipt = copy(
        modelState = GatewayReadinessFailure.modelState(modelState),
        reasonCode = reasonCode?.takeIf { it in setOf("MODEL_PREPARATION_UNSUPPORTED", "MODEL_PREPARATION_CANCELLED", "MODEL_PREPARATION_CONFIGURATION_CONFLICT") },
        preparationAction = preparationAction?.takeIf { it in setOf("NONE", "LOAD", "WAKE") },
        failure = failure?.sanitized(),
        reconciliationFailure = reconciliationFailure?.sanitized()
    )

    internal fun rejectionMessage(): String {
        val evidence = failure ?: reconciliationFailure
        return when {
            reasonCode == "MODEL_PREPARATION_UNSUPPORTED" || evidence?.failureCategory == "unsupported" -> "This gateway’s backend does not support the selected model’s load or wake action. Update the backend or load this exact model with its host controls, then recheck."
            evidence?.failureCategory == "denied" || evidence?.upstreamStatus in setOf(401, 403) -> "The backend rejected the gateway’s access to load this model. Repair gateway-to-backend authorization, then recheck."
            evidence?.reasonCode == "SELECTED_MODEL_NOT_AVAILABLE" -> "The exact selected model is no longer advertised by the backend. Restore its model configuration on the gateway computer, then recheck."
            evidence?.upstreamStatus != null -> "The backend rejected the selected model’s preparation (HTTP ${evidence.upstreamStatus}). Check that model’s backend settings, then recheck."
            else -> "The gateway could not start loading this exact model. Check the backend model and access settings, then recheck."
        }
    }
}

/** Only typed, allowlisted backend evidence survives decoding; upstream prose is ignored. */
@Serializable
data class GatewayModelPreparationFailure(
    val reasonCode: String? = null,
    val failureCode: String? = null,
    val failureCategory: String? = null,
    val dependency: String? = null,
    val upstreamStatus: Int? = null,
    val upstreamMessageCode: String? = null,
    val upstreamErrorBodyState: String? = null
) {
    internal fun sanitized() = copy(
        reasonCode = GatewayReadinessFailure.reasonCode(reasonCode),
        failureCode = GatewayReadinessFailure.failureCode(failureCode),
        failureCategory = GatewayReadinessFailure.category(failureCategory),
        dependency = GatewayReadinessFailure.dependency(dependency),
        upstreamStatus = upstreamStatus?.takeIf { it in 100..599 },
        upstreamMessageCode = GatewayReadinessFailure.upstreamMessage(upstreamMessageCode),
        upstreamErrorBodyState = GatewayReadinessFailure.upstreamBodyState(upstreamErrorBodyState)
    )
}

@Serializable
private data class GatewayModelPreparationRequest(val model: String, val operationId: String)

class GatewayModelPreparationRejectedException(val httpStatus: Int) : IllegalStateException("The gateway rejected model preparation before dispatch (HTTP $httpStatus).")

/** An explicit profile-selection action. Neither transport failures nor metadata retries replay this POST. */
class GatewayModelPreparationAPI @Inject constructor(private val network: NetworkClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(config: ProviderRequestConfig, model: String, operationId: String): GatewayModelPreparationReceipt {
        require(model.length in 1..512 && model == model.trim() && model.none { it.code < 32 })
        require(operationId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return gatewayClientExchange(config, "models", safeRead = false, method = "POST", operationId = operationId) { headers ->
            network().preparePost(config.buildEndpoint("gateway/models/prepare")) {
                config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                headers.forEach { (key, value) -> header(key, value) }
                header("Idempotency-Key", operationId)
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(GatewayModelPreparationRequest.serializer(), GatewayModelPreparationRequest(model, operationId)))
                timeout {
                    connectTimeoutMillis = 3_000L
                    requestTimeoutMillis = 25_000L
                    socketTimeoutMillis = 25_000L
                }
            }.execute { response ->
                GatewayClientDiagnostics.observe(config, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                if (response.status.value in setOf(400, 401, 403, 404, 405, 409, 413, 415, 422, 501)) {
                    throw GatewayModelPreparationRejectedException(response.status.value)
                }
                check(response.status.isSuccess()) { "Gateway model preparation did not return a successful receipt (HTTP ${response.status.value})." }
                val receipt = try {
                    json.decodeFromString<GatewayModelPreparationReceipt>(response.boundedMetadata(256 * 1024))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Never retain decoder excerpts containing backend configuration or upstream text.
                    error("Gateway model preparation returned an invalid receipt.")
                }
                check(
                    receipt.schema == "gptmobile.gateway.model-preparation.v1" &&
                        receipt.operationId == operationId &&
                        receipt.requestedModel == model &&
                        receipt.dispatchState in setOf("NOT_DISPATCHED", "DISPATCHED") &&
                        receipt.loadRequests in 0..1 &&
                        !receipt.inferenceTested &&
                        !receipt.automaticSubstitution &&
                        !receipt.reasoningSettingsChanged &&
                        receipt.state in setOf("PREPARING", "PRECHECK_REJECTED", "LOAD_REJECTED", "LOAD_ACCEPTED", "LOAD_OUTCOME_UNKNOWN", "WAITING_FOR_MODEL", "METADATA_QUALIFIED", "QUALIFICATION_BLOCKED")
                ) { "Gateway model preparation did not confirm this exact operation and model." }
                receipt.sanitized().also { safe ->
                    GatewayReportDiagnostics.modelPreparation(config.profileUid, model, safe)?.let(ReportDiagnosticSnapshots::retain)
                }
            }
        }
    }
}
