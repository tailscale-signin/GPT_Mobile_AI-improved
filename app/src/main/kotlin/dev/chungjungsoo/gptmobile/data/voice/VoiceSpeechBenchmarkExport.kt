package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Fixed identities and numeric observations; no transcript, PCM, serial or exception text. */
internal fun voiceSpeechBenchmarkExport(report: VoiceSpeechBenchmarkReport): String =
    Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), speechBenchmarkJson(report))

/** Freeze the report before calling; staging and document-provider copies run off the UI thread. */
internal fun writeVoiceSpeechBenchmarkExport(report: VoiceSpeechBenchmarkReport, output: java.io.OutputStream, local: Boolean = false) {
    VoiceExportJsonWriter(output).apply {
        objectValue(
            speechBenchmarkJson(report, local = local, includeRecords = false),
            mapOf(
                "results" to {
                    array(report.results.takeUnless { report.configuration.playback }.orEmpty()) { result ->
                        objectValue(
                            speechBenchmarkResultJson(report, result, includeRecords = false),
                            mapOf(
                                "samples" to { array(result.samples) { element(speechBenchmarkSampleJson(it)) } },
                                "incompleteChunks" to { array(result.incompleteChunks) { element(chunkJson(it)) } }
                            )
                        )
                    }
                },
                "playbackResults" to { array(report.playbackResults) { element(playbackJson(it)) } },
                "engineResults" to {
                    array(report.engineResults) { result ->
                        objectValue(
                            speechEngineBenchmarkJson(result, includeSamples = false),
                            mapOf("samples" to { array(result.samples) { element(engineSampleJson(it)) } })
                        )
                    }
                }
            )
        )
        finish()
    }
}

internal fun speechBenchmarkJson(report: VoiceSpeechBenchmarkReport, local: Boolean = false, includeRecords: Boolean = true): JsonObject = buildJsonObject {
    put("schemaVersion", 6)
    val nativeEngine = report.configuration.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO
    put("fixtureVersion", report.configuration.fixtureVersion)
    nullable("playbackFixture", "PER_SAMPLE_MANIFEST".takeIf { report.configuration.playback })
    nullable("playbackPacing", "BALANCED_NATURAL".takeIf { report.configuration.playback })
    put("measurement", if (report.configuration.playback) "production_pipeline_playback_on_this_device" else "planned_phrase_synthesis_on_this_device")
    put("coldScope", "fresh_engine_session_os_file_cache_not_cleared")
    put("warmScope", if (nativeEngine) "repeated_workload_each_chunk_records_actual_shape_cache_use" else "repeated_workload_engine_reuse_recorded_shape_cache_receipt_unavailable")
    put(
        "firstPcmBasis",
        if (nativeEngine) {
            "first_complete_native_chunk_of_fixture"
        } else if (report.configuration.playback) {
            "first_nonempty_retained_sdk_pcm_callback_of_fixture"
        } else {
            "first_nonempty_sdk_pcm_callback_of_fixture"
        }
    )
    put("kernelProfileScope", if (nativeEngine) "identified_first_shape_inference_reused_profiles_are_not_new_measurements" else "NOT_EXPOSED_BY_SDK")
    put("nativeSynthesisCostScope", if (nativeEngine) "native_run_including_profile_finalize_frontend_and_validation_separate" else "NOT_APPLICABLE_SEE_ENGINE_GENERATION_AND_PLAYBACK_COST_SCOPES")
    put("summaryKernelScope", if (nativeEngine) "last_chunk_profile_see_chunks_for_individual_shapes" else "NOT_EXPOSED_BY_SDK")
    put("aggregateTimingScope", "mixed_fixture_lengths_descriptive_only_use_fixture_statistics")
    put("verifiedRequestedBackendScope", if (nativeEngine) "completed_structural_and_provider_execution_not_cpu_audio_equivalence" else "SDK_DELEGATE_REQUEST_IS_NOT_OBSERVED_PROCESSOR_EXECUTION")
    nullable("audioReferenceProtocol", VoiceSpeechAudioReference.PROTOCOL.takeIf { nativeEngine })
    put("audioReferenceScope", if (nativeEngine) "same_run_fixed_input_voice_rate_threads_model_runtime_duration_token_timing_signal_not_perceptual_equivalence" else "NOT_APPLICABLE_ACROSS_ENGINE_TOKENIZERS_AND_VOICES")
    put("crossEngineQuality", "NOT_MEASURED_INTELLIGIBILITY_AND_PERCEPTUAL_REVIEW_REQUIRED")
    put("engineComparisonScope", "fixed_spoken_fixture_actual_engine_voice_rate_model_and_runtime_recorded_no_automatic_cross_engine_winner")
    put("cacheBudgetScope", if (nativeEngine) "memory_admitted_residency_estimate_retention_ceiling_not_allocator_limit_active_shape_may_exceed" else "NOT_EXPOSED_BY_SDK")
    put("synthesisCancellationScope", if (nativeEngine) "request_phase_and_native_retirement_distinct_preparation_cannot_be_interrupted_by_run_options" else "request_native_call_return_and_handle_close_are_distinct_sdk_preparation_may_not_be_interruptible")
    put(
        "audioReferenceLimits",
        buildJsonObject {
            put("minimumDurationRatio", VoiceSpeechAudioReference.MIN_DURATION_RATIO)
            put("maximumDurationRatio", VoiceSpeechAudioReference.MAX_DURATION_RATIO)
            put("maximumTokenTimingDistance", VoiceSpeechAudioReference.MAX_TOKEN_TIMING_DISTANCE)
            put("minimumRmsRatio", VoiceSpeechAudioReference.MIN_RMS_RATIO)
            put("maximumRmsRatio", VoiceSpeechAudioReference.MAX_RMS_RATIO)
            put("maximumPeakRatio", VoiceSpeechAudioReference.MAX_PEAK_RATIO)
            put("maximumPeakAbsoluteIncrease", VoiceSpeechAudioReference.MAX_PEAK_ABSOLUTE_INCREASE)
            put("maximumClippedFractionIncrease", VoiceSpeechAudioReference.MAX_CLIPPED_FRACTION_INCREASE)
            put("maximumReferenceAndCandidateClippedSamples", VoiceSpeechBenchmarkTargets.CLIPPED_SAMPLES)
            put("maximumActivityFractionDifference", VoiceSpeechAudioReference.MAX_ACTIVITY_FRACTION_DIFFERENCE)
        }.takeIf { nativeEngine } ?: JsonNull
    )
    put("medianConvention", "average_middle_pair_for_even_samples")
    put("percentileConvention", "nearest_rank_minimum_20_samples")
    put("minimumPercentileSamples", VoiceSpeechBenchmarkBackendResult.MIN_PERCENTILE_SAMPLES)
    put("playbackCancellationBasis", "audio_stop_acknowledgement_before_native_retirement")
    put("captionValidationScope", "independent_fixed_fixture_ranges_plus_clock_consistency_not_acoustic_alignment")
    put("orderingProtocol", "recorded_cyclic_rotation_across_runs_not_randomized_within_run")
    put("plannedBackendOrder", buildJsonArray { report.configuration.plannedBackendOrder.forEach { add(JsonPrimitive(it.name)) } })
    put("playbackLatencyMs", JsonNull)
    put("acousticLatencyMs", JsonNull)
    put("audioUnderruns", JsonNull)
    put("batteryEnergy", JsonNull)
    put("batteryCounterScope", "WHOLE_DEVICE_REMAINING_NWH_COUNTER_ENDPOINTS_AND_OBSERVED_POWER_EVENTS_NOT_ENGINE_ENERGY_OR_POWER")
    put("batteryCounterWindow", "BACKEND_PUBLICATION_WINDOW_INCLUDES_PREPARATION_WORKLOAD_REPORTING_AND_OBSERVED_CLEANUP_PARTIAL_WORK_RETAINED")
    put("batteryCounterLimitations", "DEVICE_SUPPORT_RESOLUTION_AND_BROADCAST_DELIVERY_VARY_BACKGROUND_LOAD_AND_UNOBSERVED_TRANSITIONS_ARE_NOT_ISOLATED_NO_AUTOMATIC_ENERGY_RANKING")
    put("batteryCounterObservations", buildJsonArray { report.energyObservations.forEach { add(speechBenchmarkEnergyJson(it)) } })
    put(
        "measurementAvailability",
        buildJsonObject {
            put("synthesisSamples", if (report.configuration.playback) "NOT_APPLICABLE_PLAYBACK_MODE" else "COLLECTED_PER_FIXTURE")
            put("playbackTiming", if (report.configuration.playback) "COLLECTED_PER_SAMPLE" else "NOT_RUN")
            put("acousticLatency", "NOT_MEASURED_EXTERNAL_SENSOR_REQUIRED")
            put("batteryEnergy", "NOT_ATTRIBUTABLE_TO_ENGINE")
            put("batteryCounter", if (report.energyObservations.isEmpty()) "NOT_COLLECTED" else "SEE_BOUNDED_ENDPOINT_AND_POWER_EVENT_OBSERVATIONS")
            put("processorUtilization", "NOT_COLLECTED_PROVIDER_KERNEL_EVIDENCE_IS_DISTINCT")
            put("acousticCaptionAlignment", "NOT_TESTED")
            put("semanticCorrectness", "NOT_MEASURED")
            put("intelligibility", "NOT_MEASURED")
            put("cancellation", if (report.configuration.playback && VoiceSpeechPlaybackScenario.CANCELLATION in report.configuration.playbackScenarios) "SEE_TARGET_OBSERVATIONS" else "NOT_TESTED")
        }
    )
    put(
        "performanceTargets",
        buildJsonObject {
            put("scope", "PRODUCT_BUDGETS_NOT_MEASURED_ACHIEVEMENTS")
            put("firstPresentedAudioMs", VoiceSpeechPlaybackProtocol.FIRST_AUDIO_TARGET_MS)
            put("cancellationMs", VoiceSpeechPlaybackProtocol.CANCELLATION_TARGET_MS)
            put("activePlaybackUnderruns", 0)
            put("bufferRestarts", 0)
            put("prepareToFirstPcmExclusiveUpperBoundMs", VoiceSpeechBenchmarkTargets.PREPARE_TO_FIRST_PCM_MS)
            put("warmFirstPcmExclusiveUpperBoundMs", VoiceSpeechBenchmarkTargets.WARM_FIRST_PCM_MS)
            put("longSpeechRealTimeFactorExclusiveUpperBound", VoiceSpeechBenchmarkTargets.LONG_SPEECH_RTF)
            put("acceleratorKernelTimeShareExclusiveLowerBound", VoiceSpeechBenchmarkTargets.ACCELERATOR_KERNEL_TIME_SHARE)
            put("acceleratorShareScope", "OBSERVED_KERNEL_TIME_WHERE_FEASIBLE_NOT_DEVICE_UTILIZATION_OR_A_QUALIFICATION_REQUIREMENT")
            put("maximumClippedSamples", VoiceSpeechBenchmarkTargets.CLIPPED_SAMPLES)
        }
    )
    put(
        "playbackManifest",
        buildJsonArray {
            if (report.configuration.playback && report.configuration.playbackProtocolVersion == VoiceSpeechPlaybackProtocol.VERSION) {
                VoiceSpeechPlaybackProtocol.plans(report.configuration).forEach { plan ->
                    add(
                        buildJsonObject {
                            put("scenario", plan.scenario.name)
                            put("repeat", plan.repeat)
                            put("fixtureHash", plan.manifest.hash)
                            put("segments", plan.segments.size)
                            put("wordsExpected", plan.manifest.spans.size)
                            put("warmup", plan.warmup)
                            put("expectedResident", plan.expectedResident)
                            put("arrivalIntervalMs", plan.arrivalIntervalMs)
                            nullable("cancellationTarget", plan.cancellationTarget?.name)
                        }
                    )
                }
            }
        }
    )
    put(
        "backendStates",
        buildJsonArray {
            if (report.configuration.playback) {
                report.results.forEach { result ->
                    add(
                        buildJsonObject {
                            put("requestedBackend", result.requestedBackend.name)
                            put("state", result.state.name)
                            nullable("failure", result.failure?.name)
                            nullable("failureStage", result.failureStage?.name)
                            put("measurementScope", "PLAYBACK_LIFECYCLE_ONLY")
                            put("audioReferenceStatus", report.playbackAudioReference(result.requestedBackend).name)
                        }
                    )
                }
            }
        }
    )
    put("startedAtMillis", report.startedAtMillis)
    nullable("finishedAtMillis", report.finishedAtMillis)
    put("state", report.state.name)
    put("outcome", report.outcome.name)
    nullable("issue", report.issue?.name)
    nullable("recommendedBackend", report.recommendation()?.name)
    put(
        "configuration",
        buildJsonObject {
            val c = report.configuration
            put("engine", c.engine.name)
            nullable("engineVoiceId", c.engineVoiceId?.takeIf { it.matches(IDENTITY) })
            put("voiceScope", if (nativeEngine) "configured_native_voice" else "engineVoiceId_is_actual_sdk_voice_native_voice_field_is_not_used")
            put("threadsScope", if (nativeEngine) "configured_native_cpu_threads" else "not_exposed_by_sdk_native_threads_field_is_not_used")
            put("deepDiagnostics", c.deepDiagnostics)
            put("kokoroBucket", c.kokoroBucket.name)
            put("kokoroBucketTokens", c.kokoroBucket.sdkValue)
            put("incrementalInput", c.incrementalInput)
            put(
                "sdkInputMode",
                if (nativeEngine) {
                    "NOT_APPLICABLE"
                } else if (c.incrementalInput) {
                    "INCREMENTAL_REPLY"
                } else {
                    "PHRASE_STREAMS"
                }
            )
            put("htpPerformanceMode", c.htpPerformanceMode)
            put("nativeModelVariant", c.nativeModelVariant.name)
            put(
                "deadlines",
                c.deadlines?.let { limits ->
                    buildJsonObject {
                        put("preparationMs", limits.preparationMs)
                        put("chunkMs", limits.chunkMs)
                        put("backendMs", limits.backendMs)
                    }
                } ?: JsonNull
            )
            put("mode", c.mode.name)
            put("backends", buildJsonArray { c.backends.forEach { add(JsonPrimitive(it.name)) } })
            put("voice", c.voice.name)
            put("threads", c.threads)
            put("speechRate", c.speechRate)
            nullable("modelSha256", c.modelSha256.takeIf { it.matches(SHA256) })
            nullable("runtimeId", c.runtimeId.takeIf { it.matches(IDENTITY) })
            put("appBuild", c.appBuild)
            put("sdk", c.sdk)
            put("workload", c.workload.name)
            put("protocolId", c.protocolId)
            nullable("fixtureHash", c.fixtureHash)
            put("backendOrderSeed", c.backendOrderSeed)
            nullable("sourceCommit", c.sourceCommit)
            put("playback", c.playback)
            put("playbackProtocolVersion", c.playbackProtocolVersion)
            put("playbackScenarios", buildJsonArray { c.playbackScenarios.forEach { add(JsonPrimitive(it.name)) } })
            put("sustainedTargetAudioMs", c.sustainedTargetAudioMs)
            put("calibrationPolicy", c.calibrationPolicy.name)
            put("captionOffsetMs", c.captionOffsetMs)
            if (local) nullable("localDeviceKey", c.deviceKey.takeIf { it.matches(SHA256) })
        }
    )
    put(
        "results",
        buildJsonArray {
            for (r in report.results.takeIf { includeRecords && !report.configuration.playback }.orEmpty()) {
                add(
                    speechBenchmarkResultJson(report, r)
                )
            }
        }
    )
    put("playbackResults", buildJsonArray { report.playbackResults.takeIf { includeRecords }.orEmpty().forEach { add(playbackJson(it)) } })
    put("engineResults", buildJsonArray { report.engineResults.takeIf { includeRecords }.orEmpty().forEach { add(speechEngineBenchmarkJson(it)) } })
}

