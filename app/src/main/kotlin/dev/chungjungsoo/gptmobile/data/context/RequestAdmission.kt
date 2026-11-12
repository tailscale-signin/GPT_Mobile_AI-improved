package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable

@Serializable
enum class CapacityProvenance { VERIFIED_ROUTE, DECLARED, CONSERVATIVE_POLICY, UNKNOWN }

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

    /** A verified route is still subject to the smaller profile, catalog, and transport ceilings. */
    fun withCeilings(contextCeiling: Long?, outputCeiling: Long?): CapacitySnapshot {
        require(listOfNotNull(contextCeiling, outputCeiling).all { it > 0 })
        return copy(
            contextTokens = listOfNotNull(contextTokens, contextCeiling).minOrNull(),
            outputTokens = listOfNotNull(outputTokens, outputCeiling).minOrNull()
        )
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
    val capacityChecked: Boolean,
    val reasoning: ReasoningDecision? = null,
    val physicalAttemptId: String? = null,
    val messageValidatorRevision: String = "unvalidated",
    val promptBudget: PromptBudgetReceipt? = null,
    val requestDeadline: RequestDeadlineReceipt? = null
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
    /** Auxiliary titles are optional: reject oversized input rather than dispatch unchecked. */
    fun auxiliaryCapacity(role: String, capacity: CapacitySnapshot): CapacitySnapshot =
        if (role == "title" && capacity.contextTokens == null) capacity.copy(contextTokens = 4096, provenance = CapacityProvenance.CONSERVATIVE_POLICY) else capacity

    fun prepare(
        payload: String,
        role: String,
        capacity: CapacitySnapshot,
        measurement: RequestMeasurement,
        outputTokens: Long?,
        permissionRevision: String,
        requireKnownCapacity: Boolean = false,
        serializerRevision: String = "provider-json-v1",
        reasoning: ReasoningDecision? = null,
        messageValidatorRevision: String = "unvalidated",
        promptBudget: PromptBudgetReceipt? = null,
        requestDeadline: RequestDeadlineReceipt? = null
    ): PreparedModelRequest {
        require(role.isNotBlank() && permissionRevision.isNotBlank())
        require(outputTokens == null || outputTokens > 0)
        require(promptBudget == null || promptBudget.promptTokens == measurement.promptTokens && promptBudget.safetyTokens == measurement.uncertaintyTokens && promptBudget.outputAllowanceTokens == outputTokens && promptBudget.contextTokens == capacity.contextTokens) { "PROMPT_BUDGET_IDENTITY_CHANGED" }
        val reservedInput = promptBudget?.toolResultReserveTokens ?: 0
        val safety = Math.addExact(measurement.uncertaintyTokens, reservedInput)
        val promptWithSafety = Math.addExact(measurement.promptTokens, safety)
        val total = outputTokens?.let { Math.addExact(promptWithSafety, it) }
        fun reject(code: String, message: String, ceiling: Long? = capacity.contextTokens): Nothing =
            throw RequestCapacityException(code, message, measurement.promptTokens, safety, outputTokens, ceiling, measurement.kind, promptBudget)
        if (requireKnownCapacity && (capacity.contextTokens == null || outputTokens == null)) reject("CAPACITY_UNVERIFIED", "configure a route capacity or verify the serving model before budget-sensitive inference")
        capacity.inputTokens?.let { if (promptWithSafety > it) reject("INPUT_CAPACITY_EXCEEDED", "the complete input is retained; no inference was sent.", it) }
        capacity.outputTokens?.let { if (outputTokens == null || outputTokens > it) reject("OUTPUT_CAPACITY_EXCEEDED", "re-plan the writing stage without discarding its goal.", it) }
        capacity.contextTokens?.let { ceiling ->
            if (promptWithSafety >= ceiling) reject("CONTEXT_CAPACITY_EXCEEDED", "no space remains for output; the input was not truncated.")
            if (total != null && total > ceiling) reject("CONTEXT_CAPACITY_EXCEEDED", "prompt + output + safety exceed the serving context; no inference was sent.")
        }
        val receipt = RequestAdmissionReceipt(role, serializerRevision, capacity.fingerprint, permissionRevision, measurement.promptTokens, outputTokens, safety, capacity.contextTokens, capacity.provenance, measurement.kind, total != null && capacity.contextTokens != null, reasoning, messageValidatorRevision = messageValidatorRevision, promptBudget = promptBudget, requestDeadline = requestDeadline)
        return PreparedModelRequest(payload, receipt, researchHash(payload))
    }

    fun estimate(payload: String): RequestMeasurement {
        val bytes = payload.toByteArray(Charsets.UTF_8).size.toLong()
        val prompt = Math.addExact(bytes, 2) / 3
        // Deliberately labeled heuristic. It is never a serving-tokenizer or calibrated count.
        return RequestMeasurement(prompt, estimateSafety(prompt), TokenMeasurementKind.UTF8_ESTIMATE)
    }

    /** Shared by planning and final serialization so a helper cannot spend the safety reserve. */
    fun estimateSafety(promptTokens: Long): Long {
        require(promptTokens >= 0)
        return maxOf(256L, promptTokens / 4)
    }
}
