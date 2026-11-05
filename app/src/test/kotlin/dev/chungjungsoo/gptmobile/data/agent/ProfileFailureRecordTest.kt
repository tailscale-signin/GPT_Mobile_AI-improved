package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFailureRecordTest {
    @Test fun typedCircuitFailureDoesNotInferDispatchFromCodeOrProviderText() {
        for (message in listOf("Service temporarily unavailable", "CIRCUIT_BREAKER_OPEN: dispatched=false; No request was dispatched")) {
            val failure = ProfileFailureRecord.fromProvider(message, "CIRCUIT_BREAKER_OPEN")
            assertEquals("CIRCUIT_BREAKER_OPEN", failure.category)
            assertEquals(null, failure.dispatched)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, failure.remoteAcceptance)
            assertEquals("stage_not_recorded", failure.phase)
        }
    }

    @Test fun actualCircuitRejectionRetainsAdmissionReceiptAndCooldownAfterPersistence() {
        val breaker = dev.chungjungsoo.gptmobile.data.network.error.CircuitBreaker(failureThreshold = 1, cooldownMs = 5000, timeProvider = { 1000L })
        breaker.onFailure(IllegalStateException("previous request failed"))
        var requestExecuted = false
        val error = try {
            breaker.execute { requestExecuted = true }
            throw AssertionError("The open circuit should reject the request")
        } catch (failure: dev.chungjungsoo.gptmobile.data.network.error.CircuitBreakerOpenException) {
            failure
        }
        assertFalse(requestExecuted)
        val before = System.currentTimeMillis()
        val failure = ProfileFailureRecord.fromProvider("Service temporarily unavailable", "CIRCUIT_BREAKER_OPEN", error.failureDetails())
        val after = System.currentTimeMillis()
        val restored = kotlinx.serialization.json.Json.decodeFromString<ProfileFailureRecord>(kotlinx.serialization.json.Json.encodeToString(ProfileFailureRecord.serializer(), failure))
        assertEquals("CIRCUIT_BREAKER_OPEN", restored.category)
        assertEquals(false, restored.dispatched)
        assertEquals(RemoteRequestAcceptance.NO, restored.remoteAcceptance)
        assertEquals("provider_admission", restored.phase)
        assertTrue(requireNotNull(restored.retryAfterMillis) in (before + 5000)..(after + 5000))
    }

    @Test fun gatewayUncertaintyOverridesConflictingCircuitRejectionReceipt() {
        val receipt = dev.chungjungsoo.gptmobile.data.network.error.CircuitBreakerOpenException(3000L).failureDetails()
        for (code in listOf("GATEWAY_OUTCOME_UNKNOWN", "REMOTE_OUTCOME_UNKNOWN")) {
            val failure = ProfileFailureRecord.fromProvider("$code: reconcile the prior request", "CIRCUIT_BREAKER_OPEN", receipt)
            assertEquals("GATEWAY_OUTCOME_UNKNOWN", failure.category)
            assertFalse(failure.retryAllowed)
            assertEquals(true, failure.dispatched)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, failure.remoteAcceptance)
            assertEquals("gateway_dispatch", failure.phase)
            assertEquals(null, failure.retryAfterMillis)
        }
    }

    @Test
    fun typedUnknownOutcomesCannotBecomeRetryableTimeouts() {
        for (code in listOf("GATEWAY_OUTCOME_UNKNOWN", "REMOTE_OUTCOME_UNKNOWN", "GATEWAY_TOOL_OUTCOME_UNKNOWN", "TOOL_RESULT_ID_MISMATCH")) {
            val failure = ProfileFailureRecord.fromProvider("Request timed out", code)
            assertFalse(code, failure.retryAllowed)
            assertTrue(failure.category in setOf("GATEWAY_OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN"))
        }
    }

    @Test
    fun typedParsingFailureDoesNotEncourageUnchangedRetry() {
        val failure = ProfileFailureRecord.fromProvider("Response unavailable", "PARSING_ERROR")
        assertEquals("SERIALIZATION_ERROR", failure.category)
        assertFalse(failure.retryAllowed)
    }

    @Test fun gatewayOutcomeUnknownRemainsNonRetryableAfterDurableClassification() {
        for (code in listOf("GATEWAY_OUTCOME_UNKNOWN", "REMOTE_OUTCOME_UNKNOWN")) {
            val failure = ProfileFailureRecord.classify("$code: connection timed out; HTTP 502; retryAfterMs=2000")
            val restored = kotlinx.serialization.json.Json.decodeFromString<ProfileFailureRecord>(kotlinx.serialization.json.Json.encodeToString(ProfileFailureRecord.serializer(), failure))
            assertEquals("GATEWAY_OUTCOME_UNKNOWN", restored.category)
            assertFalse(restored.retryAllowed)
            assertEquals(true, restored.dispatched)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, restored.remoteAcceptance)
            assertEquals("gateway_dispatch", restored.phase)
        }
    }

    @Test fun reviewRetryAdmissionAllowsRealRecoveryAndExplainsExhaustion() {
        assertEquals(null, ProfileFailureRecord.reviewRetryBlock(null))
        assertEquals(null, ProfileFailureRecord.reviewRetryBlock("Connection reset by peer"))
        assertEquals(null, ProfileFailureRecord.reviewRetryBlock("GITHUB_REVIEW_INVALID_VERDICT: One explicit retry can resume answer review"))
        assertTrue(ProfileFailureRecord.reviewRetryBlock("Saved review requires new evidence: GITHUB_REVIEW_INVALID_VERDICT")!!.contains("updated prompt"))
        assertTrue(ProfileFailureRecord.reviewRetryBlock("GITHUB_REVIEW_REQUIRED: the unchanged draft will not be reviewed again")!!.contains("retained"))
    }

    @Test fun deterministicReviewAndPrivacyFailuresRequireChangedInputs() {
        for (message in listOf("Saved review requires new evidence or a changed draft: GITHUB_REVIEW_INVALID_VERDICT", "POLICY_RESTRICTED: no matching privacy endpoint", "PERSONAL_DATA_LOCAL_ONLY")) {
            assertFalse(ProfileFailureRecord.classify(message).retryAllowed)
        }
        assertTrue(ProfileFailureRecord.classify("GITHUB_REVIEW_INVALID_VERDICT: The reviewer did not return a valid verdict after one format repair. One explicit retry can resume answer review").retryAllowed)
    }

    @Test fun missingSerializerRequiresAConfigurationChange() {
        val failure = ProfileFailureRecord.classify("Serializer for subclass 'TreeMap' is not found in the polymorphic scope of 'SortedMap'.")
        assertEquals("INCOMPATIBLE_CONFIGURATION", failure.category)
        assertFalse(failure.retryAllowed)
    }

    @Test fun ordinaryTransportInterruptionRemainsRetryable() {
        assertTrue(ProfileFailureRecord.classify("Connection reset by peer").retryAllowed)
    }

    @Test fun typedFailureKeepsItsActualPhaseAndDispatchStateAfterSerialization() {
        val details = kotlinx.serialization.json.buildJsonObject {
            put("failed_stage", kotlinx.serialization.json.JsonPrimitive("verification"))
            put("dispatched", kotlinx.serialization.json.JsonPrimitive(true))
        }
        val failure = ProfileFailureRecord.fromProvider("The objective still has pending evidence", "GITHUB_TASK_INCOMPLETE", details)
        val restored = kotlinx.serialization.json.Json.decodeFromString<ProfileFailureRecord>(kotlinx.serialization.json.Json.encodeToString(ProfileFailureRecord.serializer(), failure))
        assertEquals("TASK_INCOMPLETE", restored.category)
        assertEquals("verification", restored.phase)
        assertEquals(true, restored.dispatched)
    }

    @Test fun wrappedDnsFailureNeverBecomesAnUnexplainedIncompleteRun() {
        val failure = ProfileFailureRecord.classify("java.net.UnknownHostException: host cannot be resolved")
        assertEquals("DNS_RESOLUTION_FAILED", failure.category)
        assertEquals("dns_resolution", failure.phase)
        assertTrue(failure.retryAllowed)
    }

    @Test fun typedTransportStageAndPreflightRejectionAreRetained() {
        val details = kotlinx.serialization.json.buildJsonObject {
            put("failed_stage", kotlinx.serialization.json.JsonPrimitive("dns"))
            put("dispatched", kotlinx.serialization.json.JsonPrimitive(false))
        }
        val failure = ProfileFailureRecord.fromProvider("The endpoint is unavailable", "DNS_RESOLUTION_FAILED", details)
        assertEquals("DNS_RESOLUTION_FAILED", failure.category)
        assertEquals("dns", failure.phase)
        assertEquals(false, failure.dispatched)
    }

    @Test fun nativeConnectionPreflightKeepsItsKnownNoDispatchFact() {
        val details = kotlinx.serialization.json.buildJsonObject {
            put("failed_stage", kotlinx.serialization.json.JsonPrimitive("connection_preflight"))
            put("inference_dispatched", kotlinx.serialization.json.JsonPrimitive(false))
        }
        val failure = ProfileFailureRecord.fromProvider("Local endpoint unavailable", details = details)
        assertEquals("connection_preflight", failure.phase)
        assertEquals(false, failure.dispatched)
        assertEquals("PRECHECK_REJECTED", AgentRunTerminalDiagnostics.from("FAILED", failure).executionState)
    }

    @Test fun arbitraryStageTextIsNotPublishedAsStructuredDiagnostics() {
        val details = kotlinx.serialization.json.buildJsonObject { put("failed_stage", kotlinx.serialization.json.JsonPrimitive("https://private.example/path?secret=value")) }
        val failure = ProfileFailureRecord.fromProvider("connection reset", details = details)
        assertEquals("transport", failure.phase)
    }

    @Test fun ambiguousToolOutcomeAndTlsFailureRequireRecoveryBeforeRetry() {
        assertFalse(ProfileFailureRecord.fromProvider("Request timed out", "DISPATCH_OUTCOME_UNKNOWN").retryAllowed)
        assertFalse(ProfileFailureRecord.fromProvider("Secure connection rejected", "TLS_FAILURE").retryAllowed)
    }
}
