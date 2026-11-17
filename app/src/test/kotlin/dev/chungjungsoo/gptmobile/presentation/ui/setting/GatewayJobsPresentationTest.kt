package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.github.GatewayJobGroup
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRetry
import dev.chungjungsoo.gptmobile.data.github.GatewayJobSummary
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayJobsPresentationTest {
    @Test fun protocolStateClassificationDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(GatewayJobGroup.RUNNING, gatewayJobDisplayGroup("running"))
            assertEquals(GatewayJobGroup.NEEDS_ATTENTION, gatewayJobDisplayGroup("interrupted"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun retryOutcomesDoNotTurnMissingTimingIntoAClaimOfInterruption() {
        val job = GatewayJobSummary("job", "engineering", state = "queued", acceptedAt = 100.0, providerRetryAt = 200.0, retry = GatewayJobRetry("APP_BACKOFF", 2, "READ", "SCHEDULED"))
        assertEquals(GatewayRetryPresentation.SCHEDULED, gatewayRetryPresentation(job))
        assertEquals(GatewayRetryPresentation.NEEDS_INPUT, gatewayRetryPresentation(job.copy(retry = job.retry?.copy(outcome = "NEEDS_INPUT"))))
        assertEquals(GatewayRetryPresentation.RESUMED, gatewayRetryPresentation(job.copy(retry = job.retry?.copy(outcome = "RESUMED"))))
        assertEquals(GatewayRetryPresentation.INTERRUPTED, gatewayRetryPresentation(job.copy(retry = job.retry?.copy(outcome = "INTERRUPTED"))))
        assertEquals(GatewayRetryPresentation.RECORDED, gatewayRetryPresentation(job.copy(providerRetryAt = null)))
        assertEquals(GatewayRetryPresentation.RECORDED, gatewayRetryPresentation(job.copy(providerRetryAt = Double.NaN)))
        assertEquals(GatewayRetryPresentation.RECORDED, gatewayRetryPresentation(job.copy(state = "cancelled")))
    }

    @Test fun retryCountdownRequiresFreshFiniteServerEvidenceAndMonotonicTime() {
        assertEquals(9_000L, gatewayRetryRemainingMillis(110.0, 100.0, 500, 1500, true))
        assertEquals(0L, gatewayRetryRemainingMillis(100.0, 100.0, 500, 1500, true))
        assertNull(gatewayRetryRemainingMillis(110.0, 100.0, 500, 1500, false))
        assertNull(gatewayRetryRemainingMillis(110.0, Double.NaN, 500, 1500, true))
        assertNull(gatewayRetryRemainingMillis(Double.POSITIVE_INFINITY, 100.0, 500, 1500, true))
        assertNull(gatewayRetryRemainingMillis(110.0, 100.0, 500, 499, true))
        assertNull(gatewayRetryRemainingMillis(110.0, 100.0, 500, 30_501, true))
        assertNull(gatewayRetryRemainingMillis(110.0, 100.0, 0, 500, true))
    }

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
