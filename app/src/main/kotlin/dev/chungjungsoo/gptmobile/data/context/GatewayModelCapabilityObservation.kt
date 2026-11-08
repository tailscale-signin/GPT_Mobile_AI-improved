package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayReadinessFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

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
    val receivedAtMonotonicMillis: Long? = null
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
        "capabilityFailureCategory" to failureCategory,
        "capabilityRetryable" to retryable,
        "capabilityObservedAtMillis" to observedAtMillis,
        "capabilityObservationAgeMillis" to observationAgeMillis,
        "reasoningControlState" to reasoningControlState,
        "exactModelCapacityVerified" to (capacity != null)
    )
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
        receivedAtMonotonicMillis = now
    )
}
