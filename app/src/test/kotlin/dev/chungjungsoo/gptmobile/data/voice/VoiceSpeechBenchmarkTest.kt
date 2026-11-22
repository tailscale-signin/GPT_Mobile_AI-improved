package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechBenchmarkTest {
    private fun configuration(backends: List<VoiceSpeechBackend> = VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS) = VoiceSpeechBenchmarkConfiguration(
        VoiceSpeechBenchmarkMode.QUICK, backends, KokoroVoice.EMMA, 3, 1.1f, "a".repeat(64), "test-runtime", 147, 36, "b".repeat(64)
    )

    private class Clock(var nanos: Long = 0) {
        fun advance(ms: Long) {
            nanos += ms * 1_000_000
        }
    }

    private class Engine(
        private val backend: VoiceSpeechBackend,
        private val clock: Clock,
        private val duration: Long = 100,
        private val effective: String = VoiceSpeechBenchmarkBackendResult.provider(backend),
        private val validDuration: Boolean = true
    ) : VoicePhraseEngine {
        val calls = mutableListOf<Triple<String, Int, Float>>()
        var closed = false
        var cancellations = 0
        override val executionProvider get() = effective
        override val executionEvidence get() = VoiceSpeechExecutionEvidence(
            backend, effective, "a".repeat(64), "test-runtime", "device", VoiceSpeechStage.READY,
            acceleratorKernels = if (effective == "cpu") 0 else 10,
            cpuKernels = 20,
            verifiedAudio = true,
            observedAtMillis = 1
        )
        override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
            calls += Triple(text, speaker, speed)
            clock.advance(duration)
            return VoicePhraseAudio(FloatArray(1800) { .1f }, if (validDuration) longArrayOf(1, 1, 1) else longArrayOf(1), intArrayOf(1, 2))
        }
        override fun cancel() {
            cancellations++
        }
        override fun resetCancellation() = Unit
        override fun close() {
            closed = true
        }
    }

    @Test fun allRequestedBackendsRunIdenticalWarmAndColdFixturesAndKeepActualTimings() {
        val clock = Clock()
        val engines = mutableMapOf<VoiceSpeechBackend, Engine>()
        val report = VoiceSpeechBenchmarkRunner(
            create = { backend ->
                clock.advance(10)
                Engine(backend, clock, if (backend == VoiceSpeechBackend.QNN_GPU) 50 else 100).also { engines[backend] = it }
            },
            nanoTime = { clock.nanos },
            wallTime = { 10_000 },
            thermalStatus = { 0 }
        ).run(VoiceSpeechBenchmarkReport(1, configuration())) {}
        assertEquals(VoiceSpeechBenchmarkRunState.COMPLETE, report.state)
        assertEquals(27, report.completedSamples)
        assertEquals(VoiceSpeechBackend.QNN_GPU, report.recommendation())
        val expected = VoiceSpeechBenchmarkFixture.entries.flatMap { fixture -> List(3) { Triple(fixture.text, KokoroVoice.EMMA.speakerId, 1.1f) } }
        for (engine in engines.values) {
            assertEquals(expected, engine.calls)
            assertTrue(engine.closed)
        }
        val cpu = report.results.first()
        assertEquals(10L, cpu.sessionPrepareMs)
        assertEquals(110L, cpu.samples.first().prepareToFirstPcmMs)
        assertEquals(100L, cpu.medianMs)
        assertEquals(100L, cpu.p95Ms)
        assertEquals(75.0, cpu.samples.first().audioMs, .001)
        assertEquals(100.0 / 75, cpu.medianRtf!!, .001)
        assertEquals(6, cpu.warmSamples.size)
        assertEquals(3, cpu.samples.count { !it.warm })
        assertTrue(report.results.all { it.verified })
    }

    @Test fun cpuFallbackIsNotAcceptedAsGpuPerformanceAndOtherBackendsStillRun() {
        val clock = Clock()
        val report = VoiceSpeechBenchmarkRunner(
            create = { backend -> Engine(backend, clock, effective = if (backend == VoiceSpeechBackend.QNN_GPU) "cpu" else VoiceSpeechBenchmarkBackendResult.provider(backend)) },
            nanoTime = { clock.nanos }
        ).run(VoiceSpeechBenchmarkReport(1, configuration())) {}
        val gpu = report.results[1]
        assertEquals(VoiceSpeechBenchmarkBackendState.UNSUPPORTED, gpu.state)
        assertEquals(VoiceSpeechFailureCode.CPU_ONLY, gpu.failure)
        assertEquals("cpu", gpu.failureEffectiveProvider)
        assertEquals(0, gpu.failureAcceleratorKernels)
        assertTrue(gpu.samples.isEmpty())
        assertFalse(gpu.verified)
        assertTrue(report.results.last().verified)
    }

    @Test fun missingNativeBackendIsExplicitAndDoesNotFabricateSamples() {
        val report = VoiceSpeechBenchmarkRunner(create = { throw UnsatisfiedLinkError("private/path/driver.log") })
            .run(VoiceSpeechBenchmarkReport(1, configuration(listOf(VoiceSpeechBackend.QNN_HTP)))) {}
        assertEquals(VoiceSpeechBenchmarkBackendState.UNSUPPORTED, report.results.single().state)
        assertEquals(VoiceSpeechFailureCode.PROVIDER_LOAD, report.results.single().failure)
        assertTrue(report.results.single().samples.isEmpty())
        assertNull(report.recommendation())
        assertFalse(voiceSpeechBenchmarkExport(report).contains("private/path"))
    }

    @Test fun invalidPcmDurationsDoNotQualifyEvenWhenProviderSaysValidated() {
        val clock = Clock()
        val engine = Engine(VoiceSpeechBackend.CPU, clock, validDuration = false)
        val report = VoiceSpeechBenchmarkRunner(create = { engine }, nanoTime = { clock.nanos })
            .run(VoiceSpeechBenchmarkReport(1, configuration(listOf(VoiceSpeechBackend.CPU)))) {}
        assertEquals(VoiceSpeechFailureCode.OUTPUT_INVALID, report.results.single().failure)
        assertEquals("cpu", report.results.single().failureEffectiveProvider)
        assertEquals(0, report.results.single().failureAcceleratorKernels)
        assertEquals(20, report.results.single().failureCpuKernels)
        assertTrue(report.results.single().samples.isEmpty())
        assertTrue(engine.closed)
    }

    @Test fun failedLaterPhraseCannotBorrowThePreviousPhraseKernelEvidence() {
        val clock = Clock()
        val engine = Engine(VoiceSpeechBackend.QNN_GPU, clock)
        var attempts = 0
        val failing = object : VoicePhraseEngine by engine {
            override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
                if (++attempts > 1) error("Native generation failed with private driver details")
                return engine.generate(text, speaker, speed)
            }
        }
        val report = VoiceSpeechBenchmarkRunner(create = { failing }, nanoTime = { clock.nanos })
            .run(VoiceSpeechBenchmarkReport(1, configuration(listOf(VoiceSpeechBackend.QNN_GPU)))) {}
        val result = report.results.single()
        assertEquals(2, attempts)
        assertEquals(VoiceSpeechBenchmarkBackendState.FAILED, result.state)
        assertEquals(VoiceSpeechFailureCode.EXECUTION_FAILED, result.failure)
        assertEquals(1, result.samples.size)
        assertEquals("qnn-gpu", result.samples.single().effectiveProvider)
        assertEquals(10, result.samples.single().acceleratorKernels)
        assertEquals(20, result.samples.single().cpuKernels)
        assertEquals(10, engine.executionEvidence.acceleratorKernels)
        assertNull(result.failureEffectiveProvider)
        assertNull(result.failureAcceleratorKernels)
        assertNull(result.failureCpuKernels)
        assertTrue(engine.closed)
        assertFalse(voiceSpeechBenchmarkExport(report).contains("private driver details"))
        assertNull(report.recommendation())
    }

    @Test fun cancellationRetainsCompletedSampleClosesEngineAndSkipsRemainingBackends() {
        val clock = Clock()
        val engine = Engine(VoiceSpeechBackend.CPU, clock)
        var opens = 0
        val runner = VoiceSpeechBenchmarkRunner(create = {
            opens++
            engine
        }, nanoTime = { clock.nanos })
        val report = runner.run(VoiceSpeechBenchmarkReport(1, configuration())) { if (it.completedSamples == 1) runner.cancel() }
        assertEquals(VoiceSpeechBenchmarkRunState.CANCELLED, report.state)
        assertEquals(1, report.completedSamples)
        assertEquals(1, opens)
        assertTrue(engine.closed)
        assertTrue(engine.cancellations > 0)
        assertEquals(VoiceSpeechBenchmarkBackendState.CANCELLED, report.results.first().state)
        assertTrue(report.results.drop(1).all { it.state == VoiceSpeechBenchmarkBackendState.NOT_RUN })
        assertNull(report.recommendation())
    }

    @Test fun thermalLimitStopsFurtherWorkAndDoesNotRecommendAnEngine() {
        val clock = Clock()
        val engine = Engine(VoiceSpeechBackend.CPU, clock)
        val runner = VoiceSpeechBenchmarkRunner(create = { engine }, nanoTime = { clock.nanos }, thermalStatus = { if (engine.calls.isEmpty()) 0 else 2 })
        val report = runner.run(VoiceSpeechBenchmarkReport(1, configuration())) {}
        assertEquals(VoiceSpeechBenchmarkRunState.INTERRUPTED, report.state)
        assertEquals(VoiceSpeechBenchmarkIssue.THERMAL_LIMIT, report.issue)
        assertEquals(1, report.completedSamples)
        assertNull(report.recommendation())
    }

    @Test fun nativeCancellationRetainsCompletedBackendWithoutRecommendingAnUnfinishedComparison() {
        val clock = Clock()
        val engine = Engine(VoiceSpeechBackend.CPU, clock)
        val opened = mutableListOf<VoiceSpeechBackend>()
        val published = mutableListOf<VoiceSpeechBenchmarkReport>()
        val report = VoiceSpeechBenchmarkRunner(
            create = { backend ->
                opened += backend
                if (backend == VoiceSpeechBackend.CPU) engine else throw java.util.concurrent.CancellationException("Native preparation cancelled")
            },
            nanoTime = { clock.nanos }
        ).run(VoiceSpeechBenchmarkReport(1, configuration()), published::add)
        assertEquals(listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU), opened)
        assertEquals(VoiceSpeechBenchmarkRunState.CANCELLED, report.state)
        assertNull(report.issue)
        assertEquals(9, report.completedSamples)
        assertTrue(report.results.first().verified)
        assertTrue(engine.closed)
        assertEquals(VoiceSpeechBenchmarkBackendState.CANCELLED, report.results[1].state)
        assertTrue(report.results[1].samples.isEmpty())
        assertNull(report.results[1].failure)
        assertEquals(VoiceSpeechBenchmarkBackendState.NOT_RUN, report.results[2].state)
        assertEquals(report, published.last())
        assertNull(report.recommendation())
    }

    @Test fun interruptedWorkerCannotPublishTheInterruptedSampleOrQualifyACompleteRun() {
        val clock = Clock()
        val engine = Engine(VoiceSpeechBackend.CPU, clock)
        val interrupting = object : VoicePhraseEngine by engine {
            override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio =
                engine.generate(text, speaker, speed).also { Thread.currentThread().interrupt() }
        }
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            val report = VoiceSpeechBenchmarkRunner(create = { interrupting }, nanoTime = { clock.nanos })
                .run(VoiceSpeechBenchmarkReport(1, configuration())) {}
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(VoiceSpeechBenchmarkRunState.CANCELLED, report.state)
            assertNull(report.issue)
            assertEquals(1, engine.calls.size)
            assertEquals(0, report.completedSamples)
            assertEquals(VoiceSpeechBenchmarkBackendState.CANCELLED, report.results.first().state)
            assertTrue(report.results.drop(1).all { it.state == VoiceSpeechBenchmarkBackendState.NOT_RUN })
            assertTrue(engine.closed)
            assertNull(report.recommendation())
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun comparisonIncludesPreparationAndRequiresCompletePairedCoverage() {
        val clock = Clock()
        val report = VoiceSpeechBenchmarkRunner(
            create = { backend ->
                clock.advance(if (backend == VoiceSpeechBackend.CPU) 10 else 2000)
                Engine(backend, clock, if (backend == VoiceSpeechBackend.CPU) 100 else 50)
            },
            nanoTime = { clock.nanos }
        ).run(VoiceSpeechBenchmarkReport(1, configuration())) {}
        assertEquals(VoiceSpeechBackend.CPU, report.recommendation())
        val incomplete = report.copy(results = report.results.map { if (it.requestedBackend == VoiceSpeechBackend.CPU) it.copy(samples = it.samples.dropLast(1)) else it })
        assertNull(incomplete.recommendation())
    }

    @Test fun exportIsNumericSafeAndLocalHistoryRoundTripsWithoutLeakingDeviceBinding() {
        val clock = Clock()
        val report = VoiceSpeechBenchmarkRunner(create = { Engine(it, clock) }, nanoTime = { clock.nanos })
            .run(VoiceSpeechBenchmarkReport(1, configuration(listOf(VoiceSpeechBackend.CPU)))) {}
        val export = voiceSpeechBenchmarkExport(report)
        val json = Json.parseToJsonElement(export).jsonObject
        assertEquals(JsonNull, json["playbackLatencyMs"])
        assertEquals(JsonNull, json["acousticLatencyMs"])
        assertEquals(JsonNull, json["batteryEnergy"])
        assertFalse(export.contains("localDeviceKey"))
        assertFalse(export.contains("b".repeat(64)))
        assertFalse(export.contains(VoiceSpeechBenchmarkFixture.LONG.text))
        assertEquals(report, speechBenchmarkReportFromJson(speechBenchmarkJson(report, local = true)))
        val config = speechBenchmarkJson(report, local = true).getValue("configuration").jsonObject
        val broken = JsonObject(speechBenchmarkJson(report, local = true) + ("configuration" to JsonObject(config + ("speechRate" to JsonPrimitive("NaN")))))
        assertNull(speechBenchmarkReportFromJson(broken))
        assertNotNull(json.getValue("results").jsonArray.single().jsonObject["samples"])
    }
}
