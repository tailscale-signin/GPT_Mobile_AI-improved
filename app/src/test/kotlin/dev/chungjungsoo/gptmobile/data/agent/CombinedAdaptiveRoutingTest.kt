package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedAdaptiveRoutingTest {
    private val selected = listOf("a", "b", "c", "d")
    private val settings = CombinedSynthesisPreferences(sourceDiversity = 50).applyTo(ConversationCombinedSettings(effort = 100, adaptiveContributors = true))

    @Test fun newlyAcceptedRoutineScopeKeepsOutputEffortAndHardBudgetContracts() {
        val accepted = CombinedPolicyResolver.accept(settings, selected, emptyMap(), "Rewrite: Thank you.", preferredOwnerUid = "d")
        val explicit = CombinedPolicyResolver.accept(settings.copy(adaptiveContributors = false), selected, emptyMap(), "Rewrite: Thank you.", preferredOwnerUid = "d")
        val limits = CombinedHardLimits(totalTokens = 100_000, toolOperations = 7)
        val adaptivePlan = CombinedPolicyResolver.resolve(accepted, hardLimits = limits)
        val explicitPlan = CombinedPolicyResolver.resolve(explicit, hardLimits = limits)
        assertEquals(listOf("a", "d"), accepted.participantUids)
        assertEquals(selected, explicit.participantUids)
        assertEquals(limits, adaptivePlan.hardLimits)
        assertEquals(explicitPlan.finalizationReserve, adaptivePlan.finalizationReserve)
        assertEquals(explicitPlan.finalOutputTokens, adaptivePlan.finalOutputTokens)
        assertEquals(explicitPlan.optionalAllowance, adaptivePlan.optionalAllowance)
        assertTrue(adaptivePlan.verificationAllowed)
        assertEquals(2L, adaptivePlan.caps.single { it.name == "accepted_contributors" }.value)
    }

    @Test fun fullDiversityExplicitScopeAndAcceptedResearchKeepEverySelectedContributor() {
        val fullDiversity = CombinedSynthesisPreferences(sourceDiversity = 100).applyTo(settings)
        assertEquals(selected, CombinedPolicyResolver.accept(fullDiversity, selected, emptyMap(), "Hello!").participantUids)
        assertEquals(selected, CombinedPolicyResolver.accept(settings, selected, emptyMap(), "Ask all models to rewrite: Thank you.").participantUids)
        val research = CombinedResearchRequirement(1, 1, "WEB_AND_PAPERS", independentReviewRequested = true)
        val accepted = CombinedPolicyResolver.accept(settings, selected, emptyMap(), "Hello!", acceptedResearch = research)
        assertEquals(selected, accepted.participantUids)
        assertEquals(research, accepted.acceptedResearch)
    }

    @Test fun resolvingPreviouslyAcceptedParticipantsDoesNotPruneThemAgain() {
        val accepted = CombinedPolicyResolver.accept(settings.copy(adaptiveContributors = false), selected, emptyMap(), "Hello!")
        val plan = CombinedPolicyResolver.resolve(accepted)
        assertEquals(selected, plan.requested.participantUids)
        assertTrue(selected.all { "contributor:$it" in plan.baselineByOwner })
    }
}
