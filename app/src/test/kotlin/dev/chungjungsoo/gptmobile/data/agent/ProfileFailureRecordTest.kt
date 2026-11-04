package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFailureRecordTest {
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
}