private fun speechBenchmarkResultJson(report: VoiceSpeechBenchmarkReport, r: VoiceSpeechBenchmarkBackendResult, includeRecords: Boolean = true): JsonObject = buildJsonObject {
    put("requestedBackend", r.requestedBackend.name)
    put("state", r.state.name)
    nullable("sessionPrepareMs", r.sessionPrepareMs)
    nullable("preparationThermalBefore", r.preparationThermalBefore)
    nullable("failure", r.failure?.name)
    nullable("failureStage", r.failureStage?.name)
    nullable("nativeCode", r.nativeCode)
    put("failureEffectiveProvider", provider(r.failureEffectiveProvider))
    nullable("failureAcceleratorKernels", r.failureAcceleratorKernels)
    nullable("failureCpuKernels", r.failureCpuKernels)
    nullable("failedFixture", r.failedFixture?.name)
    nullable("failedRepeat", r.failedRepeat)
    nullable("failedChunk", r.failedChunk)
    nullable("failedInputBytes", r.failedInputBytes)
    nullable("failedInputByteLimit", r.failedInputByteLimit)
    nullable("failedTokenCount", r.failedTokenCount)
    nullable("failedTokenLimit", r.failedTokenLimit)
    put("failedRequestedProvider", provider(r.failedRequestedProvider))
    nullable("failedDiscoverySummary", discovery(r.failedDiscoverySummary))
    nullable("failedAttemptMs", r.failedAttemptMs)
    put("failureObservation", r.failureObservation?.let(::failureObservationJson) ?: JsonNull)
    nullable("incompleteFixture", r.incompleteFixture?.name)
    nullable("incompleteRepeat", r.incompleteRepeat)
    put("incompleteChunks", buildJsonArray { r.incompleteChunks.takeIf { includeRecords }.orEmpty().forEach { add(chunkJson(it)) } })
    put("executionVerified", r.executionVerified)
    put("audioReference", audioReferenceJson(report.audioReference(r)))
    put("shapeObservations", shapeObservationsJson(r))
    nullable("cancellationStage", r.cancellationStage?.name)
    nullable("cancellationNativeStage", r.cancellationNativeStage?.name)
    nullable("cancellationNativeRetirementMs", r.cancellationNativeRetirementMs)
    nullable("stopReason", r.stopReason?.name)
    nullable("cleanupState", r.cleanupState?.name)
    nullable("deadlineNativeRetirementMs", r.deadlineNativeRetirementMs)
    put(
        "deadline",
        r.deadline?.let { timeout ->
            buildJsonObject {
                put("scope", timeout.scope.name)
                put("limitMs", timeout.limitMs)
                put("elapsedMs", timeout.elapsedMs)
                nullable("stage", timeout.stage?.name)
            }
        } ?: JsonNull
    )
    put("verifiedRequestedBackend", r.verified)
    put("qualifiedComparison", r in report.qualifiedResults())
    put("measuredCostMs", r.measuredCostMs)
    put("warmSampleCount", r.warmSamples.size)
    put("medianWarmMs", number(r.medianMs))
    nullable("p95WarmMs", r.p95Ms)
    put("p95Availability", if (r.p95Ms == null) "INSUFFICIENT_SAMPLES" else "MEASURED")
    put("medianWarmRealTimeFactor", number(r.medianRtf))
    put(
        "fixtureStatistics",
        buildJsonArray {
            for (fixture in VoiceSpeechBenchmarkFixture.entries) {
                val s = r.fixtureStatistics(fixture)
                add(
                    buildJsonObject {
                        put("fixture", fixture.name)
                        put("sampleCount", s.sampleCount)
                        put("warmSampleCount", s.warmSampleCount)
                        put("medianWarmMs", number(s.medianWarmMs))
                        nullable("p95WarmMs", s.p95WarmMs)
                        put("medianFirstPcmMs", number(s.medianFirstPcmMs))
                        put("medianWarmRtf", number(s.medianWarmRtf))
                        put("totalGenerationMs", s.totalGenerationMs)
                        put("totalAudioMs", number(s.totalAudioMs))
                        put("completedChunks", s.completedChunks)
                    }
                )
            }
        }
    )
    put(
        "samples",
        buildJsonArray {
            for (s in if (includeRecords) r.samples else emptyList()) {
                add(
                    speechBenchmarkSampleJson(s)
                )
            }
        }
    )
}

