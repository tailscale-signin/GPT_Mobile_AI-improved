package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ProviderUsagePhase
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkEngineeringGenerationUsageTest {
    private fun usage(owner: String?, input: Int, output: Int, confirmed: Boolean = true) = ProviderEvent.Usage(
        inputTokens = input,
        outputTokens = output,
        effectiveSettingsConfirmed = confirmed,
        invocationId = owner
    )

    @Test fun usageWithoutPhysicalDispatchCoverageRetainsItsSubtotalWithoutCertifyingAllInCost() {
        val collector = BenchmarkEngineeringGenerationUsage()
        collector.invocation("logical-wrapper")
        collector.accept(usage("logical-wrapper", 100, 10))
        val result = collector.snapshot()
        assertEquals(100, result.input.value)
        assertEquals(10, result.output.value)
        assertFalse(result.input.complete)
        assertFalse(result.output.complete)
        assertFalse(result.settingsConfirmed)
    }

    @Test fun distinctPhysicalOwnersAreSummedAndCumulativeFramesStayWithinTheirOwner() {
        val collector = BenchmarkEngineeringGenerationUsage()
        collector.invocation("wrapper")
        // Buffered delivery may place both callbacks before either usage frame.
        collector.physicalRequest("first")
        collector.physicalRequest("second")
        collector.accept(usage("first", 90, 8).copy(phase = ProviderUsagePhase.INTERIM))
        collector.accept(usage("first", 100, 10))
        collector.accept(usage("second", 200, 20))
        val result = collector.snapshot()
        assertEquals(300, result.input.value)
        assertEquals(30, result.output.value)
        assertEquals("final_reported", result.input.confidence)
        assertEquals("final_reported", result.output.confidence)
        assertTrue(result.settingsConfirmed)
        assertEquals(1, result.retryAttempts)
        assertEquals(listOf("first", "second"), result.physicalRequestIds)
    }

    @Test fun missingEarlierUsagePreservesTheKnownSubtotalAndNeverReportsCompleteUsage() {
        val collector = BenchmarkEngineeringGenerationUsage()
        collector.invocation("wrapper")
        collector.physicalRequest("first")
        collector.physicalRequest("second")
        collector.accept(usage("second", 200, 20))
        val result = collector.snapshot()
        assertEquals(200, result.input.value)
        assertEquals(20, result.output.value)
        assertEquals("partial_observation", result.input.confidence)
        assertEquals("partial_observation", result.output.confidence)
        assertFalse(result.settingsConfirmed)
        assertEquals("false", result.evidence["usageOwnershipComplete"])
        assertEquals(1, result.retryAttempts)
    }

    @Test fun oneLedgerOwnerWrappingTwoRequestsCannotCertifyTheLastAttemptAsAllInUsage() {
        val collector = BenchmarkEngineeringGenerationUsage()
        collector.invocation("ledger")
        collector.physicalRequest("first")
        collector.accept(usage("ledger", 100, 10))
        collector.physicalRequest("second")
        collector.accept(usage("ledger", 200, 20, confirmed = false))
        val result = collector.snapshot()
        assertEquals(200, result.input.value)
        assertEquals(20, result.output.value)
        assertFalse(result.input.complete)
        assertFalse(result.output.complete)
        assertFalse(result.settingsConfirmed)
        assertEquals("1", result.evidence["unattributedUsageOwners"])
    }

    @Test fun settingsConfirmationDoesNotCarryAcrossDistinctAttempts() {
        val collector = BenchmarkEngineeringGenerationUsage()
        collector.physicalRequest("first")
        collector.physicalRequest("second")
        collector.accept(usage("first", 100, 10))
        collector.accept(usage("second", 200, 20, confirmed = false))
        val result = collector.snapshot()
        assertTrue(result.input.complete)
        assertTrue(result.output.complete)
        assertFalse(result.settingsConfirmed)
    }

    @Test fun separateLedgerInvocationsCanEachOwnOneObservedPhysicalRequest() {
        val collector = BenchmarkEngineeringGenerationUsage()
        collector.invocation("first-ledger")
        collector.physicalRequest("first-request")
        collector.invocation("second-ledger")
        collector.physicalRequest("second-request")
        collector.accept(usage("first-ledger", 100, 10))
        collector.accept(usage("second-ledger", 200, 20))
        val result = collector.snapshot()
        assertEquals(300, result.input.value)
        assertEquals(30, result.output.value)
        assertTrue(result.input.complete)
        assertTrue(result.output.complete)
        assertTrue(result.settingsConfirmed)
    }

    @Test fun unknownRetryOwnershipAndUnlabelledMultiRequestUsageStayIncomplete() {
        val collector = BenchmarkEngineeringGenerationUsage()
        collector.physicalRequest("only-observed")
        collector.accept(usage("only-observed", 100, 10).copy(retryAttempts = 2))
        assertFalse(collector.snapshot().input.complete)
        assertFalse(collector.snapshot().settingsConfirmed)
        assertEquals(2, collector.snapshot().retryAttempts)

        val unlabelled = BenchmarkEngineeringGenerationUsage()
        unlabelled.physicalRequest("first")
        unlabelled.physicalRequest("second")
        unlabelled.accept(usage(null, 200, 20))
        assertEquals(200, unlabelled.snapshot().input.value)
        assertFalse(unlabelled.snapshot().input.complete)
        assertFalse(unlabelled.snapshot().settingsConfirmed)
    }

    @Test fun overlappingWrapperAndPhysicalReceiptsAreNeverDoubleChargedOrCertified() {
        val collector = BenchmarkEngineeringGenerationUsage()
        collector.invocation("ledger")
        collector.physicalRequest("request")
        collector.accept(usage("ledger", 100, 10))
        collector.accept(usage("request", 100, 10))
        val result = collector.snapshot()
        assertEquals(100, result.input.value)
        assertEquals(10, result.output.value)
        assertFalse(result.input.complete)
        assertFalse(result.settingsConfirmed)
    }

    @Test fun interimCountsCannotBecomeFinalBecauseAnotherOwnerCompleted() {
        val collector = BenchmarkEngineeringGenerationUsage()
        collector.physicalRequest("first")
        collector.physicalRequest("second")
        collector.accept(usage("first", 100, 10).copy(phase = ProviderUsagePhase.INTERIM))
        collector.accept(usage("second", 200, 20))
        val result = collector.snapshot()
        assertEquals(300, result.input.value)
        assertEquals(30, result.output.value)
        assertFalse(result.input.complete)
        assertFalse(result.output.complete)
    }

    @Test fun optionalReasoningAndCacheCountersRequireTheirOwnCompleteOwnerCoverage() {
        val collector = BenchmarkEngineeringGenerationUsage()
        collector.physicalRequest("first")
        collector.physicalRequest("second")
        collector.accept(usage("first", 100, 10).copy(reasoningTokens = 4, cachedPromptTokens = 40))
        collector.accept(usage("second", 200, 20))
        var result = collector.snapshot()
        assertTrue(result.input.complete)
        assertTrue(result.output.complete)
        assertEquals(4, result.reasoning.value)
        assertFalse(result.reasoning.complete)
        assertEquals(40, result.cached.value)
        assertFalse(result.cached.complete)
        collector.accept(usage("second", 200, 20).copy(reasoningTokens = 6, cachedPromptTokens = 60))
        result = collector.snapshot()
        assertEquals(10, result.reasoning.value)
        assertTrue(result.reasoning.complete)
        assertEquals(100, result.cached.value)
        assertTrue(result.cached.complete)
    }

    @Test fun aSaturatedTotalCannotBeAdvertisedAsAnExactFinalCount() {
        val collector = BenchmarkEngineeringGenerationUsage()
        collector.physicalRequest("first")
        collector.physicalRequest("second")
        collector.accept(usage("first", Int.MAX_VALUE, 10))
        collector.accept(usage("second", 1, 20))
        assertEquals(Int.MAX_VALUE, collector.snapshot().input.value)
        assertFalse(collector.snapshot().input.complete)
        assertTrue(collector.snapshot().output.complete)
    }

    @Test fun generationSnapshotAndInterruptedSnapshotExposeIncompletePhysicalUsage() = runBlocking {
        val profile = PlatformV2(name = "Selected", uid = "selected", model = "selected-model")
        val test = BenchmarkCase("test", "Test", "engineering", "Repair the fixture")
        val digest = "a".repeat(64)
        val plan = BenchmarkEngineeringPlan(
            "experiment", BenchmarkEngineeringLane.EXECUTABLE_CODING, 3, BenchmarkEngineeringOrdering.SELECTED_ORDER,
            listOf(profile.uid), mapOf(profile.uid to digest), digest, BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1,
            BenchmarkEngineeringBudget(), "b".repeat(40), digest, digest
        ).frozen()
        suspend fun generate(interrupt: Boolean): BenchmarkSample {
            val chats = mockk<ChatRepository>()
            coEvery { chats.openBenchmarkSession(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val invocation = arg<(String) -> Unit>(5)
                val physical = arg<(String) -> Unit>(6)
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        invocation("ledger")
                        physical("first")
                        physical("second")
                        emit(ProviderEvent.TextDelta("{\"changes\":[]}"))
                        emit(usage("second", 200, 20).copy(cachedPromptTokens = 40))
                        if (interrupt) throw CancellationException("test interrupted")
                        emit(ProviderEvent.Completed)
                    }
                }
            }
            var interrupted: BenchmarkSample? = null
            val result = runCatching {
                benchmarkEngineeringGeneration(chats, profile, test, test.prompt, null, plan, "logical", 10_000) { interrupted = it }
            }
            if (interrupt) {
                assertTrue(result.exceptionOrNull() is CancellationException)
                assertNotNull(interrupted)
                return checkNotNull(interrupted)
            }
            assertNull(interrupted)
            return result.getOrThrow()
        }
        for (interrupted in listOf(false, true)) {
            val result = generate(interrupted)
            assertEquals(200, result.inputTokens)
            assertEquals(20, result.outputTokens)
            assertTrue(result.estimatedTokens)
            assertEquals("partial_observation", result.inputTokenConfidence)
            assertEquals("partial_observation", result.outputTokenConfidence)
            assertTrue(result.rounds.single().estimatedOutput)
            assertFalse(result.effectiveSettingsConfirmed)
            assertNull(result.reportedCachedPromptTokens)
            assertEquals("40", result.adapterRequestSettings["recordedCachedTokenSubtotal"])
            assertEquals(1, result.reconnectAttempts)
            assertEquals(if (interrupted) BenchmarkOutcome.CANCELED else BenchmarkOutcome.PARTIAL, result.outcome)
        }
    }
}
