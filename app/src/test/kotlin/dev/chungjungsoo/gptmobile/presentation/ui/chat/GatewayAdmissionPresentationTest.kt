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
}