private fun speechBenchmarkSampleJson(s: VoiceSpeechBenchmarkSample): JsonObject = buildJsonObject {
    put("fixture", s.fixture.name)
    put("repeat", s.repeat)
    put("warm", s.warm)
    put("generationMs", s.generationMs)
    put("firstPcmMs", s.firstPcmMs)
    nullable("prepareToFirstPcmMs", s.prepareToFirstPcmMs)
    put("audioMs", number(s.audioMs))
    put("realTimeFactor", number(s.realTimeFactor))
    put("effectiveProvider", provider(s.effectiveProvider))
    val unprofiled = s.chunks.isNotEmpty() && s.chunks.last().profileAvailable != true
    nullable("acceleratorKernels", s.acceleratorKernels.takeUnless { unprofiled })
    nullable("cpuKernels", s.cpuKernels.takeUnless { unprofiled })
    nullable("acceleratorMicros", s.acceleratorMicros.takeUnless { unprofiled })
    nullable("cpuMicros", s.cpuMicros.takeUnless { unprofiled })
    put("graphPrepareMs", s.graphPrepareMs)
    put("tokenCount", s.tokenCount)
    put("validatedAudio", s.validatedAudio)
    nullable("thermalBefore", s.thermalBefore)
    nullable("thermalAfter", s.thermalAfter)
    nullable("hardwareType", s.hardwareType)
    put("chunks", buildJsonArray { s.chunks.forEach { add(chunkJson(it)) } })
}

private fun chunkJson(c: VoiceSpeechBenchmarkChunk) = buildJsonObject {
    put("index", c.index)
    put("inputBytes", c.inputBytes)
    put("inputSha256", c.inputSha256)
    put("generationMs", c.generationMs)
    put("audioMs", number(c.audioMs))
    put("realTimeFactor", number(c.realTimeFactor))
    put("effectiveProvider", provider(c.effectiveProvider))
    val unprofiled = c.profileAvailable != true
    nullable("acceleratorKernels", c.acceleratorKernels.takeUnless { unprofiled })
    nullable("cpuKernels", c.cpuKernels.takeUnless { unprofiled })
    nullable("acceleratorMicros", c.acceleratorMicros.takeUnless { unprofiled })
    nullable("cpuMicros", c.cpuMicros.takeUnless { unprofiled })
    put("graphPrepareMs", c.graphPrepareMs)
    put("tokenCount", c.tokenCount)
    put("validatedAudio", c.validatedAudio)
    nullable("thermalBefore", c.thermalBefore)
    nullable("thermalAfter", c.thermalAfter)
    nullable("hardwareType", c.hardwareType)
    nullable("profileAvailable", c.profileAvailable)
    nullable("profileId", c.profileId)
    nullable("profileReused", c.profileReused)
    nullable("cacheHit", c.cacheHit)
    nullable("currentPrepareMs", c.currentPrepareMs)
    nullable("graphPreparedThisCall", c.graphPreparedThisCall)
    nullable("cachedShapeCount", c.cachedShapeCount)
    nullable("cacheLimit", c.cacheLimit)
    nullable("cacheEstimatedBytes", c.cacheEstimatedBytes)
    put("audioRms", number(c.audioRms))
    put("audioPeak", number(c.audioPeak))
    nullable("clippedSamples", c.clippedSamples)
    nullable("nonSilentSamples", c.nonSilentSamples)
    nullable("discoverySummary", discovery(c.discoverySummary))
    nullable("frontendMs", c.frontendMs)
    nullable("validationMs", c.validationMs)
    nullable("cacheEvictions", c.cacheEvictions)
    nullable("profileFinalizeMs", c.profileFinalizeMs)
    nullable("nativeSynthesisMs", c.nativeSynthesisMs)
    put("audioObservation", c.audioObservation?.let(::speechAudioObservationJson) ?: JsonNull)
    nullable("cacheBudgetBytes", c.cacheBudgetBytes)
    nullable("activeShapeEstimatedBytes", c.activeShapeEstimatedBytes)
    nullable("availableMemoryBytes", c.availableMemoryBytes)
    nullable("cacheAdmission", safeSpeechCacheAdmission(c.cacheAdmission))
    nullable("graphPreparationSource", safeSpeechGraphPreparation(c.graphPreparationSource))
    nullable("contextCacheStatus", safeSpeechContextCacheStatus(c.contextCacheStatus))
    nullable("contextLoadMs", c.contextLoadMs)
    nullable("contextStoreMs", c.contextStoreMs)
    nullable("contextBytes", c.contextBytes)
    nullable("contextCacheEvictions", c.contextCacheEvictions)
    nullable("contextFallback", c.contextFallback)
    nullable("htpPerformanceMode", safeSpeechHtpPerformanceMode(c.htpPerformanceMode))
}

internal fun speechAudioObservationJson(o: VoiceSpeechAudioObservation) = buildJsonObject {
    put("inputSha256", o.inputSha256)
    put("inputBytes", o.inputBytes)
    put("sampleCount", o.sampleCount)
    put("tokenDurations", buildJsonArray { o.tokenDurations.forEach { add(JsonPrimitive(it)) } })
    put("rms", number(o.rms))
    put("peak", number(o.peak))
    put("clippedSamples", o.clippedSamples)
    put("nonSilentSamples", o.nonSilentSamples)
}

internal fun readSpeechAudioObservation(o: JsonObject) = VoiceSpeechAudioObservation(
    o.string("inputSha256").also { require(it.matches(SHA256)) },
    o.boundedLong("inputBytes", 128).toInt(),
    o.boundedLong("sampleCount", 720_000).toInt(),
    o.getValue("tokenDurations").jsonArray.also { require(it.size in 1..511) }.map { it.jsonPrimitive.long.also { value -> require(value in 1..1200) } },
    o.positiveDouble("rms", 1_000_000.0),
    o.positiveDouble("peak", 1_000_000.0),
    o.boundedLong("clippedSamples", 720_000),
    o.boundedLong("nonSilentSamples", 720_000)
).also { require(it.valid) }

private fun audioReferenceJson(reference: VoiceSpeechBenchmarkAudioReference) = buildJsonObject {
    val comparison = reference.comparison
    put("status", comparison.status.name)
    nullable("reason", comparison.reason?.name)
    put("comparedChunks", reference.comparedChunks)
    put("observedChunks", reference.observedChunks)
    nullable("fixture", reference.fixture?.name)
    nullable("repeat", reference.repeat)
    nullable("chunk", reference.chunk)
    put("referenceAudioMs", number(comparison.referenceAudioMs))
    put("candidateAudioMs", number(comparison.candidateAudioMs))
    put("durationRatio", number(comparison.durationRatio))
    put("tokenTimingDistance", number(comparison.tokenTimingDistance))
    put("rmsRatio", number(comparison.rmsRatio))
}

private fun shapeObservationsJson(result: VoiceSpeechBenchmarkBackendResult) = buildJsonArray {
    val chunks = result.samples.flatMap { it.chunks } + result.incompleteChunks
    for ((tokens, rows) in chunks.groupBy { it.tokenCount }.toSortedMap()) {
        add(
            buildJsonObject {
                put("tokenCount", tokens)
                put("completedChunks", rows.size)
                put("observedCacheLookups", rows.count { it.cacheHit != null })
                put("cacheHits", rows.count { it.cacheHit == true })
                put("graphPreparations", rows.count { it.graphPreparedThisCall == true })
                put("observedPreparationChunks", rows.count { it.currentPrepareMs != null })
                put("totalCurrentPrepareMs", rows.sumOf { it.currentPrepareMs ?: 0 })
                put("cacheEvictions", rows.sumOf { it.cacheEvictions ?: 0 })
                put("observedEvictionChunks", rows.count { it.cacheEvictions != null })
                put("totalGenerationMs", rows.sumOf { it.generationMs })
            }
        )
    }
}

private fun failureObservationJson(o: VoiceSpeechBenchmarkFailureObservation) = buildJsonObject {
    nullable("profileAvailable", o.profileAvailable)
    nullable("profileId", o.profileId)
    nullable("profileReused", o.profileReused)
    nullable("acceleratorMicros", o.acceleratorMicros.takeIf { o.profileAvailable == true })
    nullable("cpuMicros", o.cpuMicros.takeIf { o.profileAvailable == true })
    put("graphPrepareMs", o.graphPrepareMs)
    nullable("currentPrepareMs", o.currentPrepareMs)
    put("synthesisMs", o.synthesisMs)
    put("tokenCount", o.tokenCount)
    nullable("hardwareType", o.hardwareType)
    nullable("cacheHit", o.cacheHit)
    nullable("cachedShapeCount", o.cachedShapeCount)
    nullable("cacheLimit", o.cacheLimit)
    nullable("cacheEstimatedBytes", o.cacheEstimatedBytes)
    val measured = o.audioMeasurementAvailable == true
    nullable("audioMeasurementAvailable", o.audioMeasurementAvailable)
    nullable("audioFinite", o.audioFinite.takeIf { measured })
    put("audioRms", number(o.audioRms.takeIf { measured }))
    put("audioPeak", number(o.audioPeak.takeIf { measured }))
    nullable("clippedSamples", o.clippedSamples.takeIf { measured })
    nullable("nonSilentSamples", o.nonSilentSamples.takeIf { measured })
    nullable("validationReason", safeSpeechValidationReason(o.validationReason))
    nullable("outputTensorCount", o.outputTensorCount)
    nullable("outputFrames", o.outputFrames)
    nullable("outputElementType", o.outputElementType)
    nullable("outputRank", o.outputRank)
    nullable("durationCount", o.durationCount)
    nullable("durationElementType", o.durationElementType)
    nullable("durationRank", o.durationRank)
    nullable("durationStatsAvailable", o.durationStatsAvailable)
    val durationMeasured = o.durationStatsAvailable == true
    nullable("durationSumOverflow", o.durationSumOverflow.takeIf { durationMeasured })
    nullable("durationSum", o.durationSum.takeIf { durationMeasured && o.durationSumOverflow != true })
    nullable("durationMin", o.durationMin.takeIf { durationMeasured })
    nullable("durationMax", o.durationMax.takeIf { durationMeasured })
    nullable("frontendMs", o.frontendMs)
    nullable("validationMs", o.validationMs)
    nullable("cacheEvictions", o.cacheEvictions)
    nullable("profileFinalizeMs", o.profileFinalizeMs)
    nullable("cacheBudgetBytes", o.cacheBudgetBytes)
    nullable("activeShapeEstimatedBytes", o.activeShapeEstimatedBytes)
    nullable("availableMemoryBytes", o.availableMemoryBytes)
    nullable("cacheAdmission", safeSpeechCacheAdmission(o.cacheAdmission))
    nullable("graphPreparationSource", safeSpeechGraphPreparation(o.graphPreparationSource))
    nullable("contextCacheStatus", safeSpeechContextCacheStatus(o.contextCacheStatus))
    nullable("contextLoadMs", o.contextLoadMs)
    nullable("contextStoreMs", o.contextStoreMs)
    nullable("contextBytes", o.contextBytes)
    nullable("contextCacheEvictions", o.contextCacheEvictions)
    nullable("contextFallback", o.contextFallback)
    nullable("htpPerformanceMode", safeSpeechHtpPerformanceMode(o.htpPerformanceMode))
}

