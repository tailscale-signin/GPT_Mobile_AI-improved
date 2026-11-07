package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.network.error.ErrorCategory
import dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification
import dev.chungjungsoo.gptmobile.data.network.error.ErrorTemplates
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubFailureRecoveryClassificationTest {
    @Test fun deterministicGitHubFailuresCannotBecomeRetryableTransportFailures() {
        for (code in listOf("AUTH_REQUIRED", "PERMISSION_DENIED", "PERMISSION_REVOKED", "INVALID_ARGUMENTS", "GITHUB_VALIDATION_FAILED", "GITHUB_BRANCH_RULES_BLOCKED", "GITHUB_ENDPOINT_PERMISSION_REQUIRED", "GRAPH_CONFIGURATION_REQUIRED")) {
            val profile = ProfileFailureRecord.fromProvider("Connection timed out; HTTP 503", code)
            assertFalse(code, profile.retryAllowed)
            val classified = ErrorClassification.classify(IllegalStateException("$code: Connection timed out; HTTP 503"))
            assertEquals(code, classified.errorCode)
            assertFalse(ErrorTemplates.getTemplateFor(classified).autoRetry)
            assertTrue(classified.userMessage.length > 40)
        }
    }

    @Test fun typedTerminalFailuresCannotBecomeRetryableFromIncidentalGitHubText() {
        for (code in listOf("CLIENT_CANCELLED", "AUTHENTICATION_FAILED", "ACCESS_FORBIDDEN", "SERIALIZATION_ERROR", "PARSING_ERROR", "TLS_FAILURE")) {
            for (incidental in listOf("GITHUB_TRANSIENT_SERVER", "GITHUB_PRIMARY_RATE_LIMITED")) {
                val failure = ProfileFailureRecord.fromProvider("A previous tool returned $incidental; retryAfterMs=1000", code)
                assertEquals(code, ProfileFailureRecord.classify(code).category, failure.category)
                assertFalse("$code must not become retryable because of $incidental", failure.retryAllowed)
                assertTrue("Retain the recorded timing without granting retry", failure.retryAfterMillis != null)
            }
        }
    }

    @Test fun uncertainOperationStillOutranksAnAuthoritativeLocalFailure() {
        for (unknown in listOf("TOOL_OUTCOME_UNKNOWN", "GATEWAY_OUTCOME_UNKNOWN")) {
            val failure = ProfileFailureRecord.fromProvider("$unknown: a prior response was lost", "CLIENT_CANCELLED")
            assertEquals(unknown, failure.category)
            assertFalse(failure.retryAllowed)
        }
    }

    @Test fun localRejectionCannotProveAnUncertainToolWasNotDispatched() {
        val localRejection = buildJsonObject {
            put("failed_stage", "provider_admission")
            put("dispatched", false)
        }
        val failure = ProfileFailureRecord.fromProvider("TOOL_OUTCOME_UNKNOWN: prior write response was lost", "AUTH_REQUIRED", localRejection)
        assertEquals("TOOL_OUTCOME_UNKNOWN", failure.category)
        assertEquals(null, failure.dispatched)
        assertEquals("tool_dispatch", failure.phase)
        assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.UNKNOWN, failure.remoteAcceptance)
        assertFalse(failure.retryAllowed)
        val confirmedDispatch = ProfileFailureRecord.fromProvider("TOOL_OUTCOME_UNKNOWN", details = buildJsonObject { put("dispatched", true) })
        assertEquals(true, confirmedDispatch.dispatched)
        assertFalse(confirmedDispatch.retryAllowed)
    }

    @Test fun permissionAndValidationRemainDistinctAndRateLimitsDoNotRevokeAccess() {
        assertEquals(ErrorCategory.PERMISSION_REQUIRED, ErrorClassification.classify(IllegalStateException("PERMISSION_DENIED")).category)
        assertEquals(ErrorCategory.REQUEST_REJECTED, ErrorClassification.classify(IllegalStateException("GITHUB_VALIDATION_FAILED")).category)
        for (code in listOf("GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED")) {
            val error = ErrorClassification.classify(IllegalStateException(code))
            assertEquals(ErrorCategory.RATE_LIMIT_EXCEEDED, error.category)
            assertTrue(error.userMessage.contains("does not revoke access"))
            assertFalse("Only the read owner can decide safe retry", ErrorTemplates.getTemplateFor(error).autoRetry)
        }
    }

    @Test fun uncertainWriteHasPriorityOverLaterPermissionOrRateFailures() {
        fun failure(code: String, dispatched: Boolean) = buildJsonObject {
            put("trusted", true)
            put("success", false)
            put("error_code", code)
            put("dispatched", dispatched)
        }
        val advice = GitHubVerificationRecovery.advice(listOf(failure("PERMISSION_DENIED", false), failure("GATEWAY_OUTCOME_UNKNOWN", true)))
        assertTrue(advice.startsWith("Reconcile the exact uncertain remote operation"))
        val classification = ErrorClassification.classify(IllegalStateException("TOOL_OUTCOME_UNKNOWN: PERMISSION_DENIED; timeout"))
        assertEquals(ErrorCategory.REMOTE_OUTCOME_UNKNOWN, classification.category)
        assertFalse(classification.isRetryable)
    }

    @Test fun summariesGiveCodeSpecificRecoveryWithoutRawFailurePayloads() {
        val report = GitHubFailureSummary.render(
            mapOf(
                "permission" to buildJsonObject {
                    put("trusted", true)
                    put("success", false)
                    put("error_code", "PERMISSION_DENIED")
                    put("result", "sensitive raw payload")
                }
            )
        )
        assertTrue(report.contains("local permissions"))
        assertFalse(report.contains("sensitive raw payload"))
    }
}
