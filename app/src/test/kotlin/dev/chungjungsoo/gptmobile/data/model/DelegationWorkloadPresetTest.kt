package dev.chungjungsoo.gptmobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DelegationWorkloadPresetTest {
    @Test
    fun presetDoesNotEnableWorkersOrRewriteSafetyAndProfileSettings() {
        val original = ModelDelegationSettings(enabled = false, targetProfileUid = "worker", reviewerProfileUid = "reviewer", reviewerEnabled = false, reviewerMinimumScore = 90, allowRemoteWorkers = false, localPlatformsOnly = true, lowBatteryThresholdPercent = 40)
        DelegationWorkloadPreset.entries.forEach { preset ->
            val changed = original.withWorkloadPreset(preset)
            assertEquals(original.withStrategy(preset.depth), changed)
            assertEquals(original.enabled, changed.enabled)
            assertEquals(original.targetProfileUid, changed.targetProfileUid)
            assertEquals(original.reviewerProfileUid, changed.reviewerProfileUid)
            assertEquals(original.reviewerEnabled, changed.reviewerEnabled)
            assertEquals(original.reviewerMinimumScore, changed.reviewerMinimumScore)
            assertEquals(original.remoteWorkersAllowed(), changed.remoteWorkersAllowed())
            assertEquals(original.lowBatteryThresholdPercent, changed.lowBatteryThresholdPercent)
        }
    }
}
