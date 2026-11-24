package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoiceSpeechPlaybackState { COMPLETE, FAILED, UNSUPPORTED, CANCELLED }

internal data class VoiceSpeechPlaybackChunk(
    val index: Int,
    val completedAtMs: Long,
    val generationMs: Long,
    val audioMs: Long,
    val inputBytes: Int?,
    val inputSha256: String?,
    val effectiveProvider: String?,
    val verifiedAudio: Boolean,
    val acceleratorKernels: Int?,
    val cpuKernels: Int?,
    val observation: VoiceSpeechBenchmarkFailureObservation?,
    val audioObservation: VoiceSpeechAudioObservation? = null
)

internal data class VoiceSpeechReserveObservation(val phase: String, val elapsedMs: Long, val targetMs: Long, val acceptedMs: Long, val exitReason: String, val startMs: Long? = null, val endMs: Long? = null)
internal data class VoiceSpeechCalibrationObservation(val route: String, val offsetMs: Int, val routeEpoch: Long)
internal data class VoiceSpeechProgressObservation(val stage: VoiceSpeechStage, val attemptId: Long, val stageSequence: Long, val stageElapsedMs: Long, val attemptElapsedMs: Long, val cancellationRequested: Boolean, val active: Boolean = true)

/** Fixed-fixture observations. An unmeasured value is never evidence of zero cost or successful execution. */
internal data class VoiceSpeechPlaybackResult(
    val requestedBackend: VoiceSpeechBackend,
    val state: VoiceSpeechPlaybackState,
    val firstPcmMs: Long? = null,
    val firstQueuedPcmMs: Long? = null,
    val firstPresentedAudioMs: Long? = null,
    val elapsedMs: Long = 0,
    val totalSynthesisMs: Long = 0,
    val totalAudioMs: Long = 0,
    val chunks: Int = 0,
    val underruns: Int? = null,
    val bufferRestarts: Int = 0,
    val longestRefillMs: Long = 0,
    val cancellationMs: Long? = null,
    val captionObservations: Int = 0,
    val captionClockRegressions: Int = 0,
    val captionTimingViolations: Int = 0,
    val clockBasis: String? = null,
    val route: String? = null,
    val effectiveProvider: String? = null,
    val acceleratorExecutionObserved: Boolean = false,
    val thermalBefore: Int? = null,
    val thermalAfter: Int? = null,
    val failure: VoiceSpeechFailureCode? = null,
    val failureStage: VoiceSpeechStage? = null,
    val validatedChunks: Int = 0,
    val captionWordsExpected: Int = 0,
    val captionWordsObserved: Int = 0,
    val protocolVersion: Int = 0,
    val scenario: VoiceSpeechPlaybackScenario = VoiceSpeechPlaybackScenario.COLD_START,
    val repeat: Int = 0,
    val warmup: Boolean = false,
    val expectedResident: Boolean = false,
    val engineReused: Boolean? = null,
    val fixtureHash: String? = null,
    val manifestWordsExpected: Int? = null,
    val manifestWordsPrepared: Int? = null,
    val manifestWordsObserved: Int? = null,
    val unexpectedCaptionWords: Int? = null,
    val firstAudioTrackWriteMs: Long? = null,
    val trackPlayRequestedMs: Long? = null,
    val sessionPrepareMs: Long? = null,
    val requestedThreads: Int? = null,
    val effectiveThreads: Int? = null,
    val activePlaybackUnderruns: Int? = null,
    val startupUnderruns: Int? = null,
    val drainUnderruns: Int? = null,
    val chunkRecords: List<VoiceSpeechPlaybackChunk> = emptyList(),
    val reserves: List<VoiceSpeechReserveObservation> = emptyList(),
    val calibrations: List<VoiceSpeechCalibrationObservation> = emptyList(),
    val progress: List<VoiceSpeechProgressObservation> = emptyList(),
    val pipelineFailureCode: String? = null,
    val nativeCode: Int? = null,
    val failedChunk: Int? = null,
    val failedInputBytes: Int? = null,
    val failedInputByteLimit: Int? = null,
    val failedTokenCount: Int? = null,
    val failedTokenLimit: Int? = null,
    val failedRequestedProvider: String? = null,
    val failedEffectiveProvider: String? = null,
    val failedDiscoverySummary: String? = null,
    val failedAttemptMs: Long? = null,
    val failureObservation: VoiceSpeechBenchmarkFailureObservation? = null,
    val cancellationTarget: VoiceSpeechCancellationTarget? = null,
    val cancellationTargetReached: Boolean? = null,
    val cleanupState: String = "NOT_OBSERVED",
    val arrivalIntervalMs: Long = 0,
    val underrunsByPhase: Map<String, Int> = emptyMap(),
    val preparationPhases: Map<String, Long> = emptyMap(),
    val timeoutLimitMs: Long? = null,
    val timeoutElapsedMs: Long? = null,
    val timeoutScope: String? = null,
    val streamedAudioValidated: Boolean? = null,
    val requestedDelegate: String? = null,
    val observedProviderStatus: String? = null,
    val engineStreamCalls: Int? = null,
    val engineStreamWallMs: Long? = null,
    val omittedPcmRecords: Int = 0,
    val sustainedTargetAudioMs: Long? = null,
    val sustainedObservedAudioMs: Long? = null,
    val sustainedWallElapsedMs: Long? = null,
    val sustainedWallDeadlineMs: Long? = null,
    val sustainedCompletedRepeats: Int? = null,
    val sustainedQualification: String? = null
) {
    val captionManifestValid: Boolean
        get() = protocolVersion == VoiceSpeechPlaybackProtocol.VERSION &&
            manifestWordsExpected?.let { it > 0 && manifestWordsPrepared == it && manifestWordsObserved == it } == true &&
            unexpectedCaptionWords == 0 &&
            captionClockRegressions == 0 &&
            captionTimingViolations == 0

    val executionVerified: Boolean get() = chunks > 0 &&
        validatedChunks == chunks &&
        effectiveProvider == VoiceSpeechBenchmarkBackendResult.provider(requestedBackend) &&
        (requestedBackend == VoiceSpeechBackend.CPU || acceleratorExecutionObserved)

    val synthesisRealTimeFactor: Double? get() = (totalSynthesisMs.toDouble() / totalAudioMs).takeIf { totalAudioMs > 0 && chunks > 0 && it.isFinite() }

    val streamingStructureVerified: Boolean get() = requestedBackend.isStreamingLocal &&
        requestedDelegate == requestedSpeechDelegate(requestedBackend) &&
        observedProviderStatus == "NOT_EXPOSED_BY_SDK" &&
        streamedAudioValidated == true &&
        (engineStreamCalls ?: 0) > 0 &&
        chunks > 0 &&
        totalAudioMs > 0

    /** Completion and interactive suitability are independent verdicts. Targets are product budgets, not acoustic claims. */
    val quality: VoiceSpeechPlaybackQuality
        get() = when {
            protocolVersion != VoiceSpeechPlaybackProtocol.VERSION -> VoiceSpeechPlaybackQuality.UNASSESSED
            cancellationTarget != null && cancellationTargetReached != true -> VoiceSpeechPlaybackQuality.CANCELLATION_TARGET_NOT_REACHED
            cancellationTarget != null && cleanupState != "RETIRED" -> VoiceSpeechPlaybackQuality.INCOMPLETE
            cancellationTarget != null && state == VoiceSpeechPlaybackState.CANCELLED && failure == null && cancellationMs?.let { it <= VoiceSpeechPlaybackProtocol.CANCELLATION_TARGET_MS } == true -> VoiceSpeechPlaybackQuality.CANCELLATION_VALIDATED
            state == VoiceSpeechPlaybackState.FAILED || state == VoiceSpeechPlaybackState.UNSUPPORTED -> VoiceSpeechPlaybackQuality.FAILED
            state != VoiceSpeechPlaybackState.COMPLETE || !(executionVerified || streamingStructureVerified) || !captionManifestValid || firstPresentedAudioMs == null || cleanupState != "RETIRED" -> VoiceSpeechPlaybackQuality.INCOMPLETE
            scenario == VoiceSpeechPlaybackScenario.SUSTAINED && sustainedQualification !in setOf("IN_PROGRESS", "TARGET_REACHED") -> VoiceSpeechPlaybackQuality.INCOMPLETE
            thermalBefore !in 0..1 || thermalAfter !in 0..1 -> VoiceSpeechPlaybackQuality.THERMAL_LIMITED
            bufferRestarts > 0 || (activePlaybackUnderruns ?: 0) > 0 -> VoiceSpeechPlaybackQuality.COMPLETE_WITH_STALLS
            firstPresentedAudioMs > VoiceSpeechPlaybackProtocol.FIRST_AUDIO_TARGET_MS -> VoiceSpeechPlaybackQuality.SLOW_START
            expectedResident && engineReused != true -> VoiceSpeechPlaybackQuality.INCOMPLETE
            activePlaybackUnderruns == null -> VoiceSpeechPlaybackQuality.INCOMPLETE
            streamingStructureVerified -> VoiceSpeechPlaybackQuality.STREAMING_PLAYBACK_OBSERVED
            else -> VoiceSpeechPlaybackQuality.PLAYBACK_VALIDATED
        }
}

