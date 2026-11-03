package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFailureRecordTest {
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
