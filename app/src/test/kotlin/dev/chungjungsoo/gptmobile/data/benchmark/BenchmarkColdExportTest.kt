package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.localruntime.EngineLoadReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.GenerationConfigReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.LocalSamplerConfig
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCleanupReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkColdExportTest {
    private fun sample(
        index: Int,
        backend: String = "cpu",
        offload: Int = 0,
        first: Long = 100
    ): BenchmarkSample {
        val test =
            BenchmarkPerformanceSuite
                .cases()
                .first {
                    it.performanceTrial?.dimension ==
                        BenchmarkPerformanceDimension.FIRST_VISIBLE_TEXT
                }.copy(repeatIndex = index)
        val load =
            EngineLoadReceipt(
                "load",
                "artifact",
                "runtime",
                backend,
                backend,
                "engine",
                workerGenerationId = "worker",
                actualOffloadLayers = offload,
                contextTokens = 4096,
                templateIdentity = "template",
                startedAtElapsedMs = 0,
                completedAtElapsedMs = 5,
                outcome = "ready"
            )
        val sampler = LocalSamplerConfig(40, .95f, 0f)
        val configuration =
            GenerationConfigReceipt(
                "generation-$index",
                index.toLong(),
                "engine",
                sampler,
                64,
                effectiveSampler = sampler,
                effectiveOutputTokens = 64,
                contextTokens = 4096,
                templateIdentity = "template",
                renderedPromptDigest = "same-rendered-prompt",
                observedAtElapsedMs = 100,
                nativeAcknowledgedFields = setOf("sampler.temperature", "effectiveOutputTokens", "contextTokens", "renderedPromptDigest")
            )
        val receipt =
            RuntimeExecutionReceipt(
                runtimeRequestId = "request-$index",
                workerGenerationId = "worker",
                engineInstanceId = "engine",
                nativeOperationId = index.toLong(),
                nativeEntered = true,
                nativeReturned = true,
                terminalKind = "eos",
                promptTokens = 100,
                sampledTokens = 20,
                configuration = configuration,
                engineLoad = load
            )
        val cleanup =
            RuntimeCleanupReceipt(
                "benchmark",
                workerGenerationId = "worker",
                engineInstanceId = "engine",
                requiredActions = setOf("close_conversation"),
                obligationsObserved = true,
                nativeClosureConfirmed = true,
                conversationClosed = true,
                engineHealthy = true
            )
        return BenchmarkSample(
            "performance-$index",
            test.label,
            "performance",
            BenchmarkOutcome.PASSED,
            durationMs = first + 100,
            firstTextMs = first,
            outputCharacters = 11,
            requestDispatched = true,
            terminalReceived = true,
            fixtureContentHash = test.contentHash,
            repeatIndex = index,
            performanceTrial = test.performanceTrial,
            runtimeExecutions = listOf(receipt),
            runtimeCleanup = cleanup,
            cacheRegime = "observed_uncached",
            loadRegime = "ready",
            attemptId = "attempt-$index",
            validatorVersion = test.validatorVersion
        )
    }

    private fun run(
        id: String,
        backend: String = "cpu",
        first: Long = 100,
        repeats: Int = 3,
        at: Long = 1
    ) = BenchmarkRun(
        id,
        "profile",
        "Friendly profile",
        "LITERT_LM",
        "model",
        "key",
        true,
        BenchmarkMode.PERFORMANCE,
        at,
        suiteVersion = 10,
        measurementVersion = 4,
        executionPolicy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1,
        deviceBuild = "device-build",
        fixtureSetVersion = 4,
        requestedAccelerator = backend,
        deadlinePolicy = "benchmark-workload-deadlines-v2",
        readiness =
        BenchmarkReadinessReceipt(
            0,
            1,
            listOf(BenchmarkReadinessCheck("configuration", BenchmarkReadinessStatus.PASSED)),
            setOf("configuration")
        ),
        samples =
        (0 until repeats).map {
            sample(
                it,
                backend,
                if (benchmarkBackendKind(backend) in
                    setOf("gpu", "opencl")
                ) {
                    8
                } else {
                    0
                },
                first
            )
        },
        plannedTrials = repeats
    )

    @Test fun comparisonProjectionAndFullRunSupplierPreserveEveryDerivedMetadataSection() {
        val raw = "unchanged evidence [\"quoted\"] 🧪".repeat(200)
        val original =
            listOf(
                run("new", first = 80, at = 3),
                run("gpu", "gpu", first = 50, at = 2),
                run("old", first = 100, at = 1),
                run("unknown-cache", at = 4).let { run ->
                    run.copy(samples = run.samples.map { it.copy(cacheRegime = "unknown", attemptId = null) })
                },
                run("primary-retained", at = 5).let { it.copy(readiness = it.readiness!!.copy(nativePrimaryFailureRetained = true)) }
            ).map { run ->
                run.copy(
                    samples = run.samples.map { it.copy(canonicalAnswer = raw, finalAnswer = raw, preview = raw) },
                    caseManifest =
                    listOf(
                        BenchmarkCase("custom-preserved", "Frozen", "task", "Original frozen context " + raw, "expected")
                    ),
                    readiness =
                    run.readiness!!.copy(
                        checks = run.readiness.checks.map { it.copy(sample = run.samples.first().copy(canonicalAnswer = raw)) }
                    )
                )
            }
        val projected = original.map(::benchmarkExportComparisonRun)
        assertTrue(
            projected.all {
                it.caseManifest == null &&
                    it.samples.all { sample -> sample.canonicalAnswer == null && sample.finalAnswer == null && sample.preview.isEmpty() }
            }
        )
        val backup =
            benchmarkExportJson
                .encodeToJsonElement(
                    BenchmarkBackupPayload.serializer(),
                    BenchmarkBackupPayload(runs = original)
                ).jsonObject
        val expected = benchmarkExportJson.parseToJsonElement(benchmarkExport(backup.toString(), true)).jsonObject
        var fullPasses = 0
        val source =
            BenchmarkExportSource(projected, emptyList(), null, null, JsonNull, readCompleteRuns = {
                fullPasses++
                original.asSequence()
            })
        val output = ByteArrayOutputStream()
        val writer = BenchmarkStreamingExport(output)
        writeBenchmarkExport(writer, source, true) { fields ->
            backup.forEach { (name, value) -> if (name !in benchmarkExportMetadata) fields.put(name, value) }
        }
        writer.finish()
        val actual = benchmarkExportJson.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject
        assertEquals(expected - "exportedAt", actual - "exportedAt")
        assertTrue(fullPasses >= 5)
        val metrics =
            actual
                .getValue(
                    "perRunMetrics"
                ).jsonArray
                .first {
                    it.jsonObject
                        .getValue("runId")
                        .jsonPrimitive.content == "new"
                }.jsonObject
        val performance =
            metrics
                .getValue("performance")
                .jsonObject
                .getValue("metrics")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("old", performance.getValue("previousRunId").jsonPrimitive.content)
        assertTrue(actual.getValue("performanceComparisonMatrix").jsonArray.isNotEmpty())
    }

    @Test fun legacyArrayWalkPreservesNestedUnknownValuesAndRejectsIncompleteTrailingData() {
        val valid = " [ {\"nested\":[[1,2],{\"text\":\"a ] \\\" quote 🧪\"}]}, {\"future\":true} ] \n"
        assertEquals(benchmarkExportJson.parseToJsonElement(valid).jsonArray, benchmarkLegacyExportRecords(valid).toList())
        for (invalid in listOf("{}", "[{}", "[{},]", "[{}] trailing", "[\"unterminated]", "[{]")) {
            assertTrue(
                "Invalid JSON must never silently truncate the original export: $invalid",
                runCatching {
                    benchmarkLegacyExportRecords(invalid).toList()
                }.isFailure
            )
        }
    }
}