/** Optional numeric observer. No transcript, PCM, device identity or credentials cross this boundary. */
internal interface VoicePlaybackMeasurementObserver {
    /** Each production reply captures its observer once, before any asynchronous work starts. */
    fun forPlayback(): VoicePlaybackMeasurementObserver = this
    fun pcmReady(synthesisMs: Long, audioFrames: Int, sampleRate: Int, evidence: VoiceSpeechExecutionEvidence?) = Unit
    fun pcmQueued() = Unit
    fun captionsPrepared(segment: Int, ranges: List<IntRange>) = Unit
    fun firstPresented(clockBasis: String, route: String) = Unit
    fun terminal(underruns: Int?, bufferRestarts: Int, longestRefillMs: Long, clockBasis: String, route: String) = Unit
    fun firstAudioTrackWrite() = Unit
    fun trackPlayRequested() = Unit
    fun reserveCompleted(phase: String, elapsedMs: Long, targetMs: Long, acceptedMs: Long, exitReason: String) = Unit
    fun underrunObserved(delta: Int, phase: String) = Unit
    fun calibration(route: String, offsetMs: Int, routeEpoch: Long) = Unit
    fun nativePreparation(elapsedMs: Long, requestedThreads: Int, effectiveThreads: Int) = Unit
    fun engineReady(reused: Boolean) = Unit
    fun preparationPhase(stage: String, elapsedMs: Long) = Unit
    fun executionProgress(progress: VoiceSpeechProgress) = Unit
}

