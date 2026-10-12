package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationPlanTest {
    @Test fun dependenciesSortAndCyclesAreRejected() {
        val a = DelegationPlanStep("a")
        val b = DelegationPlanStep("b", dependsOn = listOf("a"))
        val plan = DelegationTaskPlan(steps = listOf(b, a))
        plan.validate()
        assertEquals(listOf("a", "b"), plan.ordered().map { it.id })
        assertTrue(runCatching { plan.copy(steps = listOf(a.copy(dependsOn = listOf("b")), b)).validate() }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { plan.copy(steps = listOf(a, a)).validate() }.isFailure)
    }

    @Test fun interruptedCheckpointRetainsCompletedEvidenceAndReservedBudget() {
        val plan = DelegationTaskPlan(steps = listOf(DelegationPlanStep("a"), DelegationPlanStep("b")))
        val original = DelegationPlanCheckpoint(fingerprint = plan.fingerprint("policy"), steps = listOf(DelegationStepCheckpoint("a", PlanStepState.COMPLETE, 1, "retained", listOf("S1")), DelegationStepCheckpoint("b", PlanStepState.RUNNING, 1)), reservedCalls = 2, deadlineEpochMs = 1234)
        val restored = original.interrupted()
        restored.validate(plan)
        assertEquals(original.steps[0], restored.steps[0])
        assertEquals(PlanStepState.INTERRUPTED, restored.steps[1].status)
        assertEquals(2, restored.reservedCalls)
        assertEquals(1234L, restored.deadlineEpochMs)
        assertNotEquals(plan.fingerprint("policy"), plan.fingerprint("revoked"))
    }

    @Test fun providerAliasesCollapseButPrivateAliasesRemainUnresolved() {
        val direct = PlatformV2(uid = "a", name = "Direct", model = "gpt-4o-2024-08-06", apiUrl = "https://api.openai.com/v1", compatibleType = ClientType.OPENAI)
        val router = direct.copy(uid = "b", model = "openai/gpt-4o", apiUrl = "https://openrouter.ai/api/v1")
        assertEquals(ModelIndependence.SAME, CanonicalModelIdentity.independence(direct, router))
        assertNotEquals(CanonicalModelIdentity.resolve(direct).deployment, CanonicalModelIdentity.resolve(router).deployment)
        assertEquals(ModelIndependence.UNKNOWN, CanonicalModelIdentity.independence(direct, router.copy(apiUrl = "http://private:8080", model = "alias")))
        assertEquals(ModelIndependence.UNKNOWN, CanonicalModelIdentity.independence(direct, direct.copy(uid = "alias", model = "custom-alias")))
    }
}
