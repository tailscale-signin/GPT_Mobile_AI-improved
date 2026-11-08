package dev.chungjungsoo.gptmobile.presentation.ui.setting

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayJobsPresentationTest {
    @Test fun schedulingGroupsKeepWaitingDistinctFromRunningPhases() {
        assertEquals("Running", gatewayJobGroup("running"))
        assertEquals("Queued", gatewayJobGroup("queued"))
        assertEquals("Needs attention", gatewayJobGroup("waiting"))
        assertEquals("Running", gatewayJobGroup("waiting", "CI"))
        assertEquals("Running", gatewayJobGroup("waiting", "WAITING_FOR_CI"))
        assertEquals("Needs attention", gatewayJobGroup("waiting", "MERGE_READY"))
        assertEquals("Needs attention", gatewayJobGroup("unknown"))
        assertEquals("Recent", gatewayJobGroup("completed"))
        assertEquals("Recent", gatewayJobGroup("cancelled"))
        assertEquals("Waiting for CI", gatewayPhaseLabel("CI"))
        assertEquals("Waiting for device validation", gatewayPhaseLabel("WAITING_FOR_DEVICE"))
        assertEquals("Waiting for approval", gatewayPhaseLabel("MERGE_READY"))
        assertEquals("Waiting for this device", gatewayPhaseLabel("WAITING_FOR_CLIENT_TOOL"))
    }

    @Test fun durationShowsTotalAcceptedTimeAndUnknownMeansUnmeasured() {
        assertEquals("Not recorded", gatewayElapsedLabel(null))
        assertEquals("2h 3m 4s", gatewayElapsedLabel(7_384_000))
        assertEquals("1m 0s", gatewayElapsedLabel(60_000))
    }
}
