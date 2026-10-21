package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.ReasoningRepairPolicy
import dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind
import dev.chungjungsoo.gptmobile.data.agent.tool.DelegateGenerationException
import java.io.IOException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolAndUsageRecoveryTest {
    @Test fun splitControlFramesCannotReachRecoveryDraftOrDispatch() = runTest {
        val checkpoints = mutableListOf<String>()
        val raw = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta("Useful fact. <tool_"))
                emit(ProviderEvent.TextDelta("call>{\"name\":\"write\"}</tool_call>"))
                emit(ProviderEvent.Completed)
            }
        }
        val normalized = raw.withNormalizedAssistantText()
        assertSame(normalized, normalized.withNormalizedAssistantText())
        val recovery = OutputLimitRecoverySession(normalized, saveProgress = { text, _, _, _ -> checkpoints += text }) { _, _ -> error("A protocol failure cannot become output continuation") }
        val events = recovery.streamRound(emptyList(), emptyList()).toList()
        assertEquals("Useful fact. ", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(checkpoints.all { "tool_" !in it && "write" !in it })
        assertEquals("TOOL_PROTOCOL_INVALID_FRAME", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertFalse(events.any { it == ProviderEvent.Completed || it is ProviderEvent.ToolCall })
    }

    @Test fun finalUsageReplacesHigherEstimateAndMetadataOnlyFramesCannotPromoteInterimCounts() = runTest {
        val counter = ProviderUsageCounter()
        counter.accept(300, true, "estimated", ProviderUsagePhase.INTERIM)
        counter.accept(30, true, "runtime_reported", ProviderUsagePhase.FINAL)
        assertEquals(30L, counter.value)
        assertTrue(measuredUsageSource(counter.source, counter.phase))
        counter.accept(500, true, "provider_reported", ProviderUsagePhase.INTERIM)
        assertEquals(30L, counter.value)
        val usage = flow {
            emit(ProviderEvent.Usage(20, 2, 22, phase = ProviderUsagePhase.INTERIM))
            emit(ProviderEvent.Usage(finishReason = "stop"))
        }.withSummedRequestUsage().toList().filterIsInstance<ProviderEvent.Usage>().single()
        assertEquals(ProviderUsagePhase.INTERIM, usage.phase)
        assertEquals("provider_reported_interim", usage.outputSource)
        assertFalse(measuredUsageSource(usage.outputSource, usage.phase))
    }

    @Test fun runnerKeepsCompletedTransportProofWithoutAcceptingFailedStage() = runTest {
        val raw = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.ThinkingDelta("thinking"))
                emit(ProviderEvent.Usage(10, 100, 110, outputSource = "runtime_reported", phase = ProviderUsagePhase.FINAL))
                emit(ProviderEvent.Failed("The response reached its output limit.", transportComplete = true))
            }
        }
        val events = AgentRunner().run(raw, emptyList()).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().transportComplete)
        assertFalse(events.any { it == ProviderEvent.Completed })
        assertEquals("runtime_reported", events.filterIsInstance<ProviderEvent.Usage>().single().outputSource)
    }

    @Test fun lateTransportFailureClearsCompletionProofButRetainsInterimUsage() = runTest {
        val raw = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.Usage(outputTokens = 100, phase = ProviderUsagePhase.INTERIM))
                emit(ProviderEvent.Failed("The response reached its output limit.", transportComplete = true))
                throw IOException("socket interrupted")
            }
        }
        val events = AgentRunner().run(raw, emptyList()).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertFalse(events.filterIsInstance<ProviderEvent.Failed>().single().transportComplete)
        assertEquals(ProviderUsagePhase.INTERIM, events.filterIsInstance<ProviderEvent.Usage>().single().phase)
    }

    @Test fun reasoningRepairRequiresCompletionAndRespectsVerifiedAndExplicitLimits() {
        assertEquals(4096, ReasoningRepairPolicy.nextCap(2048, true, false, false))
        assertEquals(3000, ReasoningRepairPolicy.nextCap(2048, true, false, false, providerCeiling = 3000))
        assertEquals(2500, ReasoningRepairPolicy.nextCap(2048, true, false, false, userCap = 2500))
        assertNull(ReasoningRepairPolicy.nextCap(2048, true, false, false, userCap = 2048))
        assertNull(ReasoningRepairPolicy.nextCap(2048, false, false, false))
        assertNull(ReasoningRepairPolicy.nextCap(2048, true, true, false))
        assertNull(ReasoningRepairPolicy.nextCap(2048, true, false, true))
        assertNull(ReasoningRepairPolicy.nextCap(Int.MAX_VALUE, true, false, false))
    }

    @Test fun privatePartialIsUtf8BoundedAndDoesNotClaimPersistedOrValidated() {
        val failure = DelegateGenerationException(DelegateFailureKind.TRANSPORT_FAILURE, "Interrupted", 1, 1, 2, true, "漢字".repeat(50000))
        assertTrue(failure.partialText.toByteArray().size <= 128 * 1024)
        assertFalse(failure.partialText.contains('\uFFFD'))
        assertFalse(failure.message.orEmpty().contains("漢"))
        assertFalse(failure.transportComplete || failure.schemaValid || failure.stageAccepted)
    }
}
