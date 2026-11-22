package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Closed fixture facts contain no tool arguments, responses, credentials or exception text. */
@Serializable
enum class AppAuditAssertionCode {
    FIXTURE_ASSERTION,
    ARGUMENTS_REJECTED_BEFORE_DISPATCH,
    ACCEPTED_CALL_RESERVES_BUDGET,
    DUPLICATE_INVOCATION_REJECTED,
    TOOL_BUDGET_EXHAUSTED,
    DENIED_AUTHORITY,
    DENIAL_PRESERVES_BUDGET,
    MISMATCHED_RECEIPT_UNCERTAIN,
    UNCERTAIN_RECEIPT_BLOCKS_REPLAY,
    CANONICAL_ARGUMENT_ORDER,
    GRANT_IDENTITY_STABLE,
    GRANT_CHAT_BOUND,
    GRANT_RESOURCE_BOUND,
    GRANT_ACTION_BOUND,
    RUNNER_RECEIPT_SUCCESS,
    RUNNER_COMPLETION,
    RUNNER_BUDGET_ISOLATED
}

@Serializable
enum class AppAuditFixtureCode {
    SUCCESS,
    INVALID_ARGUMENTS,
    AUTH_REQUIRED,
    DUPLICATE_INVOCATION,
    BUDGET_EXHAUSTED,
    TOOL_RESULT_ID_MISMATCH,
    RECONCILIATION_REQUIRED,
    PRIVACY_EXTERNAL_TOOL_BLOCKED,
    PRIVACY_SERVICE_UNVERIFIED,
    UNKNOWN_CODE
}

@Serializable
enum class AppAuditFixtureStatus { EXECUTED, SETUP_BLOCKED, EXECUTION_FAILED }

@Serializable
data class AppAuditFixtureState(
    val code: AppAuditFixtureCode? = null,
    val dispatched: Boolean? = null,
    val admission: AppAuditNetworkAdmission? = null,
    val reservedCalls: Int? = null,
    val usedCalls: Int? = null,
    val remainingCalls: Int? = null,
    val invocationCount: Int? = null,
    val roundCount: Int? = null,
    val completedCount: Int? = null,
    val failedCount: Int? = null
)

@Serializable
data class AppAuditAssertionEvidence(
    val ordinal: Int,
    val code: AppAuditAssertionCode,
    val passed: Boolean,
    val expected: AppAuditFixtureState? = null,
    val observed: AppAuditFixtureState? = null,
    val blocked: Boolean = false
)

@Serializable
data class AppAuditFixtureEvidence(
    val status: AppAuditFixtureStatus,
    val attempted: Int,
    val passed: Int,
    val assertions: List<AppAuditAssertionEvidence> = emptyList()
)

/** Settings metadata only. Custom terms, category choices and replacement maps are never read here. */
@Serializable
data class AppAuditPrivacyEvidence(val enabled: Boolean?, val revision: Long?, val changedDuringProbe: Boolean? = null)

@Serializable
data class AppAuditRecoveryEvidence(
    val originalReasonCode: String? = null,
    val retryEligibleAtEpochMs: Long? = null,
    val explicitReconnectRequired: Boolean = false
)

internal fun appAuditPrivacySnapshot(): AppAuditPrivacyEvidence? = runCatching { PrivacyModeStore.auditPolicySnapshot() }.getOrNull()?.let {
    AppAuditPrivacyEvidence(it.enabled, it.revision.takeIf { revision -> revision >= 0 })
}

internal fun appAuditPrivacyObservation(before: AppAuditPrivacyEvidence?, after: AppAuditPrivacyEvidence?): AppAuditPrivacyEvidence? =
    (before ?: after)?.copy(changedDuringProbe = if (before == null || after == null) null else before.enabled != after.enabled || before.revision != after.revision)

internal fun AppAuditFixtureState.safe(): AppAuditFixtureState = copy(
    reservedCalls = reservedCalls?.takeIf { it in 0..2048 },
    usedCalls = usedCalls?.takeIf { it in 0..2048 },
    remainingCalls = remainingCalls?.takeIf { it in 0..2048 },
    invocationCount = invocationCount?.takeIf { it in 0..2048 },
    roundCount = roundCount?.takeIf { it in 0..2048 },
    completedCount = completedCount?.takeIf { it in 0..2048 },
    failedCount = failedCount?.takeIf { it in 0..2048 }
)

