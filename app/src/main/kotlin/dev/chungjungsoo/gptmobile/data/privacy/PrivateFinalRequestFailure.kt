package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import dev.chungjungsoo.gptmobile.data.diagnostics.PrivacyFinalRequestFailureEvidence
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The rejected wire was never dispatched; earlier attempts retain their own uncertainty. */
internal fun privateFinalRequestFailure(
    rejected: PrivateDispatchRejected,
    dispatch: RequestDispatchContext?,
    observedDispatch: Boolean
): ProviderEvent.Failed {
    val entirelyUnsent = dispatch?.provenEntirelyUnsent() ?: !observedDispatch
    val dispatched = when {
        entirelyUnsent -> false
        observedDispatch || dispatch?.receipts?.isNotEmpty() == true -> true
        else -> null
    }
    return ProviderEvent.Failed(
        "Final request validation stopped this private request. This request was not automatically retried.",
        errorCode = PrivacyFinalRequestFailureEvidence.CODE,
        taskOutcome = if (entirelyUnsent) "BLOCKED" else "PARTIAL",
        failureDetails = buildJsonObject {
            put("failed_stage", PrivacyFinalRequestFailureEvidence.STAGE)
            PrivacyFinalRequestFailureEvidence.outcome(rejected.receipt["outcome"])?.let {
                put(PrivacyFinalRequestFailureEvidence.FIELD, it)
            }
            dispatched?.let { put("dispatched", it) }
            put("remote_acceptance", if (entirelyUnsent) "NO" else "UNKNOWN")
            put("request_replay_allowed", false)
            put("logical_continuation_allowed", false)
            put("retryable", false)
        }
    )
}
