package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPlannedContextCapacityTest {
    private val adaptive = EngineeringProfileSettings(mode = EngineeringMode.ADAPTIVE)
    private val runtime = RuntimeFingerprint("profile", "route", "credential", "requested", "resolved", "revision", "template", "allocation")

    @Test fun knownRouteTaskCeilingTravelsFromPlanningIntoReplayAndSerializedAdmission() {
        val original = TokenBudgetSettings(contextTokens = 1_048_576)
        val route = ProviderRouteCapacity.PrimaryContextDecision(1_048_576, null, null, 1_048_576, CapacityProvenance.DECLARED)
        val selected = EngineeringAdaptivePolicy.forToolContext(adaptive, "List open pull requests", original, true)
        assertEquals(8192, selected.contextTokens)
        val plan = ContextBudgetService.plan(emptyList(), "x".repeat(3013 * 3), emptyList(), selected, planningProvenance = route.toolPlanningProvenance(selected.contextTokens, selected.contextTokens))
        val capacity = requireNotNull(plan.capacity)
        val execution = capacity.applyTo(original)
        val allocation = ReplayAllowance.forToolTurn(execution.contextTokens, plan.promptTokens, requireNotNull(plan.outputTokens), 4096)
        assertEquals(8192, allocation.ceiling)
        assertEquals(2027, allocation.output)
        assertEquals(1919, allocation.replay)
        val current = CapacitySnapshot(runtime, 1_048_576, provenance = CapacityProvenance.DECLARED).withCeilings(execution.contextTokens.toLong(), null)
        val serialized = capacity.retainProvenance(current, execution.contextTokens)
        val payload = "{\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(3013 * 3) + "\"}],\"max_tokens\":" + allocation.output + "}"
        val prepared = RequestAdmission.prepare(payload, "primary", serialized, RequestAdmission.estimate(payload), allocation.output.toLong(), "accepted")
        assertEquals(8192L, prepared.receipt.contextTokens)
        assertEquals(CapacityProvenance.DECLARED, prepared.receipt.capacityProvenance)
        assertEquals(payload, prepared.dispatchPayload(runtime, "accepted"))
    }

    @Test fun adaptiveTargetCannotTurnUnknownProviderCapacityIntoAnEightKRoute() {
        val original = TokenBudgetSettings()
        val selected = EngineeringAdaptivePolicy.forToolContext(adaptive, "List open pull requests", original, true)
        assertEquals(4096, selected.contextTokens)
        assertEquals(768, selected.outputTokens)
        val route = ProviderRouteCapacity.PrimaryContextDecision(Int.MAX_VALUE, null, null, null, CapacityProvenance.UNKNOWN)
        assertEquals(CapacityProvenance.CONSERVATIVE_POLICY, route.toolPlanningProvenance(selected.contextTokens, selected.contextTokens))
        for (prompt in listOf(4821, 5801, 8208, 8747)) {
            val failure = assertThrows(RequestCapacityException::class.java) {
                ContextBudgetService.plan(emptyList(), "x".repeat(prompt * 3), emptyList(), selected, planningProvenance = CapacityProvenance.CONSERVATIVE_POLICY)
            }
            assertEquals(4096L, failure.capacityTokens)
        }
    }

    @Test fun conservativeProvenanceSurvivesWireAdmissionAndContinuation() {
        val capacity = PlannedContextCapacity(4096, CapacityProvenance.CONSERVATIVE_POLICY)
        val execution = capacity.applyTo(TokenBudgetSettings())
        val available = CapacitySnapshot(runtime, 1_048_576, provenance = CapacityProvenance.DECLARED).withCeilings(execution.contextTokens.toLong(), null)
        val bound = capacity.retainProvenance(available, execution.contextTokens)
        for (role in listOf("primary", "github-output-continuation", "github-visible-output-continuation")) {
            val payload = "{\"messages\":[{\"role\":\"user\",\"content\":\"Continue from the retained receipt\"}],\"max_tokens\":768}"
            val result = RequestAdmission.prepare(payload, role, bound, RequestAdmission.estimate(payload), 768, "accepted")
            assertEquals(CapacityProvenance.CONSERVATIVE_POLICY, result.receipt.capacityProvenance)
            assertEquals(4096L, result.receipt.contextTokens)
        }
    }

    @Test fun aSmallerCurrentRouteOrUserCeilingRequiresReplanningAndNeverWidens() {
        val capacity = PlannedContextCapacity(8192, CapacityProvenance.DECLARED)
        assertThrows(IllegalArgumentException::class.java) { capacity.applyTo(TokenBudgetSettings(contextTokens = 4096)) }
        val smaller = CapacitySnapshot(runtime, 2048, provenance = CapacityProvenance.VERIFIED_ROUTE)
        assertSame(smaller, capacity.retainProvenance(smaller, 8192))
        assertThrows(RequestCapacityException::class.java) {
            val payload = "x".repeat(1800 * 3)
            RequestAdmission.prepare(payload, "primary", smaller, RequestAdmission.estimate(payload), 512, "accepted")
        }
        assertTrue(capacity.applyTo(TokenBudgetSettings(contextTokens = 1_048_576)).contextTokens == 8192)
    }
}