internal fun appAuditFixtureState(result: AgentToolResult, usedCalls: Int? = null, remainingCalls: Int? = null, invocationCount: Int? = null) = AppAuditFixtureState(
    code = if (!result.isError) AppAuditFixtureCode.SUCCESS else AppAuditFixtureCode.entries.firstOrNull { it.name == result.errorCode } ?: AppAuditFixtureCode.UNKNOWN_CODE,
    dispatched = result.dispatched,
    admission = result.toolCallBudgetReserved?.let { if (it > 0) AppAuditNetworkAdmission.ADMITTED else AppAuditNetworkAdmission.NOT_ADMITTED } ?: AppAuditNetworkAdmission.UNKNOWN,
    reservedCalls = result.toolCallBudgetReserved,
    usedCalls = usedCalls,
    remainingCalls = remainingCalls,
    invocationCount = invocationCount
).safe()

internal fun appAuditFixtureEvidenceJson(evidence: AppAuditFixtureEvidence?, probeId: String): JsonObject? {
    val safe = safeAuditFixtureEvidence(evidence) ?: return null
    val retained = safe.assertions
    return buildJsonObject {
        put("status", safe.status.name)
        put("assertionsAttempted", safe.attempted)
        put("assertionsPassed", safe.passed)
        put("retainedAssertions", retained.size)
        put("omittedAssertions", safe.attempted - retained.size)
        put("stateScope", "DISPOSABLE_LOCAL_FIXTURE_ONLY_NO_NETWORK_DISPATCH")
        put(
            "assertions",
            JsonArray(
                retained.map { assertion ->
                    buildJsonObject {
                        put("id", "${safeAuditProbe(probeId)}.assertion-${assertion.ordinal}")
                        put("code", assertion.code.name)
                        put("passed", assertion.passed)
                        put(
                            "outcome",
                            if (assertion.blocked) {
                                "BLOCKED"
                            } else if (assertion.passed) {
                                "PASS"
                            } else {
                                "FAIL"
                            }
                        )
                        put("expected", fixtureStateJson(assertion.expected) ?: JsonNull)
                        put("observed", fixtureStateJson(assertion.observed) ?: JsonNull)
                    }
                }
            )
        )
    }
}

internal fun safeAuditFixtureEvidence(evidence: AppAuditFixtureEvidence?): AppAuditFixtureEvidence? {
    if (evidence == null || evidence.attempted !in 0..2048 || evidence.passed !in 0..evidence.attempted || evidence.assertions.size > 2048) return null
    return evidence.copy(
        assertions = evidence.assertions.filter { it.ordinal in 1..evidence.attempted }.distinctBy { it.ordinal }.takeLast(64).map {
            it.copy(passed = it.passed && !it.blocked, expected = it.expected?.safe(), observed = it.observed?.safe())
        }
    )
}

private fun fixtureStateJson(state: AppAuditFixtureState?): JsonObject? = state?.safe()?.let {
    buildJsonObject {
        put("code", it.code?.name)
        put("dispatched", it.dispatched?.let(::JsonPrimitive) ?: JsonNull)
        put("admission", it.admission?.name)
        put("reservedCalls", it.reservedCalls?.let(::JsonPrimitive) ?: JsonNull)
        put("usedCalls", it.usedCalls?.let(::JsonPrimitive) ?: JsonNull)
        put("remainingCalls", it.remainingCalls?.let(::JsonPrimitive) ?: JsonNull)
        put("invocationCount", it.invocationCount?.let(::JsonPrimitive) ?: JsonNull)
        put("roundCount", it.roundCount?.let(::JsonPrimitive) ?: JsonNull)
        put("completedCount", it.completedCount?.let(::JsonPrimitive) ?: JsonNull)
        put("failedCount", it.failedCount?.let(::JsonPrimitive) ?: JsonNull)
    }
}
