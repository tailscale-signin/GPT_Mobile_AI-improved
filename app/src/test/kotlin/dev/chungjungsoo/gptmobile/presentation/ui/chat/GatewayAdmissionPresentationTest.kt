package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAdmissionPresentationTest {
    private val unsent = ProfileFailureRecord(
        "GATEWAY_ENGINEERING_GITHUB_REQUIRED",
        retryAllowed = false,
        dispatched = false,
        remoteAcceptance = RemoteRequestAcceptance.NO,
        phase = "connection_preflight"
    )

    @Test fun knownUnsentGithubPreflightShowsHostRepairAndExplicitRetryAfterSetup() {
        val presentation = requireNotNull(gatewayAdmissionPresentation(unsent))
        assertEquals("Gateway GitHub setup required", presentation.title)
        assertTrue(presentation.guidance.contains("selected gateway computer"))
        assertTrue(presentation.guidance.contains("Check gateway readiness"))
        assertTrue(presentation.canRetryAfterRepair)
    }

    @Test fun legacyOrPartialRunNeverGetsAResubmissionActionFromTheErrorCodeAlone() {
        for (failure in listOf(
            ProfileFailureRecord.classify("GATEWAY_ENGINEERING_GITHUB_REQUIRED (HTTP 200)"),
            unsent.copy(dispatched = null, remoteAcceptance = RemoteRequestAcceptance.UNKNOWN),
            unsent.copy(dispatched = true),
            unsent.copy(remoteAcceptance = RemoteRequestAcceptance.YES),
            unsent.copy(phase = "gateway_recovery")
        )) {
            assertFalse(requireNotNull(gatewayAdmissionPresentation(failure)).canRetryAfterRepair)
        }
    }

    @Test fun unknownOutcomesAndOrdinaryProviderFailuresKeepTheirExistingRecovery() {
        assertNull(gatewayAdmissionPresentation(null))
        for (code in listOf("GATEWAY_OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN", "NETWORK_TIMEOUT", "AUTHENTICATION_FAILED")) {
            assertNull(gatewayAdmissionPresentation(unsent.copy(category = code)))
        }
    }

    @Test fun otherEngineeringPrerequisitesDescribeTheirActualRecovery() {
        assertEquals("Gateway storage needs attention", gatewayAdmissionPresentation(unsent.copy(category = "GATEWAY_ENGINEERING_STORAGE_DISABLED"))?.title)
        assertEquals("Gateway worker needs attention", gatewayAdmissionPresentation(unsent.copy(category = "GATEWAY_ENGINEERING_WORKER_UNAVAILABLE"))?.title)
        assertEquals("Gateway is not ready", gatewayAdmissionPresentation(unsent.copy(category = "GATEWAY_ENGINEERING_DRAINING"))?.title)
    }

    @Test fun validationPolicyExplainsTheRequiredSetupAndOnlyAllowsKnownUnsentRetry() {
        val failure = unsent.copy(category = "VALIDATION_POLICY_REQUIRED", phase = "admission", recoveryActions = listOf("CONFIGURE_VALIDATION_POLICY"))
        val presentation = requireNotNull(gatewayAdmissionPresentation(failure))
        assertEquals("Validation policy required", presentation.title)
        assertTrue(presentation.guidance.contains("Configure the validation policy"))
        assertTrue(presentation.canRetryAfterRepair)
        assertFalse(requireNotNull(gatewayAdmissionPresentation(failure.copy(dispatched = null, remoteAcceptance = RemoteRequestAcceptance.UNKNOWN))).canRetryAfterRepair)
        assertFalse(requireNotNull(gatewayAdmissionPresentation(failure.copy(dispatched = true, remoteAcceptance = RemoteRequestAcceptance.YES))).canRetryAfterRepair)
    }

    @Test fun remainingPrerequisitesNameTheirActualRepair() {
        val titles = mapOf(
            "VALIDATION_CHECKS_REQUIRED" to "Validation checks need configuration",
            "VALIDATION_CHECK_UNSUPPORTED" to "Validation checks need configuration",
            "ACCEPTANCE_CONTRACT_REQUIRED" to "Acceptance requirements need configuration",
            "ACCEPTANCE_PRODUCER_REQUIRED" to "Acceptance requirements need configuration",
            "SOURCE_REVISION_REQUIRED" to "Source revision needs refreshing",
            "SOURCE_REVISION_CHANGED" to "Source revision needs refreshing",
            "VALIDATION_REVISION_UNAVAILABLE" to "Validation source is unavailable",
            "VALIDATION_RUNTIME_UNAVAILABLE" to "Validation runtime is unavailable",
            "ENGINEERING_REPOSITORY_ACCESS_REQUIRED" to "Repository access needs attention"
        )
        for ((category, title) in titles) {
            val presentation = requireNotNull(gatewayAdmissionPresentation(unsent.copy(category = category, phase = "admission")))
            assertEquals(title, presentation.title)
            assertTrue(presentation.canRetryAfterRepair)
        }
    }
}
