package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.provider.GitHubReasoningPolicy
import dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.delegation.DelegationFallback
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubEngineeringStrategyTest {
    @Test fun intelligentDefaultsAndAdaptsToTaskComplexityWithinSavedBudget() {
        assertEquals(GitHubIntelligenceLevel.INTELLIGENT, Json.decodeFromString<GitHubChatProfileOverride>("{}").intelligenceLevel)
        val level = GitHubIntelligenceLevel.INTELLIGENT
        assertEquals(GitHubReasoningEffort.LOW, GitHubEngineeringStrategy.predict(level, "status").effort)
        assertEquals(GitHubReasoningEffort.MEDIUM, GitHubEngineeringStrategy.predict(level, "Explain how the repository handles retries").effort)
        val request = "Implement the fix in the repository and verify the tests"
        assertEquals(GitHubReasoningEffort.HIGH, GitHubEngineeringStrategy.predict(level, request).effort)
        assertNull(GitHubEngineeringStrategy.predict(GitHubIntelligenceLevel.INSTANT, request).explorationRole(request, true))
        assertEquals("github-architecture-options", GitHubEngineeringStrategy.predict(GitHubIntelligenceLevel.DEEP_ENGINEERING, request).explorationRole(request, false))
    }

    @Test fun modelSpecificEffortAndBoundedContinuationHonorControls() {
        val low = RequestConstraints(gitHubIntelligenceLevel = GitHubIntelligenceLevel.INTELLIGENT, gitHubEffort = GitHubReasoningEffort.LOW, maxOutputTokens = 512)
        assertEquals("low", GitHubReasoningPolicy.requestedOpenAiEffort("gpt-5.2", low))
        assertEquals("xhigh", GitHubReasoningPolicy.requestedOpenAiEffort("gpt-5.2", low.copy(gitHubEffort = GitHubReasoningEffort.MAXIMUM)))
        assertEquals("high", GitHubReasoningPolicy.requestedOpenAiEffort("o3-pro", low))
        assertEquals(GitHubReasoningEffort.MEDIUM, low.forRole(GitHubStageBudget.CONTINUATION_ROLE).gitHubEffort)
        assertEquals(GitHubReasoningEffort.HIGH, low.copy(gitHubEffort = GitHubReasoningEffort.MEDIUM).forRole("github-execution-continuation").gitHubEffort)
        assertEquals(512, low.forRole(GitHubStageBudget.CONTINUATION_ROLE).maxOutputTokens)
        assertEquals(GitHubReasoningEffort.LOW, low.copy(gitHubIntelligenceLevel = GitHubIntelligenceLevel.INSTANT).forRole(GitHubStageBudget.CONTINUATION_ROLE).gitHubEffort)
        assertNull(low.copy(allowReasoning = false).forRole("primary").gitHubEffort)
    }

    @Test fun recoveryAdviceUsesOnlyActionableAcceptedCriteria() {
        val report = Json.parseToJsonElement("""{"requirements":[{"criterion":"denied","readiness":{"authorization":"NOT_AUTHORIZED"}},{"criterion":"inspect tests","state":"PARTIAL"}]}""") as kotlinx.serialization.json.JsonObject
        assertEquals("inspect tests", GitHubRecoveryConsult.unresolvedCriterion(report, "denied; inspect tests"))
        assertNull(GitHubRecoveryConsult.unresolvedCriterion(report, "a different accepted task"))
    }

    @Test fun recoveryAdviceRequiresExactLocalProfileWorkspaceAndActiveOptIn() {
        val primary = PlatformV2(uid = "p", name = "Primary", model = "primary-model")
        val workspace = GitHubConversationContext("c", "owner", "repo", "main", "a".repeat(40), primary.uid)
        val helper = PlatformV2(uid = "device", name = "Local", model = "local-model", compatibleType = ClientType.LITERT_LM)
        val config = ModelDelegationSettings(enabled = true, approvedWorkerUids = listOf(helper.uid), fallbackPolicy = DelegationFallback.APPROVED_ORDER)
        val preferences = GitHubChatProfileOverride(intelligenceLevel = GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE, recoveryConsultConsent = GitHubRecoveryConsult.consent(workspace, primary, helper))
        assertEquals(helper, GitHubRecoveryConsult.target(preferences, workspace, primary, listOf(helper), config))
        assertNull(GitHubRecoveryConsult.target(preferences.copy(recoveryConsultConsent = null), workspace, primary, listOf(helper), config))
        assertNull(GitHubRecoveryConsult.target(preferences.copy(intelligenceLevel = GitHubIntelligenceLevel.INTELLIGENT), workspace, primary, listOf(helper), config))
        assertNull(GitHubRecoveryConsult.target(preferences, workspace.copy(ref = "other"), primary, listOf(helper), config))
        assertNull(GitHubRecoveryConsult.target(preferences, workspace, primary, listOf(helper.copy(model = "replaced")), config))
        assertNull(GitHubRecoveryConsult.target(preferences, workspace, primary, listOf(helper.copy(compatibleType = ClientType.OPENAI)), config))
        assertNull(GitHubRecoveryConsult.target(preferences, workspace, primary, listOf(helper), config.copy(enabled = false)))
        assertNull(GitHubRecoveryConsult.target(preferences, workspace, primary, listOf(helper), config.copy(approvedWorkerUids = emptyList())))
        assertNull(GitHubRecoveryConsult.target(preferences, workspace, primary, listOf(helper), config.copy(fallbackPolicy = DelegationFallback.ASK)))
    }
}
