package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFailureClassificationTest {
    @Test fun privacyEligibilityFailureRemainsTerminalWithoutInferringDispatchFromItsExplanation() {
        val code = "PRIVACY_PROVIDER_INELIGIBLE"
        val misleading = "HTTP 503, no request was dispatched; retryAfterMs=2000"
        val optimistic = kotlinx.serialization.json.Json.parseToJsonElement("""{"retryable":true,"request_replay_allowed":true}""") as kotlinx.serialization.json.JsonObject
        val failure = ProfileFailureRecord.fromProvider(misleading, code, optimistic)
        val restored = kotlinx.serialization.json.Json.decodeFromString(ProfileFailureRecord.serializer(), kotlinx.serialization.json.Json.encodeToString(ProfileFailureRecord.serializer(), failure))
        assertEquals(code, restored.category)
        assertEquals("stage_not_recorded", restored.phase)
        assertFalse(restored.retryAllowed)
        assertEquals(false, restored.requestReplayAllowed)
        assertEquals(null, restored.dispatched)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, restored.remoteAcceptance)
        assertEquals(null, restored.retryAfterMillis)
        val legacy = ProfileFailureRecord.classify("$code: $misleading")
        assertEquals(code, legacy.category)
        assertFalse(legacy.retryAllowed)
        assertEquals(false, legacy.requestReplayAllowed)
        assertEquals(null, legacy.dispatched)
        assertEquals(null, legacy.retryAfterMillis)
    }

    @Test fun privacyEligibilityKeepsObservedAdmissionOrGenerationWithoutTurningDispatchIntoAcceptance() {
        for ((stage, dispatched, acceptance) in listOf(
            Triple("provider_admission", false, "NO"),
            Triple("provider_generation", true, "UNKNOWN")
        )) {
            val details = kotlinx.serialization.json.buildJsonObject {
                put("failed_stage", kotlinx.serialization.json.JsonPrimitive(stage))
                put("dispatched", kotlinx.serialization.json.JsonPrimitive(dispatched))
                put("remote_acceptance", kotlinx.serialization.json.JsonPrimitive(acceptance))
                put("retryable", kotlinx.serialization.json.JsonPrimitive(true))
                put("request_replay_allowed", kotlinx.serialization.json.JsonPrimitive(true))
            }
            val failure = ProfileFailureRecord.fromProvider("Privacy check unavailable; no request was dispatched", "PRIVACY_PROVIDER_INELIGIBLE", details)
            assertEquals("PRIVACY_PROVIDER_INELIGIBLE", failure.category)
            assertEquals(stage, failure.phase)
            assertEquals(dispatched, failure.dispatched)
            assertEquals(RemoteRequestAcceptance.valueOf(acceptance), failure.remoteAcceptance)
            assertFalse(failure.retryAllowed)
            assertEquals(false, failure.requestReplayAllowed)
        }
    }

    @Test fun priorUnknownMutationOutcomesOutrankALaterPrivacyAdmissionRejection() {
        val details = kotlinx.serialization.json.Json.parseToJsonElement("""{"failed_stage":"provider_admission","dispatched":false,"remote_acceptance":"NO"}""") as kotlinx.serialization.json.JsonObject
        for (unknown in listOf("TOOL_OUTCOME_UNKNOWN", "RECONCILIATION_REQUIRED", "GATEWAY_OUTCOME_UNKNOWN")) {
            val failure = ProfileFailureRecord.fromProvider("$unknown: prior remote operation needs reconciliation", "PRIVACY_PROVIDER_INELIGIBLE", details)
            assertEquals(if (unknown == "GATEWAY_OUTCOME_UNKNOWN") unknown else "TOOL_OUTCOME_UNKNOWN", failure.category)
            assertFalse(failure.retryAllowed)
            assertEquals(false, failure.requestReplayAllowed)
            assertTrue(failure.dispatched != false)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, failure.remoteAcceptance)
        }
    }
}