private fun readFailureObservation(o: JsonObject, validatedAudio: Boolean = false): VoiceSpeechBenchmarkFailureObservation {
    // A current explicit unknown cannot inherit the legacy success-only inference.
    val measured = if ("audioMeasurementAvailable" in o) o.optionalBoolean("audioMeasurementAvailable") else true.takeIf { validatedAudio }
    return VoiceSpeechBenchmarkFailureObservation(
        profileAvailable = o.optionalBoolean("profileAvailable"), profileId = o.optionalBoundedLong("profileId", Long.MAX_VALUE),
        profileReused = o.optionalBoolean("profileReused"), acceleratorMicros = o.optionalBoundedLong("acceleratorMicros", 3_600_000_000) ?: 0,
        cpuMicros = o.optionalBoundedLong("cpuMicros", 3_600_000_000) ?: 0, graphPrepareMs = o.boundedLong("graphPrepareMs"),
        currentPrepareMs = o.optionalBoundedLong("currentPrepareMs"), synthesisMs = o.boundedLong("synthesisMs"), tokenCount = o.boundedLong("tokenCount", 511).toInt(),
        hardwareType = o.optionalLong("hardwareType")?.also { require(it in -1..1000) }?.toInt(), cacheHit = o.optionalBoolean("cacheHit"),
        cachedShapeCount = o.optionalBoundedLong("cachedShapeCount", 32)?.toInt(), cacheLimit = o.optionalBoundedLong("cacheLimit", 32)?.toInt(),
        cacheEstimatedBytes = o.optionalBoundedLong("cacheEstimatedBytes", 16L * 1024 * 1024 * 1024),
        audioRms = o.optionalDouble("audioRms", 1_000_000.0).takeIf { measured == true },
        audioPeak = o.optionalDouble("audioPeak", 1_000_000.0).takeIf { measured == true },
        clippedSamples = o.optionalBoundedLong("clippedSamples", 720_000).takeIf { measured == true },
        nonSilentSamples = o.optionalBoundedLong("nonSilentSamples", 720_000).takeIf { measured == true },
        frontendMs = o.optionalBoundedLong("frontendMs"), validationMs = o.optionalBoundedLong("validationMs"),
        cacheEvictions = o.optionalBoundedLong("cacheEvictions", 1000)?.toInt(), profileFinalizeMs = o.optionalBoundedLong("profileFinalizeMs"),
        cacheBudgetBytes = o.optionalBoundedLong("cacheBudgetBytes", 16L * 1024 * 1024 * 1024),
        activeShapeEstimatedBytes = o.optionalBoundedLong("activeShapeEstimatedBytes", 16L * 1024 * 1024 * 1024),
        availableMemoryBytes = o.optionalBoundedLong("availableMemoryBytes", Long.MAX_VALUE),
        cacheAdmission = o.optionalCacheAdmission(),
        graphPreparationSource = o.optionalString("graphPreparationSource")?.also { require(safeSpeechGraphPreparation(it) != null) },
        contextCacheStatus = o.optionalString("contextCacheStatus")?.also { require(safeSpeechContextCacheStatus(it) != null) },
        contextLoadMs = o.optionalBoundedLong("contextLoadMs"),
        contextStoreMs = o.optionalBoundedLong("contextStoreMs"),
        contextBytes = o.optionalBoundedLong("contextBytes", 256L * 1024 * 1024),
        contextCacheEvictions = o.optionalBoundedLong("contextCacheEvictions", Long.MAX_VALUE),
        contextFallback = o.optionalBoolean("contextFallback"),
        htpPerformanceMode = o.optionalString("htpPerformanceMode")?.also { require(safeSpeechHtpPerformanceMode(it) != null) },
        audioMeasurementAvailable = measured,
        audioFinite = o.optionalBoolean("audioFinite").takeIf { measured == true },
        validationReason = o.optionalString("validationReason")?.also { require(safeSpeechValidationReason(it) != null) },
        outputTensorCount = o.optionalBoundedLong("outputTensorCount", Long.MAX_VALUE),
        outputFrames = o.optionalBoundedLong("outputFrames", Long.MAX_VALUE),
        outputElementType = o.optionalBoundedLong("outputElementType", 255), outputRank = o.optionalBoundedLong("outputRank", 64),
        durationCount = o.optionalBoundedLong("durationCount", Long.MAX_VALUE),
        durationElementType = o.optionalBoundedLong("durationElementType", 255), durationRank = o.optionalBoundedLong("durationRank", 64),
        durationStatsAvailable = o.optionalBoolean("durationStatsAvailable"),
        durationSumOverflow = o.optionalBoolean("durationSumOverflow").takeIf { o.optionalBoolean("durationStatsAvailable") == true },
        durationSum = o.optionalLong("durationSum").takeIf { o.optionalBoolean("durationStatsAvailable") == true && o.optionalBoolean("durationSumOverflow") != true },
        durationMin = o.optionalLong("durationMin").takeIf { o.optionalBoolean("durationStatsAvailable") == true },
        durationMax = o.optionalLong("durationMax").takeIf { o.optionalBoolean("durationStatsAvailable") == true }
    )
}

