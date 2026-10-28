package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedFallbackEligibilityTest {
    @Test fun localFallbackHandlesBudgetAndCapacityWithoutSendingAStaleRequest() {
        assertTrue(CombinedFallbackEligibility.allows("DISPATCH_POLICY_CHANGED: changed fields=budget,spend. Resume final synthesis."))
        assertTrue(CombinedFallbackEligibility.allows("COMBINED_CONTEXT_CAPACITY: complete sources exceed context"))
        assertTrue(CombinedFallbackEligibility.allows("COMBINED_OUTPUT_ALLOWANCE_CHANGED: no request sent"))
    }

    @Test fun revocationCancellationPrivateEvidenceAndAmbiguousFailuresNeverAuthorizeFallback() {
        for (reason in listOf("DISPATCH_POLICY_CHANGED: changed fields=tools,budget.", "DISPATCH_POLICY_CHANGED: changed fields=profile.token.", "DISPATCH_POLICY_CHANGED: Review settings", "PROVIDER_WAIT_PERMISSION_CHANGED", "PERSONAL_DATA_PERMISSION_REQUIRED", "COMBINED_TURN_CANCELLED", "COMBINED_SNAPSHOT_INTEGRITY_FAILED", "Unknown provider error")) {
            assertFalse(reason, CombinedFallbackEligibility.allows(reason))
        }
    }
}
