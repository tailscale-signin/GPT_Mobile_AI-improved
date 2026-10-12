package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ResearchIntensity
import dev.chungjungsoo.gptmobile.data.model.ResearchUserLimits
import dev.chungjungsoo.gptmobile.data.model.effectiveResearchPlan
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchPlanTest {
    @Test fun legacyCapsNeverReduceTheEffortGoal() {
        for (version in 1..2) {
            for (cap in listOf(1, 30, 150)) {
                for (intensity in ResearchIntensity.entries) {
                    val old = DeepResearchSettings(schemaVersion = version, sourceSafetyCap = cap, effort = 100, intensity = intensity)
                    val migrated = old.migrated()
                    val restored = Json.decodeFromString<DeepResearchSettings>(Json.encodeToString(migrated))
                    assertEquals(3, restored.schemaVersion)
                    assertEquals(300, effectiveResearchPlan(restored).sourceGoal)
                    assertEquals(cap, restored.sourceSafetyCap)
                }
            }
        }
    }

    @Test fun customGoalsAndExplicitBudgetsSurviveSerialization() {
        for (goal in listOf(301, 500, 1000)) {
            val settings = DeepResearchSettings(customSourceTarget = goal, userLimits = ResearchUserLimits(maxRequests = 7000)).forEffort(100)
            val restored = Json.decodeFromString<DeepResearchSettings>(Json.encodeToString(settings))
            assertEquals(goal, effectiveResearchPlan(restored).sourceGoal)
            assertEquals(7000L, restored.userLimits.maxRequests)
        }
    }

    @Test fun zeroEffortOrDisabledResearchNeverDispatchesACustomGoal() {
        assertFalse(effectiveResearchPlan(DeepResearchSettings(customSourceTarget = 500, effort = 0)).active)
        assertEquals(0, effectiveResearchPlan(DeepResearchSettings(customSourceTarget = 500, enabled = false)).sourceGoal)
    }

    @Test fun invalidPolicyNeverBecomesUnlimitedExecution() {
        assertTrue(runCatching { effectiveResearchPlan(DeepResearchSettings(schemaVersion = 99)) }.isFailure)
        assertTrue(runCatching { effectiveResearchPlan(DeepResearchSettings(customSourceTarget = 0)) }.isFailure)
        assertTrue(runCatching { effectiveResearchPlan(DeepResearchSettings(userLimits = ResearchUserLimits(maxRequests = -1))) }.isFailure)
    }
}
