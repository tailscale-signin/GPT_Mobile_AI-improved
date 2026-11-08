package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayReadinessFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Retains a failed qualification as evidence, rather than probing forbidden administrative routes. */
internal data class GatewayModelCapabilityObservation(
    val instanceId: String? = null,
    val capacity: CapacitySnapshot? = null,
    val reasoningControl: ReasoningTemplateControl? = null,
    val reasoningControlState: String = "UNVERIFIED",
    val reasonCode: String? = null,
    val failureCode: String? = null,
    val dependency: String? = null,
    val httpStatus: Int? = null,
    val upstreamStatus: Int? = null,
    val failureCategory: String? = null,
    val retryable: Boolean? = null,
    val observedAtMillis: Long? = null,
    val observationAgeMillis: Long? = null,
    val receivedAtMonotonicMillis: Long? = null,
    val modelAdvertised: Boolean? = null,
    val modelLoaded: Boolean? = null,
    val modelState: String = "UNKNOWN",
    val upstreamCode: String? = null,
    val upstreamErrorType: String? = null,
    val upstreamMessageCode: String? = null,
    val upstreamErrorBodyState: String? = null
) {
    fun fresh(now: Long): GatewayModelCapabilityObservation {
        val received = receivedAtMonotonicMillis ?: return this
        val serverAge = observationAgeMillis ?: return this
        val elapsed = (now - received).takeIf { it >= 0 } ?: Long.MAX_VALUE
        val age = if (serverAge > Long.MAX_VALUE - elapsed) Long.MAX_VALUE else serverAge + elapsed
        return copy(
            capacity = capacity.takeIf { age < 20_000 },
            reasoningControl = reasoningControl.takeIf { age < 20_000 },
            reasoningControlState = reasoningControlState.takeIf { age < 20_000 } ?: "UNVERIFIED",
            observationAgeMillis = age,
            receivedAtMonotonicMillis = now
        )
    }

    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "capabilityInstanceId" to instanceId,
        "capabilityHttp" to httpStatus,
        "capabilityReasonCode" to reasonCode,
        "capabilityFailureCode" to failureCode,
        "capabilityDependency" to dependency,
        "capabilityUpstreamStatus" to upstreamStatus,
        "capabilityUpstreamCode" to upstreamCode,
        "capabilityUpstreamErrorType" to upstreamErrorType,
        "capabilityUpstreamMessageCode" to upstreamMessageCode,
        "capabilityUpstreamErrorBodyState" to upstreamErrorBodyState,
        "capabilityFailureCategory" to failureCategory,
        "capabilityRetryable" to retryable,
        "capabilityObservedAtMillis" to observedAtMillis,
        "capabilityObservationAgeMillis" to observationAgeMillis,
        "reasoningControlState" to reasoningControlState,
        "exactModelCapacityVerified" to (capacity != null),
        "modelAdvertised" to modelAdvertised,
        "modelLoaded" to modelLoaded,
        "modelState" to modelState
    )

    /** Fixed summaries keep the actual preflight blocker visible without upstream text. */
    fun blockingSummary(): String? = when {
        reasonCode == "SELECTED_MODEL_LOADING" || modelState == "LOADING" -> "The exact selected model is loading on the gateway host. Recheck this profile when loading finishes."
        reasonCode == "SELECTED_MODEL_NOT_LOADED" || modelState in setOf("UNLOADED", "SLEEPING") -> "The exact selected model is advertised but not loaded. On the gateway host, run prepare-model --model with that exact ID, then recheck this profile."
        dependency == "backend_properties" && upstreamStatus in 400..499 -> "Backend properties validation returned HTTP $upstreamStatus. Check the exact model, backend version and properties route on the gateway host. Catalogue membership alone does not verify a loaded model."
        reasonCode == "BACKEND_METADATA_DENIED" -> "The gateway could not read the configured backend's model metadata. Repair gateway-to-backend authorization, then recheck this profile."
        reasonCode == "SELECTED_MODEL_NOT_AVAILABLE" -> "The exact selected model is absent from the configured backend catalogue. Restore that model on the gateway host, then recheck this profile."
        else -> null
    }

    fun failureFields(): JsonObject = buildJsonObject {
        GatewayReadinessFailure.reasonCode(reasonCode)?.let { put("gateway_reason_code", it) }
        GatewayReadinessFailure.failureCode(failureCode)?.let { put("gateway_failure_code", it) }
        GatewayReadinessFailure.dependency(dependency)?.let { put("gateway_dependency", it) }
        upstreamStatus?.takeIf { it in 100..599 }?.let { put("gateway_upstream_status", it) }
        GatewayReadinessFailure.upstreamCode(upstreamCode)?.let { put("gateway_upstream_code", it) }
        GatewayReadinessFailure.upstreamType(upstreamErrorType)?.let { put("gateway_upstream_error_type", it) }
        GatewayReadinessFailure.upstreamMessage(upstreamMessageCode)?.let { put("gateway_upstream_message_code", it) }
        GatewayReadinessFailure.upstreamBodyState(upstreamErrorBodyState)?.let { put("gateway_upstream_error_body_state", it) }
        GatewayReadinessFailure.modelState(modelState)?.let { put("gateway_model_state", it) }
        modelAdvertised?.let { put("gateway_model_advertised", it) }
        modelLoaded?.let { put("gateway_model_loaded", it) }
    }
}

