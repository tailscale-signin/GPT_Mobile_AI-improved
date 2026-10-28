package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedAppendPolicyTest {
    private fun policy(append: Boolean) = CombinedPolicyResolver.accept(
        ConversationCombinedSettings(continueLongAnswers = append),
        listOf("p"),
        mapOf("p" to "model"),
        "Task",
        acceptedAtMillis = 0,
        singlePass = true
    )

    @After fun clearObservations() = CombinedWriterObservations.clearForTest()

    @Test fun strictDefaultsSealNewSynthesisPreferencesWithoutGrantingContinuation() {
        val strict = policy(false)
        val wire = Json.encodeToString(strict.copy(requestedPolicyDigest = ""))
        assertTrue(wire.contains("\"synthesisIncremental\":true"))
        assertEquals(combinedDigest(wire), strict.requestedPolicyDigest)
        assertFalse(strict.appendContinuations)
        val archived = Json { encodeDefaults = true }.encodeToString(strict)
        assertFalse(archived.contains("completionPolicy"))
        assertEquals(strict, Json.decodeFromString<CombinedRequestedPolicy>(archived))
        val legacy = Json.decodeFromString<CombinedRequestedPolicy>("""{"participantUids":["p"],"requestedModels":{"p":"model"},"userPrompt":"Task","singlePass":true}""")
        assertFalse(legacy.incrementalSynthesis)
        assertFalse(legacy.appendContinuations)
    }

    @Test fun appendPermissionIsVersionedSealedAndRoundTrips() {
        val append = policy(true)
        assertEquals(2, append.version)
        assertEquals("APPEND_CONTINUATIONS_V1", append.completionPolicy)
        assertTrue(append.singlePass && append.appendContinuations)
        assertFalse(append.requestedPolicyDigest == policy(false).requestedPolicyDigest)
        val restored = Json.decodeFromString<CombinedRequestedPolicy>(Json.encodeToString(append))
        assertEquals(append, restored)
        assertEquals(append.requestedPolicyDigest, CombinedPolicyResolver.resolve(restored).requested.requestedPolicyDigest)
        assertTrue(runCatching { CombinedPolicyResolver.resolve(append.copy(settings = ConversationCombinedSettings(continueLongAnswers = false))) }.isFailure)
        assertFalse(policy(false).copy(settings = ConversationCombinedSettings(continueLongAnswers = true)).appendContinuations)
    }

    @Test fun disabledFeatureCannotAcceptAdditionalRequests() {
        assertTrue(
            runCatching {
                CombinedPolicyResolver.accept(ConversationCombinedSettings(continueLongAnswers = true), listOf("p"), mapOf("p" to "model"), "Task", singlePass = true, appendFeatureEnabled = false)
            }.isFailure
        )
    }

    @Test fun usefulPerRequestAllowanceDoesNotRelaxTheCompleteInputRequirement() {
        val writer = PlatformV2(uid = "p", name = "Writer", compatibleType = ClientType.CUSTOM, model = "model", apiUrl = "https://writer.example/v1", enabled = true, maxTokens = 8192)
        val task = "Write about history in 20000 words."
        assertTrue(CombinedWriterPolicy.unavailableReason(writer, task) != null)
        assertEquals(null, CombinedWriterPolicy.unavailableReason(writer, task, appendContinuations = true))
        val planned = CombinedWriterRequestPlan.freeze(writer, task, "Full frozen contributions", "Write the answer", emptyList(), "PASSAGES_V3", 8192, appendContinuations = true)
        assertEquals(8192, planned.effectiveOutputTokens)
        assertTrue(planned.requiredOutputTokens < CombinedWriterPolicy.requiredOutput(task, writer))
        assertTrue(
            runCatching {
                CombinedWriterRequestPlan.freeze(writer, task, "Complete source input ".repeat(20000), "Write the answer", emptyList(), "PASSAGES_V3", 8192, userContextLimit = 12000, appendContinuations = true)
            }.exceptionOrNull() is CombinedInputCapacityException
        )
        assertTrue(
            runCatching {
                CombinedWriterRequestPlan.freeze(writer, task, "Full frozen contributions", "Write the answer", emptyList(), "PASSAGES_V3", 8192, userOutputLimit = 256, appendContinuations = true)
            }.isFailure
        )
    }

    @Test fun appendedRequestsRequireBoundWriterAndControllerWorkAndKeepCharges() = runTest {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(continueLongAnswers = true), listOf("p", "q"), mapOf("p" to "model", "q" to "model"), "Task", singlePass = true)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root", CombinedRunRole.SYNTHESIS, "p")
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        budget.bindAppendWriter("p", "frozen-plan")
        assertTrue(budget.reserve(context, "initial", CombinedResourceQuota(10, 20)) is CombinedBudgetAdmission.Granted)
        val continued = context.forRole(CombinedRunRole.CONTINUATION, "p", "cursor-2")
        budget.authorizeRequiredWork(CombinedWorkItem("cursor-2", CombinedRunRole.CONTINUATION), CombinedResourceQuota(12, 20), "REQUIRED_COVERAGE_GAP")
        assertTrue(budget.reserve(continued, "overlap", CombinedResourceQuota(12, 20)) is CombinedBudgetAdmission.Denied)
        budget.unknown("initial", "Completed response without measured usage; upper bound retained")
        assertTrue(budget.reserve(continued.forRole(CombinedRunRole.CONTINUATION, "q", "cursor-2"), "different-writer", CombinedResourceQuota(12, 20)) is CombinedBudgetAdmission.Denied)
        assertTrue(budget.reserve(continued, "append", CombinedResourceQuota(12, 20)) is CombinedBudgetAdmission.Granted)
        assertEquals(CombinedResourceQuota(22, 40), budget.snapshot().charged)
        assertTrue(budget.reserve(context, "second-initial", CombinedResourceQuota(10, 20)) is CombinedBudgetAdmission.Denied)
        for (role in listOf(CombinedRunRole.REPAIR, CombinedRunRole.VERIFIER)) {
            assertTrue(budget.reserve(context.forRole(role), role.name, CombinedResourceQuota(10, 20)) is CombinedBudgetAdmission.Denied)
        }
    }

    @Test fun knownPreflightRejectionCanContinueWithoutPretendingTheFirstRequestWasSent() = runTest {
        val requested = policy(true)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root", CombinedRunRole.SYNTHESIS, "p")
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        budget.bindAppendWriter("p", "frozen-plan")
        budget.authorizeRequiredWork(CombinedWorkItem("saved-rejection", CombinedRunRole.CONTINUATION), CombinedResourceQuota(10, 20), "REQUIRED_COVERAGE_GAP")
        assertTrue(budget.reserve(context.forRole(CombinedRunRole.CONTINUATION, "p", "saved-rejection"), "first-physical-request", CombinedResourceQuota(10, 20)) is CombinedBudgetAdmission.Granted)
        assertEquals(1, budget.snapshot().reservations.size)
    }

    @Test fun appendPermissionCannotRaiseTheAcceptedTurnHardLimit() = runTest {
        val requested = policy(true)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested, hardLimits = CombinedHardLimits(generatedTokens = 30)), "root", CombinedRunRole.SYNTHESIS, "p")
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        budget.bindAppendWriter("p", "frozen-plan")
        assertTrue(budget.reserve(context, "initial", CombinedResourceQuota(10, 20)) is CombinedBudgetAdmission.Granted)
        budget.unknown("initial", "Measured usage unavailable")
        assertTrue(
            runCatching {
                budget.authorizeRequiredWork(CombinedWorkItem("too-large", CombinedRunRole.CONTINUATION), CombinedResourceQuota(10, 20), "REQUIRED_COVERAGE_GAP")
            }.exceptionOrNull() is CombinedBudgetExceeded
        )
        assertEquals(CombinedResourceQuota(10, 20), budget.snapshot().charged)
    }
}
