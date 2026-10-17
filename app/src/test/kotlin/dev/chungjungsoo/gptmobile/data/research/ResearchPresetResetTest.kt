package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ResearchIntensity
import org.junit.Assert.assertEquals
import org.junit.Test

class ResearchPresetResetTest {
    @Test fun lighterPresetResetsParallelismAndTimeoutWithoutChangingAccess() {
        val settings = DeepResearchSettings(allowRemoteReaders = false, allowExternalLinks = false).preset(ResearchIntensity.EXHAUSTIVE)
        val fast = settings.preset(ResearchIntensity.QUICK)
        assertEquals(2, fast.concurrency)
        assertEquals(15, fast.pageTimeoutSeconds)
        assertEquals(false, fast.allowRemoteReaders)
        assertEquals(false, fast.allowExternalLinks)
        assertEquals(3, fast.preset(ResearchIntensity.RESEARCH).concurrency)
    }

    @Test fun customGoalStillSurvivesEffortAdjustment() {
        assertEquals(500, DeepResearchSettings(customSourceTarget = 500).forEffort(25).customSourceTarget)
    }
}
