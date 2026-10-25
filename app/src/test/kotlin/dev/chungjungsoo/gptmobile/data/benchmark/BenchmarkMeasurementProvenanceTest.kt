package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.localruntime.EngineLoadReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.GenerationConfigReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineSpec
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeExecutionIdentity
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeState
import dev.chungjungsoo.gptmobile.data.localruntime.LocalSamplerConfig
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkMeasurementProvenanceTest {
    private fun observation(model: String, digest: String = model, engine: String = "engine", worker: String = "worker", spec: LocalEngineSpec = LocalEngineSpec(model, "cpu", 2048, cpuThreads = 4)) = BenchmarkRuntimeObservation(
        LocalRuntimeState(backend = LocalRuntimeBackend.LLAMA_CPP, engineSpec = spec),
        LocalRuntimeExecutionIdentity(workerGenerationId = worker, engineInstanceId = engine),
        spec,
        spec,
        false,
        digest
    )
    private fun execution(id: Int, terminal: Boolean = true, backend: String = "cpu"): RuntimeExecutionReceipt {
        val load = EngineLoadReceipt("load", "artifact", "llama-revision", backend, backend, "engine", startedAtElapsedMs = 0, completedAtElapsedMs = 1, outcome = "loaded")
        return RuntimeExecutionReceipt(runtimeRequestId = "request-$id", physicalAttemptId = "physical-$id", nativeGenerationId = "generation-$id", workerGenerationId = "worker", engineInstanceId = "engine", nativeOperationId = id.toLong(), nativeEntered = true, nativeEnteredAtElapsedMs = 1, nativeReturned = terminal, nativeReturnedAtElapsedMs = 11.takeIf { terminal }?.toLong(), terminalKind = "eos".takeIf { terminal }, engineLoad = load)
    }

    @Test fun residentModelADoesNotGiveModelBAReadyClassification() {
        val a = observation("a.gguf", engine = "a-engine")
        val b = observation("b.gguf", engine = "b-engine", worker = "b-worker")
        val request = execution(1).copy(engineInstanceId = "b-engine", workerGenerationId = "b-worker")
        val result = benchmarkResidentMatch(a, b, request)
        assertEquals("model_switch", result.regime)
        assertEquals(false, result.exactMatch)
        assertEquals("not_dispatched_or_unknown", benchmarkResidentMatch(a, b, request.copy(nativeEntered = null)).regime)
        assertEquals("resident_identity_unknown", benchmarkResidentMatch(b.copy(artifactDigest = null), b, request).regime)
    }

    @Test fun exactResidentMatchIgnoresPerRequestSamplerAndOutputButChecksEngineConfiguration() {
        val before = observation("same.gguf")
        val requestOnly = before.loaded!!.copy(samplerIdentity = "temperature=0", workload = "benchmark", expectedOutputTokens = 768)
        val after = observation("same.gguf", spec = requestOnly)
        assertEquals(true, benchmarkResidentMatch(before, after, execution(1)).exactMatch)
        val differentContext = after.copy(loaded = requestOnly.copy(maxTokens = 4096))
        assertEquals("configuration_switch", benchmarkResidentMatch(before, differentContext, execution(1)).regime)
        val differentWorker = after.copy(identity = after.identity.copy(workerGenerationId = "replacement"))
        assertEquals("reloaded", benchmarkResidentMatch(before, differentWorker, execution(1).copy(workerGenerationId = "replacement")).regime)
        assertEquals("resident_not_healthy", benchmarkResidentMatch(before.copy(state = before.state.copy(engineHealth = "RESTART_REQUIRED")), after, execution(1)).regime)
    }

    @Test fun creationAndPerRequestConfigurationRemainDistinctAndCacheNeedsObservation() {
        val load = execution(1).engineLoad!!.copy(cpuThreads = 4, contextTokens = 2048)
        val config = GenerationConfigReceipt("generation-1", 1, "engine", LocalSamplerConfig(40, .95f, 0f), 32, effectiveSampler = LocalSamplerConfig(40, .95f, 0f), effectiveOutputTokens = 32, measuredPromptTokens = 70, reusedPromptTokens = 64, processedPromptTokens = 6, nativeAcknowledgedFields = setOf("sampler", "output_cap", "reused_prompt_tokens"), observedAtElapsedMs = 10)
        val sample = BenchmarkSample("case", "Case", "task", BenchmarkOutcome.PASSED, cacheConfigured = true, runtimeExecutions = listOf(execution(1).copy(engineLoad = load, configuration = config)))
        assertEquals(2048, sample.runtimeExecution!!.engineLoad!!.contextTokens)
        assertEquals(32, sample.runtimeExecution!!.configuration!!.effectiveOutputTokens)
        assertEquals("observed_cached_prefix", benchmarkObservedCache(sample))
        assertEquals("unknown", benchmarkObservedCache(sample.copy(runtimeExecutions = emptyList())))
        assertEquals("observed_uncached", benchmarkObservedCache(sample.copy(runtimeExecutions = listOf(execution(1).copy(configuration = config.copy(reusedPromptTokens = 0))))))
        assertEquals("mixed_observed_reuse", benchmarkObservedCache(sample.copy(runtimeExecutions = sample.runtimeExecutions + execution(2).copy(configuration = config.copy(generationId = "generation-2", reusedPromptTokens = 0)))))
    }

    @Test fun executionResourcePeaksUseTheSameObservationsAndSurviveCleanup() {
        val worker = listOf(4_000_000_000L, 4_800_000_000L, 4_100_000_000L, 146_000_000L)
        var calls = 0
        val recorder = BenchmarkResourceRecorder { scope ->
            val index = calls++
            BenchmarkResourceSample("case", index.toLong(), 100_000L + index, 50_000L, 43, 0, worker[index], scope)
        }
        listOf("before_execution", "execution", "before_cleanup", "after_cleanup").forEach(recorder::observe)
        val saved = recorder.snapshots()
        assertEquals(4, calls)
        assertEquals(146_000_000L, saved.last().workerPssBytes)
        assertEquals(4_800_000_000L, recorder.peakExecutionWorkerPssBytes)
        assertEquals(saved.maxOf { it.processPssKb }, recorder.peakClientPssKb)
        assertEquals(saved.filter { it.scope in setOf("execution", "before_cleanup") }.maxOf { it.workerPssBytes!! }, recorder.peakExecutionWorkerPssBytes)
        assertEquals(100_002L, recorder.peakExecutionClientPssKb)
    }

    @Test fun sevenExecutedAndFortyThreeSkippedNeverBecomeFiftyExecuted() {
        val completed = (1..6).map { index -> BenchmarkSample("case-$index", "Case", "speed", BenchmarkOutcome.PASSED, outputCharacters = 50, chunks = 2, requestDispatched = true, terminalReceived = true, warmup = index == 1, runtimeExecutions = listOf(execution(index)), fixtureContentHash = "same-content") }
        val failedWarmup = BenchmarkSample("warmup", "Warmup", "speed", BenchmarkOutcome.TIMED_OUT, requestDispatched = true, terminalReceived = false, warmup = true, runtimeExecutions = listOf(execution(7, terminal = false)))
        val skipped = (1..43).map { BenchmarkSample("skip-$it", "Untested", if (it > 35) "intelligence" else "task", BenchmarkOutcome.SKIPPED, requestDispatched = false) }
        val run = BenchmarkRun("run", "profile", "Profile", "CUSTOM", "model", "key", false, BenchmarkMode.FULL, 1, listOf(failedWarmup) + completed + skipped, suiteVersion = 8, measurementVersion = 3, plannedTrials = 50)
        val coverage = benchmarkCoverage(run)
        assertEquals(50, coverage.planned)
        assertEquals(50, coverage.accounted)
        assertEquals(7, coverage.executed)
        assertEquals(6, coverage.terminalized)
        assertEquals(43, coverage.skipped)
        assertEquals(0, coverage.pending)
        val metrics = benchmarkMetricResults(listOf(run)).associateBy { it.id }
        val historical = metrics.getValue("delivery_completion")
        assertEquals(5, historical.numerator)
        assertEquals(5, historical.denominator)
        assertEquals("benchmark-metrics-v3", historical.policyVersion)
        val operational = metrics.getValue("operational_delivery_including_warmups")
        assertEquals(6, operational.numerator)
        assertEquals(7, operational.denominator)
        assertEquals(100.0 * 6 / 7, operational.value!!, .00001)
        assertNull(metrics.getValue("reasoning_fixture_accuracy").value)
        val native = metrics.getValue("physical_generation_terminal_receipt")
        assertEquals(6, native.numerator)
        assertEquals(7, native.denominator)
        assertFalse(native.plannedKnown)
    }

    @Test fun missingLegacyPlanRemainsUnknownAndPriorToolReturnDoesNotTerminalizeTheLogicalCase() {
        val sample = BenchmarkSample("case", "Case", "tools", BenchmarkOutcome.QUOTA_BLOCKED, requestDispatched = true, terminalReceived = false, runtimeExecutions = listOf(execution(1)))
        val run = BenchmarkRun("legacy", "profile", "Profile", "CUSTOM", "model", "key", false, BenchmarkMode.QUICK, 1, listOf(sample))
        val coverage = benchmarkCoverage(run)
        assertFalse(coverage.plannedKnown)
        assertEquals(1, coverage.executed)
        assertEquals(0, coverage.terminalized)
        val metrics = benchmarkMetricResults(listOf(run)).associateBy { it.id }
        assertFalse(metrics.getValue("planned_task_success").plannedKnown)
        assertNull(metrics.getValue("planned_task_success").value)
        assertEquals(100.0, metrics.getValue("physical_generation_terminal_receipt").value!!, .001)
    }

    @Test fun fixedPromptRepetitionsDoNotInflateUniqueContentAndCpuDiagnosticsNeverRank() {
        val trials = BenchmarkCpuExperiment.cases("experiment")
        assertEquals(24, trials.size)
        assertEquals(setOf(2, 4, 6, 8), trials.map { it.cpuExperiment!!.threadCount }.toSet())
        assertEquals(1, trials.map { it.contentHash }.toSet().size)
        assertEquals(1, trials.map { it.prompt }.toSet().size)
        assertEquals(1, trials.map { it.cpuExperiment!!.fixtureDigest }.toSet().size)
        assertEquals(12, trials.count { it.dependsOn.isNotEmpty() })
        assertTrue(trials.filter { it.dependsOn.isNotEmpty() }.all { hot -> trials.indexOfFirst { it.id in hot.dependsOn } == trials.indexOf(hot) - 1 })
        val run = BenchmarkRun("cpu", "profile", "Profile", "LITERT_LM", "model", "key", true, BenchmarkMode.CPU_EXPERIMENT, 1, suiteVersion = 9, measurementVersion = 4, plannedTrials = 24)
        assertTrue(DynamicScoreEngine.snapshot(listOf(run), 1, 10, mapOf("profile" to "key")).rows.isEmpty())
        val a = trials.first()
        assertEquals(a.contentHash, a.copy(id = "different-repeat", seed = 123, repeatIndex = 99).contentHash)
        assertNotEquals(a.contentHash, a.copy(prompt = a.prompt + " Extra instruction.").contentHash)
    }

    @Test fun mixedOrUnknownActualRuntimeCannotBecomeVersionFourVerifiedTiming() {
        fun case(index: Int, backend: String?) = BenchmarkSample("case-$index", "Case", "speed", BenchmarkOutcome.PASSED, durationMs = 100, firstTextMs = 10, lastTextMs = 100, chunks = 2, outputCharacters = 50, referenceDecodeTokens = 20, requestDispatched = true, terminalReceived = true, runtimeExecutions = backend?.let { listOf(execution(index, backend = it)) }.orEmpty())
        val run = BenchmarkRun("run", "profile", "Profile", "LITERT_LM", "model", "key", true, BenchmarkMode.FULL, 1, listOf(case(1, "cpu"), case(2, "vulkan")), suiteVersion = 9, measurementVersion = 4, plannedTrials = 50)
        assertEquals("MIXED_ACTUAL_EXECUTION_CONTEXTS", benchmarkTimingExecutionExclusion(run))
        val metrics = benchmarkMetricResults(listOf(run)).associateBy { it.id }
        assertNull(metrics.getValue("reference_decode_rate").value)
        assertTrue(metrics.getValue("reference_decode_rate").exclusions.containsKey("MIXED_ACTUAL_EXECUTION_CONTEXTS"))
        assertNotNull(metrics.getValue("raw_reference_decode_rate").value)
        val unknown = run.copy(samples = listOf(case(1, null)))
        assertEquals("ACTUAL_EXECUTION_CONTEXT_UNKNOWN", benchmarkTimingExecutionExclusion(unknown))
        assertNull(benchmarkTimingExecutionExclusion(unknown.copy(measurementVersion = 3)))
        assertNull(benchmarkTimingExecutionExclusion(run.copy(samples = listOf(case(1, "cpu"), case(2, "cpu")))))
        assertNotEquals(DynamicScoreEngine.cohort(run), DynamicScoreEngine.cohort(unknown))
    }
}
