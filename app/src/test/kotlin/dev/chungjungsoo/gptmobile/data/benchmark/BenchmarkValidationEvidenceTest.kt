package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics
import dev.chungjungsoo.gptmobile.data.localruntime.NativeInferenceMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkValidationEvidenceTest {
    private fun session(events: Flow<ProviderEvent>) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = events
    }

    @Test fun historical117WordRainEosRetainsRawTimingAndExplicitWordRangeFailure() = runTest {
        val fixture = benchmarkSuiteForVersion(8, BenchmarkMode.FULL).first { it.id == "speed-short-1" }
        val answer = listOf("evaporation", "condensation", "cloud", "precipitation").plus(List(113) { "water" }).joinToString(" ")
        val sample = BenchmarkRunner(
            { _, _ ->
                session(
                    flow {
                        delay(10)
                        emit(ProviderEvent.TextDelta(answer.take(30)))
                        delay(100)
                        emit(ProviderEvent.TextDelta(answer.drop(30)))
                        emit(ProviderEvent.LocalMetrics(LocalInferenceMetrics(native = NativeInferenceMetrics(80, 130, 80.0, 65.0, decodeDurationMs = 2000))))
                        emit(ProviderEvent.Usage(finishReason = "stop"))
                        emit(ProviderEvent.Completed)
                    }
                )
            },
            now = { testScheduler.currentTime }
        ).run(fixture, false)
        assertEquals(BenchmarkOutcome.FAILED, sample.outcome)
        assertEquals("benchmark-validator-v3", sample.validatorVersion)
        assertEquals(answer, sample.canonicalAnswer)
        assertEquals(benchmarkHash(answer), sample.answerHash)
        assertEquals(117, sample.validation?.observedWordCount)
        assertEquals(180, sample.validation?.minimumWords)
        assertEquals(220, sample.validation?.maximumWords)
        assertEquals(false, sample.validation?.lengthCompliant)
        assertTrue(sample.validation!!.requiredConcepts.values.all { it })
        assertEquals(false, sample.timingEligible)
        assertNotNull(sample.rawReferenceSpeed)
        val run = BenchmarkRun("run", "profile", "Profile", "CUSTOM", "model", "key", false, BenchmarkMode.FULL, 1, listOf(sample), suiteVersion = 8, measurementVersion = 3, plannedTrials = 50)
        val metrics = benchmarkMetricResults(listOf(run)).associateBy { it.id }
        assertNull(metrics.getValue("reference_decode_rate").value)
        assertNotNull(metrics.getValue("raw_reference_decode_rate").value)
        assertEquals(65.0, metrics.getValue("raw_native_decode_rate").value!!, .001)
        assertEquals(10.0, metrics.getValue("raw_first_visible_text").value!!, .001)
    }

    @Test fun missingKilogramsIsCorrectNumericValueButFailsTheFrozenExactFormat() = runTest {
        val fixture = benchmarkSuiteForVersion(8, BenchmarkMode.FULL).single { it.id == "extract-0" }
        val sample = BenchmarkRunner({ _, _ -> session(flowOf(ProviderEvent.TextDelta("12"), ProviderEvent.Completed)) }).run(fixture, false)
        assertEquals(BenchmarkOutcome.FAILED, sample.outcome)
        assertEquals(true, sample.validation?.factualCorrect)
        assertEquals(false, sample.validation?.unitsCompliant)
        assertEquals(false, sample.validation?.formatCompliant)
        assertEquals("12 kg", sample.validation?.expectedValue)
        assertEquals("12", sample.validation?.observedValue)
        assertEquals("benchmark-validator-v3", sample.validatorVersion)
        val revised = benchmarkSuiteForVersion(9, BenchmarkMode.FULL).single { it.id == fixture.id }
        assertTrue(revised.prompt.contains("one space, and kg"))
        assertEquals("benchmark-validator-v4", revised.validatorVersion)
        assertEquals(fixture.prompt, benchmarkSuiteForVersion(8, BenchmarkMode.FULL).single { it.id == fixture.id }.prompt)
    }

    @Test fun codeWithAdditionalTextSeparatesToolSuccessFromFinalFormat() = runTest {
        val fixture = benchmarkSuiteForVersion(8, BenchmarkMode.QUICK).single { it.category == "tools" }
        val sample = toolRunner { code -> "The requested code is $code." }.run(fixture, true)
        assertEquals(BenchmarkOutcome.FAILED, sample.outcome)
        assertEquals(true, sample.validation?.toolExecutionSuccessful)
        assertEquals(true, sample.validation?.factualCorrect)
        assertEquals(false, sample.validation?.formatCompliant)
        assertEquals(true, sample.validation?.protocolCompliant)
        assertEquals(sample.canonicalAnswer, sample.finalAnswer)
    }

    @Test fun closedProgressHasAnExplicitVersionedBoundaryAndNeverRegradesLegacyOutput() = runTest {
        val legacy = benchmarkSuiteForVersion(8, BenchmarkMode.QUICK).single { it.category == "tools" }
        val current = benchmarkSuiteForVersion(9, BenchmarkMode.QUICK).single { it.category == "tools" }
        for ((fixture, expected) in listOf(legacy to BenchmarkOutcome.FAILED, current to BenchmarkOutcome.PASSED)) {
            val sample = toolRunner { code -> "<progress_update>Reading the fixture.</progress_update>\n$code" }.run(fixture, true)
            assertEquals(expected, sample.outcome)
            val raw = checkNotNull(sample.canonicalAnswer)
            assertTrue(raw.contains("<progress_update>"))
            assertEquals(benchmarkHash(raw), sample.answerHash)
            assertEquals(listOf(BenchmarkOutputChannel.PUBLIC_PROGRESS, BenchmarkOutputChannel.FINAL_ANSWER), sample.outputParts.map { it.channel })
            val progress = sample.outputParts.first()
            assertEquals("Reading the fixture.", raw.substring(progress.startUtf16, progress.endUtf16))
            assertEquals(sample.validation?.expectedValue, sample.finalAnswer)
            assertEquals(fixture.outputPolicy, sample.validation?.outputPolicy)
        }
    }

    @Test fun malformedProgressAndRepetitionRemainFailedWithRawEvidence() = runTest {
        val fixture = benchmarkSuiteForVersion(9, BenchmarkMode.QUICK).single { it.category == "tools" }
        val malformed = toolRunner { code -> "<progress_update>working $code" }.run(fixture, true)
        assertEquals(BenchmarkOutcome.FAILED, malformed.outcome)
        assertEquals(false, malformed.validation?.protocolCompliant)
        assertEquals(BenchmarkOutputChannel.UNPARSED, malformed.outputParts.single().channel)
        val repeated = toolRunner { _ -> "<progress_update>Looking up the record.</progress_update>" + "This answer repeats an irrelevant sentence. ".repeat(8) }.run(fixture, true)
        assertEquals(BenchmarkOutcome.FAILED, repeated.outcome)
        assertEquals(true, repeated.validation?.toolExecutionSuccessful)
        assertEquals(false, repeated.validation?.factualCorrect)
        assertTrue(repeated.repetitionDiagnostic!!.startsWith("REPEATED_PASSAGE:"))
        assertTrue(repeated.canonicalAnswer!!.contains("<progress_update>"))
    }

    @Test fun literalProgressTagsInJsonAreDataAndDoNotIntroduceAnOutputChannel() {
        val raw = "{\"text\":\"<progress_update>literal</progress_update>\"}"
        val fixture = BenchmarkCase("json", "JSON", "json", "Return the object", raw, validatorVersion = "benchmark-validator-v4")
        val validation = benchmarkValidation(fixture, raw, raw, raw, true, null)
        assertEquals(true, validation.protocolCompliant)
        assertEquals(true, validation.formatCompliant)
    }

    private fun toolRunner(answer: (String) -> String) = BenchmarkRunner({ _, _ ->
        object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (exchanges.isEmpty()) {
                    emit(ProviderEvent.ToolCall("lookup", "benchmark_lookup", buildJsonObject { put("key", "parcel") }))
                } else {
                    val code = (exchanges.last().results.single().content as ToolResultContent.Text).text.substringAfter("FINAL_PARCEL_CODE=")
                    emit(ProviderEvent.TextDelta(answer(code)))
                }
                emit(ProviderEvent.Completed)
            }
        }
    })
}
