package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechBenchmarkEvidenceExportTest {
    private fun configuration() = VoiceSpeechBenchmarkConfiguration(
        VoiceSpeechBenchmarkMode.QUICK, listOf(VoiceSpeechBackend.CPU), KokoroVoice.HEART, 2, 1f,
        "a".repeat(64), "test-runtime", 149, 36, "b".repeat(64)
    )

    private fun failure(measured: Boolean?) = VoiceSpeechBenchmarkFailureObservation.from(
        VoiceSpeechExecutionEvidence(
            VoiceSpeechBackend.CPU, "cpu", "a".repeat(64), "test-runtime", "b".repeat(64), VoiceSpeechStage.VALIDATING,
            failure = VoiceSpeechFailureCode.OUTPUT_INVALID, observedAtMillis = 1,
            audioMeasurementAvailable = measured, audioFinite = true.takeIf { measured == true },
            audioRms = 0.0.takeIf { measured == true }, audioPeak = 0.0.takeIf { measured == true },
            clippedSamples = 0L.takeIf { measured == true }, nonSilentSamples = 0L.takeIf { measured == true },
            validationReason = if (measured == true) "SILENT_PCM" else "AUDIO_TOO_LONG",
            outputFrames = if (measured == true) 12_600 else 2_511_000,
            durationStatsAvailable = true, durationSumOverflow = false,
            durationCount = 93, durationSum = 4185, durationMin = 45, durationMax = 45,
            cacheAdmission = "BOUNDED_THREE_SHAPES"
        )
    )

    @Test fun failureAvailabilityAndValidationScalarsSurviveCurrentRoundTripAndLegacyZeroesRemainUnknown() {
        for (measured in listOf(false, true)) {
            val report = VoiceSpeechBenchmarkReport(
                10,
                configuration(),
                VoiceSpeechBenchmarkRunState.COMPLETE,
                results = listOf(VoiceSpeechBenchmarkBackendResult(VoiceSpeechBackend.CPU, VoiceSpeechBenchmarkBackendState.FAILED, failure = VoiceSpeechFailureCode.OUTPUT_INVALID, failureObservation = failure(measured)))
            )
            val json = speechBenchmarkJson(report, local = true)
            assertEquals(report, speechBenchmarkReportFromJson(json))
            val row = json.getValue("results").jsonArray.single().jsonObject
            val observation = row.getValue("failureObservation").jsonObject
            assertEquals(if (measured) JsonPrimitive(0.0) else JsonNull, observation["audioRms"])
            assertEquals(JsonPrimitive(measured), observation["audioMeasurementAvailable"])
            val oldObservation = JsonObject(observation - "audioMeasurementAvailable" - "audioFinite" + mapOf("audioRms" to JsonPrimitive(0.0), "audioPeak" to JsonPrimitive(0.0)))
            val legacy = JsonObject(json + mapOf("schemaVersion" to JsonPrimitive(4), "results" to JsonArray(listOf(JsonObject(row + ("failureObservation" to oldObservation))))))
            val restored = checkNotNull(speechBenchmarkReportFromJson(legacy)).results.single().failureObservation!!
            assertNull(restored.audioMeasurementAvailable)
            assertNull(restored.audioRms)
            assertNull(restored.audioPeak)
        }
    }

    @Test fun deadlineRequestAndNativeRetirementStaySeparateInPersistedRunningAndFinishedReports() {
        val timeout = VoiceSpeechBenchmarkTimeout(VoiceSpeechBenchmarkDeadlineScope.CHUNK, 30_000, 30_050, VoiceSpeechStage.PREPARING)
        val pending = VoiceSpeechBenchmarkReport(
            10,
            configuration(),
            results = listOf(
                VoiceSpeechBenchmarkBackendResult(
                    VoiceSpeechBackend.CPU,
                    VoiceSpeechBenchmarkBackendState.RUNNING,
                    stopReason = VoiceSpeechBenchmarkStopReason.DEADLINE,
                    deadline = timeout,
                    cleanupState = VoiceSpeechBenchmarkCleanupState.PENDING
                )
            )
        )
        assertEquals(pending, speechBenchmarkReportFromJson(speechBenchmarkJson(pending, local = true)))
        val finished = pending.copy(
            state = VoiceSpeechBenchmarkRunState.COMPLETE,
            results = listOf(pending.results.single().copy(state = VoiceSpeechBenchmarkBackendState.FAILED, cleanupState = VoiceSpeechBenchmarkCleanupState.RETIRED, deadlineNativeRetirementMs = 6500))
        )
        assertEquals(finished, speechBenchmarkReportFromJson(speechBenchmarkJson(finished, local = true)))
        assertNull(finished.recommendation())
    }

    @Test fun signedInvalidDurationsAndOverflowRemainDistinctThroughHistory() {
        val negative = failure(false).copy(validationReason = "TOKEN_DURATION_INVALID", durationSum = -1, durationMin = -2, durationMax = 1)
        val overflow = negative.copy(durationSum = null, durationSumOverflow = true, durationMin = Long.MIN_VALUE, durationMax = Long.MAX_VALUE)
        for (observation in listOf(negative, overflow)) {
            val report = VoiceSpeechBenchmarkReport(
                10,
                configuration(),
                VoiceSpeechBenchmarkRunState.COMPLETE,
                results = listOf(VoiceSpeechBenchmarkBackendResult(VoiceSpeechBackend.CPU, VoiceSpeechBenchmarkBackendState.FAILED, failureObservation = observation))
            )
            assertEquals(report, speechBenchmarkReportFromJson(speechBenchmarkJson(report, local = true)))
        }
    }

    @Test fun clippedFailureAndAdvisoryTargetsRoundTripWithoutCreatingSuccessfulPerformanceEvidence() {
        val clipped = failure(true).copy(validationReason = "CLIPPED_PCM", audioRms = .1, audioPeak = 1.02, clippedSamples = 8, nonSilentSamples = 1200)
        val report = VoiceSpeechBenchmarkReport(
            10,
            configuration(),
            VoiceSpeechBenchmarkRunState.COMPLETE,
            results = listOf(VoiceSpeechBenchmarkBackendResult(VoiceSpeechBackend.CPU, VoiceSpeechBenchmarkBackendState.FAILED, failure = VoiceSpeechFailureCode.OUTPUT_INVALID, failureObservation = clipped))
        )
        val json = speechBenchmarkJson(report, local = true)
        assertEquals(report, speechBenchmarkReportFromJson(json))
        assertEquals(JsonPrimitive("cpu-reference-duration-timing-signal-v2"), json["audioReferenceProtocol"])
        assertEquals(JsonPrimitive(0L), json.getValue("audioReferenceLimits").jsonObject["maximumReferenceAndCandidateClippedSamples"])
        val targets = json.getValue("performanceTargets").jsonObject
        assertEquals(JsonPrimitive("PRODUCT_BUDGETS_NOT_MEASURED_ACHIEVEMENTS"), targets["scope"])
        assertEquals(JsonPrimitive(800L), targets["prepareToFirstPcmExclusiveUpperBoundMs"])
        assertEquals(JsonPrimitive(400L), targets["warmFirstPcmExclusiveUpperBoundMs"])
        assertEquals(JsonPrimitive(.5), targets["longSpeechRealTimeFactorExclusiveUpperBound"])
        assertEquals(JsonPrimitive(.8), targets["acceleratorKernelTimeShareExclusiveLowerBound"])
        assertNull(report.recommendation())
        assertTrue(report.qualifiedResults().isEmpty())
    }

    @Test fun boundedBatteryCounterHistoryPreservesUnknownAndRejectsDuplicateOrUnselectedBackends() {
        val pending = VoiceSpeechBenchmarkEnergyObservation(VoiceSpeechBackend.CPU, VoiceSpeechEnergyEndpoint())
        val report = VoiceSpeechBenchmarkReport(10, configuration(), energyObservations = listOf(pending))
        val local = speechBenchmarkJson(report, local = true)
        assertEquals(report, speechBenchmarkReportFromJson(local))
        assertEquals(JsonNull, local["batteryEnergy"])
        val historical = checkNotNull(speechBenchmarkReportFromJson(JsonObject(local - "batteryCounterObservations")))
        assertTrue(historical.energyObservations.isEmpty())
        val entry = local.getValue("batteryCounterObservations").jsonArray.single()
        assertNull(speechBenchmarkReportFromJson(JsonObject(local + ("batteryCounterObservations" to JsonArray(listOf(entry, entry))))))
        val wrongBackend = JsonObject(entry.jsonObject + ("requestedBackend" to JsonPrimitive(VoiceSpeechBackend.QNN_GPU.name)))
        assertNull(speechBenchmarkReportFromJson(JsonObject(local + ("batteryCounterObservations" to JsonArray(listOf(wrongBackend))))))
        assertNull(report.recommendation())
    }

    @Test fun playbackGenerationWallCostDoesNotMasqueradeAsInferenceWithoutGraphPreparation() {
        val observation = failure(true).copy(currentPrepareMs = 120, synthesisMs = 100)
        val chunk = VoiceSpeechPlaybackChunk(0, 220, 220, 1000, 4, "a".repeat(64), "cpu", true, null, null, observation)
        val row = VoiceSpeechPlaybackResult(VoiceSpeechBackend.CPU, VoiceSpeechPlaybackState.COMPLETE, totalSynthesisMs = 220, chunkRecords = listOf(chunk))
        val report = VoiceSpeechBenchmarkReport(10, configuration().copy(playback = true), playbackResults = listOf(row))
        val exported = speechBenchmarkJson(report).getValue("playbackResults").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(220L), exported["completedGenerationWallMs"])
        assertEquals(JsonPrimitive(120L), exported["totalGraphPreparationMs"])
        assertEquals(JsonPrimitive(100L), exported["totalNativeRunMs"])
        val missing = report.copy(playbackResults = listOf(row.copy(chunkRecords = listOf(chunk.copy(observation = null)))))
        val unknown = speechBenchmarkJson(missing).getValue("playbackResults").jsonArray.single().jsonObject
        assertEquals(JsonNull, unknown["totalGraphPreparationMs"])
        assertEquals(JsonNull, unknown["totalNativeRunMs"])
    }

    @Test fun verifiedPlaybackDoesNotPromoteAnExplicitUnknownPcmMeasurementWhenRestored() {
        for (available in listOf(null, false, true)) {
            val observation = failure(available).copy(
                audioRms = .1.takeIf { available == true },
                audioPeak = .2.takeIf { available == true },
                clippedSamples = 0L.takeIf { available == true },
                nonSilentSamples = 24_000L.takeIf { available == true },
                validationReason = null
            )
            val chunk = VoiceSpeechPlaybackChunk(0, 100, 100, 1000, 4, "a".repeat(64), "cpu", true, null, null, observation)
            val row = VoiceSpeechPlaybackResult(VoiceSpeechBackend.CPU, VoiceSpeechPlaybackState.COMPLETE, totalSynthesisMs = 100, totalAudioMs = 1000, chunks = 1, validatedChunks = 1, chunkRecords = listOf(chunk))
            val report = VoiceSpeechBenchmarkReport(10, configuration().copy(playback = true), playbackResults = listOf(row))
            val restored = checkNotNull(speechBenchmarkReportFromJson(speechBenchmarkJson(report, local = true)))
            assertEquals(available, restored.playbackResults.single().chunkRecords.single().observation!!.audioMeasurementAvailable)
            assertEquals(report, restored)
        }
    }

    private fun sdkReport(): VoiceSpeechBenchmarkReport {
        val config = configuration().copy(
            mode = VoiceSpeechBenchmarkMode.STABILITY,
            engine = VoiceSpeechBenchmarkEngine.PHONON,
            engineVoiceId = "freya",
            backends = listOf(VoiceSpeechBackend.PHONON_CPU),
            deadlines = VoiceSpeechBenchmarkDeadlines.forMode(VoiceSpeechBenchmarkMode.STABILITY)
        )
        val samples = VoiceSpeechBenchmarkFixture.entries.flatMap { fixture ->
            (0..20).map { repeat ->
                VoiceSpeechEngineBenchmarkSample(
                    fixture, repeat, 10, "c".repeat(64), 150, 0, 129, null, 78_000, 130, 1, 1,
                    true, 0, 0, .1, .2, 0, 78_000,
                    List(128) { VoiceSpeechEnginePcmReceipt(it, it.toLong(), 600, 0) }, 2
                )
            }
        }
        return VoiceSpeechBenchmarkReport(
            10,
            config,
            VoiceSpeechBenchmarkRunState.COMPLETE,
            results = emptyList(),
            engineResults = listOf(VoiceSpeechEngineBenchmarkResult(VoiceSpeechBackend.PHONON_CPU, VoiceSpeechBenchmarkBackendState.COMPLETE, samples = samples, cleanupState = "RETIRED"))
        )
    }

    @Test fun sdkStabilityRetainsAllSamplesAndBoundedCallbackReceiptsWithoutClaimingProviderOrQuality() {
        val report = sdkReport()
        val local = speechBenchmarkJson(report, local = true)
        assertEquals(report, speechBenchmarkReportFromJson(local))
        assertTrue(local.toString().toByteArray().size < 4 * 1024 * 1024)
        val exported = speechBenchmarkJson(report)
        assertEquals(JsonNull, exported["recommendedBackend"])
        assertEquals(JsonNull, exported["audioReferenceProtocol"])
        assertFalse(exported.getValue("configuration").jsonObject.containsKey("localDeviceKey"))
        assertEquals(JsonPrimitive("freya"), exported.getValue("configuration").jsonObject["engineVoiceId"])
        val result = exported.getValue("engineResults").jsonArray.single().jsonObject
        assertEquals(JsonNull, result["observedProvider"])
        assertEquals(JsonPrimitive("NOT_EXPOSED_BY_SDK"), result["providerEvidenceStatus"])
        assertEquals(63, result.getValue("samples").jsonArray.size)
        result.getValue("fixtureStatistics").jsonArray.forEach { statistics ->
            assertEquals(JsonPrimitive(20), statistics.jsonObject["warmSampleCount"])
            assertEquals(JsonPrimitive(150L), statistics.jsonObject["p95WarmMs"])
        }
        assertNull(report.recommendation())
    }

    @Test fun impossibleSdkPlacementAndCallbackCountsCannotBeRestoredAsEvidence() {
        val original = speechBenchmarkJson(sdkReport(), local = true)
        val result = original.getValue("engineResults").jsonArray.single().jsonObject
        val inventedProvider = JsonObject(result + ("observedProvider" to JsonPrimitive("gpu")))
        assertNull(speechBenchmarkReportFromJson(JsonObject(original + ("engineResults" to JsonArray(listOf(inventedProvider))))))
        val samples = result.getValue("samples").jsonArray
        val inconsistent = JsonObject(samples.first().jsonObject + ("omittedPcmReceipts" to JsonPrimitive(0)))
        val invalidResult = JsonObject(result + ("samples" to JsonArray(listOf(inconsistent) + samples.drop(1))))
        assertNull(speechBenchmarkReportFromJson(JsonObject(original + ("engineResults" to JsonArray(listOf(invalidResult))))))
    }

    @Test fun boundedSdkPlaybackTracesKeepCompleteTotalsAndCannotInventNativeExecution() {
        val sdk = sdkReport()
        val records = List(128) { index -> VoiceSpeechPlaybackChunk(index, index.toLong(), 1, 1, null, null, null, false, null, null, null) }
        val row = VoiceSpeechPlaybackResult(
            VoiceSpeechBackend.PHONON_CPU, VoiceSpeechPlaybackState.COMPLETE, totalSynthesisMs = 130, totalAudioMs = 130, chunks = 130,
            protocolVersion = VoiceSpeechPlaybackProtocol.VERSION, chunkRecords = records, requestedDelegate = "CPU",
            observedProviderStatus = "NOT_EXPOSED_BY_SDK", streamedAudioValidated = true, engineStreamCalls = 1,
            engineStreamWallMs = 150, omittedPcmRecords = 2, cleanupState = "RETIRED"
        )
        val report = sdk.copy(
            configuration = sdk.configuration.copy(mode = VoiceSpeechBenchmarkMode.FULL, playback = true),
            engineResults = listOf(sdk.engineResults.single().copy(samples = emptyList())),
            playbackResults = listOf(row)
        )
        val json = speechBenchmarkJson(report, local = true)
        assertEquals(report, speechBenchmarkReportFromJson(json))
        val exported = json.getValue("playbackResults").jsonArray.single().jsonObject
        assertEquals(JsonNull, exported["completedGenerationWallMs"])
        assertEquals(JsonPrimitive(130L), exported["deliveredPcmProductionMs"])
        assertEquals(JsonPrimitive(150L), exported["engineStreamWallMs"])
        assertEquals(JsonPrimitive(2), exported["omittedPcmRecords"])
        assertEquals(JsonNull, json["audioReferenceLimits"])
        assertEquals(JsonPrimitive("NOT_EXPOSED_BY_SDK"), json["cacheBudgetScope"])
        for (changes in listOf(
            mapOf("effectiveProvider" to JsonPrimitive("cpu")),
            mapOf("acceleratorExecutionObserved" to JsonPrimitive(true)),
            mapOf("requestedDelegate" to JsonPrimitive("GPU")),
            mapOf("omittedPcmRecords" to JsonPrimitive(0))
        )) {
            val forged = JsonObject(exported + changes)
            assertNull(speechBenchmarkReportFromJson(JsonObject(json + ("playbackResults" to JsonArray(listOf(forged))))))
        }
    }
}