private fun playbackJson(r: VoiceSpeechPlaybackResult) = buildJsonObject {
    put("requestedBackend", r.requestedBackend.name)
    put("state", r.state.name)
    nullable("firstPcmMs", r.firstPcmMs)
    nullable("firstQueuedPcmMs", r.firstQueuedPcmMs)
    nullable("firstPresentedAudioMs", r.firstPresentedAudioMs)
    put("elapsedMs", r.elapsedMs)
    put("totalSynthesisMs", r.totalSynthesisMs)
    nullable("completedGenerationWallMs", r.totalSynthesisMs.takeIf { r.requestedDelegate == null })
    nullable("deliveredPcmProductionMs", r.totalSynthesisMs.takeIf { r.requestedDelegate != null })
    put("synthesisCostScope", if (r.requestedDelegate == null) "COMPLETED_CHUNK_WALL_TIME_INCLUDING_GRAPH_PREPARATION_SESSION_OPEN_AND_FAILED_ATTEMPTS_SEPARATE" else "RETAINED_PCM_PRODUCTION_INTERVALS_EXCLUDING_CALLBACK_BACKPRESSURE_AND_POST_FINAL_CALLBACK_TAIL")
    val observedNativeChunks = r.chunkRecords.takeIf { r.requestedDelegate == null && it.isNotEmpty() && it.all { chunk -> chunk.observation != null } }
    nullable("totalNativeRunMs", observedNativeChunks?.sumOf { checkNotNull(it.observation).synthesisMs })
    nullable("totalGraphPreparationMs", observedNativeChunks?.takeIf { it.all { chunk -> chunk.observation?.currentPrepareMs != null } }?.sumOf { checkNotNull(it.observation?.currentPrepareMs) })
    put("totalAudioMs", r.totalAudioMs)
    put("chunks", r.chunks)
    put("validatedChunks", r.validatedChunks)
    put("captionWordsExpected", r.captionWordsExpected)
    put("captionWordsObserved", r.captionWordsObserved)
    nullable("underruns", r.underruns)
    put("bufferRestarts", r.bufferRestarts)
    put("longestRefillMs", r.longestRefillMs)
    nullable("cancellationMs", r.cancellationMs)
    put("captionObservations", r.captionObservations)
    put("captionClockRegressions", r.captionClockRegressions)
    put("captionTimingViolations", r.captionTimingViolations)
    nullable("clockBasis", r.clockBasis?.takeIf { it in CLOCKS })
    nullable("route", r.route?.takeIf { it in ROUTES })
    put("effectiveProvider", provider(r.effectiveProvider))
    put("acceleratorExecutionObserved", r.acceleratorExecutionObserved)
    nullable("streamedAudioValidated", r.streamedAudioValidated)
    nullable("requestedDelegate", r.requestedDelegate?.takeIf { it in setOf("CPU", "GPU") })
    nullable("observedProviderStatus", r.observedProviderStatus?.takeIf { it == "NOT_EXPOSED_BY_SDK" })
    nullable("engineStreamCalls", r.engineStreamCalls)
    nullable("engineStreamWallMs", r.engineStreamWallMs)
    nullable("engineStreamWallScope", "completed_stream_calls_including_consumer_callback_backpressure".takeIf { r.engineStreamWallMs != null })
    put("omittedPcmRecords", r.omittedPcmRecords)
    nullable("thermalBefore", r.thermalBefore)
    nullable("thermalAfter", r.thermalAfter)
    nullable("failure", r.failure?.name)
    nullable("failureStage", r.failureStage?.name)
    put("protocolVersion", r.protocolVersion)
    put("scenario", r.scenario.name)
    put("repeat", r.repeat)
    put("warmup", r.warmup)
    put("expectedResident", r.expectedResident)
    nullable("engineReused", r.engineReused)
    nullable("fixtureHash", r.fixtureHash?.takeIf { it.matches(SHA256) })
    nullable("manifestWordsExpected", r.manifestWordsExpected)
    nullable("manifestWordsPrepared", r.manifestWordsPrepared)
    nullable("manifestWordsObserved", r.manifestWordsObserved)
    nullable("unexpectedCaptionWords", r.unexpectedCaptionWords)
    put("captionManifestValid", r.captionManifestValid)
    put("executionVerified", r.executionVerified)
    put("quality", r.quality.name)
    nullable("firstAudioTrackWriteMs", r.firstAudioTrackWriteMs)
    nullable("trackPlayRequestedMs", r.trackPlayRequestedMs)
    nullable("sessionPrepareMs", r.sessionPrepareMs)
    nullable("requestedThreads", r.requestedThreads)
    nullable("effectiveThreads", r.effectiveThreads)
    nullable("activePlaybackUnderruns", r.activePlaybackUnderruns)
    nullable("startupUnderruns", r.startupUnderruns)
    nullable("drainUnderruns", r.drainUnderruns)
    put("underrunsByPhase", buildJsonObject { r.underrunsByPhase.filterKeys { it in UNDERRUN_PHASES }.forEach { (key, count) -> put(key, count) } })
    put("preparationPhases", buildJsonObject { r.preparationPhases.filterKeys { it in PREPARATION_PHASES }.forEach { (key, elapsed) -> put(key, elapsed) } })
    nullable("pipelineFailureCode", r.pipelineFailureCode?.takeIf { it.matches(SAFE_CODE) })
    nullable("nativeCode", r.nativeCode)
    nullable("failedChunk", r.failedChunk)
    nullable("failedInputBytes", r.failedInputBytes)
    nullable("failedInputByteLimit", r.failedInputByteLimit)
    nullable("failedTokenCount", r.failedTokenCount)
    nullable("failedTokenLimit", r.failedTokenLimit)
    put("failedRequestedProvider", provider(r.failedRequestedProvider))
    put("failedEffectiveProvider", provider(r.failedEffectiveProvider))
    nullable("failedDiscoverySummary", discovery(r.failedDiscoverySummary))
    nullable("failedAttemptMs", r.failedAttemptMs)
    put("failureObservation", r.failureObservation?.let(::failureObservationJson) ?: JsonNull)
    nullable("cancellationTarget", r.cancellationTarget?.name)
    nullable("cancellationTargetReached", r.cancellationTargetReached)
    nullable("timeoutLimitMs", r.timeoutLimitMs)
    nullable("timeoutElapsedMs", r.timeoutElapsedMs)
    nullable("timeoutScope", r.timeoutScope?.takeIf { it.matches(SAFE_CODE) })
    nullable("sustainedTargetAudioMs", r.sustainedTargetAudioMs)
    nullable("sustainedObservedAudioMs", r.sustainedObservedAudioMs)
    nullable("sustainedWallElapsedMs", r.sustainedWallElapsedMs)
    nullable("sustainedWallDeadlineMs", r.sustainedWallDeadlineMs)
    nullable("sustainedCompletedRepeats", r.sustainedCompletedRepeats)
    nullable("sustainedQualification", safeSpeechSustainedQualification(r.sustainedQualification))
    put("cleanupState", r.cleanupState.takeIf { it in CLEANUP_STATES } ?: "NOT_OBSERVED")
    put("arrivalIntervalMs", r.arrivalIntervalMs)
    put("synthesisRealTimeFactor", number(r.synthesisRealTimeFactor))
    put(
        "chunkRecords",
        buildJsonArray {
            r.chunkRecords.forEach { chunk ->
                add(
                    buildJsonObject {
                        put("index", chunk.index)
                        put("completedAtMs", chunk.completedAtMs)
                        put("generationMs", chunk.generationMs)
                        put("audioMs", chunk.audioMs)
                        nullable("inputBytes", chunk.inputBytes)
                        nullable("inputSha256", chunk.inputSha256?.takeIf { it.matches(SHA256) })
                        put("effectiveProvider", provider(chunk.effectiveProvider))
                        put("verifiedAudio", chunk.verifiedAudio)
                        nullable("acceleratorKernels", chunk.acceleratorKernels)
                        nullable("cpuKernels", chunk.cpuKernels)
                        put("observation", chunk.observation?.let(::failureObservationJson) ?: JsonNull)
                        put("audioObservation", chunk.audioObservation?.let(::speechAudioObservationJson) ?: JsonNull)
                    }
                )
            }
        }
    )
    put(
        "reserveObservations",
        buildJsonArray {
            r.reserves.forEach { reserve ->
                add(
                    buildJsonObject {
                        nullable("phase", reserve.phase.takeIf { it.matches(SAFE_CODE) })
                        put("elapsedMs", reserve.elapsedMs)
                        nullable("startMs", reserve.startMs)
                        nullable("endMs", reserve.endMs)
                        put("targetMs", reserve.targetMs)
                        put("acceptedMs", reserve.acceptedMs)
                        nullable("exitReason", reserve.exitReason.takeIf { it.matches(SAFE_CODE) })
                    }
                )
            }
        }
    )
    put(
        "calibrations",
        buildJsonArray {
            r.calibrations.forEach { calibration ->
                add(
                    buildJsonObject {
                        nullable("route", calibration.route.takeIf { it in ROUTES })
                        put("offsetMs", calibration.offsetMs)
                        put("routeEpoch", calibration.routeEpoch)
                    }
                )
            }
        }
    )
    put(
        "nativeProgress",
        buildJsonArray {
            r.progress.forEach { progress ->
                add(
                    buildJsonObject {
                        put("stage", progress.stage.name)
                        put("attemptId", progress.attemptId)
                        put("stageSequence", progress.stageSequence)
                        put("stageElapsedMs", progress.stageElapsedMs)
                        put("attemptElapsedMs", progress.attemptElapsedMs)
                        put("cancellationRequested", progress.cancellationRequested)
                        put("active", progress.active)
                    }
                )
            }
        }
    )
}

