package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomaticPreparationPolicyTest {
    private val automatic = ModelDelegationSettings(
        policyVersion = 2,
        enabled = true,
        selectionMode = WorkerSelectionMode.AUTOMATIC,
        processingOwnership = 50,
        deepResearch = DeepResearchSettings(enabled = false)
    ).normalized()

    @Test fun skipsOnlyOptionalAutomaticPreparationForKnownRoutineWork() {
        assertEquals("AUTOMATIC_PREPARATION_NOT_NEEDED_GREETING", AutomaticPreparationPolicy.skipReason("Hello!", automatic, automatic = true))
        assertEquals("AUTOMATIC_PREPARATION_NOT_NEEDED_INLINE_TEXT_TRANSFORMATION", AutomaticPreparationPolicy.skipReason("Rewrite: Thank you.", automatic, automatic = true))
        assertNull(AutomaticPreparationPolicy.skipReason("Explain this result", automatic, automatic = true))
        assertNull(AutomaticPreparationPolicy.skipReason("Research the current market", automatic, automatic = true))
    }

    @Test fun explicitWorkersReviewersAndLocalOwnershipAreNeverSkipped() {
        for (config in listOf(
            automatic.copy(targetProfileUid = "chosen-helper"),
            automatic.copy(selectionMode = WorkerSelectionMode.FIXED),
            automatic.copy(selectionMode = WorkerSelectionMode.INHERIT),
            automatic.copy(processingOwnership = 0),
            automatic.copy(processingOwnership = 49),
            automatic.copy(reviewerEnabled = true),
            automatic.copy(reviewMode = DelegationReviewMode.REQUIRED),
            automatic.copy(deepResearch = DeepResearchSettings(enabled = true)),
            automatic.copy(taskPlan = DelegationTaskPlan(steps = listOf(DelegationPlanStep("required-step")))),
            automatic.copy(templateRole = DelegationTemplate.Role.DOCUMENT_EXTRACTION),
            automatic.copy(enabled = false),
            automatic.copy(automaticResearch = false),
            automatic.copy(researchEnabled = false)
        )) {
            assertNull(config.toString(), AutomaticPreparationPolicy.skipReason("Rewrite: Thank you.", config, automatic = true))
        }
    }

    @Test fun explicitCallsAndAcceptedTargetsOrResearchGoalsKeepPreparation() {
        assertNull(AutomaticPreparationPolicy.skipReason("Hello!", automatic, automatic = false))
        assertNull(AutomaticPreparationPolicy.skipReason("Hello!", automatic, automatic = true, explicitTarget = true))
        assertNull(AutomaticPreparationPolicy.skipReason("Hello!", automatic, automatic = true, explicitResearchGoal = true))
        assertNull(AutomaticPreparationPolicy.skipReason("Ask all models to reword this: Hello.", automatic, automatic = true))
    }
}
