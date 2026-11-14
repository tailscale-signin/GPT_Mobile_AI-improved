package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.context.ToolOmissionReason
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRouteCapacityTest {
    private val profile = PlatformV2(uid = "selected", name = "Fixture", compatibleType = ClientType.LLAMA, model = "exact-model", apiUrl = "https://fixture.invalid/v1")
    private val readiness = ReasoningCapabilityObservation("selected", "exact-model", ReasoningMode.AUTO, ReasoningCapabilityStatus.SUPPORTED, "configuration", "runtime", 100L, null, true, true, verifiedContextTokens = 5632)

    @Test fun verifiedSmallRouteBoundsToolAndHistoryPlanningBeforeSerialization() {
        val configured = TokenBudgetSettings()
        val context = ProviderRouteCapacity.primaryContext(profile, configured, readiness, githubMode = true)
        assertEquals(5632, context)
        val settings = configured.copy(contextTokens = context, outputTokens = GitHubReasoningPolicy.outputBudget(context, configured.outputTokens, profile.maxTokens))
        val current = ConversationTurn(MessageV2(content = "Inspect this repository and summarize its checks.", platformType = null), null, true)
        val history = ConversationTurn(MessageV2(content = "Previous work ".repeat(3000), platformType = null), null, false)
        val small = AgentToolDefinition("github_context", "Read repository status.", buildJsonObject {})
        val oversized = AgentToolDefinition("large_optional_catalog", "Capability details ".repeat(3000), buildJsonObject {})
        val plan = ContextBudgetService.plan(listOf(history, current), "Preserve the user's task and authorized scope.", listOf(small, oversized), settings)

        assertEquals(listOf(current), plan.turns)
        assertEquals(listOf(small), plan.tools)
        assertEquals(ToolOmissionReason.CONTEXT_BUDGET, plan.toolOmissions[oversized.name])
        assertEquals(1877, plan.outputTokens)
        assertTrue(plan.promptTokens + requireNotNull(plan.outputTokens) + plan.toolResultBytes / 3 + 256 <= context)
        assertTrue(plan.notice.contains("1 earlier turns and 1 tools omitted"))
    }

    @Test fun unverifiedOrDifferentSelectionCannotSupplyAPlanningCapacity() {
        val configured = TokenBudgetSettings()
        for (observation in listOf(null, readiness.copy(routeVerified = false), readiness.copy(profileUid = "other"), readiness.copy(model = "other"), readiness.copy(verifiedContextTokens = 0))) {
            assertEquals(32768, ProviderRouteCapacity.primaryContext(profile, configured, observation, githubMode = true))
        }
        assertEquals(Int.MAX_VALUE, ProviderRouteCapacity.primaryContext(profile, configured, null, githubMode = false))
    }

    @Test fun verifiedCapacityNeverExpandsConfiguredCeilingsOrChangesTheSelectedProfile() {
        val configured = TokenBudgetSettings(contextTokens = 4096, outputTokens = 512, profileContextCeilings = mapOf(profile.uid to 2048))
        assertEquals(2048, ProviderRouteCapacity.primaryContext(profile, configured, readiness, githubMode = true))
        assertEquals(4096, configured.contextTokens)
        assertEquals(512, configured.outputTokens)
        assertEquals("exact-model", profile.model)
    }
}