/** Legacy runs remain readable, but carry their old protocol and cannot qualify current settings. */
internal fun speechBenchmarkReportFromJson(value: JsonObject): VoiceSpeechBenchmarkReport? = runCatching {
    val schema = value.getValue("schemaVersion").jsonPrimitive.int.also { require(it in 1..6) }
    val fixtureVersion = value.getValue("fixtureVersion").jsonPrimitive.int.also { require(it in 1..1000) }
    val c = value.getValue("configuration").jsonObject
    val configuration = VoiceSpeechBenchmarkConfiguration(
        mode = VoiceSpeechBenchmarkMode.valueOf(c.string("mode")),
        backends = c.getValue("backends").jsonArray.also { require(it.size in 1..3) }.map { VoiceSpeechBackend.valueOf(it.jsonPrimitive.content) },
        voice = KokoroVoice.valueOf(c.string("voice")),
        threads = c.getValue("threads").jsonPrimitive.int,
        speechRate = c.getValue("speechRate").jsonPrimitive.float,
        modelSha256 = c.string("modelSha256").also { require(it.matches(SHA256)) },
        runtimeId = c.string("runtimeId").also { require(it.matches(IDENTITY)) },
        appBuild = c.getValue("appBuild").jsonPrimitive.int.also { require(it > 0) },
        sdk = c.getValue("sdk").jsonPrimitive.int.also { require(it in 1..100) },
        deviceKey = c.string("localDeviceKey").also { require(it.matches(SHA256)) },
        workload = if (schema == 1) VoiceSpeechBenchmarkWorkload.NATIVE_PHRASE else VoiceSpeechBenchmarkWorkload.valueOf(c.string("workload")),
        protocolId = if (schema == 1) "legacy-fixtures-v1" else c.string("protocolId"),
        fixtureVersion = fixtureVersion,
        fixtureHash = if (schema == 1) null else c.optionalString("fixtureHash"),
        backendOrderSeed = c.optionalLong("backendOrderSeed") ?: 0,
        sourceCommit = c.optionalString("sourceCommit"),
        playback = c.optionalBoolean("playback") ?: false,
        playbackProtocolVersion = if (schema < 3) 0 else c.optionalBoundedLong("playbackProtocolVersion", VoiceSpeechPlaybackProtocol.VERSION.toLong())?.toInt() ?: 0,
        playbackScenarios = c["playbackScenarios"]?.jsonArray?.also { require(it.size in 1..6) }?.map { VoiceSpeechPlaybackScenario.valueOf(it.jsonPrimitive.content) } ?: listOf(VoiceSpeechPlaybackScenario.COLD_START),
        calibrationPolicy = c.optionalString("calibrationPolicy")?.let(VoiceSpeechCalibrationPolicy::valueOf) ?: VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS,
        captionOffsetMs = c.optionalSignedInt("captionOffsetMs")?.also { require(it in -500..500) } ?: 0,
        engine = c.optionalString("engine")?.let(VoiceSpeechBenchmarkEngine::valueOf) ?: VoiceSpeechBenchmarkEngine.NATIVE_KOKORO,
        engineVoiceId = c.optionalString("engineVoiceId")?.also { require(it.matches(IDENTITY)) },
        deepDiagnostics = c.optionalBoolean("deepDiagnostics") ?: false,
        kokoroBucket = c.optionalString("kokoroBucket")?.let(MediaPipeKokoroBucket::valueOf) ?: MediaPipeKokoroBucket.AUTO,
        incrementalInput = c.optionalBoolean("incrementalInput") ?: (c.optionalString("engine") in setOf(null, VoiceSpeechBenchmarkEngine.NATIVE_KOKORO.name)),
        htpPerformanceMode = c.optionalString("htpPerformanceMode") ?: "balanced",
        nativeModelVariant = c.optionalString("nativeModelVariant")?.let(KokoroModelVariant::valueOf) ?: KokoroModelVariant.INT8,
        sustainedTargetAudioMs = c.optionalBoundedLong("sustainedTargetAudioMs", 600_000) ?: 300_000L,
        deadlines = c["deadlines"]?.takeUnless { it == JsonNull }?.jsonObject?.let { limits ->
            VoiceSpeechBenchmarkDeadlines(limits.boundedLong("preparationMs", 300_000), limits.boundedLong("chunkMs", 300_000), limits.boundedLong("backendMs", 7_200_000))
        }
    )
    val nativeEngine = configuration.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO
    val results = value.getValue(if (schema >= 3 && configuration.playback) "backendStates" else "results").jsonArray.also { require(it.size == if (nativeEngine) configuration.backends.size else 0) }.map { raw ->
        val r = raw.jsonObject
        VoiceSpeechBenchmarkBackendResult(
            requestedBackend = VoiceSpeechBackend.valueOf(r.string("requestedBackend")),
            state = VoiceSpeechBenchmarkBackendState.valueOf(r.string("state")),
            sessionPrepareMs = r.optionalBoundedLong("sessionPrepareMs"),
            preparationThermalBefore = r.thermal("preparationThermalBefore"),
            samples = r["samples"]?.jsonArray.orEmpty().also { require(it.size <= VoiceSpeechBenchmarkFixture.entries.size * (configuration.mode.warmRepeats + 1)) }.map { sample ->
                val s = sample.jsonObject
                val chunks = s.readChunks("chunks")
                VoiceSpeechBenchmarkSample(
                    fixture = VoiceSpeechBenchmarkFixture.valueOf(s.string("fixture")),
                    repeat = s.boundedLong("repeat", configuration.mode.warmRepeats.toLong()).toInt(),
                    generationMs = s.boundedLong("generationMs"),
                    firstPcmMs = s.boundedLong("firstPcmMs"),
                    prepareToFirstPcmMs = s.optionalBoundedLong("prepareToFirstPcmMs"),
                    audioMs = s.positiveDouble("audioMs", 960_000.0),
                    effectiveProvider = s.string("effectiveProvider").also { require(it in PROVIDERS) },
                    acceleratorKernels = s.optionalBoundedLong("acceleratorKernels")?.toInt() ?: 0,
                    cpuKernels = s.optionalBoundedLong("cpuKernels")?.toInt() ?: 0,
                    acceleratorMicros = s.optionalBoundedLong("acceleratorMicros", 3_600_000_000) ?: 0,
                    cpuMicros = s.optionalBoundedLong("cpuMicros", 3_600_000_000) ?: 0,
                    graphPrepareMs = s.boundedLong("graphPrepareMs"),
                    tokenCount = s.boundedLong("tokenCount", 511L * VoiceSpeechWorkload.MAX_CHUNKS).toInt(),
                    validatedAudio = s.getValue("validatedAudio").jsonPrimitive.boolean,
                    thermalBefore = s.thermal("thermalBefore"),
                    thermalAfter = s.thermal("thermalAfter"),
                    hardwareType = s.optionalLong("hardwareType")?.also { require(it in -1..1000) }?.toInt(),
                    chunks = chunks
                ).also { row ->
                    require(row.firstPcmMs <= row.generationMs)
                    if (chunks.isNotEmpty()) {
                        require(row.generationMs == chunks.sumOf { it.generationMs })
                        require(kotlin.math.abs(row.audioMs - chunks.sumOf { it.audioMs }) < .0001)
                        require(row.firstPcmMs == chunks.first().generationMs)
                    }
                }
            },
            failure = r.optionalString("failure")?.let(VoiceSpeechFailureCode::valueOf),
            failureStage = r.optionalString("failureStage")?.let(VoiceSpeechStage::valueOf),
            nativeCode = r.optionalSignedInt("nativeCode"),
            failureEffectiveProvider = r.optionalProvider("failureEffectiveProvider"),
            failureAcceleratorKernels = r.optionalBoundedLong("failureAcceleratorKernels")?.toInt(),
            failureCpuKernels = r.optionalBoundedLong("failureCpuKernels")?.toInt(),
            failedFixture = r.optionalString("failedFixture")?.let(VoiceSpeechBenchmarkFixture::valueOf),
            failedRepeat = r.optionalBoundedLong("failedRepeat", configuration.mode.warmRepeats.toLong())?.toInt(),
            failedChunk = r.optionalBoundedLong("failedChunk", VoiceSpeechWorkload.MAX_CHUNKS - 1L)?.toInt(),
            failedInputBytes = r.optionalBoundedLong("failedInputBytes", 1_000_000)?.toInt(),
            failedInputByteLimit = r.optionalBoundedLong("failedInputByteLimit", 1_000_000)?.toInt(),
            failedTokenCount = r.optionalBoundedLong("failedTokenCount", 1_000_000)?.toInt(),
            failedTokenLimit = r.optionalBoundedLong("failedTokenLimit", 1_000_000)?.toInt(),
            failedRequestedProvider = r.optionalProvider("failedRequestedProvider"),
            failedDiscoverySummary = r.optionalDiscovery("failedDiscoverySummary"),
            failedAttemptMs = r.optionalBoundedLong("failedAttemptMs"),
            incompleteFixture = r.optionalString("incompleteFixture")?.let(VoiceSpeechBenchmarkFixture::valueOf),
            incompleteRepeat = r.optionalBoundedLong("incompleteRepeat", configuration.mode.warmRepeats.toLong())?.toInt(),
            incompleteChunks = r.readChunks("incompleteChunks"),
            failureObservation = r["failureObservation"]?.takeUnless { it == JsonNull }?.jsonObject?.let { readFailureObservation(it) },
            cancellationStage = r.optionalString("cancellationStage")?.let(VoiceSpeechStage::valueOf),
            cancellationNativeStage = r.optionalString("cancellationNativeStage")?.let(VoiceSpeechStage::valueOf),
            cancellationNativeRetirementMs = r.optionalBoundedLong("cancellationNativeRetirementMs", 86_400_000),
            stopReason = r.optionalString("stopReason")?.let(VoiceSpeechBenchmarkStopReason::valueOf),
            cleanupState = r.optionalString("cleanupState")?.let(VoiceSpeechBenchmarkCleanupState::valueOf),
            deadlineNativeRetirementMs = r.optionalBoundedLong("deadlineNativeRetirementMs", 86_400_000),
            deadline = r["deadline"]?.takeUnless { it == JsonNull }?.jsonObject?.let { timeout ->
                VoiceSpeechBenchmarkTimeout(
                    VoiceSpeechBenchmarkDeadlineScope.valueOf(timeout.string("scope")),
                    timeout.boundedLong("limitMs", 7_200_000),
                    timeout.boundedLong("elapsedMs", 86_400_000),
                    timeout.optionalString("stage")?.let(VoiceSpeechStage::valueOf)
                )
            }
        ).also { row ->
            require(row.incompleteChunks.isEmpty() || (row.incompleteFixture != null && row.incompleteRepeat != null))
            require(row.state != VoiceSpeechBenchmarkBackendState.COMPLETE || (row.failure == null && row.incompleteChunks.isEmpty()))
        }
    }
    require(!nativeEngine || results.map { it.requestedBackend } == configuration.backends)
    require(results.all { row -> row.samples.map { it.fixture to it.repeat }.distinct().size == row.samples.size })
    val playback = value["playbackResults"]?.jsonArray.orEmpty().also { require(it.size <= 3 * VoiceSpeechPlaybackProtocol.MAX_SAMPLES_PER_BACKEND) }.map { readPlayback(it.jsonObject) }
    require(playback.map { listOf(it.requestedBackend.name, it.scenario.name, it.repeat.toString(), it.cancellationTarget?.name) }.distinct().size == playback.size && playback.all { it.requestedBackend in configuration.backends })
    require(configuration.playback || playback.isEmpty())
    val engines = value["engineResults"]?.jsonArray.orEmpty().also { require(it.size == if (nativeEngine) 0 else configuration.backends.size) }
        .map { readSpeechEngineBenchmark(it.jsonObject, configuration) }
    require(nativeEngine || engines.map { it.requestedBackend } == configuration.backends)
    val energy = value["batteryCounterObservations"]?.jsonArray.orEmpty().also { require(it.size <= configuration.backends.size) }.map { readSpeechBenchmarkEnergy(it.jsonObject) }
    require(energy.map { it.requestedBackend }.distinct().size == energy.size && energy.all { it.requestedBackend in configuration.backends })
    VoiceSpeechBenchmarkReport(
        startedAtMillis = value.getValue("startedAtMillis").jsonPrimitive.long.also { require(it >= 0) },
        configuration = configuration,
        state = VoiceSpeechBenchmarkRunState.valueOf(value.string("state")),
        results = results,
        finishedAtMillis = value.optionalLong("finishedAtMillis")?.also { require(it >= 0) },
        issue = value.optionalString("issue")?.let(VoiceSpeechBenchmarkIssue::valueOf),
        playbackResults = playback,
        engineResults = engines,
        energyObservations = energy
    )
}.getOrNull()