internal fun parseGatewayModelObservation(body: String, config: ProviderRequestConfig, model: String, now: Long): GatewayModelCapabilityObservation {
    val invalid = GatewayModelCapabilityObservation(httpStatus = 200, failureCode = "GATEWAY_CAPABILITY_INVALID_RESPONSE", failureCategory = "CONTRACT", retryable = false)
    if (body.toByteArray(Charsets.UTF_8).size > 256 * 1024) return invalid
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return invalid
    val selected = root["selectedModel"] as? JsonObject ?: return invalid
    fun text(value: JsonObject, key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    fun number(value: JsonObject, key: String) = (value[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    if (number(root, "contractVersion") != 1L ||
        text(root, "instanceId")?.matches(Regex("[a-f0-9]{32}")) != true ||
        text(selected, "schema") != "gptmobile.gateway.selected-model.v1" ||
        number(selected, "schemaVersion") != 1L ||
        text(selected, "requestedModel") != model
    ) {
        return invalid
    }
    val age = if ("observationAgeMs" in selected) number(selected, "observationAgeMs")?.takeIf { it >= 0 } ?: return invalid else null
    val capacity = parseGatewayCapacity(body, config, model, now)?.takeUnless { age != null && age >= 20_000 }
    val control = capacity?.let { parseGatewayReasoningControl(body, it) }
    return GatewayModelCapabilityObservation(
        instanceId = text(root, "instanceId"),
        capacity = capacity,
        reasoningControl = control,
        reasoningControlState = when {
            control != null -> "VERIFIED"
            capacity != null && text(selected, "reasoningControlState") == "UNSUPPORTED" -> "UNSUPPORTED"
            else -> "UNVERIFIED"
        },
        reasonCode = GatewayReadinessFailure.reasonCode(text(selected, "reasonCode")),
        failureCode = GatewayReadinessFailure.failureCode(text(selected, "failureCode")),
        dependency = GatewayReadinessFailure.dependency(text(selected, "dependency")),
        httpStatus = 200,
        upstreamStatus = number(selected, "upstreamStatus")?.takeIf { it in 100..599 }?.toInt(),
        failureCategory = GatewayReadinessFailure.category(text(selected, "failureCategory")),
        retryable = (selected["retryable"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
        observedAtMillis = number(selected, "observedAtMillis")?.takeIf { it > 0 },
        observationAgeMillis = age,
        receivedAtMonotonicMillis = now,
        modelAdvertised = (selected["modelAdvertised"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
        modelLoaded = (selected["modelLoaded"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
        modelState = GatewayReadinessFailure.modelState(text(selected, "modelState")) ?: "UNKNOWN",
        upstreamCode = GatewayReadinessFailure.upstreamCode((selected["upstreamCode"] as? JsonPrimitive)?.contentOrNull),
        upstreamErrorType = GatewayReadinessFailure.upstreamType(text(selected, "upstreamErrorType")),
        upstreamMessageCode = GatewayReadinessFailure.upstreamMessage(text(selected, "upstreamMessageCode")),
        upstreamErrorBodyState = GatewayReadinessFailure.upstreamBodyState(text(selected, "upstreamErrorBodyState"))
    )
}
