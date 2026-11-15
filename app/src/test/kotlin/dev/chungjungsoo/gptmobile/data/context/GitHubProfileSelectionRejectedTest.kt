package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.network.error.ErrorCategory
import dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification
import dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubProfileSelectionRejectedTest {
    private fun rejection() = GitHubProfileSelectionRejected(
        profileMatches = true,
        registeredProfilePresent = true,
        modelMatches = false,
        requestedProfileHash = researchHash("private-requested-profile"),
        requestedModelHash = researchHash("private-requested-model"),
        selectedProfileHash = researchHash("private-requested-profile"),
        registeredModelHash = researchHash("private-registered-model")
    )

    @Test fun typedSelectionFailureKeepsItsCanonicalAdmissionAndNeverGrantsReplay() {
        val failure = rejection()
        val classified = ErrorClassification.classify(failure)
        assertEquals("GITHUB_PROFILE_LOCKED", classified.errorCode)
        assertEquals(ErrorCategory.REQUEST_REJECTED, classified.category)
        assertFalse(classified.isRetryable)
        assertEquals(0L, classified.suggestedBackoffMs)
        assertSame(failure, classified.originalThrowable)
        val detail = requireNotNull(providerExceptionDetails(failure, partialOutput = false))
        assertEquals("GITHUB_PROFILE_LOCKED", detail.code)
        assertEquals("local_admission", detail.type)
        assertEquals(false, detail.retryable)
        val fields = requireNotNull(detail.failureDetails)
        assertEquals(JsonPrimitive("provider_admission"), fields["failed_stage"])
        assertEquals(JsonPrimitive("ADMISSION"), fields["provider_failure_stage"])
        assertEquals(JsonPrimitive("APPLICATION"), fields["failure_origin"])
        assertEquals(JsonPrimitive(false), fields["request_replay_allowed"])
        assertEquals(JsonPrimitive(false), fields["retryable"])
        assertEquals(JsonPrimitive("RETRY_AFTER_CONFIGURATION_CHANGE"), fields["recovery_action"])
        failure.failureFields().forEach { (key, value) -> assertEquals(key, value, fields[key]) }
        for (key in listOf("dispatched", "dispatch_state", "attempt_dispatch_state", "remote_acceptance", "partial_output_preserved", "invocation_id", "call_id", "causal_id", "http_status", "output_tokens", "reasoning_tokens")) assertFalse(key, key in fields)
        for (privateValue in listOf("private-requested-profile", "private-requested-model", "private-registered-model")) {
            assertFalse(detail.toString().contains(privateValue))
            assertFalse(classified.userMessage.contains(privateValue))
        }
    }

    @Test fun selectionRejectionCannotInferRunDispatchFromEitherPartialOutputFlag() {
        val failure = rejection()
        val projections = listOf(false, true).map { partialOutput ->
            val detail = requireNotNull(providerExceptionDetails(failure, partialOutput = partialOutput))
            assertEquals("GITHUB_PROFILE_LOCKED", detail.code)
            assertEquals(false, detail.retryable)
            val fields = requireNotNull(detail.failureDetails)
            for (key in listOf("dispatched", "dispatch_state", "attempt_dispatch_state", "remote_acceptance", "partial_output_preserved", "invocation_id", "causal_id", "logical_continuation_allowed")) assertFalse("partialOutput=$partialOutput must not create $key", key in fields)
            assertEquals(JsonPrimitive(false), fields["request_replay_allowed"])
            assertEquals(JsonPrimitive(false), fields["retryable"])
            assertEquals(JsonPrimitive("provider_admission"), fields["failed_stage"])
            failure.failureFields().forEach { (key, value) -> assertEquals(key, value, fields[key]) }
            fields
        }
        assertEquals(projections.first(), projections.last())
    }

    @Test fun absentRegisteredProfileLeavesBothModelObservationsUnavailable() {
        val failure = GitHubProfileSelectionRejected(true, false, null, researchHash("requested"), researchHash("requested-model"), researchHash("requested"), null)
        val fields = requireNotNull(providerExceptionDetails(failure, partialOutput = false)?.failureDetails)
        assertEquals(JsonPrimitive(true), fields["github_selection_profile_match"])
        assertEquals(JsonPrimitive(false), fields["github_selection_registered_profile"])
        assertFalse("github_selection_model_match" in fields)
        assertFalse("github_selection_registered_model_hash" in fields)
        assertEquals(JsonPrimitive(researchHash("requested-model")), fields["model_hash"])
    }

    @Test fun anActualTypedCauseKeepsItsCodeWithoutUsingWrapperTextAsSelectionEvidence() {
        val failure = rejection()
        val wrapper = IllegalStateException("A wrapper mentioned a transport timeout", failure)
        val classified = ErrorClassification.classify(wrapper)
        assertEquals("GITHUB_PROFILE_LOCKED", classified.errorCode)
        assertEquals(ErrorCategory.REQUEST_REJECTED, classified.category)
        assertFalse(classified.isRetryable)
        assertEquals(failure.message, classified.userMessage)
        assertSame(wrapper, classified.originalThrowable)
    }

    @Test fun aLookalikeMessageCannotCreateTypedSelectionEvidence() {
        val lookalike = IllegalStateException("GITHUB_PROFILE_LOCKED: private text is not a measured profile comparison")
        for (error in listOf(lookalike, IllegalStateException("An unrelated wrapper", lookalike))) {
            val classified = ErrorClassification.classify(error)
            assertEquals(ErrorCategory.UNKNOWN, classified.category)
            assertEquals("UNKNOWN", classified.errorCode)
            assertNull(providerExceptionDetails(error, partialOutput = false))
        }
    }

    @Test fun cancellationKeepsPrecedenceOverAnEarlierTypedSelectionCause() {
        val cancelled = CancellationException("Cancelled by the owning coroutine").also { it.initCause(rejection()) }
        val classified = ErrorClassification.classify(cancelled)
        assertEquals(ErrorCategory.CLIENT_CANCELLED, classified.category)
        assertFalse(classified.isRetryable)
        assertSame(cancelled, classified.originalThrowable)
        assertNull(providerExceptionDetails(cancelled, partialOutput = true))
    }

    @Test fun anUnknownRemoteOutcomeCannotBeDowngradedToTheLocalSelectionGuard() {
        val uncertain = IllegalStateException("GATEWAY_OUTCOME_UNKNOWN: reconcile the saved operation", rejection())
        val classified = ErrorClassification.classify(uncertain)
        assertEquals(ErrorCategory.REMOTE_OUTCOME_UNKNOWN, classified.category)
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", classified.errorCode)
        assertFalse(classified.isRetryable)
        assertSame(uncertain, classified.originalThrowable)
    }

    @Test fun unrelatedPolicyAndNetworkFailuresKeepTheirEstablishedClassification() {
        val policy = DispatchPolicyChangedException(listOf("profile.model"))
        val policyFailure = ErrorClassification.classify(policy)
        assertEquals(ErrorCategory.POLICY_STALE, policyFailure.category)
        assertFalse(policyFailure.isRetryable)
        assertSame(policy, policyFailure.originalThrowable)
        val timeout = SocketTimeoutException("The connection timed out")
        val networkFailure = ErrorClassification.classify(timeout)
        assertEquals(ErrorCategory.NETWORK_TIMEOUT, networkFailure.category)
        assertTrue(networkFailure.isRetryable)
        assertSame(timeout, networkFailure.originalThrowable)
    }
}