/** A new reply captures the current receipt; delayed callbacks keep their previous receipt. */
internal class VoiceSpeechPlaybackMeasurementRelay : VoicePlaybackMeasurementObserver {
    @Volatile var current: VoiceSpeechPlaybackMeasurement? = null
    override fun forPlayback(): VoicePlaybackMeasurementObserver = current ?: Empty

    private object Empty : VoicePlaybackMeasurementObserver
}

/** Native, output and main-thread caption callbacks share one bounded receipt. */
internal class VoiceSpeechPlaybackMeasurement(
    private val backend: VoiceSpeechBackend,
    private val thermalBefore: Int?,
    private val plan: VoiceSpeechPlaybackPlan? = null,
    private val nanoTime: () -> Long = System::nanoTime
) : VoicePlaybackMeasurementObserver {
    private val started = nanoTime()
    private var firstPcm: Long? = null
    private var firstQueued: Long? = null
    private var firstPresented: Long? = null
    private var firstWrite: Long? = null
    private var playRequested: Long? = null
    private var prepareMs: Long? = null
    private var requestedThreads: Int? = null
    private var effectiveThreads: Int? = null
    private var reused: Boolean? = null
    private var synthesis = 0L
    private var audioMs = 0L
    private var streamingFrames = 0L
    private var omittedPcmRecords = 0
    private var chunks = 0
    private var validatedChunks = 0
    private var underruns: Int? = null
    private var activeUnderruns: Int? = null
    private var startupUnderruns: Int? = null
    private var drainUnderruns: Int? = null
    private var restarts = 0
    private var refill = 0L
    private var clock: String? = null
    private var route: String? = null
    private var provider: String? = null
    private var accelerated = false
    private var captions = 0
    private var regressions = 0
    private var violations = 0
    private var lastCaptionFrame: Long? = null
    private var cancellationRequested: Long? = null
    private var cancellationTargetReached: Boolean? = null
    private var stoppedAt: Long? = null
    private var currentStage: VoiceSpeechStage? = null
    private var modelPreparationActive = false
    private var preparationAttemptStarted: Long? = null
    private var attemptStarted: Long? = null
    private var attemptBytes: Int? = null
    private var attemptHash: String? = null
    private var attemptAudio: VoiceSpeechAudioObservation? = null
    private val expectedWords = mutableSetOf<VoiceSpeechCaptionSpan>()
    private val observedWords = mutableSetOf<VoiceSpeechCaptionSpan>()
    private val chunkRecords = mutableListOf<VoiceSpeechPlaybackChunk>()
    private val reserves = mutableListOf<VoiceSpeechReserveObservation>()
    private val calibrations = mutableListOf<VoiceSpeechCalibrationObservation>()
    private val progress = mutableListOf<VoiceSpeechProgressObservation>()
    private val phaseUnderruns = linkedMapOf<String, Int>()
    private val preparationPhases = linkedMapOf<String, Long>()
    private var capturedFailure: VoiceSpeechPlaybackFailureEvidence? = null

    @Synchronized fun preparationStarted() {
        if (stoppedAt == null) {
            modelPreparationActive = true
            currentStage = VoiceSpeechStage.PREPARING
            preparationAttemptStarted = elapsed()
        }
    }

    @Synchronized fun attemptStarted(text: String) {
        if (stoppedAt != null) return
        modelPreparationActive = false
        currentStage = VoiceSpeechStage.FRONTEND
        attemptStarted = elapsed()
        attemptBytes = text.toByteArray(Charsets.UTF_8).size
        attemptHash = VoiceSpeechWorkload.digest(text)
        attemptAudio = null
    }

    /** The complete input is not yet known; do not label the first delta as the whole attempt. */
    @Synchronized fun incrementalAttemptStarted() {
        if (stoppedAt != null) return
        modelPreparationActive = false
        currentStage = VoiceSpeechStage.FRONTEND
        attemptStarted = elapsed()
        attemptBytes = null
        attemptHash = null
        attemptAudio = null
    }

    /** Called only by the fixed-fixture benchmark engine, before optional playback trimming. */
    @Synchronized fun audioGenerated(observation: VoiceSpeechAudioObservation) {
        if (stoppedAt != null) return
        require(observation.valid && observation.inputSha256 == attemptHash && observation.inputBytes == attemptBytes)
        attemptAudio = observation
    }

    @Synchronized fun nativeFailure(error: Throwable, deviceKey: String, modelSha256: String = KokoroModelPack.MODEL_SHA256) {
        if (stoppedAt != null) return
        modelPreparationActive = false
        if (generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(12).any { it is java.util.concurrent.CancellationException }) return
        if (capturedFailure == null) capturedFailure = speechPlaybackFailureEvidence(error, backend, deviceKey, modelSha256)
    }

    @Synchronized fun reached(target: VoiceSpeechCancellationTarget): Boolean = when (target) {
        VoiceSpeechCancellationTarget.PREPARATION -> currentStage == VoiceSpeechStage.PREPARING
        VoiceSpeechCancellationTarget.INFERENCE -> currentStage == VoiceSpeechStage.SYNTHESIS
        VoiceSpeechCancellationTarget.PLAYBACK -> firstPresented != null
    }

    /** Native stage admission needs a fresh active snapshot; model opening and audio presentation are observed separately. */
    @Synchronized fun reached(target: VoiceSpeechCancellationTarget, currentProgress: VoiceSpeechProgress?): Boolean {
        currentProgress?.let(::executionProgress)
        if (stoppedAt != null) return false
        val activeStage = currentProgress?.takeIf { it.active }?.stage
        return when (target) {
            VoiceSpeechCancellationTarget.PREPARATION -> modelPreparationActive || activeStage == VoiceSpeechStage.PREPARING
            VoiceSpeechCancellationTarget.INFERENCE -> activeStage == VoiceSpeechStage.SYNTHESIS
            VoiceSpeechCancellationTarget.PLAYBACK -> firstPresented != null
        }
    }

    @Synchronized override fun pcmReady(synthesisMs: Long, audioFrames: Int, sampleRate: Int, evidence: VoiceSpeechExecutionEvidence?) {
        if (stoppedAt != null) return
        if (firstPcm == null) firstPcm = elapsed()
        val duration = if (backend.isStreamingLocal && sampleRate == VOICE_PCM_SAMPLE_RATE) {
            val before = streamingFrames * 1_000 / sampleRate
            streamingFrames += audioFrames.coerceAtLeast(0)
            streamingFrames * 1_000 / sampleRate - before
        } else {
            audioFrames.coerceAtLeast(0).toLong() * 1000 / sampleRate.coerceAtLeast(1)
        }
        synthesis += synthesisMs.coerceAtLeast(0)
        audioMs += duration
        chunks++
        provider = evidence?.effectiveProvider?.takeIf { it in PROVIDERS } ?: provider
        accelerated = accelerated || evidence?.let { it.verifiedAudio && it.profileAvailable == true && it.acceleratorKernels > 0 && it.effectiveProvider == VoiceSpeechBenchmarkBackendResult.provider(backend) } == true
        val valid = audioFrames > 0 &&
            sampleRate == 24_000 &&
            evidence?.verifiedAudio == true &&
            evidence.effectiveProvider == VoiceSpeechBenchmarkBackendResult.provider(backend) &&
            (backend == VoiceSpeechBackend.CPU || evidence.profileAvailable == true && evidence.acceleratorKernels > 0)
        if (valid) validatedChunks++
        if (chunkRecords.size < VoiceSpeechPlaybackProtocol.MAX_CHUNKS_PER_SAMPLE) {
            chunkRecords += VoiceSpeechPlaybackChunk(
                chunks - 1, elapsed(), synthesisMs.coerceAtLeast(0), duration, attemptBytes, attemptHash, evidence?.effectiveProvider?.takeIf { it in PROVIDERS }, valid,
                evidence?.acceleratorKernels?.takeIf { evidence.profileAvailable == true }, evidence?.cpuKernels?.takeIf { evidence.profileAvailable == true }, evidence?.let(VoiceSpeechBenchmarkFailureObservation::from),
                attemptAudio
            )
        } else if (backend.isStreamingLocal) {
            omittedPcmRecords++
        } else {
            violations++
        }
        attemptStarted = null
        currentStage = VoiceSpeechStage.READY
    }

    @Synchronized override fun pcmQueued() {
        if (stoppedAt == null && firstQueued == null) firstQueued = elapsed()
    }

    @Synchronized override fun firstAudioTrackWrite() {
        if (stoppedAt == null && firstWrite == null) firstWrite = elapsed()
    }

    @Synchronized override fun trackPlayRequested() {
        if (stoppedAt == null && playRequested == null) playRequested = elapsed()
    }

    @Synchronized override fun nativePreparation(elapsedMs: Long, requestedThreads: Int, effectiveThreads: Int) {
        if (stoppedAt != null) return
        prepareMs = elapsedMs.coerceAtLeast(0)
        this.requestedThreads = requestedThreads.takeIf { it in 1..4 }
        this.effectiveThreads = effectiveThreads.takeIf { it in 1..4 }
    }

    @Synchronized override fun engineReady(reused: Boolean) {
        if (stoppedAt != null) return
        modelPreparationActive = false
        this.reused = reused
        currentStage = VoiceSpeechStage.READY
    }

    @Synchronized override fun executionProgress(progress: VoiceSpeechProgress) {
        if (stoppedAt != null) return
        currentStage = progress.stage.takeIf { progress.active }
        if (this.progress.size < 128 && (this.progress.lastOrNull()?.let { it.attemptId != progress.attemptId || it.stageSequence != progress.stageSequence || it.active != progress.active || progress.attemptElapsedMs - it.attemptElapsedMs >= 1000 } != false)) this.progress += VoiceSpeechProgressObservation(progress.stage, progress.attemptId, progress.stageSequence, progress.stageElapsedMs, progress.attemptElapsedMs, progress.cancellationRequested, progress.active)
    }

    @Synchronized override fun preparationPhase(stage: String, elapsedMs: Long) {
        if (stoppedAt == null && stage in setOf("GATE_WAIT", "MODEL_LOAD")) preparationPhases[stage] = elapsedMs.coerceAtLeast(0)
    }

    @Synchronized override fun reserveCompleted(phase: String, elapsedMs: Long, targetMs: Long, acceptedMs: Long, exitReason: String) {
        if (stoppedAt != null || reserves.size >= 128) return
        val end = elapsed()
        val duration = elapsedMs.coerceAtLeast(0)
        reserves += VoiceSpeechReserveObservation(safeCode(phase) ?: "UNKNOWN", duration, targetMs.coerceAtLeast(0), acceptedMs.coerceAtLeast(0), safeCode(exitReason) ?: "UNKNOWN", (end - duration).coerceAtLeast(0), end)
    }

    @Synchronized override fun underrunObserved(delta: Int, phase: String) {
        if (stoppedAt != null) return
        if (phase.lowercase() in setOf("startup", "active", "refill", "draining", "paused", "cancelled")) phaseUnderruns[phase.lowercase()] = (phaseUnderruns[phase.lowercase()] ?: 0) + delta.coerceAtLeast(0)
        when (phase.uppercase()) {
            "STARTUP" -> startupUnderruns = (startupUnderruns ?: 0) + delta.coerceAtLeast(0)
            "ACTIVE", "PLAYBACK", "ACTIVE_PLAYBACK" -> activeUnderruns = (activeUnderruns ?: 0) + delta.coerceAtLeast(0)
            "DRAIN", "DRAINING", "END" -> drainUnderruns = (drainUnderruns ?: 0) + delta.coerceAtLeast(0)
        }
    }

    @Synchronized override fun calibration(route: String, offsetMs: Int, routeEpoch: Long) {
        if (stoppedAt != null || calibrations.size >= 32 || offsetMs !in -2000..2000 || routeEpoch < 0) return
        val value = VoiceSpeechCalibrationObservation(safeRoute(route) ?: "other", offsetMs, routeEpoch)
        if (calibrations.lastOrNull() != value) calibrations += value
    }

    @Synchronized override fun firstPresented(clockBasis: String, route: String) {
        if (stoppedAt != null) return
        if (firstPresented == null) firstPresented = elapsed()
        this.clock = clockBasis.takeIf { it in CLOCKS } ?: this.clock
        this.route = safeRoute(route) ?: this.route
    }

    @Synchronized override fun captionsPrepared(segment: Int, ranges: List<IntRange>) {
        if (stoppedAt != null) return
        for (range in ranges.filterNot { it.isEmpty() }) {
            val span = VoiceSpeechCaptionSpan(segment, range.first, range.last)
            if (span in expectedWords || expectedWords.size < VoiceSpeechCaptionManifest.MAX_WORDS) expectedWords.add(span) else violations++
        }
    }

    @Synchronized fun caption(timing: VoiceCaptionTiming, segment: Int? = null, range: IntRange? = null) {
        if (stoppedAt != null) return
        captions++
        if (lastCaptionFrame?.let { timing.presentedFrame < it } == true) regressions++
        if (timing.wordEndFrame < timing.wordStartFrame || timing.presentedFrame > timing.bufferedEndFrame) violations++
        lastCaptionFrame = timing.presentedFrame
        if (segment != null && range != null && !range.isEmpty()) {
            val span = VoiceSpeechCaptionSpan(segment, range.first, range.last)
            if (span in observedWords || observedWords.size < VoiceSpeechCaptionManifest.MAX_WORDS) observedWords.add(span) else violations++
        }
    }

    @Synchronized fun cancelRequested(targetReached: Boolean? = null) {
        if (stoppedAt != null) return
        if (cancellationRequested == null) cancellationRequested = elapsed()
        cancellationTargetReached = targetReached ?: cancellationTargetReached
    }

    @Synchronized override fun terminal(underruns: Int?, bufferRestarts: Int, longestRefillMs: Long, clockBasis: String, route: String) {
        if (stoppedAt != null) return
        this.underruns = underruns?.coerceAtLeast(0)
        restarts = bufferRestarts.coerceAtLeast(0)
        refill = longestRefillMs.coerceAtLeast(0)
        clock = clockBasis.takeIf { it in CLOCKS } ?: clock
        this.route = safeRoute(route) ?: this.route
        stoppedAt = elapsed()
    }

    @Synchronized fun result(state: VoiceSpeechPlaybackState, thermalAfter: Int?, failure: VoiceSpeechFailureCode? = null, stage: VoiceSpeechStage? = null, pipelineFailureCode: String? = null, failureEvidence: VoiceSpeechPlaybackFailureEvidence? = null): VoiceSpeechPlaybackResult {
        val ended = stoppedAt ?: elapsed()
        // The factory can retain a typed native cause before a later pipeline wrapper loses it.
        val native = listOfNotNull(failureEvidence, capturedFailure).maxByOrNull {
            (if (it.classification.nativeCode != null) 4 else 0) + (if (it.classification.stage != null) 2 else 0) + (if (it.observation != null) 1 else 0)
        }
        val manifest = plan?.manifest
        return VoiceSpeechPlaybackResult(
            requestedBackend = backend, state = state, firstPcmMs = firstPcm, firstQueuedPcmMs = firstQueued, firstPresentedAudioMs = firstPresented,
            elapsedMs = ended, totalSynthesisMs = synthesis, totalAudioMs = audioMs, chunks = chunks, underruns = underruns, bufferRestarts = restarts, longestRefillMs = refill,
            cancellationMs = cancellationRequested?.let { requested -> stoppedAt?.let { (it - requested).coerceAtLeast(0) } },
            captionObservations = captions, captionClockRegressions = regressions, captionTimingViolations = violations + observedWords.count { it !in expectedWords },
            clockBasis = clock, route = route, effectiveProvider = provider, acceleratorExecutionObserved = accelerated,
            thermalBefore = thermalBefore, thermalAfter = thermalAfter, failure = native?.classification?.code ?: failure, failureStage = native?.classification?.stage ?: stage,
            validatedChunks = validatedChunks, captionWordsExpected = expectedWords.size, captionWordsObserved = observedWords.count { it in expectedWords },
            protocolVersion = if (plan == null) 0 else VoiceSpeechPlaybackProtocol.VERSION,
            scenario = plan?.scenario ?: VoiceSpeechPlaybackScenario.COLD_START, repeat = plan?.repeat ?: 0, warmup = plan?.warmup ?: false, expectedResident = plan?.expectedResident ?: false,
            engineReused = reused, fixtureHash = manifest?.hash, manifestWordsExpected = manifest?.spans?.size,
            manifestWordsPrepared = manifest?.let { m -> expectedWords.count { it in m.spans } }, manifestWordsObserved = manifest?.let { m -> observedWords.count { it in m.spans } },
            unexpectedCaptionWords = manifest?.let { m -> (expectedWords + observedWords).count { it !in m.spans } },
            firstAudioTrackWriteMs = firstWrite, trackPlayRequestedMs = playRequested, sessionPrepareMs = prepareMs,
            requestedThreads = requestedThreads, effectiveThreads = effectiveThreads, activePlaybackUnderruns = activeUnderruns, startupUnderruns = startupUnderruns, drainUnderruns = drainUnderruns,
            chunkRecords = chunkRecords.toList(), reserves = reserves.toList(), calibrations = calibrations.toList(), progress = progress.toList(), pipelineFailureCode = safeCode(pipelineFailureCode),
            nativeCode = native?.classification?.nativeCode, failedChunk = chunks.takeIf { attemptStarted != null && (failure != null || native != null) },
            failedInputBytes = native?.inputBytes ?: attemptBytes.takeIf { failure != null || native != null }, failedInputByteLimit = native?.inputByteLimit,
            failedTokenCount = native?.tokenCount, failedTokenLimit = native?.tokenLimit, failedRequestedProvider = native?.requestedProvider,
            failedEffectiveProvider = native?.effectiveProvider, failedDiscoverySummary = native?.discoverySummary,
            failedAttemptMs = (attemptStarted ?: preparationAttemptStarted.takeIf { native != null && chunks == 0 })?.let { (ended - it).coerceAtLeast(0) }.takeIf { failure != null || native != null }, failureObservation = native?.observation,
            cancellationTarget = plan?.cancellationTarget, cancellationTargetReached = cancellationTargetReached, arrivalIntervalMs = plan?.arrivalIntervalMs ?: 0,
            underrunsByPhase = phaseUnderruns.toMap(), preparationPhases = preparationPhases.toMap(),
            timeoutLimitMs = native?.timeoutLimitMs, timeoutElapsedMs = native?.timeoutElapsedMs, timeoutScope = native?.timeoutScope,
            omittedPcmRecords = omittedPcmRecords
        )
    }

    private fun elapsed() = (nanoTime() - started).coerceAtLeast(0) / 1_000_000
    private fun safeRoute(value: String): String? = when {
        value.isBlank() || value == "unknown" -> null
        value in setOf("speaker", "earpiece", "bluetooth", "usb", "wired") -> value
        else -> "other"
    }
    private fun safeCode(value: String?): String? = value?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }
    private companion object {
        val PROVIDERS = setOf("cpu", "qnn-gpu", "qnn-htp")
        val CLOCKS = setOf("audio_timestamp", "playback_head")
    }
}