private fun JsonObject.readChunks(key: String): List<VoiceSpeechBenchmarkChunk> = get(key)?.jsonArray.orEmpty().also { require(it.size <= VoiceSpeechWorkload.MAX_CHUNKS) }.mapIndexed { index, raw ->
    val c = raw.jsonObject
    VoiceSpeechBenchmarkChunk(
        index = c.boundedLong("index", VoiceSpeechWorkload.MAX_CHUNKS - 1L).toInt().also { require(it == index) },
        inputBytes = c.boundedLong("inputBytes", VoiceSpeechWorkload.MAX_PHRASE_BYTES.toLong()).toInt().also { require(it > 0) },
        inputSha256 = c.string("inputSha256").also { require(it.matches(SHA256)) },
        generationMs = c.boundedLong("generationMs"),
        audioMs = c.positiveDouble("audioMs", 30_000.0),
        effectiveProvider = c.string("effectiveProvider").also { require(it in PROVIDERS) },
        acceleratorKernels = c.optionalBoundedLong("acceleratorKernels")?.toInt() ?: 0,
        cpuKernels = c.optionalBoundedLong("cpuKernels")?.toInt() ?: 0,
        acceleratorMicros = c.optionalBoundedLong("acceleratorMicros", 3_600_000_000) ?: 0,
        cpuMicros = c.optionalBoundedLong("cpuMicros", 3_600_000_000) ?: 0,
        graphPrepareMs = c.boundedLong("graphPrepareMs"),
        tokenCount = c.boundedLong("tokenCount", 511).toInt(),
        validatedAudio = c.getValue("validatedAudio").jsonPrimitive.boolean,
        thermalBefore = c.thermal("thermalBefore"), thermalAfter = c.thermal("thermalAfter"),
        hardwareType = c.optionalLong("hardwareType")?.also { require(it in -1..1000) }?.toInt(),
        profileAvailable = c.optionalBoolean("profileAvailable"), profileId = c.optionalBoundedLong("profileId", Long.MAX_VALUE),
        profileReused = c.optionalBoolean("profileReused"), cacheHit = c.optionalBoolean("cacheHit"),
        currentPrepareMs = c.optionalBoundedLong("currentPrepareMs"), graphPreparedThisCall = c.optionalBoolean("graphPreparedThisCall"),
        cachedShapeCount = c.optionalBoundedLong("cachedShapeCount", 32)?.toInt(), cacheLimit = c.optionalBoundedLong("cacheLimit", 32)?.toInt(),
        cacheEstimatedBytes = c.optionalBoundedLong("cacheEstimatedBytes", 16L * 1024 * 1024 * 1024),
        audioRms = c.optionalDouble("audioRms", 1_000_000.0), audioPeak = c.optionalDouble("audioPeak", 1_000_000.0),
        clippedSamples = c.optionalBoundedLong("clippedSamples", 720_000), nonSilentSamples = c.optionalBoundedLong("nonSilentSamples", 720_000),
        discoverySummary = c.optionalDiscovery("discoverySummary"),
        frontendMs = c.optionalBoundedLong("frontendMs"), validationMs = c.optionalBoundedLong("validationMs"),
        cacheEvictions = c.optionalBoundedLong("cacheEvictions", 1000)?.toInt(), profileFinalizeMs = c.optionalBoundedLong("profileFinalizeMs"),
        nativeSynthesisMs = c.optionalBoundedLong("nativeSynthesisMs"),
        audioObservation = c["audioObservation"]?.takeUnless { it == JsonNull }?.jsonObject?.let(::readSpeechAudioObservation),
        cacheBudgetBytes = c.optionalBoundedLong("cacheBudgetBytes", 16L * 1024 * 1024 * 1024),
        activeShapeEstimatedBytes = c.optionalBoundedLong("activeShapeEstimatedBytes", 16L * 1024 * 1024 * 1024),
        availableMemoryBytes = c.optionalBoundedLong("availableMemoryBytes", Long.MAX_VALUE),
        cacheAdmission = c.optionalCacheAdmission(),
        graphPreparationSource = c.optionalString("graphPreparationSource")?.also { require(safeSpeechGraphPreparation(it) != null) },
        contextCacheStatus = c.optionalString("contextCacheStatus")?.also { require(safeSpeechContextCacheStatus(it) != null) },
        contextLoadMs = c.optionalBoundedLong("contextLoadMs"),
        contextStoreMs = c.optionalBoundedLong("contextStoreMs"),
        contextBytes = c.optionalBoundedLong("contextBytes", 256L * 1024 * 1024),
        contextCacheEvictions = c.optionalBoundedLong("contextCacheEvictions", Long.MAX_VALUE),
        contextFallback = c.optionalBoolean("contextFallback"),
        htpPerformanceMode = c.optionalString("htpPerformanceMode")?.also { require(safeSpeechHtpPerformanceMode(it) != null) }
    ).also { row ->
        require(row.profileAvailable != false || (row.acceleratorKernels == 0 && row.cpuKernels == 0 && row.acceleratorMicros == 0L && row.cpuMicros == 0L))
        require(row.cacheHit != true || row.currentPrepareMs == 0L)
        row.audioObservation?.let { require(it.inputSha256 == row.inputSha256 && it.inputBytes == row.inputBytes && it.tokenDurations.size == row.tokenCount && it.audioMs == row.audioMs && it.rms == row.audioRms && it.peak == row.audioPeak && it.clippedSamples == row.clippedSamples && it.nonSilentSamples == row.nonSilentSamples) }
    }
}

