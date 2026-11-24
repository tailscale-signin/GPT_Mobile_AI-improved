package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMeasuredBackendPolicyTest {
    private val now = 1_800_000_000_000L
    private fun receipt(backend: VoiceSpeechBackend, ms: Long, verified: Boolean = true) =
        VoiceBackendCapability(
            "key",
            backend,
            now,
            List(VoiceMeasuredBackendPolicy.FIXTURE_COUNT) { ms },
            verified,
            comparisonScope = "a".repeat(64),
            speakerId = 3,
            speechRate = 1f,
            thermalStatuses = List(VoiceMeasuredBackendPolicy.thermalSampleCount) { 0 },
            comparisonId = "c".repeat(32),
            audioObservations = VoiceSpeechWorkload.calibrationPhrases.map(::speechReferenceFixture)
        )

    @Test fun noBaselineOrOnlyProviderRegistrationKeepsCpu() {
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(emptyList(), now))
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(receipt(VoiceSpeechBackend.QNN_GPU, 10)), now))
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(receipt(VoiceSpeechBackend.CPU, 100), receipt(VoiceSpeechBackend.QNN_GPU, 10, false)), now))
    }

    @Test fun fasterTruncatedAcceleratorOrAnotherComparisonRunCannotWinAuto() {
        val cpu = receipt(VoiceSpeechBackend.CPU, 100)
        val gpu = receipt(VoiceSpeechBackend.QNN_GPU, 30)
        val truncated = gpu.copy(audioObservations = VoiceSpeechWorkload.calibrationPhrases.map { speechReferenceFixture(it, 43) })
        assertTrue(truncated.verifiedAccelerator)
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, truncated), now))
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, gpu.copy(comparisonId = "d".repeat(32))), now))
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, gpu.copy(audioObservations = emptyList())), now))
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu.copy(audioObservations = emptyList()), gpu), now))
        assertEquals(VoiceSpeechBackend.QNN_GPU, VoiceMeasuredBackendPolicy.choose(listOf(gpu, cpu), now))
    }

    @Test fun measuredCompleteSpeechCostChoosesFastestVerifiedAccelerator() {
        assertEquals(
            VoiceSpeechBackend.QNN_GPU,
            VoiceMeasuredBackendPolicy.choose(
                listOf(
                    receipt(VoiceSpeechBackend.CPU, 100),
                    receipt(VoiceSpeechBackend.QNN_GPU, 60),
                    receipt(VoiceSpeechBackend.QNN_HTP, 80)
                ),
                now
            )
        )
        assertEquals(
            VoiceSpeechBackend.CPU,
            VoiceMeasuredBackendPolicy.choose(
                listOf(
                    receipt(VoiceSpeechBackend.CPU, 60),
                    receipt(VoiceSpeechBackend.QNN_GPU, 100),
                    receipt(VoiceSpeechBackend.QNN_HTP, 80)
                ),
                now
            )
        )
    }

    @Test fun noisyAdvantageDoesNotSwitchEngines() {
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(receipt(VoiceSpeechBackend.CPU, 100), receipt(VoiceSpeechBackend.QNN_HTP, 91)), now))
        assertEquals(VoiceSpeechBackend.QNN_HTP, VoiceMeasuredBackendPolicy.choose(listOf(receipt(VoiceSpeechBackend.CPU, 100), receipt(VoiceSpeechBackend.QNN_HTP, 90)), now))
    }

    @Test fun roundedMeansCannotManufactureATenPercentAdvantage() {
        val cpu = receipt(VoiceSpeechBackend.CPU, 100).copy(sampleMs = listOf(100, 100, 100, 100, 100, 99))
        val gpu = receipt(VoiceSpeechBackend.QNN_GPU, 90)
        assertEquals(100L, cpu.scoreMs)
        assertEquals(90L, gpu.scoreMs)
        // 599 -> 540 improves by less than 10%, even though the rounded means are 100 -> 90.
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, gpu), now))
    }

    @Test fun equalRoundedMeansStillChooseTheLowestCompleteWorkloadCost() {
        val cpu = receipt(VoiceSpeechBackend.CPU, 100)
        val gpu = receipt(VoiceSpeechBackend.QNN_GPU, 80)
        val npu = receipt(VoiceSpeechBackend.QNN_HTP, 80).copy(sampleMs = listOf(80, 80, 80, 80, 80, 79))
        assertEquals(gpu.scoreMs, npu.scoreMs)
        assertEquals(VoiceSpeechBackend.QNN_HTP, VoiceMeasuredBackendPolicy.choose(listOf(cpu, gpu, npu), now))
        assertEquals(VoiceSpeechBackend.QNN_HTP, VoiceMeasuredBackendPolicy.choose(listOf(npu, gpu, cpu), now))
    }

    @Test fun exactThresholdAtTheTimingLimitRejectsOneExtraMillisecondAndOverflowingInputs() {
        val cpu = receipt(VoiceSpeechBackend.CPU, 300_000)
        val exact = receipt(VoiceSpeechBackend.QNN_GPU, 270_000)
        assertEquals(VoiceSpeechBackend.QNN_GPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, exact), now))
        val slower = exact.copy(sampleMs = listOf(270_000, 270_000, 270_000, 270_000, 270_000, 270_001))
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, slower), now))
        val invalid = exact.copy(sampleMs = listOf(Long.MAX_VALUE, Long.MAX_VALUE, 2, 2, 2, 2))
        assertNull(invalid.scoreMs)
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, invalid), now))
    }

    @Test fun failuresExpiredMeasurementsAndClockRollbackCannotQualify() {
        val cpu = receipt(VoiceSpeechBackend.CPU, 100)
        val fast = receipt(VoiceSpeechBackend.QNN_GPU, 10)
        for (invalid in listOf(
            fast.copy(failure = VoiceSpeechFailureCode.CPU_ONLY),
            fast.copy(observedAtMillis = now + 1),
            fast.copy(observedAtMillis = now - 8 * 24 * 60 * 60_000L),
            fast.copy(sampleMs = listOf(10)),
            fast.copy(sampleMs = listOf(10, 10)),
            fast.copy(sampleMs = listOf(10, 10, 10, 10)),
            fast.copy(sampleMs = listOf(0, 10, 10)),
            fast.copy(sampleMs = listOf(10, 300_001, 10))
        )) {
            assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, invalid), now))
        }
    }

    @Test fun negativeReceiptsExpireSooner() {
        val failure = receipt(VoiceSpeechBackend.QNN_HTP, 10).copy(failure = VoiceSpeechFailureCode.GRAPH_PARTITION)
        assertTrue(failure.current(now + 6 * 60 * 60_000L))
        assertFalse(failure.current(now + 6 * 60 * 60_000L + 1))
        assertTrue(failure.copy(failure = null).current(now + 24 * 60 * 60_000L))
    }

    @Test fun alternatingShapeComparisonIncludesColdPreparationAndEveryShapeCost() {
        assertEquals(VoiceSpeechWorkload.calibrationFixtures.size, VoiceMeasuredBackendPolicy.FIXTURE_COUNT)
        val cpu = receipt(VoiceSpeechBackend.CPU, 100).copy(sampleMs = List(VoiceMeasuredBackendPolicy.FIXTURE_COUNT) { 100 })
        val gpu = receipt(VoiceSpeechBackend.QNN_GPU, 10).copy(sampleMs = listOf(1000, 10, 10, 10, 10, 10))
        assertEquals(175L, gpu.scoreMs)
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, gpu), now))
    }

    @Test fun legacyCpuPairCannotRankCurrentSixFixtureGpuReceipt() {
        val cpu = receipt(VoiceSpeechBackend.CPU, 400).copy(sampleMs = listOf(400, 400))
        val gpu = receipt(VoiceSpeechBackend.QNN_GPU, 200).copy(sampleMs = listOf(180, 200, 220, 180, 200, 220))
        assertNull(cpu.scoreMs)
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, gpu), now))
    }

    @Test fun legacyGpuPairCannotRankAgainstCurrentSixFixtureCpuReceipt() {
        val cpu = receipt(VoiceSpeechBackend.CPU, 100)
        val gpu = receipt(VoiceSpeechBackend.QNN_GPU, 10).copy(sampleMs = listOf(10, 10))
        assertNull(gpu.scoreMs)
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, gpu), now))
    }

    @Test fun diagnosticEvidencePreservesFailedAndEffectiveProvidersWithoutRawText() {
        val evidence = VoiceSpeechExecutionEvidence(
            VoiceSpeechBackend.QNN_HTP, "cpu", "model", "runtime", "device",
            VoiceSpeechStage.CPU_FALLBACK, VoiceSpeechFailureCode.GRAPH_PARTITION, observedAtMillis = now,
            nativeCode = 11, failureStage = VoiceSpeechStage.PREPARING, failedProvider = "qnn-htp", failedTokenCount = 19
        )
        val fields = evidence.diagnostics()
        assertEquals("QNN_HTP", fields["speech_backend_selected"])
        assertEquals("cpu", fields["speech_backend_effective"])
        assertEquals("qnn-htp", fields["speech_failed_provider"])
        assertEquals(19, fields["speech_failed_token_count"])
        assertEquals("PREPARING", fields["speech_failure_stage"])
        assertEquals("unknown", fields["speech_profile_scope"])
        assertFalse(fields.containsKey("speech_cpu_kernels"))
        assertFalse(fields.containsKey("speech_accelerator_kernels"))
        assertFalse(fields.keys.any { it.contains("text") || it.contains("message") })
    }

    @Test fun supportOnlyUnknownSettingsAndOldProtocolNeverRankAsMeasuredPerformance() {
        val cpu = receipt(VoiceSpeechBackend.CPU, 100)
        val fast = receipt(VoiceSpeechBackend.QNN_HTP, 10)
        for (invalid in listOf(
            fast.copy(sampleMs = emptyList()),
            fast.copy(comparisonScope = null),
            fast.copy(comparisonScope = "b".repeat(64)),
            fast.copy(speakerId = 11),
            fast.copy(speechRate = 1.25f),
            fast.copy(speechRate = Float.NaN),
            fast.copy(protocol = "fixed-three-phrases-v1-mean-complete-cost-v1")
        )) {
            assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, invalid), now))
        }
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, cpu.copy(comparisonScope = "b".repeat(64)), fast), now))
    }

    @Test fun allInputFailuresAndLegacyFrontendRejectionsAreNotHardwareFailures() {
        for (failure in VoiceSpeechFailureCode.entries.filter { it.isInputFailure }) {
            assertFalse(receipt(VoiceSpeechBackend.QNN_HTP, 10).copy(failure = failure).cacheable)
        }
        assertFalse(receipt(VoiceSpeechBackend.QNN_HTP, 10).copy(failure = VoiceSpeechFailureCode.EXECUTION_FAILED, failureStage = VoiceSpeechStage.FRONTEND).cacheable)
        assertTrue(receipt(VoiceSpeechBackend.QNN_HTP, 10).copy(failure = VoiceSpeechFailureCode.GRAPH_PREPARATION, failureStage = VoiceSpeechStage.PREPARING).cacheable)
    }

    @Test fun unavailableCpuProfileAndUnsafeDiscoveryDetailsRemainExplicit() {
        val fields = VoiceSpeechExecutionEvidence(
            VoiceSpeechBackend.CPU, "cpu", "model", "runtime", "device", VoiceSpeechStage.READY,
            observedAtMillis = now, profileAvailable = false, currentPrepareMs = 0,
            discoverySummary = "stage=selected;type=1,vendor=20803,id=0",
            failedDiscoverySummary = "/private/path/or/user text"
        ).diagnostics()
        assertEquals(false, fields["speech_profile_available"])
        assertEquals("not_collected", fields["speech_profile_scope"])
        assertFalse(fields.containsKey("speech_cpu_kernels"))
        assertFalse(fields.containsKey("speech_cpu_us"))
        assertEquals("stage=selected;type=1,vendor=20803,id=0", fields["speech_discovery_summary"])
        assertFalse(fields.containsKey("speech_failed_discovery_summary"))
    }

    @Test fun unknownMissingAndTransientWarmThermalsCannotPromoteAccelerators() {
        val cpu = receipt(VoiceSpeechBackend.CPU, 100)
        val fast = receipt(VoiceSpeechBackend.QNN_HTP, 10)
        for (invalid in listOf(
            fast.copy(thermalStatuses = emptyList()),
            fast.copy(thermalStatuses = fast.thermalStatuses.dropLast(1)),
            fast.copy(thermalStatuses = fast.thermalStatuses.mapIndexed { index, value -> if (index == 5) null else value }),
            fast.copy(thermalStatuses = fast.thermalStatuses.mapIndexed { index, value -> if (index == 5) 2 else value })
        )) {
            assertNull(invalid.scoreMs)
            assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, invalid), now))
        }
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu.copy(thermalStatuses = emptyList()), fast), now))
    }

    @Test fun explicitComparisonsRotateAllStartingBackendsWithoutDroppingAny() {
        val orders = (0L..2L).map(VoiceMeasuredBackendPolicy::comparisonOrder)
        assertEquals(setOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP), orders.map { it.first() }.toSet())
        orders.forEach { assertEquals(3, it.toSet().size) }
        assertEquals(orders.first(), VoiceMeasuredBackendPolicy.comparisonOrder(3))
        assertEquals(3, VoiceMeasuredBackendPolicy.comparisonOrder(Long.MAX_VALUE).size)
    }
}
