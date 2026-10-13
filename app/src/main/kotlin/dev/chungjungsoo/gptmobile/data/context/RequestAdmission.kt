package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable

@Serializable
enum class CapacityProvenance { VERIFIED_ROUTE, DECLARED, UNKNOWN }

@Serializable
data class RuntimeFingerprint(
    val connectionId: String,
    val route: String,
    val credentialRevision: String,
    val requestedModel: String,
    val resolvedModel: String,
    val runtimeRevision: String,
    val templateRevision: String,
    val allocationRevision: String
)

@Serializable
data class CapacitySnapshot(
    val fingerprint: RuntimeFingerprint,
    val contextTokens: Long?,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val provenance: CapacityProvenance = CapacityProvenance.UNKNOWN,
    val observedAtMillis: Long = 0
) {
    init {
        require(listOfNotNull(contextTokens, inputTokens, outputTokens).all { it > 0 })
    }
}

@Serializable
enum class TokenMeasurementKind { SERVING_TOKENIZER, UTF8_ESTIMATE }

@Serializable
data class RequestMeasurement(val promptTokens: Long, val uncertaintyTokens: Long, val kind: TokenMeasurementKind) {
    init {
        require(promptTokens >= 0 && uncertaintyTokens >= 0)
    }
}

@Serializable
data class RequestAdmissionReceipt(
    val role: String,
    val serializerRevision: String,
    val runtime: RuntimeFingerprint,
    val permissionRevision: String,
    val promptTokens: Long,
    val outputTokens: Long?,
    val safetyTokens: Long,
    val contextTokens: Long?,
    val capacityProvenance: CapacityProvenance,
    val measurement: TokenMeasurementKind,
    val capacityChecked: Boolean
)

/** Holds the actual admitted bytes. This digest is internal and must never label a redacted export. */
class PreparedModelRequest internal constructor(
    val payload: String,
    val receipt: RequestAdmissionReceipt,
    private val wireDigest: String
) {
    fun dispatchPayload(runtime: RuntimeFingerprint, permissionRevision: String): String {
        check(runtime == receipt.runtime && permissionRevision == receipt.permissionRevision) { "REQUEST_IDENTITY_CHANGED: prepare the request again" }
        check(researchHash(payload) == wireDigest) { "REQUEST_MUTATED_AFTER_ADMISSION" }
        return payload
    }
}

/** One checked-Long invariant; preview reserves are not added again after evidence is serialized. */
object RequestAdmission {
    fun prepare(
        payload: String,
        role: String,
        capacity: CapacitySnapshot,
        measurement: RequestMeasurement,
        outputTokens: Long?,
        permissionRevision: String,
        requireKnownCapacity: Boolean = false,
        serializerRevision: String = "provider-json-v1"
    ): PreparedModelRequest {
        require(role.isNotBlank() && permissionRevision.isNotBlank())
        require(outputTokens == null || outputTokens > 0)
        val promptWithSafety = Math.addExact(measurement.promptTokens, measurement.uncertaintyTokens)
        val total = outputTokens?.let { Math.addExact(promptWithSafety, it) }
        check(!requireKnownCapacity || capacity.contextTokens != null && outputTokens != null) { "CAPACITY_UNVERIFIED: configure a route capacity or verify the serving model before budget-sensitive inference" }
        capacity.inputTokens?.let { check(promptWithSafety <= it) { "INPUT_CAPACITY_EXCEEDED: the complete input is retained; no inference was sent" } }
        capacity.outputTokens?.let { check(outputTokens != null && outputTokens <= it) { "OUTPUT_CAPACITY_EXCEEDED: re-plan the writing stage without discarding its goal" } }
        capacity.contextTokens?.let { ceiling ->
            check(promptWithSafety < ceiling) { "CONTEXT_CAPACITY_EXCEEDED: no space remains for output; the input was not truncated" }
            if (total != null) check(total <= ceiling) { "CONTEXT_CAPACITY_EXCEEDED: prompt + output + safety exceed the serving context; no inference was sent" }
        }
        val receipt = RequestAdmissionReceipt(role, serializerRevision, capacity.fingerprint, permissionRevision, measurement.promptTokens, outputTokens, measurement.uncertaintyTokens, capacity.contextTokens, capacity.provenance, measurement.kind, total != null && capacity.contextTokens != null)
        return PreparedModelRequest(payload, receipt, researchHash(payload))
    }

    fun estimate(payload: String): RequestMeasurement {
        val bytes = payload.toByteArray(Charsets.UTF_8).size.toLong()
        val prompt = Math.addExact(bytes, 2) / 3
        // Deliberately labeled heuristic. It is never a serving-tokenizer or calibrated count.
        return RequestMeasurement(prompt, maxOf(256L, prompt / 4), TokenMeasurementKind.UTF8_ESTIMATE)
    }
}
