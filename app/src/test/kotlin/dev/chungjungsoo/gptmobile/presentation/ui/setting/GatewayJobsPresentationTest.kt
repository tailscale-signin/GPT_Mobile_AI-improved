package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.github.GatewayJobGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayJobsPresentationTest {
    @Test fun schedulingGroupsKeepWaitingDistinctFromRunningPhases() {
        assertEquals(GatewayJobGroup.RUNNING, gatewayJobDisplayGroup("running"))
        assertEquals(GatewayJobGroup.QUEUED, gatewayJobDisplayGroup("queued"))
        assertEquals(GatewayJobGroup.NEEDS_ATTENTION, gatewayJobDisplayGroup("waiting"))
        assertEquals(GatewayJobGroup.RUNNING, gatewayJobDisplayGroup("waiting", "CI"))
        assertEquals(GatewayJobGroup.RUNNING, gatewayJobDisplayGroup("waiting", "WAITING_FOR_CI"))
        assertEquals(GatewayJobGroup.NEEDS_ATTENTION, gatewayJobDisplayGroup("waiting", "MERGE_READY"))
        assertEquals(GatewayJobGroup.NEEDS_ATTENTION, gatewayJobDisplayGroup("unknown"))
        assertEquals(GatewayJobGroup.NEEDS_ATTENTION, gatewayJobDisplayGroup("interrupted"))
        assertEquals(GatewayJobGroup.RECENT, gatewayJobDisplayGroup("completed"))
        assertEquals(GatewayJobGroup.RECENT, gatewayJobDisplayGroup("cancelled"))
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
