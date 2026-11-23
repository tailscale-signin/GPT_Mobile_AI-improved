package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechBenchmarkCancellationTest {
    @Test
    fun preparationCancellationRetainsObservedPreparationExactlyOnce() {
        val clock = Clock()
        val runner = runner(clock) {
            clock.advance(100)
            throw CancellationException("Preparation cancelled")
        }

        val report = runner.run(initial()) {}
        val result = cancelledResult(report)

        assertEquals(100L, result.sessionPrepareMs)
        assertEquals(100L, result.measuredCostMs)
        assertNoInterruptedGeneration(result)
    }

    @Test
    fun cancellationImmediatelyAfterCreateKeepsPreparationAndClosesEngine() {
        val clock = Clock()
        val engine = Engine(clock)
        lateinit var runner: VoiceSpeechBenchmarkRunner
        runner = runner(clock) {
            clock.advance(10)
            runner.cancel()
            engine
        }

        val report = runner.run(initial()) {}
        val result = cancelledResult(report)

        assertEquals(10L, result.sessionPrepareMs)
        assertEquals(10L, result.measuredCostMs)
        assertEquals(0, engine.calls)
        assertTrue(engine.closed)
        assertNoInterruptedGeneration(result)
    }

    @Test
    fun firstGenerationCancellationRetainsCurrentAttemptWithoutExecutionEvidence() {
        val (report, engine) = generationFailure(call = 1)
        val result = cancelledResult(report)

        assertEquals(60L, result.measuredCostMs)
        assertEquals(0, result.samples.size)
        assertEquals(0, result.incompleteChunks.size)
        assertEquals(0, engine.evidenceReads)
        assertInterruptedGeneration(result, VoiceSpeechBenchmarkFixture.SHORT, chunk = 0)
    }

    @Test
    fun laterGenerationCancellationKeepsCompletedSamplesAndPartialChunks() {
        val (report, engine) = generationFailure(call = 5)
        val result = cancelledResult(report)

        assertEquals(260L, result.measuredCostMs)
        assertEquals(3, result.samples.size)
        assertEquals(1, result.incompleteChunks.size)
        assertEquals(VoiceSpeechBenchmarkFixture.MEDIUM, result.incompleteFixture)
        assertEquals(0, result.incompleteRepeat)
        assertEquals(4, engine.evidenceReads)
        assertInterruptedGeneration(result, VoiceSpeechBenchmarkFixture.MEDIUM, chunk = 1)
    }

    @Test
    fun cancellationAfterGenerateReturnsDoesNotReadOrBorrowAnEarlierReceipt() {
        val (report, engine) = generationFailure(call = 5, cancelAfterReturn = true)
        val result = cancelledResult(report)

        assertEquals(260L, result.measuredCostMs)
        assertEquals(3, result.samples.size)
        assertEquals(1, result.incompleteChunks.size)
        assertEquals(4, engine.evidenceReads)
        assertInterruptedGeneration(result, VoiceSpeechBenchmarkFixture.MEDIUM, chunk = 1)
    }

    @Test
    fun partialCancellationRetainsCostAndContextThroughHistoryRoundTrip() {
        val (report) = generationFailure(call = 5)
        val persisted = speechBenchmarkJson(report, local = true).toString()
        val restored = checkNotNull(speechBenchmarkReportFromJson(Json.parseToJsonElement(persisted).jsonObject))

        assertEquals(report, restored)
        assertEquals(260L, cancelledResult(restored).measuredCostMs)
        assertInterruptedGeneration(restored.results.single(), VoiceSpeechBenchmarkFixture.MEDIUM, chunk = 1)
        val exported = Json.parseToJsonElement(voiceSpeechBenchmarkExport(restored)).jsonObject
            .getValue("results").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(50), exported["failedAttemptMs"])
        assertEquals(JsonPrimitive("MEDIUM"), exported["failedFixture"])
        assertEquals(JsonPrimitive(0), exported["failedRepeat"])
        assertEquals(JsonPrimitive(1), exported["failedChunk"])
    }

    @Test
    fun cancellationBetweenIterationsDoesNotAttributeTheLastCompletedChunk() {
        betweenCalls(VoiceSpeechBenchmarkFixture.SHORT, expectedCalls = 1, completedSamples = 1, partialChunks = 0)
    }

    @Test
    fun cancellationBetweenChunksDoesNotAttributeTheLastCompletedChunk() {
        betweenCalls(VoiceSpeechBenchmarkFixture.MEDIUM, expectedCalls = 4, completedSamples = 3, partialChunks = 1)
    }

    @Test
    fun ordinaryFailureStillRetainsCurrentAttemptAndCompletedWork() {
        val (report, engine) = generationFailure(call = 5, cancelled = false)
        val result = report.results.single()

        assertEquals(VoiceSpeechBenchmarkBackendState.FAILED, result.state)
        assertEquals(260L, result.measuredCostMs)
        assertEquals(10L, result.sessionPrepareMs)
        assertEquals(50L, result.failedAttemptMs)
        assertEquals(VoiceSpeechBenchmarkFixture.MEDIUM, result.failedFixture)
        assertEquals(0, result.failedRepeat)
        assertEquals(1, result.failedChunk)
        assertEquals(3, result.samples.size)
        assertEquals(1, result.incompleteChunks.size)
        assertEquals(4, engine.evidenceReads)
        assertEquals(0, report.qualifiedResults().size)
    }

    private fun generationFailure(call: Int, cancelled: Boolean = true, cancelAfterReturn: Boolean = false): Pair<VoiceSpeechBenchmarkReport, Engine> {
        val clock = Clock()
        lateinit var runner: VoiceSpeechBenchmarkRunner
        val engine = Engine(clock) { count ->
            if (count == call) {
                when {
                    cancelAfterReturn -> runner.cancel()
                    cancelled -> throw CancellationException("Generation cancelled")
                    else -> error("EXECUTION_FAILED")
                }
            }
        }
        runner = runner(clock) {
            clock.advance(10)
            engine
        }
        val report = runner.run(initial()) {}
        assertTrue(engine.closed)
        assertEquals(call, engine.calls)
        return report to engine
    }

    private fun betweenCalls(fixture: VoiceSpeechBenchmarkFixture, expectedCalls: Int, completedSamples: Int, partialChunks: Int) {
        val clock = Clock()
        val engine = Engine(clock)
        val runner = runner(clock) {
            clock.advance(10)
            engine
        }
        var requested = false
        val report = runner.run(initial()) { current ->
            val result = current.results.single()
            if (!requested && result.incompleteFixture == fixture && result.incompleteRepeat == 0 && result.incompleteChunks.size == 1) {
                requested = true
                runner.cancel()
            }
        }
        val result = cancelledResult(report)

        assertTrue(requested)
        assertTrue(engine.closed)
        assertEquals(expectedCalls, engine.calls)
        assertEquals(completedSamples, result.samples.size)
        assertEquals(partialChunks, result.incompleteChunks.size)
        assertEquals(10L + expectedCalls * 50, result.measuredCostMs)
        assertNoInterruptedGeneration(result)
    }

    private fun cancelledResult(report: VoiceSpeechBenchmarkReport): VoiceSpeechBenchmarkBackendResult {
        assertEquals(VoiceSpeechBenchmarkRunState.CANCELLED, report.state)
        assertEquals(0, report.qualifiedResults().size)
        assertNull(report.recommendation())
        assertNull(report.activeFixture)
        assertNull(report.activeRepeat)
        assertNull(report.activeChunk)
        return report.results.single().also { assertEquals(VoiceSpeechBenchmarkBackendState.CANCELLED, it.state) }
    }

    private fun assertInterruptedGeneration(result: VoiceSpeechBenchmarkBackendResult, fixture: VoiceSpeechBenchmarkFixture, chunk: Int) {
        assertEquals(10L, result.sessionPrepareMs)
        assertEquals(50L, result.failedAttemptMs)
        assertEquals(fixture, result.failedFixture)
        assertEquals(0, result.failedRepeat)
        assertEquals(chunk, result.failedChunk)
        assertEquals(VoiceSpeechWorkload.phrases(fixture, VoiceSpeechBenchmarkWorkload.UTTERANCE)[chunk].toByteArray(Charsets.UTF_8).size, result.failedInputBytes)
        assertEquals(VoiceSpeechWorkload.MAX_PHRASE_BYTES, result.failedInputByteLimit)
        assertNoFailureEvidence(result)
    }

    private fun assertNoInterruptedGeneration(result: VoiceSpeechBenchmarkBackendResult) {
        assertNull(result.failedAttemptMs)
        assertNull(result.failedFixture)
        assertNull(result.failedRepeat)
        assertNull(result.failedChunk)
        assertNull(result.failedInputBytes)
        assertNull(result.failedInputByteLimit)
        assertNoFailureEvidence(result)
    }

    private fun assertNoFailureEvidence(result: VoiceSpeechBenchmarkBackendResult) {
        assertNull(result.failureObservation)
        assertNull(result.failureEffectiveProvider)
        assertNull(result.failureAcceleratorKernels)
        assertNull(result.failureCpuKernels)
        assertNull(result.failedRequestedProvider)
    }

    private fun initial() = VoiceSpeechBenchmarkReport(
        1,
        VoiceSpeechBenchmarkConfiguration(VoiceSpeechBenchmarkMode.QUICK, listOf(VoiceSpeechBackend.CPU), KokoroVoice.HEART, 2, 1f, "a".repeat(64), "runtime", 147, 35, "b".repeat(64))
    )

    private fun runner(clock: Clock, create: (VoiceSpeechBackend) -> VoicePhraseEngine) = VoiceSpeechBenchmarkRunner(
        create = create,
        nanoTime = { clock.nanos },
        wallTime = { 1 },
        thermalStatus = { 0 }
    )

    private class Clock(var nanos: Long = 0) {
        fun advance(milliseconds: Long) {
            nanos += milliseconds * 1_000_000
        }
    }

    private class Engine(private val clock: Clock, private val onGenerate: (Int) -> Unit = {}) : VoicePhraseEngine {
        var calls = 0
        var evidenceReads = 0
        var closed = false
        override val executionEvidence: VoiceSpeechExecutionEvidence
            get() {
                evidenceReads++
                return VoiceSpeechExecutionEvidence(
                    VoiceSpeechBackend.CPU, "cpu", "a".repeat(64), "runtime", "b".repeat(64), VoiceSpeechStage.READY,
                    tokenCount = 3, verifiedAudio = true, observedAtMillis = 1, profileAvailable = false, nonSilentSamples = 1800
                )
            }

        override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
            calls++
            clock.advance(50)
            onGenerate(calls)
            return VoicePhraseAudio(FloatArray(1800) { .1f }, longArrayOf(1, 1, 1), intArrayOf(1, 2))
        }

        override fun resetCancellation() = Unit
        override fun cancel() = Unit
        override fun close() {
            closed = true
        }
    }
}
