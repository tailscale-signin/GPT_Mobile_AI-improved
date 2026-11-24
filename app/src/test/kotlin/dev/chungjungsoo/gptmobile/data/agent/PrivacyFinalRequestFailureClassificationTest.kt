package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFinalRequestFailureClassificationTest {
    private val code = "PRIVACY_FINAL_REQUEST_REJECTED"

    @Test fun finalRequestRefusalDoesNotInferProviderOutageDispatchOrCooldownFromProse() {
        val message = "HTTP 503; BILLING_UNAVAILABLE; no request was dispatched; retryAfterMs=2000"
        val optimistic = buildJsonObject {
            put("retryable", true)
            put("request_replay_allowed", true)
            put("dispatched", "false")
        }
        for (failure in listOf(ProfileFailureRecord.fromProvider(message, code, optimistic), ProfileFailureRecord.classify("$code: $message"))) {
            assertEquals(code, failure.category)
            assertFalse(failure.retryAllowed)
            assertEquals(false, failure.requestReplayAllowed)
            assertNull(failure.retryAfterMillis)
            assertNull(failure.dispatched)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, failure.remoteAcceptance)
        }
    }

    @Test fun actualFinalValidationFactsKeepTheLocalRefusalTerminal() {
        val failure = ProfileFailureRecord.fromProvider("Fictional local refusal", code, localDetails())
        assertEquals(code, failure.category)
        assertEquals("request_validation", failure.phase)
        assertEquals(false, failure.dispatched)
        assertEquals(RemoteRequestAcceptance.NO, failure.remoteAcceptance)
        assertFalse(failure.retryAllowed)
        assertEquals(false, failure.requestReplayAllowed)
        assertNull(failure.retryAfterMillis)
    }

    @Test fun priorUnknownOperationsStillOutrankALaterFinalValidationRefusal() {
        for (unknown in listOf("TOOL_OUTCOME_UNKNOWN", "RECONCILIATION_REQUIRED", "GATEWAY_OUTCOME_UNKNOWN")) {
            val failure = ProfileFailureRecord.fromProvider("$unknown: retained earlier operation", code, localDetails())
            assertEquals(if (unknown == "GATEWAY_OUTCOME_UNKNOWN") unknown else "TOOL_OUTCOME_UNKNOWN", failure.category)
            assertTrue(failure.dispatched != false)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, failure.remoteAcceptance)
            assertFalse(failure.retryAllowed)
            assertEquals(false, failure.requestReplayAllowed)
        }
    }

    @Test fun structuredEarlierEvidenceDominatesConflictingLocalNegativeFields() {
        val cases = listOf(
            Triple(
                buildJsonObject {
                    put("remote_acceptance", "YES")
                    put("dispatch_state", "PRECHECK_REJECTED")
                },
                true,
                RemoteRequestAcceptance.YES
            ),
            Triple(
                buildJsonObject {
                    put("dispatched", true)
                    put("remote_acceptance", "UNKNOWN")
                    put("dispatch_state", "NOT_DISPATCHED")
                },
                true,
                RemoteRequestAcceptance.UNKNOWN
            ),
            Triple(
                buildJsonObject {
                    put("requires_reconciliation", true)
                    put("dispatch_state", "PRECHECK_REJECTED")
                },
                null,
                RemoteRequestAcceptance.UNKNOWN
            ),
            Triple(buildJsonObject { put("dispatch_state", "UNKNOWN") }, null, RemoteRequestAcceptance.UNKNOWN),
            Triple(buildJsonObject { put("dispatch_state", "UNKNOWN_OUTCOME") }, null, RemoteRequestAcceptance.UNKNOWN),
            Triple(buildJsonObject { put("command_outcome_unknown", true) }, null, RemoteRequestAcceptance.UNKNOWN),
            Triple(
                buildJsonObject {
                    put("remote_acceptance", "UNKNOWN")
                    put("dispatch_state", "PRECHECK_REJECTED")
                },
                null,
                RemoteRequestAcceptance.UNKNOWN
            ),
            Triple(
                buildJsonObject {
                    put("dispatched", true)
                    put("dispatch_state", "PRECHECK_REJECTED")
                },
                true,
                RemoteRequestAcceptance.NO
            )
        )
        for ((additional, dispatched, acceptance) in cases) {
            val failure = ProfileFailureRecord.fromProvider("Fictional local refusal", code, JsonObject(localDetails() + additional))
            assertEquals(dispatched, failure.dispatched)
            assertEquals(acceptance, failure.remoteAcceptance)
            assertFalse(failure.retryAllowed)
            assertEquals(false, failure.requestReplayAllowed)
        }
    }

    @Test fun stringTypedPositiveOrNegativeBooleansAreNotDispatchEvidence() {
        for (value in listOf("true", "false")) {
            val failure = ProfileFailureRecord.fromProvider(
                "Fictional local refusal",
                code,
                buildJsonObject {
                    put("dispatched", JsonPrimitive(value))
                    put("requires_reconciliation", JsonPrimitive(value))
                    put("request_replay_allowed", JsonPrimitive(value))
                }
            )
            assertNull(failure.dispatched)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, failure.remoteAcceptance)
            assertFalse(failure.retryAllowed)
            assertEquals(false, failure.requestReplayAllowed)
        }
    }

    private fun localDetails(): JsonObject = buildJsonObject {
        put("failed_stage", "request_validation")
        put("privacy_dispatch_outcome", "KNOWN_VALUE_REINTRODUCED")
        put("dispatched", false)
        put("remote_acceptance", "NO")
        put("dispatch_state", "PRECHECK_REJECTED")
        put("request_replay_allowed", false)
        put("logical_continuation_allowed", false)
    }
}
