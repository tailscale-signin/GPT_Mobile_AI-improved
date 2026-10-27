package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedIncrementalBudgetTest {
    @Test fun boundedInspectionsDoNotConsumeTheSingleFinalDispatchAndCannotBeReplayed() = runBlocking {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(continueLongAnswers = false), listOf("p"), mapOf("p" to "model"), "Compare the evidence", singlePass = true)
        assertTrue(requested.incrementalSynthesis)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested, estimatedInputTokens = 256), "incremental", CombinedRunRole.SYNTHESIS, "p")
        val store = CombinedTurnBudgetStore.inMemory()
        val budget = store.open(context)
        val cost = CombinedResourceQuota(128, 64)
        for (index in 1..2) {
            val id = "corpus-inspection:run:span-$index"
            budget.authorizeRequiredWork(CombinedWorkItem(id, CombinedRunRole.SYNTHESIS, reason = "REQUIRED_COVERAGE_GAP"), cost, "REQUIRED_COVERAGE_GAP")
            val inspection = context.copy(workItemId = id)
            assertTrue(budget.reserve(inspection, "inspection-$index", cost) is CombinedBudgetAdmission.Granted)
            budget.settle("inspection-$index", CombinedUsage(cost))
            assertTrue(budget.reserve(inspection, "replay-$index", cost) is CombinedBudgetAdmission.Denied)
        }
        assertTrue(budget.reserve(context, "final", cost) is CombinedBudgetAdmission.Granted)
        budget.settle("final", CombinedUsage(cost))
        assertTrue(budget.reserve(context, "second-final", cost) is CombinedBudgetAdmission.Denied)
    }

    @Test fun oldSnapshotsAndExplicitStrictPreferenceDoNotAuthorizeInspection() {
        val strict = CombinedSynthesisPreferences(incrementalSynthesis = false).applyTo(ConversationCombinedSettings())
        val requested = CombinedPolicyResolver.accept(strict, listOf("p"), emptyMap(), "Compare", singlePass = true)
        assertFalse(requested.incrementalSynthesis)
        assertFalse(requested.copy(settings = ConversationCombinedSettings()).incrementalSynthesis)
    }
}