internal data class VoiceSpeechPlaybackFailureEvidence(
    val classification: VoiceSpeechBenchmarkFailure,
    val inputBytes: Int?,
    val inputByteLimit: Int?,
    val tokenCount: Int?,
    val tokenLimit: Int?,
    val requestedProvider: String?,
    val effectiveProvider: String?,
    val discoverySummary: String?,
    val observation: VoiceSpeechBenchmarkFailureObservation?,
    val timeoutLimitMs: Long? = null,
    val timeoutElapsedMs: Long? = null,
    val timeoutScope: String? = null
)

internal fun speechPlaybackFailureEvidence(error: Throwable?, requested: VoiceSpeechBackend, deviceKey: String, modelSha256: String = KokoroModelPack.MODEL_SHA256): VoiceSpeechPlaybackFailureEvidence? {
    if (error == null) return null
    val causes = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(12).toList()
    val native = causes.filterIsInstance<dev.melo.kokoro.TimedKokoro.Failure>().firstOrNull()
    val timeout = causes.filterIsInstance<VoicePlaybackTimeout>().firstOrNull()
    val typed = causes.filterIsInstance<VoiceSpeechPreparationFailure>().firstOrNull()?.executionEvidence
    val classified = speechBenchmarkFailure((native ?: error) as? Exception ?: IllegalStateException("EXECUTION_FAILED"))
    val observed = native?.let { speechFailureExecutionEvidence(it, requested, deviceKey, modelSha256) } ?: typed
    return VoiceSpeechPlaybackFailureEvidence(
        if (typed?.failure != null) {
            VoiceSpeechBenchmarkFailure(typed.failure, classified.state, typed.failureStage ?: typed.stage, typed.nativeCode)
        } else if (timeout != null) {
            classified.copy(stage = timeout.stage)
        } else {
            classified
        },
        native?.inputBytes?.takeIf { it >= 0 } ?: typed?.failedInputBytes, native?.inputByteLimit?.takeIf { it > 0 } ?: typed?.failedInputByteLimit,
        native?.tokenCount?.takeIf { it >= 0 } ?: typed?.failedTokenCount, native?.tokenLimit?.takeIf { it > 0 } ?: typed?.failedTokenLimit,
        native?.provider?.takeIf { it in setOf("cpu", "qnn-gpu", "qnn-htp") } ?: typed?.failedProvider ?: VoiceSpeechBenchmarkBackendResult.provider(requested),
        observed?.effectiveProvider?.takeIf { it in setOf("cpu", "qnn-gpu", "qnn-htp") }, safeSpeechDiscoverySummary(native?.discoverySummary ?: typed?.failedDiscoverySummary), observed?.let(VoiceSpeechBenchmarkFailureObservation::from),
        timeout?.limitMs, timeout?.elapsedMs, timeout?.scope?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }
    )
}
