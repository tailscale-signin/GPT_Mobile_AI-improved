package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRecoveryControlsTest {
    private val profile = PlatformV2(uid = "llama", name = "Private model", model = "exact-model", compatibleType = ClientType.LLAMA, apiUrl = "https://gateway.example", token = "private-token")
    private val request = ReasoningRecoveryRequest(profile, ReasoningMode.OFF)
    private fun observation(mode: ReasoningMode = ReasoningMode.OFF, status: ReasoningCapabilityStatus = ReasoningCapabilityStatus.SUPPORTED, supportVerified: Boolean = true) = ReasoningCapabilityObservation(
        profile.uid, profile.model, mode, status, "opaque-config", "runtime-template", 1000L, null, true, supportVerified
    )

    @Test fun retainedFailureAndProcessRestartRequireExplicitSuccessfulQualification() {
        val gate = ReasoningRecoveryGate { 1000 }
        assertFalse(gate.permits("run", request))
        assertTrue(gate.record("run", request, observation()))
        assertTrue(gate.permits("run", request))
        assertFalse(ReasoningRecoveryGate { 1000 }.permits("run", request))
        assertFalse(gate.permits("other-run", request))
    }

    @Test fun proofExpiresAtTwentySecondsAndRejectsClockRollback() {
        var now = 1000L
        val gate = ReasoningRecoveryGate { now }
        assertTrue(gate.record("run", request, observation()))
        now = 20_999
        assertTrue(gate.permits("run", request))
        now = 21_000
        assertFalse(gate.permits("run", request))
        now = 999
        assertFalse(gate.permits("run", request))
    }

    @Test fun changingExactModelRouteCredentialsOrModeInvalidatesTheCheckedAttempt() {
        val gate = ReasoningRecoveryGate { 1000 }
        assertTrue(gate.record("run", request, observation()))
        listOf(profile.copy(model = "other"), profile.copy(apiUrl = "https://other.example"), profile.copy(token = "replacement"), profile.copy(uid = "another"), profile.copy(secretRef = "new-record"), profile.copy(providerConnectionUid = "different-connection")).forEach {
            assertFalse(gate.permits("run", request.copy(profile = it)))
        }
        assertFalse(gate.permits("run", request.copy(mode = ReasoningMode.AUTO)))
    }

    @Test fun unsupportedUnverifiedAndUnprovenSupportCannotAuthorizeRetry() {
        val gate = ReasoningRecoveryGate { 1000 }
        for (status in listOf(ReasoningCapabilityStatus.UNSUPPORTED, ReasoningCapabilityStatus.UNVERIFIED)) assertFalse(gate.record("run", request, observation(status = status)))
        assertFalse(gate.record("run", request, observation(supportVerified = false)))
        assertFalse(gate.record("run", request, observation().copy(routeVerified = false)))
        assertFalse(gate.record("run", request, observation().copy(model = "another")))
        assertFalse(gate.record("run", request, observation().copy(profileUid = "another")))
        assertFalse(gate.record("run", request, observation(ReasoningMode.AUTO)))
    }

    @Test fun aFailedRequalificationRetiresAnEarlierSuccessfulProof() {
        val gate = ReasoningRecoveryGate { 1000 }
        assertTrue(gate.record("run", request, observation()))
        assertFalse(gate.record("run", request, observation(status = ReasoningCapabilityStatus.UNSUPPORTED)))
        assertFalse(gate.permits("run", request))
    }

    @Test fun onlyExactAnthropicAccountRouteExposesTheProviderBillingAction() {
        val direct = profile.copy(compatibleType = ClientType.ANTHROPIC, apiUrl = "https://api.anthropic.com/v1/")
        assertEquals("https://platform.claude.com/settings/billing", profileBillingUrl(direct))
        assertNull(profileBillingUrl(direct.copy(apiUrl = "https://api.anthropic.com.attacker.example/v1")))
        assertNull(profileBillingUrl(direct.copy(apiUrl = "https://user@api.anthropic.com/v1")))
        assertNull(profileBillingUrl(direct.copy(apiUrl = "http://api.anthropic.com/v1")))
        assertNull(profileBillingUrl(direct.copy(apiUrl = "https://api.anthropic.com:8080/v1")))
        assertNull(profileBillingUrl(direct.copy(compatibleType = ClientType.OPENROUTER)))
        assertNull(profileBillingUrl(direct.copy(apiUrl = "https://proxy.example/anthropic")))
    }

    @Test fun deterministicFailuresUseRepairActionsWhileDnsStillUsesBoundedRetry() {
        assertEquals(ProfileRecoveryKind.REASONING, profileRecoveryKind("INCOMPATIBLE_CONFIGURATION", "REASONING_OFF_UNSUPPORTED: saved mode"))
        assertEquals(ProfileRecoveryKind.REASONING, profileRecoveryKind("UNAVAILABLE", "REASONING_CAPABILITY_UNVERIFIED"))
        assertEquals(ProfileRecoveryKind.BILLING, profileRecoveryKind("BILLING_UNAVAILABLE", "provider balance"))
        assertEquals(ProfileRecoveryKind.BILLING, profileRecoveryKind("INCOMPLETE", "BILLING_UNAVAILABLE"))
        assertEquals(ProfileRecoveryKind.SETTINGS, profileRecoveryKind("INCOMPLETE", "GATEWAY_REMOTE_SURFACE_NOT_ADMITTED"))
        assertEquals(ProfileRecoveryKind.NONE, profileRecoveryKind("GATEWAY_OUTCOME_UNKNOWN", "Reconcile the original command"))
        assertEquals(ProfileRecoveryKind.SETTINGS, profileRecoveryKind("REQUEST_REJECTED", "invalid configuration"))
        assertEquals(ProfileRecoveryKind.NONE, profileRecoveryKind("DNS_FAILURE", "DNS could not resolve"))
        assertEquals(ProfileRecoveryKind.NONE, profileRecoveryKind("INCOMPLETE", "ordinary incomplete answer"))
    }
}
