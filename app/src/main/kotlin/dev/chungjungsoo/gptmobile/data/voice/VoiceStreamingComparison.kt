package dev.chungjungsoo.gptmobile.data.voice

/** Optional independent observations. No waveform or conversational text is retained. */
internal data class VoiceIndependentSpeechQuality(
    val backend: VoiceSpeechBackend,
    val modelSha256: String,
    val runtimeId: String,
    val voiceId: String,
    val speechRate: Float,
    val route: String,
    val fixtureHashes: Set<String>,
    val pitchDeviationPercent: Double? = null,
    val pitchPairs: Int = 0,
    val wordTimingP95Ms: Double? = null,
    val alignedWords: Int = 0,
    val pronunciationPassRate: Double? = null,
    val ratedWords: Int = 0,
    val pcmProcessingId: String = VoiceTempoProcessor.IMPLEMENTATION_ID,
    val outputRouteIdentity: String? = null
) {
    init {
        require(backend.isStreamingLocal && modelSha256.matches(Regex("[a-f0-9]{64}")))
        require(runtimeId.matches(Regex("[A-Za-z0-9_.:+-]{1,160}")) && voiceId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(speechRate.isFinite() && speechRate in .5f..2f && route.matches(Regex("[a-z0-9_-]{1,64}")))
        require(pcmProcessingId.matches(Regex("[A-Za-z0-9._-]{1,160}")))
        require(outputRouteIdentity == null || isVoiceOutputRouteIdentity(outputRouteIdentity) && outputRouteIdentity.substringBefore(':') == route)
        require(fixtureHashes.isNotEmpty() && fixtureHashes.size <= 32 && fixtureHashes.all { it.matches(Regex("[a-f0-9]{64}")) })
        require(pitchPairs in 0..100_000 && alignedWords in 0..1_000_000 && ratedWords in 0..100_000)
        require(pitchDeviationPercent == null || pitchDeviationPercent.isFinite() && pitchDeviationPercent in 0.0..1000.0)
        require(wordTimingP95Ms == null || wordTimingP95Ms.isFinite() && wordTimingP95Ms in 0.0..60_000.0)
        require(pronunciationPassRate == null || pronunciationPassRate.isFinite() && pronunciationPassRate in 0.0..1.0)
        require((pitchDeviationPercent == null) == (pitchPairs == 0))
        require((wordTimingP95Ms == null) == (alignedWords == 0))
        require((pronunciationPassRate == null) == (ratedWords == 0))
    }

    val status: String get() = when {
        pitchPairs < 20 || alignedWords < 100 || ratedWords < 20 -> "INSUFFICIENT_INDEPENDENT_OBSERVATIONS"
        pitchDeviationPercent!! > 3.0 || wordTimingP95Ms!! > 120.0 || pronunciationPassRate!! < .98 -> "QUALITY_TARGET_NOT_MET"
        else -> "QUALITY_TARGETS_MET"
    }
}

internal data class VoiceStreamingComparison(
    val backend: VoiceSpeechBackend,
    val route: String?,
    val samples: Int,
    val medianFirstPresentedMs: Double?,
    val p95FirstPresentedMs: Long?,
    val activeUnderruns: Int?,
    val maximumCancellationMs: Long?,
    val captionCoverage: Double?,
    val nativeTimingWords: Int?,
    val estimatedTimingWords: Int?,
    val timingRevisions: Int?,
    val eligibleForLatencyComparison: Boolean,
    val exclusions: List<String>,
    val quality: VoiceIndependentSpeechQuality?,
    val outputRouteIdentity: String?,
    val asrAlignmentTimingWords: Int? = null,
    val captionAlignmentStatuses: Set<String> = emptySet()
) {
    val cueProvenance: String get() = when {
        nativeTimingWords == null || estimatedTimingWords == null -> "NOT_RECORDED_IN_BENCHMARK"
        (asrAlignmentTimingWords ?: 0) > 0 && nativeTimingWords > 0 && estimatedTimingWords > 0 -> "MIXED_NATIVE_DURATION_ESTIMATED_AND_ASR_ALIGNMENT"
        (asrAlignmentTimingWords ?: 0) > 0 && nativeTimingWords > 0 -> "MIXED_NATIVE_DURATION_AND_ASR_ALIGNMENT"
        (asrAlignmentTimingWords ?: 0) > 0 && estimatedTimingWords > 0 -> "MIXED_ESTIMATED_AND_ASR_ALIGNMENT"
        (asrAlignmentTimingWords ?: 0) > 0 -> "ASR_ALIGNMENT"
        nativeTimingWords > 0 && estimatedTimingWords > 0 -> "MIXED_NATIVE_DURATION_AND_ESTIMATED"
        estimatedTimingWords > 0 -> "ESTIMATED"
        nativeTimingWords > 0 -> "NATIVE_DURATION"
        else -> "NO_CUES_OBSERVED"
    }
    val independentQualityStatus: String get() = quality?.status ?: "NOT_MEASURED"
    val qualifiedForAutomaticSelection: Boolean get() = eligibleForLatencyComparison && independentQualityStatus == "QUALITY_TARGETS_MET"
}

/**
 * Compares only processor requests for the exact same model, voice, rate, runtime, fixtures
 * and observed output device / PCM format identity. Estimated cue coverage is not acoustic word accuracy.
 * Kernel placement and physical output latency remain outside the SDK's observations.
 */
internal fun VoiceSpeechBenchmarkReport.streamingComparisons(): List<VoiceStreamingComparison> {
    if (!configuration.playback || configuration.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO) return emptyList()
    val requiredScenarios = setOf(VoiceSpeechPlaybackScenario.COLD_START, VoiceSpeechPlaybackScenario.RESIDENT_WARM, VoiceSpeechPlaybackScenario.STREAMED_SEGMENTS, VoiceSpeechPlaybackScenario.CANCELLATION)
    val comparisonRoutes = playbackResults.filter { it.cancellationTarget == null }.mapNotNull { it.route }.toSet()
    val comparisonRouteIdentities = playbackResults.filter { it.cancellationTarget == null }.mapNotNull { it.outputRouteIdentity }.toSet()
    return configuration.backends.map { backend ->
        val rows = playbackResults.filter { it.requestedBackend == backend }
        val speech = rows.filter { it.cancellationTarget == null }
        val measured = speech.filter { !it.warmup && it.state == VoiceSpeechPlaybackState.COMPLETE }
        val warm = measured.filter { it.scenario == VoiceSpeechPlaybackScenario.RESIDENT_WARM && it.prewarmMode == null }
        val times = warm.mapNotNull { it.firstPresentedAudioMs }
        val cancel = rows.filter { it.cancellationTarget != null }
        val route = speech.mapNotNull { it.route }.distinct().singleOrNull()
        val outputRouteIdentity = speech.mapNotNull { it.outputRouteIdentity }.distinct().singleOrNull()
        val expected = measured.sumOf { it.manifestWordsExpected ?: 0 }
        val observed = measured.sumOf { it.manifestWordsObserved ?: 0 }
        val coverage = (observed.toDouble() / expected).takeIf { expected > 0 && measured.all { it.manifestWordsExpected != null && it.manifestWordsObserved != null } }
        val underruns = measured.sumOf { it.activePlaybackUnderruns ?: 0 }.takeIf { measured.isNotEmpty() && measured.all { it.activePlaybackUnderruns != null } }
        val hasTimingCounts = measured.isNotEmpty() &&
            measured.all {
                it.captionNativeTimingWords != null &&
                    it.captionEstimatedTimingWords != null &&
                    it.captionTimingRevisions != null &&
                    it.captionNativeTimingWords + it.captionEstimatedTimingWords + (it.captionAsrAlignmentTimingWords ?: 0) > 0
            }
        val quality = independentSpeechQuality.singleOrNull {
            it.backend == backend &&
                it.modelSha256 == configuration.modelSha256 &&
                it.runtimeId == configuration.runtimeId &&
                it.voiceId == configuration.engineVoiceId &&
                it.pcmProcessingId == configuration.pcmProcessingId &&
                it.speechRate.toBits() == configuration.speechRate.toBits() &&
                it.route == route &&
                outputRouteIdentity != null &&
                it.outputRouteIdentity == outputRouteIdentity &&
                it.fixtureHashes == measured.mapNotNull { row -> row.fixtureHash }.toSet()
        }
        val exclusions = buildList {
            if (state != VoiceSpeechBenchmarkRunState.COMPLETE || issue != null) add("RUN_NOT_COMPLETE")
            if (!configuration.currentProtocol || configuration.mode == VoiceSpeechBenchmarkMode.QUICK) add("CONTROLLED_PROTOCOL_REQUIRED")
            if (configuration.calibrationPolicy != VoiceSpeechCalibrationPolicy.CONTROLLED_ZERO) add("CONTROLLED_CAPTION_OFFSET_REQUIRED")
            if (!configuration.playbackScenarios.containsAll(requiredScenarios)) add("MISSING_PLAYBACK_SCENARIOS")
            if (engineResults.singleOrNull { it.requestedBackend == backend }?.state != VoiceSpeechBenchmarkBackendState.COMPLETE) add("BACKEND_NOT_COMPLETE")
            val plans = runCatching {
                VoiceSpeechPlaybackProtocol.expandedPlans(configuration, rows.count { it.scenario == VoiceSpeechPlaybackScenario.SUSTAINED })
            }.getOrNull()
            if (plans == null ||
                rows.size != plans.size ||
                rows.zip(plans).any { (row, plan) ->
                    row.scenario != plan.scenario ||
                        row.repeat != plan.repeat ||
                        row.cancellationTarget != plan.cancellationTarget ||
                        row.warmup != plan.warmup ||
                        row.fixtureHash != plan.manifest.hash ||
                        row.expectedResident != plan.expectedResident ||
                        row.prewarmMode != plan.prewarmMode ||
                        row.recoveryAfter != plan.recoveryAfter ||
                        row.arrivalIntervalMs != plan.arrivalIntervalMs
                }
            ) {
                add("INCOMPLETE_OR_MISMATCHED_FIXTURES")
            }
            if (speech.isEmpty() || speech.any { it.quality != VoiceSpeechPlaybackQuality.STREAMING_PLAYBACK_OBSERVED }) add("PLAYBACK_CHECKS_NOT_MET")
            if (speech.any { it.calibrations.isEmpty() || it.calibrations.any { calibration -> calibration.offsetMs != 0 } }) add("CAPTION_CALIBRATION_NOT_CONTROLLED")
            if (times.size < 5 || times.size != warm.size) add("INSUFFICIENT_WARM_PRESENTATION_SAMPLES")
            if (route == null || comparisonRoutes.size != 1 || speech.any { it.route != route }) add("OUTPUT_ROUTE_NOT_MATCHED")
            if (outputRouteIdentity == null ||
                !isVoiceOutputRouteIdentity(outputRouteIdentity) ||
                outputRouteIdentity.substringBefore(':') != route ||
                comparisonRouteIdentities.size != 1 ||
                speech.any { it.outputRouteIdentity != outputRouteIdentity || it.outputRouteIdentityChanges != 0 }
            ) {
                add("OUTPUT_DEVICE_ROUTE_NOT_MATCHED")
            }
            if (underruns == null || underruns != 0) add("UNDERRUNS_UNMEASURED_OR_PRESENT")
            if (coverage == null || coverage < .99 || observed > expected) add("CAPTION_COVERAGE_NOT_MET")
            if (!hasTimingCounts) add("CAPTION_TIMING_PROVENANCE_UNOBSERVED")
            if (cancel.mapNotNull { it.cancellationTarget }.toSet() != VoiceSpeechPlaybackProtocol.cancellationTargets(configuration).toSet() ||
                cancel.any { it.quality != VoiceSpeechPlaybackQuality.CANCELLATION_VALIDATED || it.cancellationMs == null }
            ) {
                add("CANCELLATION_NOT_VALIDATED")
            }
            if (quality?.status == "QUALITY_TARGET_NOT_MET") add("INDEPENDENT_QUALITY_FAILED")
        }
        VoiceStreamingComparison(
            backend, route, measured.size,
            VoiceSpeechBenchmarkBackendResult.median(times.map(Long::toDouble)),
            times.takeIf { it.size >= VoiceSpeechBenchmarkBackendResult.MIN_PERCENTILE_SAMPLES }?.let { VoiceSpeechBenchmarkBackendResult.percentile(it, .95) },
            underruns, cancel.mapNotNull { it.cancellationMs }.maxOrNull(), coverage,
            measured.sumOf { it.captionNativeTimingWords ?: 0 }.takeIf { hasTimingCounts },
            measured.sumOf { it.captionEstimatedTimingWords ?: 0 }.takeIf { hasTimingCounts },
            measured.sumOf { it.captionTimingRevisions ?: 0 }.takeIf { hasTimingCounts },
            exclusions.isEmpty(), exclusions, quality, outputRouteIdentity,
            measured.sumOf { it.captionAsrAlignmentTimingWords ?: 0 }.takeIf { hasTimingCounts && measured.any { it.captionAsrAlignmentTimingWords != null } },
            speech.flatMap { it.captionAlignmentStatuses }.toSet()
        )
    }
}

/** Descriptive latency candidate only. This does not modify Auto or claim perceived quality. */
internal fun VoiceSpeechBenchmarkReport.measuredStreamingCandidate(): VoiceSpeechBackend? {
    val comparable = streamingComparisons().filter { it.eligibleForLatencyComparison }
    if (comparable.size < 2) return null
    return comparable.minByOrNull { checkNotNull(it.medianFirstPresentedMs) }?.backend
}
