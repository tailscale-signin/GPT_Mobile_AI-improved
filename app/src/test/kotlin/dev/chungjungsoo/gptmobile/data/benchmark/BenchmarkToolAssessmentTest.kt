package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkToolAssessmentTest {
    private fun session(calls: Int = 1, answer: (String) -> String) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
            if (exchanges.size < calls) return flowOf(ProviderEvent.ToolCall("lookup-${exchanges.size}", "benchmark_lookup", buildJsonObject { put("key", "parcel") }), ProviderEvent.Completed)
            val record = (exchanges.last().results.single().content as ToolResultContent.Text).text
            val code = Regex("FINAL_PARCEL_CODE=([^\\s]+)").findAll(record).lastOrNull()?.groupValues?.get(1) ?: record
            return flowOf(ProviderEvent.TextDelta(answer(code)), ProviderEvent.Completed)
        }
    }
    private fun test(workload: String) = BenchmarkCapabilitySuiteV2.cases(0).single { it.workload == workload }

    @Test fun selectingASupersededResultDoesNotEraseSuccessfulToolExecution() = runTest {
        val sample = BenchmarkRunner({ _, _ -> session { "OLD-001" } }).run(test("distractor_selection"), true)
        assertEquals(BenchmarkOutcome.FAILED, sample.outcome)
        val observed = sample.toolAssessment!!
        assertEquals(true, observed.toolSelectionCorrect)
        assertEquals(true, observed.argumentsCorrect)
        assertEquals(true, observed.executionSuccessful)
        assertEquals(false, observed.resultUsed)
        assertEquals(false, observed.finalFormatCompliant)
        assertEquals(1, observed.successfulCalls)
    }

    @Test fun correctPermanentFailureHandlingIsDistinctFromASuccessfulToolExecution() = runTest {
        val sample = BenchmarkRunner({ _, _ -> session { "UNAVAILABLE" } }).run(test("permanent_error_decision"), true)
        assertEquals(BenchmarkOutcome.PASSED, sample.outcome)
        val observed = sample.toolAssessment!!
        assertEquals(false, observed.executionSuccessful)
        assertEquals(1, observed.expectedErrorCalls)
        assertEquals(0, observed.successfulCalls)
        assertEquals(true, observed.expectedRetryObserved)
        assertEquals(true, observed.callLimitRespected)
        assertEquals(true, observed.finalFormatCompliant)
    }

    @Test fun unauthorizedExtraCallFailsTheCallLimitWithoutLosingTheCorrectFinalResult() = runTest {
        val sample = BenchmarkRunner({ _, _ -> session(calls = 2) { it } }).run(test("tool_call_limit"), true)
        assertEquals(BenchmarkOutcome.FAILED, sample.outcome)
        assertEquals(2, sample.toolAssessment!!.attemptedCalls)
        assertEquals(true, sample.toolAssessment.executionSuccessful)
        assertEquals(true, sample.toolAssessment.resultUsed)
        assertEquals(false, sample.toolAssessment.callLimitRespected)
    }

    @Test fun typedProgressRemainsRawEvidenceAndDoesNotContaminateTheFinalChannel() = runTest {
        val sample = BenchmarkRunner({ _, _ -> session { "<progress_update>Reading returned evidence.</progress_update>$it" } }).run(test("final_result_handling"), true)
        assertEquals(BenchmarkOutcome.PASSED, sample.outcome)
        assertTrue(sample.canonicalAnswer!!.contains("<progress_update>"))
        assertFalse(sample.finalAnswer!!.contains("<progress_update>"))
        assertTrue(sample.outputParts.any { it.channel == BenchmarkOutputChannel.PUBLIC_PROGRESS })
        assertEquals(true, sample.toolAssessment!!.progressProtocolCompliant)
        assertEquals(true, sample.toolAssessment.finalFormatCompliant)
    }
}