private fun readPlayback(r: JsonObject) = VoiceSpeechPlaybackResult(
    requestedBackend = VoiceSpeechBackend.valueOf(r.string("requestedBackend")), state = VoiceSpeechPlaybackState.valueOf(r.string("state")),
    firstPcmMs = r.optionalBoundedLong("firstPcmMs"), firstQueuedPcmMs = r.optionalBoundedLong("firstQueuedPcmMs"), firstPresentedAudioMs = r.optionalBoundedLong("firstPresentedAudioMs"),
    elapsedMs = r.boundedLong("elapsedMs"), totalSynthesisMs = r.boundedLong("totalSynthesisMs"), totalAudioMs = r.boundedLong("totalAudioMs"),
    chunks = r.boundedLong("chunks", if (r.optionalString("requestedDelegate") == null) 1000 else 16_384).toInt(), underruns = r.optionalBoundedLong("underruns")?.toInt(),
    bufferRestarts = r.boundedLong("bufferRestarts").toInt(), longestRefillMs = r.boundedLong("longestRefillMs"), cancellationMs = r.optionalBoundedLong("cancellationMs"),
    captionObservations = r.boundedLong("captionObservations").toInt(), captionClockRegressions = r.boundedLong("captionClockRegressions").toInt(), captionTimingViolations = r.boundedLong("captionTimingViolations").toInt(),
    clockBasis = r.optionalString("clockBasis")?.also { require(it in CLOCKS) }, route = r.optionalString("route")?.also { require(it in ROUTES) },
    effectiveProvider = r.optionalProvider("effectiveProvider"), acceleratorExecutionObserved = r.getValue("acceleratorExecutionObserved").jsonPrimitive.boolean,
    thermalBefore = r.thermal("thermalBefore"), thermalAfter = r.thermal("thermalAfter"),
    streamedAudioValidated = r.optionalBoolean("streamedAudioValidated"),
    requestedDelegate = r.optionalString("requestedDelegate")?.also { require(it in setOf("CPU", "GPU")) },
    observedProviderStatus = r.optionalString("observedProviderStatus")?.also { require(it == "NOT_EXPOSED_BY_SDK") },
    engineStreamCalls = r.optionalBoundedLong("engineStreamCalls", 1000)?.toInt(),
    engineStreamWallMs = r.optionalBoundedLong("engineStreamWallMs", 86_400_000),
    omittedPcmRecords = r.optionalBoundedLong("omittedPcmRecords", 16_384)?.toInt() ?: 0,
    failure = r.optionalString("failure")?.let(VoiceSpeechFailureCode::valueOf), failureStage = r.optionalString("failureStage")?.let(VoiceSpeechStage::valueOf),
    validatedChunks = r.optionalBoundedLong("validatedChunks", 1000)?.toInt() ?: 0,
    captionWordsExpected = r.optionalBoundedLong("captionWordsExpected", VoiceSpeechCaptionManifest.MAX_WORDS.toLong())?.toInt() ?: 0,
    captionWordsObserved = r.optionalBoundedLong("captionWordsObserved", VoiceSpeechCaptionManifest.MAX_WORDS.toLong())?.toInt() ?: 0,
    protocolVersion = r.optionalBoundedLong("protocolVersion", VoiceSpeechPlaybackProtocol.VERSION.toLong())?.toInt() ?: 0,
    scenario = r.optionalString("scenario")?.let(VoiceSpeechPlaybackScenario::valueOf) ?: VoiceSpeechPlaybackScenario.COLD_START,
    repeat = r.optionalBoundedLong("repeat", 32)?.toInt() ?: 0, warmup = r.optionalBoolean("warmup") ?: false, expectedResident = r.optionalBoolean("expectedResident") ?: false,
    engineReused = r.optionalBoolean("engineReused"), fixtureHash = r.optionalString("fixtureHash")?.also { require(it.matches(SHA256)) },
    manifestWordsExpected = r.optionalBoundedLong("manifestWordsExpected", 1024)?.toInt(), manifestWordsPrepared = r.optionalBoundedLong("manifestWordsPrepared", 1024)?.toInt(),
    manifestWordsObserved = r.optionalBoundedLong("manifestWordsObserved", 1024)?.toInt(), unexpectedCaptionWords = r.optionalBoundedLong("unexpectedCaptionWords", 2048)?.toInt(),
    firstAudioTrackWriteMs = r.optionalBoundedLong("firstAudioTrackWriteMs"), trackPlayRequestedMs = r.optionalBoundedLong("trackPlayRequestedMs"), sessionPrepareMs = r.optionalBoundedLong("sessionPrepareMs"),
    requestedThreads = r.optionalBoundedLong("requestedThreads", 4)?.toInt()?.also { require(it > 0) }, effectiveThreads = r.optionalBoundedLong("effectiveThreads", 4)?.toInt()?.also { require(it > 0) },
    activePlaybackUnderruns = r.optionalBoundedLong("activePlaybackUnderruns")?.toInt(), startupUnderruns = r.optionalBoundedLong("startupUnderruns")?.toInt(), drainUnderruns = r.optionalBoundedLong("drainUnderruns")?.toInt(),
    pipelineFailureCode = r.optionalString("pipelineFailureCode")?.also { require(it.matches(SAFE_CODE)) }, nativeCode = r.optionalSignedInt("nativeCode"),
    failedChunk = r.optionalBoundedLong("failedChunk", if (r.optionalString("requestedDelegate") == null) 128 else 16_384)?.toInt(), failedInputBytes = r.optionalBoundedLong("failedInputBytes", 1_000_000)?.toInt(), failedInputByteLimit = r.optionalBoundedLong("failedInputByteLimit", 1_000_000)?.toInt(),
    failedTokenCount = r.optionalBoundedLong("failedTokenCount", 1_000_000)?.toInt(), failedTokenLimit = r.optionalBoundedLong("failedTokenLimit", 1_000_000)?.toInt(),
    failedRequestedProvider = r.optionalProvider("failedRequestedProvider"), failedEffectiveProvider = r.optionalProvider("failedEffectiveProvider"), failedDiscoverySummary = r.optionalDiscovery("failedDiscoverySummary"),
    failedAttemptMs = r.optionalBoundedLong("failedAttemptMs"), failureObservation = r["failureObservation"]?.takeUnless { it == JsonNull }?.jsonObject?.let { readFailureObservation(it) },
    cancellationTarget = r.optionalString("cancellationTarget")?.let(VoiceSpeechCancellationTarget::valueOf), cancellationTargetReached = r.optionalBoolean("cancellationTargetReached"),
    cleanupState = r.optionalString("cleanupState")?.also { require(it in CLEANUP_STATES) } ?: "NOT_OBSERVED", arrivalIntervalMs = r.optionalBoundedLong("arrivalIntervalMs", 10_000) ?: 0,
    timeoutLimitMs = r.optionalBoundedLong("timeoutLimitMs"), timeoutElapsedMs = r.optionalBoundedLong("timeoutElapsedMs"), timeoutScope = r.optionalString("timeoutScope")?.also { require(it.matches(SAFE_CODE)) },
    sustainedTargetAudioMs = r.optionalBoundedLong("sustainedTargetAudioMs", 600_000)?.also { require(it in VoiceSpeechSustainedSeries.TARGETS) },
    sustainedObservedAudioMs = r.optionalBoundedLong("sustainedObservedAudioMs", VoiceSpeechSustainedSeries.MAX_REPLIES * VoiceSpeechSustainedSeries.MAX_REPLY_WALL_MS),
    sustainedWallElapsedMs = r.optionalBoundedLong("sustainedWallElapsedMs"),
    sustainedWallDeadlineMs = r.optionalBoundedLong("sustainedWallDeadlineMs", VoiceSpeechSustainedSeries.deadlineMs(600_000)),
    sustainedCompletedRepeats = r.optionalBoundedLong("sustainedCompletedRepeats", VoiceSpeechSustainedSeries.MAX_REPLIES.toLong())?.toInt(),
    sustainedQualification = r.optionalString("sustainedQualification")?.also { require(safeSpeechSustainedQualification(it) != null) },
    underrunsByPhase = r["underrunsByPhase"]?.jsonObject.orEmpty().also { require(it.keys.all { key -> key in UNDERRUN_PHASES }) }.mapValues { it.value.jsonPrimitive.int.also { count -> require(count in 0..1_000_000) } },
    preparationPhases = r["preparationPhases"]?.jsonObject.orEmpty().also { require(it.keys.all { key -> key in PREPARATION_PHASES }) }.mapValues { it.value.jsonPrimitive.long.also { time -> require(time in 0..3_600_000) } },
    chunkRecords = r["chunkRecords"]?.jsonArray.orEmpty().also { require(it.size <= VoiceSpeechPlaybackProtocol.MAX_CHUNKS_PER_SAMPLE) }.mapIndexed { index, raw ->
        val chunk = raw.jsonObject
        VoiceSpeechPlaybackChunk(
            chunk.boundedLong("index", 127).toInt().also { require(it == index) }, chunk.boundedLong("completedAtMs"), chunk.boundedLong("generationMs"), chunk.boundedLong("audioMs", 30_000),
            chunk.optionalBoundedLong("inputBytes", 1_000_000)?.toInt(), chunk.optionalString("inputSha256")?.also { require(it.matches(SHA256)) }, chunk.optionalProvider("effectiveProvider"), chunk.getValue("verifiedAudio").jsonPrimitive.boolean,
            chunk.optionalBoundedLong("acceleratorKernels")?.toInt(), chunk.optionalBoundedLong("cpuKernels")?.toInt(), chunk["observation"]?.takeUnless { it == JsonNull }?.jsonObject?.let { readFailureObservation(it, chunk.getValue("verifiedAudio").jsonPrimitive.boolean) },
            chunk["audioObservation"]?.takeUnless { it == JsonNull }?.jsonObject?.let(::readSpeechAudioObservation)
        )
    },
    reserves = r["reserveObservations"]?.jsonArray.orEmpty().also { require(it.size <= 128) }.map { raw ->
        val v = raw.jsonObject
        VoiceSpeechReserveObservation(v.string("phase").also { require(it.matches(SAFE_CODE)) }, v.boundedLong("elapsedMs"), v.boundedLong("targetMs"), v.boundedLong("acceptedMs"), v.string("exitReason").also { require(it.matches(SAFE_CODE)) }, v.optionalBoundedLong("startMs"), v.optionalBoundedLong("endMs")).also { observation ->
            if (observation.startMs != null && observation.endMs != null) require(observation.endMs >= observation.startMs)
        }
    },
    calibrations = r["calibrations"]?.jsonArray.orEmpty().also { require(it.size <= 32) }.map { raw ->
        val v = raw.jsonObject
        VoiceSpeechCalibrationObservation(v.string("route").also { require(it in ROUTES) }, v.getValue("offsetMs").jsonPrimitive.int.also { require(it in -2000..2000) }, v.boundedLong("routeEpoch", Long.MAX_VALUE))
    },
    progress = r["nativeProgress"]?.jsonArray.orEmpty().also { require(it.size <= 128) }.map { raw ->
        val v = raw.jsonObject
        VoiceSpeechProgressObservation(VoiceSpeechStage.valueOf(v.string("stage")), v.boundedLong("attemptId", Long.MAX_VALUE), v.boundedLong("stageSequence", Long.MAX_VALUE), v.boundedLong("stageElapsedMs"), v.boundedLong("attemptElapsedMs"), v.getValue("cancellationRequested").jsonPrimitive.boolean, v.optionalBoolean("active") ?: true)
    }
).also { result ->
    val sdkDelegate = requestedSpeechDelegate(result.requestedBackend)
    val streaming = sdkDelegate != "UNSUPPORTED"
    if (streaming) {
        require(result.requestedDelegate == sdkDelegate && result.observedProviderStatus == "NOT_EXPOSED_BY_SDK")
        require(result.effectiveProvider == null && result.failedEffectiveProvider == null && !result.acceleratorExecutionObserved)
        require(result.requestedThreads == null && result.effectiveThreads == null && result.validatedChunks == 0)
        require(result.failureObservation == null)
        require(result.chunkRecords.all { it.effectiveProvider == null && !it.verifiedAudio && it.acceleratorKernels == null && it.cpuKernels == null && it.observation == null && it.audioObservation == null })
    } else {
        require(result.requestedDelegate == null && result.observedProviderStatus == null && result.streamedAudioValidated == null && result.engineStreamCalls == null && result.engineStreamWallMs == null && result.omittedPcmRecords == 0)
    }
    if (result.protocolVersion == VoiceSpeechPlaybackProtocol.VERSION) {
        require(result.chunkRecords.size + result.omittedPcmRecords == result.chunks)
        if (streaming && result.omittedPcmRecords > 0) {
            require(result.chunkRecords.sumOf { it.generationMs } <= result.totalSynthesisMs)
            require(result.chunkRecords.sumOf { it.audioMs } <= result.totalAudioMs)
        } else {
            require(result.chunkRecords.sumOf { it.generationMs } == result.totalSynthesisMs)
            require(result.chunkRecords.sumOf { it.audioMs } == result.totalAudioMs)
        }
        require(result.validatedChunks == result.chunkRecords.count { it.verifiedAudio })
        require(result.manifestWordsObserved == null || result.manifestWordsExpected?.let { result.manifestWordsObserved <= it } == true)
    }
}

private val SAFE_CODE = Regex("[A-Za-z0-9_]{1,80}")
private val UNDERRUN_PHASES = setOf("startup", "active", "refill", "draining", "paused", "cancelled")
private val PREPARATION_PHASES = setOf("GATE_WAIT", "MODEL_LOAD")
private val CLEANUP_STATES = setOf("NOT_OBSERVED", "PENDING", "RETIRED", "FAILED")
private val SHA256 = Regex("[0-9a-f]{64}")
private val IDENTITY = Regex("[A-Za-z0-9._-]{1,120}")
private val DISCOVERY = Regex("[A-Za-z0-9_.=,:;\\-]{1,512}")
private val PROVIDERS = setOf("cpu", "qnn-gpu", "qnn-htp")
private val CLOCKS = setOf("audio_timestamp", "playback_head")
private val ROUTES = setOf("speaker", "earpiece", "bluetooth", "usb", "wired", "other")
private fun provider(value: String?): JsonElement = value?.takeIf { it in PROVIDERS }?.let(::JsonPrimitive) ?: JsonNull
private fun discovery(value: String?): String? = value?.takeIf { it.matches(DISCOVERY) }
private fun number(value: Double?): JsonElement = value?.takeIf { it.isFinite() && it >= 0 }?.let(::JsonPrimitive) ?: JsonNull
private fun JsonObjectBuilder.nullable(key: String, value: String?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)
private fun JsonObjectBuilder.nullable(key: String, value: Long?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)
private fun JsonObjectBuilder.nullable(key: String, value: Int?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)
private fun JsonObjectBuilder.nullable(key: String, value: Boolean?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)
private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.optionalString(key: String) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
private fun JsonObject.optionalLong(key: String) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.long
private fun JsonObject.optionalBoolean(key: String) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.boolean
private fun JsonObject.boundedLong(key: String, maximum: Long = 3_600_000) = getValue(key).jsonPrimitive.long.also { require(it in 0..maximum) }
private fun JsonObject.optionalBoundedLong(key: String, maximum: Long = 3_600_000) = optionalLong(key)?.also { require(it in 0..maximum) }
private fun JsonObject.optionalSignedInt(key: String) = optionalLong(key)?.also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }?.toInt()
private fun JsonObject.thermal(key: String) = optionalBoundedLong(key, 6)?.toInt()
private fun JsonObject.optionalProvider(key: String) = optionalString(key)?.also { require(it in PROVIDERS) }
private fun JsonObject.optionalDiscovery(key: String) = optionalString(key)?.also { require(discovery(it) != null) }
private fun JsonObject.optionalCacheAdmission() = optionalString("cacheAdmission")?.also { require(safeSpeechCacheAdmission(it) != null) }
private fun JsonObject.positiveDouble(key: String, maximum: Double) = getValue(key).jsonPrimitive.double.also { require(it.isFinite() && it > 0 && it <= maximum) }
private fun JsonObject.optionalDouble(key: String, maximum: Double) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.double?.also { require(it.isFinite() && it in 0.0..maximum) }
