package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.network.ConnectionHealth
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpFailurePresentationTest {
    @Test fun currentLocalValidationCannotInheritStaleOAuthRepairGuidance() {
        val error = McpLocalValidationException(McpLocalValidationRule.CREDENTIAL_FORMAT_INVALID)
        val prior = McpConnectionHealth().copy(
            state = McpConnectionHealthState.AUTHENTICATION_REQUIRED,
            lastValidationRule = "CREDENTIAL_REAUTHORIZATION_REQUIRED"
        )
        val result = mcpFailurePresentation(error, prior)
        assertFalse(result.authenticationRequired)
        assertEquals("CREDENTIAL_FORMAT_INVALID", result.validationRule)
        assertTrue(result.message.contains("Correct the indicated connection setting"))
        assertFalse(result.message.contains("Sign in again"))
    }

    @Test fun localOAuthReauthorizationOffersSignInWithoutInventingAnHttpFailure() {
        val error = McpLocalValidationException(McpLocalValidationRule.CREDENTIAL_REAUTHORIZATION_REQUIRED)
        val result = mcpFailurePresentation(error, McpConnectionHealth())
        assertTrue(result.authenticationRequired)
        assertEquals("LOCAL_VALIDATION", result.outcome)
        assertEquals("local_validation", result.phase)
        assertEquals("NOT_DISPATCHED", result.dispatchEvidence)
        assertEquals(null, result.httpStatus)
        assertEquals(false, result.retryable)
        assertTrue(result.message.contains("Sign in again"))
        assertTrue(result.message.contains("retry alone cannot repair"))
    }

    @Test fun localRejectionCannotInheritAnEarlierServerFailureEvenWhenItsCodeMatches() {
        val current = McpLocalValidationException(McpLocalValidationRule.CREDENTIAL_FORMAT_INVALID)
        val previous = paused(current.diagnosticCode, "Earlier unrelated server detail").copy(
            lastHttpStatus = 403,
            lastValidationRule = "EARLIER_RULE",
            lastValidationField = "old_field",
            lastDispatchEvidence = "CONFIRMED",
            failureConfigurationRevision = "a".repeat(64)
        )
        val result = mcpFailurePresentation(current, previous)
        assertEquals("LOCAL_VALIDATION", result.outcome)
        assertEquals("CREDENTIAL_FORMAT_INVALID", result.validationRule)
        assertEquals("credential", result.validationField)
        assertEquals("local_validation", result.phase)
        assertEquals("NOT_DISPATCHED", result.dispatchEvidence)
        assertEquals(null, result.httpStatus)
        assertEquals(null, result.observedAtEpochMs)
        assertEquals(null, result.configurationRevision)
        assertFalse(result.message.contains("Earlier"))
    }

    @Test fun aCachedEndpointGateRetainsHistoricalStatusWithoutClaimingANew404() {
        val gate = McpEndpointConfigurationException()
        val unknown = mcpFailurePresentation(gate, McpConnectionHealth())
        assertEquals(null, unknown.httpStatus)
        assertEquals("NOT_DISPATCHED", unknown.dispatchEvidence)
        val retained = mcpFailurePresentation(
            gate,
            paused("MCP_ENDPOINT_CONFIGURATION", "Earlier initialization failure").copy(lastHttpStatus = 404, failureConfigurationRevision = "a".repeat(64))
        )
        assertEquals(404, retained.httpStatus)
        assertEquals("NOT_DISPATCHED", retained.dispatchEvidence)
        assertEquals(1_000L, retained.observedAtEpochMs)
        assertEquals("a".repeat(64), retained.configurationRevision)
    }

    @Test fun cachedAuthenticationGateRetainsOriginal401WithoutClaimingANewRequest() {
        val health = paused("MCP_AUTHENTICATION_REQUIRED", "Earlier credential rejection").copy(
            lastHttpStatus = 401,
            lastDispatchEvidence = "CONFIRMED",
            failureConfigurationRevision = "a".repeat(64)
        )
        val retained = mcpFailurePresentation(McpAuthenticationRequiredException(), health)
        assertEquals("AUTHENTICATION_REQUIRED", retained.outcome)
        assertEquals(401, retained.httpStatus)
        assertEquals("NOT_DISPATCHED", retained.dispatchEvidence)
        assertEquals(1_000L, retained.observedAtEpochMs)
        assertEquals("a".repeat(64), retained.configurationRevision)
        assertEquals(false, retained.retryable)
    }

    @Test fun recoveryRetainsTheOriginalValidationRuleFieldTimeAndConfiguration() {
        val health = paused("MCP_DISCOVERY_SCHEMA_INVALID", "supportedVersions must be nonempty").copy(
            lastHttpStatus = 200,
            failedStage = "discovery",
            lastValidationRule = "PROTOCOL_VERSIONS_NONEMPTY_REQUIRED",
            lastValidationField = "supportedVersions",
            lastDispatchEvidence = "CONFIRMED",
            lastRetryDecision = "AFTER_CONFIGURATION_CHANGE",
            failureConfigurationRevision = "b".repeat(64)
        )
        val retained = mcpFailurePresentation(McpBackoffException("connection", Long.MAX_VALUE), health)
        assertEquals(200, retained.httpStatus)
        assertEquals("NOT_DISPATCHED", retained.dispatchEvidence)
        assertEquals("PROTOCOL_VERSIONS_NONEMPTY_REQUIRED", retained.validationRule)
        assertEquals("supportedVersions", retained.validationField)
        assertEquals("AFTER_CONFIGURATION_CHANGE", retained.retryDecision)
        assertEquals(1_000L, retained.observedAtEpochMs)
        assertEquals("b".repeat(64), retained.configurationRevision)

        val current = McpProtocolConfigurationException("MCP_DISCOVERY_SCHEMA_INVALID", "New protocol rejection", 400, "REQUESTED_PROTOCOL_MATCH_REQUIRED", "error.data.requested")
        val fresh = mcpFailurePresentation(current, health)
        assertEquals(400, fresh.httpStatus)
        assertEquals("REQUESTED_PROTOCOL_MATCH_REQUIRED", fresh.validationRule)
        assertEquals(null, fresh.observedAtEpochMs)
        assertEquals(null, fresh.configurationRevision)
        assertFalse(fresh.message.contains("must be nonempty"))
    }

    @Test fun dnsFailureIsDistinctFromAuthenticationAndOnlyNamesTheAffectedConnection() {
        val result = mcpFailurePresentation(java.net.UnknownHostException("Server hostname could not be resolved"), McpConnectionHealth())
        assertEquals("DNS_FAILURE", result.outcome)
        assertEquals("DNS_FAILED", result.errorCode)
        assertEquals(true, result.retryable)
        assertFalse(result.authenticationRequired)
        assertTrue(result.message.contains("DNS and VPN"))
        assertTrue(result.message.contains("Other connections"))
    }

    @Test fun permanentFailuresRetainTheirCodeAndOriginalDetailThroughARepeatedBackoff() {
        val failures = listOf(
            Triple("TLS_FAILURE", "TLS_FAILURE", "TLS authentication handshake failed"),
            Triple("MCP_DISCOVERY_SCHEMA_INVALID", "PROTOCOL_CONFIGURATION", "supportedVersions must be nonempty"),
            Triple("MCP_AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "The saved credential was rejected"),
            Triple("MCP_ENDPOINT_CONFIGURATION", "ENDPOINT_CONFIGURATION", "The MCP endpoint returned HTTP 404")
        )
        for ((code, outcome, detail) in failures) {
            val health = paused(code, detail)
            val failure = mcpFailurePresentation(IOException("SDK wrapper", McpBackoffException("connection", Long.MAX_VALUE)), health)
            assertEquals(outcome, failure.outcome)
            assertEquals(code, failure.errorCode)
            assertEquals(false, failure.retryable)
            assertFalse(failure.waitingForRecovery)
            assertEquals(outcome == "AUTHENTICATION_REQUIRED", failure.authenticationRequired)
            assertTrue(failure.message.contains("reconnect"))
            assertTrue(failure.message.contains(detail))
            assertTrue(failure.message.contains(code))
            assertFalse(failure.message.contains("backoff", ignoreCase = true))
            assertFalse(failure.message.contains(Long.MAX_VALUE.toString()))
            assertEquals("outcome=$outcome · errorCode=$code · retryable=false", failure.diagnosticSummary)
        }
    }

    @Test fun finiteNetworkBackoffRemainsWaitableAndKeepsItsOriginalFailure() {
        val health = paused("DNS_FAILED", "The MCP hostname could not be resolved").copy(
            state = McpConnectionHealthState.DEGRADED,
            nextRetryAtMs = 30_000,
            connectionHealth = ConnectionHealth.DNS_FAILED
        )
        val failure = mcpFailurePresentation(McpBackoffException("connection", 30_000), health)
        assertEquals("WAITING_RECOVERY", failure.outcome)
        assertEquals(true, failure.retryable)
        assertTrue(failure.waitingForRecovery)
        assertTrue(failure.message.contains("backoff to expire"))
        assertTrue(failure.message.contains(requireNotNull(health.lastError)))
        assertEquals("outcome=WAITING_RECOVERY · errorCode=DNS_FAILED · retryable=true", failure.diagnosticSummary)
    }

    @Test fun anUnknownPermanentGateNeverInventsAnExpiryOrLogsAnUnsafeCode() {
        val health = paused("UNSAFE_CODE\nAuthorization: Bearer private-value", "The saved connection needs review")
        val failure = mcpFailurePresentation(McpBackoffException("connection", Long.MAX_VALUE), health)
        assertEquals("RECONNECT_REQUIRED", failure.outcome)
        assertEquals(null, failure.errorCode)
        assertEquals(false, failure.retryable)
        assertFalse(failure.waitingForRecovery)
        assertTrue(failure.message.contains("reconnect"))
        assertFalse(failure.message.contains(Long.MAX_VALUE.toString()))
        assertFalse(failure.diagnosticSummary.contains("private-value"))
        assertFalse(failure.diagnosticSummary.contains('\n'))
        assertEquals("outcome=RECONNECT_REQUIRED · errorCode=unknown · retryable=false", failure.diagnosticSummary)
    }

    @Test fun directProtocolAndAuthenticationFailuresWorkBeforeAHealthReceiptExists() {
        val protocol = McpProtocolConfigurationException("MCP_RESPONSE_ID_MISMATCH", "The response ID did not match the request")
        val failure = mcpFailurePresentation(IOException("SDK wrapper", protocol), McpConnectionHealth())
        assertEquals("PROTOCOL_CONFIGURATION", failure.outcome)
        assertEquals("MCP_RESPONSE_ID_MISMATCH", failure.errorCode)
        assertEquals(false, failure.retryable)
        assertTrue(failure.message.contains(requireNotNull(protocol.message)))

        val newerFailure = mcpFailurePresentation(IOException("SDK wrapper", protocol), paused("TLS_FAILURE", "Earlier TLS detail"))
        assertEquals("MCP_RESPONSE_ID_MISMATCH", newerFailure.errorCode)
        assertTrue(newerFailure.message.contains(requireNotNull(protocol.message)))
        assertFalse(newerFailure.message.contains("Earlier TLS detail"))

        val denied = mcpFailurePresentation(IOException("SDK wrapper", ModernMcpError(401, null, "Credential rejected")), McpConnectionHealth())
        assertTrue(denied.authenticationRequired)
        assertEquals("MCP_AUTHENTICATION_REQUIRED", denied.errorCode)
        assertEquals(false, denied.retryable)
        assertTrue(denied.message.contains("credentials"))
    }

    @Test fun originalDetailsAreRedactedAndBoundedWithoutEnteringTheSummary() {
        val raw = "supportedVersions must be nonempty\nAuthorization: Bearer private-credential\nhttps://user:private-password@server.example/mcp?api_key=private-query"
        val failure = mcpFailurePresentation(McpBackoffException("connection", Long.MAX_VALUE), paused("MCP_DISCOVERY_SCHEMA_INVALID", raw))
        assertTrue(failure.message.contains("supportedVersions must be nonempty"))
        assertFalse(failure.message.contains("private-credential"))
        assertFalse(failure.message.contains("private-password"))
        assertFalse(failure.message.contains("private-query"))
        assertFalse(failure.diagnosticSummary.contains("supportedVersions"))
        assertFalse(failure.diagnosticSummary.contains("server.example"))

        val oversized = mcpFailurePresentation(
            McpBackoffException("connection", Long.MAX_VALUE),
            paused("MCP_DISCOVERY_SCHEMA_INVALID", "Authorization: Bearer " + "private-fragment".repeat(2000))
        )
        assertTrue(oversized.message.length < 1500)
        assertFalse(oversized.message.contains("private-fragment"))
    }

    @Test fun connectedHealthCannotExposeAnEarlierPermanentFailure() {
        val health = paused("TLS_FAILURE", "Earlier TLS detail").copy(state = McpConnectionHealthState.CONNECTED)
        val failure = mcpFailurePresentation(IOException("Current network failure"), health)
        assertEquals("NETWORK_UNAVAILABLE", failure.outcome)
        assertEquals(null, failure.retryable)
        assertEquals(null, failure.errorCode)
        assertTrue(failure.message.contains("Current network failure"))
        assertFalse(failure.message.contains("Earlier TLS detail"))
    }

    @Test fun wrappedCancellationNeverBecomesAConnectionFailure() {
        val cancellation = CancellationException("Screen closed")
        val observed = assertThrows(CancellationException::class.java) {
            mcpFailurePresentation(IOException("SDK wrapper", cancellation), paused("TLS_FAILURE", "Earlier TLS detail"))
        }
        assertSame(cancellation, observed)
    }

    private fun paused(code: String, detail: String) = McpConnectionHealth(
        state = if (code == "MCP_AUTHENTICATION_REQUIRED") McpConnectionHealthState.AUTHENTICATION_REQUIRED else McpConnectionHealthState.UNREACHABLE,
        lastFailureAtMs = 1_000,
        nextRetryAtMs = Long.MAX_VALUE,
        lastErrorCode = code,
        lastError = detail
    )
}
