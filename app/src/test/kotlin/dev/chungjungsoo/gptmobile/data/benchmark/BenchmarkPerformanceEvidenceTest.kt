package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.localruntime.EngineLoadReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.GenerationConfigReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics
import dev.chungjungsoo.gptmobile.data.localruntime.LocalSamplerConfig
import dev.chungjungsoo.gptmobile.data.localruntime.NativeInferenceMetrics
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCleanupReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkPerformanceEvidenceTest {
    private fun sample(index: Int, backend: String = "cpu", offload: Int = 0, first: Long = 100): BenchmarkSample {
        val test = BenchmarkPerformanceSuite.cases().first { it.performanceTrial?.dimension == BenchmarkPerformanceDimension.FIRST_VISIBLE_TEXT }.copy(repeatIndex = index)
        val load = EngineLoadReceipt("load", "artifact", "runtime", backend, backend, "engine", workerGenerationId = "worker", actualOffloadLayers = offload, contextTokens = 4096, templateIdentity = "template", startedAtElapsedMs = 0, completedAtElapsedMs = 5, outcome = "ready")
        val sampler = LocalSamplerConfig(40, .95f, 0f)
        val configuration = GenerationConfigReceipt("generation-$index", index.toLong(), "engine", sampler, 64, effectiveSampler = sampler, effectiveOutputTokens = 64, contextTokens = 4096, templateIdentity = "template", renderedPromptDigest = "same-rendered-prompt", observedAtElapsedMs = 100, nativeAcknowledgedFields = setOf("sampler.temperature", "effectiveOutputTokens", "contextTokens", "renderedPromptDigest"))
        val receipt = RuntimeExecutionReceipt(runtimeRequestId = "request-$index", workerGenerationId = "worker", engineInstanceId = "engine", nativeOperationId = index.toLong(), nativeEntered = true, nativeReturned = true, terminalKind = "eos", promptTokens = 100, sampledTokens = 20, configuration = configuration, engineLoad = load)
        val cleanup = RuntimeCleanupReceipt("benchmark", workerGenerationId = "worker", engineInstanceId = "engine", requiredActions = setOf("close_conversation"), obligationsObserved = true, nativeClosureConfirmed = true, conversationClosed = true, engineHealthy = true)
        return BenchmarkSample("performance-$index", test.label, "performance", BenchmarkOutcome.PASSED, durationMs = first + 100, firstTextMs = first, outputCharacters = 11, requestDispatched = true, terminalReceived = true, fixtureContentHash = test.contentHash, repeatIndex = index, performanceTrial = test.performanceTrial, runtimeExecutions = listOf(receipt), runtimeCleanup = cleanup, cacheRegime = "observed_uncached", loadRegime = "ready", attemptId = "attempt-$index", validatorVersion = test.validatorVersion)
    }
    private fun run(id: String, backend: String = "cpu", first: Long = 100, repeats: Int = 3, at: Long = 1) = BenchmarkRun(
        id, "profile", "Friendly profile", "LITERT_LM", "model", "key", true, BenchmarkMode.PERFORMANCE, at, suiteVersion = 10, measurementVersion = 4,
        executionPolicy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1, deviceBuild = "device-build", fixtureSetVersion = 4,
        requestedAccelerator = backend, deadlinePolicy = "benchmark-workload-deadlines-v2",
        readiness = BenchmarkReadinessReceipt(0, 1, listOf(BenchmarkReadinessCheck("configuration", BenchmarkReadinessStatus.PASSED)), setOf("configuration")),
        samples = (0 until repeats).map { sample(it, backend, if (benchmarkBackendKind(backend) in setOf("gpu", "opencl")) 8 else 0, first) }, plannedTrials = repeats
    )

    @Test fun uppercaseGpuAndRegistryAliasesCannotBypassOffloadProof() {
        val gpu = run("gpu", "GPU")
        val zero = sample(0, "GPU", 0)
        assertEquals("NOT_PROVEN", benchmarkAcceleratorEvidence(gpu, zero).status)
        assertEquals("NOT_PROVEN", benchmarkAcceleratorEvidence(gpu, zero.copy(runtimeExecutions = zero.runtimeExecutions.map { it.copy(engineLoad = it.engineLoad!!.copy(actualBackend = "Vulkan0")) })).status)
        val positive = benchmarkAcceleratorEvidence(gpu, sample(0, "Vulkan0", 8))
        assertEquals("EXECUTION_PROVEN", positive.status)
        assertEquals("NOT_QUALIFIED", positive.qualificationStatus)
        assertEquals("Vulkan0", positive.actualBackend)
        assertEquals("NOT_PROVEN", benchmarkAcceleratorEvidence(gpu, sample(0, "mystery-device", 8)).status)
    }

    @Test fun rawTimingSurvivesQualityFailureWithoutJoiningQualifiedComparison() {
        val original = run("failed")
        val failed = original.copy(samples = original.samples.map { it.copy(outcome = BenchmarkOutcome.FAILED, failureCategory = "MODEL_QUALITY") })
        val summary = benchmarkPerformanceSummary(failed).metrics.single()
        assertEquals(3, summary.raw.count)
        assertEquals(100.0, summary.raw.median!!, .01)
        assertEquals(0, summary.qualified.count)
        assertEquals(3, summary.exclusions.getValue("TASK_OR_EXECUTION_NOT_PASSED"))
        assertNull(benchmarkComparisonMatrix(listOf(failed)).single().cells.first { it.backend == "cpu" }.dimensionScore)
    }

    @Test fun mediansP95AndHistoryUseRepeatedExactCohortsAndKeepUnknownSettingsOut() {
        val old = run("old", first = 100, at = 1)
        val current = run("new", first = 80, at = 2)
        val summary = benchmarkPerformanceSummary(current, listOf(old)).metrics.single()
        assertEquals(3, summary.repeatCount)
        assertEquals(3, summary.qualified.count)
        assertEquals(80.0, summary.qualified.p95!!, .01)
        assertEquals("old", summary.previousRunId)
        assertEquals(-20.0, summary.changePercent!!, .01)
        val one = benchmarkPerformanceSummary(current.copy(samples = current.samples.take(1)), listOf(old)).metrics.single()
        assertNull(one.qualified.p95)
        assertNull(one.changePercent)
        val changed = current.copy(measurementVersion = 3)
        assertNull(benchmarkPerformanceSummary(changed, listOf(old)).metrics.single().previousRunId)
        val unknown = current.copy(samples = current.samples.map { sample -> sample.copy(runtimeExecutions = sample.runtimeExecutions.map { it.copy(configuration = it.configuration!!.copy(nativeAcknowledgedFields = emptySet())) }) })
        assertEquals(0, benchmarkPerformanceSummary(unknown).metrics.single().qualified.count)
        assertNotNull(benchmarkPerformanceSummary(unknown).metrics.single().raw.median)
    }

    @Test fun matrixNeedsThreeTrialsAndTwoCompatibleBackendsAndNeverInfersModelFamily() {
        val cpu = run("cpu", "CPU", first = 100)
        val gpu = run("gpu", "GPU", first = 50)
        val row = benchmarkComparisonMatrix(listOf(cpu, gpu)).single()
        assertEquals("artifact", row.artifactDigest)
        assertNull(row.modelFamily)
        assertNull(row.quantization)
        assertEquals(3, row.cells.first { it.backend == "cpu" }.qualifiedTrialCount)
        assertEquals(1, row.cells.first { it.backend == "cpu" }.qualified.count)
        assertEquals(50.0, row.cells.first { it.backend == "cpu" }.dimensionScore!!, .01)
        assertEquals(100.0, row.cells.first { it.backend == "gpu" }.dimensionScore!!, .01)
        assertEquals(BenchmarkComparisonStatus.UNMEASURED, row.cells.first { it.backend == "npu" }.status)
        val single = benchmarkComparisonMatrix(listOf(cpu.copy(samples = cpu.samples.take(1)), gpu)).single()
        assertTrue(single.cells.all { it.dimensionScore == null })
        val otherPolicy = gpu.copy(executionPolicy = BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1)
        assertEquals(2, benchmarkComparisonMatrix(listOf(cpu, otherPolicy)).size)
    }

    @Test fun changedRenderedSystemPromptAndUnknownCacheCannotJoinComparisonHistory() {
        val original = run("old", at = 1)
        val changedSystem = run("changed-system", at = 2).let { run ->
            run.copy(samples = run.samples.map { sample -> sample.copy(runtimeExecutions = sample.runtimeExecutions.map { execution -> execution.copy(configuration = execution.configuration!!.copy(renderedPromptDigest = "different-rendered-system-prompt")) }) })
        }
        assertNull(benchmarkPerformanceSummary(changedSystem, listOf(original)).metrics.single().previousRunId)
        assertEquals(2, benchmarkComparisonMatrix(listOf(original, changedSystem)).size)
        for (unknown in listOf(
            original.copy(samples = original.samples.map { it.copy(cacheRegime = "unknown") }),
            original.copy(samples = original.samples.map { sample -> sample.copy(runtimeExecutions = sample.runtimeExecutions.map { execution -> execution.copy(configuration = execution.configuration!!.copy(renderedPromptDigest = null)) }) })
        )) {
            val summary = benchmarkPerformanceSummary(unknown).metrics.single()
            assertEquals(3, summary.raw.count)
            assertEquals(0, summary.qualified.count)
            assertEquals(3, summary.exclusions["COMPARISON_IDENTITY_OR_EFFECTIVE_SETTINGS_UNKNOWN"])
        }
    }

    @Test fun sdkReportedRatesRemainRawWithoutInventingDurationsOrProcessedCounts() {
        val metric = NativeInferenceMetrics(100, 20, 72.0, 36.0)
        val sample = sample(0).copy(localSegments = listOf(LocalInferenceMetrics(native = metric)))
        val prefill = benchmarkPerformanceObservation(sample, BenchmarkPerformanceDimension.PREFILL)
        val decode = benchmarkPerformanceObservation(sample, BenchmarkPerformanceDimension.DECODE)
        assertEquals(72.0, prefill.value!!, .01)
        assertEquals(36.0, decode.value!!, .01)
        assertEquals("native_reported_rate", prefill.source)
        assertTrue(prefill.scope.contains("processed_input_basis_unreported"))
        assertNull(sample.localSegments.single().native!!.prefillDurationMs)
        assertNull(sample.localSegments.single().native!!.processedPromptTokens)
        val multiple = sample.copy(localSegments = sample.localSegments + LocalInferenceMetrics(native = metric.copy(decodeTokensPerSecond = 48.0)))
        assertNull(benchmarkPerformanceObservation(multiple, BenchmarkPerformanceDimension.DECODE).value)
        val retained = benchmarkMetricResults(listOf(run("sdk").copy(samples = listOf(sample))))
        assertEquals(36.0, retained.single { it.id == "raw_reported_native_decode_rate" }.value!!, .01)
    }

    @Test fun retainedNativeFailureOrUnfinishedReadinessKeepsNewRunsProvisional() {
        val original = run("ready")
        for (readiness in listOf(null, original.readiness!!.copy(finishedAt = null), requireNotNull(original.readiness).copy(nativePrimaryFailureRetained = true))) {
            val summary = benchmarkPerformanceSummary(original.copy(readiness = readiness)).metrics.single()
            assertEquals(3, summary.raw.count)
            assertEquals(0, summary.qualified.count)
            assertTrue(summary.exclusions.keys.any { it.contains("READINESS") || it == "PRIMARY_STRUCTURED_FAILURE_RETAINED" })
        }
        assertFalse(benchmarkPerformanceAnswerValid(BenchmarkPerformanceSuite.cases().first { it.performanceTrial?.dimension == BenchmarkPerformanceDimension.DECODE }, "1 2 9 4"))
    }
}
