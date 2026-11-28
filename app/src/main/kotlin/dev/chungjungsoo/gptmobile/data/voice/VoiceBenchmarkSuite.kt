package dev.chungjungsoo.gptmobile.data.voice

import dev.melo.kokoro.TimedKokoro
import kotlinx.serialization.Serializable

internal enum class VoiceBenchProbe { CPU_CALIBRATION, AUDIO_ROUTES, MICROPHONE, RECOGNITION, LANGUAGE, SYSTEM_RECOGNITION, SYSTEM_SPEECH, GATEWAY, DIALOGUE }

internal data class VoiceBenchStage(
    val label: String,
    val engine: VoiceSpeechBenchmarkEngine? = null,
    val variant: KokoroModelVariant = KokoroModelVariant.INT8,
    val backends: List<VoiceSpeechBackend> = engine?.supportedBackends.orEmpty(),
    val mode: VoiceSpeechBenchmarkMode = VoiceSpeechBenchmarkMode.FULL,
    val workload: VoiceSpeechBenchmarkWorkload = VoiceSpeechBenchmarkWorkload.UTTERANCE,
    val playback: List<VoiceSpeechPlaybackScenario> = emptyList(),
    val calibration: VoiceSpeechCalibrationPolicy = VoiceSpeechCalibrationPolicy.CONTROLLED_ZERO,
    val threads: Int,
    val rate: Float,
    val sustainedTargetMs: Long = 300_000L,
    val incremental: Boolean = true,
    val bucket: MediaPipeKokoroBucket = MediaPipeKokoroBucket.AUTO,
    val htp: String = "balanced",
    val probe: VoiceBenchProbe? = null,
    val language: String? = null,
    val listeningStyle: VoiceListeningStyle? = null,
    val selectedVoiceId: String? = null,
    val useCalibratedThreads: Boolean = false,
    val recognition: VoiceRecognitionBenchmarkCase? = null
) {
    fun configuration(voice: KokoroVoice, streamingVoice: String?, offset: Int, order: Long, build: Int, sdk: Int, device: String, commit: String): VoiceSpeechBenchmarkConfiguration {
        val selected = requireNotNull(engine)
        val compiledPocket = selected == VoiceSpeechBenchmarkEngine.POCKET_COMPILED
        val sdkModel = selected.mediaPipeEngine()?.let(MediaPipeSpeechCatalog::model)
        val standardVoice = voice.streamingVoiceId
        val voiceId = when {
            compiledPocket -> (selectedVoiceId ?: streamingVoice?.takeIf { calibration == VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS })
                ?.takeIf { it in PocketCompiledSpeechCatalog.VOICES } ?: PocketCompiledSpeechCatalog.DEFAULT_VOICE
            sdkModel == null -> voice.name
            selectedVoiceId != null -> MediaPipeSpeechVoices.selected(sdkModel.engine, selectedVoiceId)
            calibration == VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS && streamingVoice != null && MediaPipeSpeechVoices.profile(sdkModel.engine, streamingVoice) != null -> streamingVoice
            !sdkModel.engine.isKokoro -> sdkModel.defaultVoice
            else -> standardVoice
        }
        return VoiceSpeechBenchmarkConfiguration(
            mode, backends, voice, threads, rate, if (compiledPocket) PocketCompiledSpeechCatalog.MODEL_SHA256 else sdkModel?.sha256 ?: variant.modelSha256,
            when {
                compiledPocket -> PocketCompiledSpeechCatalog.RUNTIME_ID
                sdkModel != null -> MediaPipeSpeechCatalog.RUNTIME_ID
                else -> TimedKokoro.RUNTIME_ID
            },
            build, sdk, device,
            workload = workload, backendOrderSeed = order, sourceCommit = commit.takeIf { it.matches(Regex("[0-9a-f]{40}")) },
            playback = playback.isNotEmpty(), playbackScenarios = playback.ifEmpty { listOf(VoiceSpeechPlaybackScenario.COLD_START) },
            calibrationPolicy = calibration, captionOffsetMs = if (calibration == VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS) offset.coerceIn(-500, 500) else 0,
            engine = selected, engineVoiceId = voiceId, deepDiagnostics = true, kokoroBucket = bucket, incrementalInput = incremental,
            htpPerformanceMode = htp, nativeModelVariant = variant, sustainedTargetAudioMs = sustainedTargetMs
        )
    }
}

internal fun fullVoiceBenchPlan(threads: Int, rate: Float, deviceThreads: Int): List<VoiceBenchStage> = buildList {
    val limit = VoiceCpuSchedulingPolicy.deviceLimit(deviceThreads)
    val selectedThreads = (if (threads == 0) 2 else threads).coerceIn(1, limit)
    val selection = rate.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f
    fun probe(label: String, kind: VoiceBenchProbe, language: String? = null, style: VoiceListeningStyle? = null) =
        VoiceBenchStage(label, threads = selectedThreads, rate = selection, probe = kind, language = language, listeningStyle = style)
    add(probe("Audio routes, capture effects and playback service", VoiceBenchProbe.AUDIO_ROUTES))
    add(probe("Microphone · 16 kHz capture", VoiceBenchProbe.MICROPHONE))
    add(probe("Voice replies · thinking and search cues", VoiceBenchProbe.DIALOGUE))
    add(probe("Android speech recognition · installed languages", VoiceBenchProbe.SYSTEM_RECOGNITION))
    add(probe("Android speech synthesis · installed offline voices", VoiceBenchProbe.SYSTEM_SPEECH))
    add(probe("Selected Windows Gateway · CUDA speech", VoiceBenchProbe.GATEWAY))
    val tinyRecognition = VoiceRecognitionBenchmarkCase.forPack(requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.MOONSHINE_TINY)), selectedThreads)
    VoiceListeningStyle.entries.forEach {
        add(probe("Moonshine recognition · ${it.label}", VoiceBenchProbe.RECOGNITION, style = it).copy(recognition = tinyRecognition))
    }
    val configs = VoiceSpeechBenchmarkEngine.entries.flatMap { engine ->
        val native = engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO
        (if (native) KokoroModelVariant.entries else listOf(KokoroModelVariant.INT8)).map { variant ->
            VoiceBenchStage(if (native) "${engine.label} · ${variant.displayName}" else engine.label, engine, variant, threads = selectedThreads, rate = 1f, incremental = engine != VoiceSpeechBenchmarkEngine.POCKET_COMPILED, useCalibratedThreads = native && threads == VoiceCpuSchedulingPolicy.AUTO)
        }
    }
    fun addCase(base: VoiceBenchStage, name: String, spec: VoiceBenchStage) {
        add(spec.copy(label = "${base.label} · $name"))
    }
    fun screeningRoutes(base: VoiceBenchStage): List<VoiceSpeechBackend> = when (base.engine) {
        VoiceSpeechBenchmarkEngine.NATIVE_KOKORO -> listOf(VoiceSpeechBackend.CPU)
        VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO -> listOf(VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_CPU)
        else -> base.backends.filter { it.name.endsWith("_CPU") }.ifEmpty { base.backends }
    }
    // Screen installed engines before expensive accelerator compilation and sustained playback.
    // Pocket is intentionally early so a stopped campaign still compares the lightweight engines.
    val screeningOrder = configs.sortedBy { base ->
        when (base.engine) {
            VoiceSpeechBenchmarkEngine.POCKET -> 0
            VoiceSpeechBenchmarkEngine.POCKET_MULTILINGUAL -> 1
            VoiceSpeechBenchmarkEngine.NATIVE_KOKORO -> 2
            VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO -> 3
            else -> 4
        }
    }
    val deferredPacks = setOf(VoiceSpeechBenchmarkEngine.POCKET_COMPILED, VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO_GPU, VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO_SM8750)
    screeningOrder.filterNot { it.engine in deferredPacks }.forEach { base ->
        addCase(base, "quick synthesis screening", base.copy(backends = screeningRoutes(base), mode = VoiceSpeechBenchmarkMode.QUICK))
    }
    KokoroModelVariant.entries.forEach { variant -> add(probe("Auto CPU thread calibration · ${variant.displayName}", VoiceBenchProbe.CPU_CALIBRATION).copy(variant = variant)) }
    // Collect audible output, stop/recovery and speed coverage before any exhaustive native run.
    // Keep scenarios as separate stages so cancellation and resume cannot strand every other engine.
    val playbackOrder = configs.sortedBy { base ->
        when (base.engine) {
            VoiceSpeechBenchmarkEngine.POCKET -> 0
            VoiceSpeechBenchmarkEngine.POCKET_MULTILINGUAL -> 1
            VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO -> 2
            VoiceSpeechBenchmarkEngine.POCKET_COMPILED -> 3
            VoiceSpeechBenchmarkEngine.NATIVE_KOKORO -> 4
            else -> 5
        }
    }
    val essentialOrder = playbackOrder.filterNot { it.engine in deferredPacks }
    for (scenario in listOf(VoiceSpeechPlaybackScenario.COLD_START, VoiceSpeechPlaybackScenario.CANCELLATION)) {
        essentialOrder.forEach { base ->
            addCase(base, "quick ${scenario.label.lowercase()} screening", base.copy(backends = screeningRoutes(base), mode = VoiceSpeechBenchmarkMode.QUICK, playback = listOf(scenario)))
        }
    }
    essentialOrder.forEach { base ->
        addCase(base, "quick current rate and caption calibration", base.copy(backends = screeningRoutes(base), mode = VoiceSpeechBenchmarkMode.QUICK, rate = selection, playback = listOf(VoiceSpeechPlaybackScenario.STREAMED_SEGMENTS), calibration = VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS))
    }
    for (playbackRate in listOf(.75f, 1.25f, 1.5f, 2f)) {
        essentialOrder.forEach { base ->
            addCase(base, "quick $playbackRate× streaming playback", base.copy(backends = screeningRoutes(base), mode = VoiceSpeechBenchmarkMode.QUICK, rate = playbackRate, playback = listOf(VoiceSpeechPlaybackScenario.STREAMED_SEGMENTS)))
        }
    }
    screeningOrder.filter { it.engine in deferredPacks }.forEach { base ->
        addCase(base, "quick synthesis screening", base.copy(mode = VoiceSpeechBenchmarkMode.QUICK))
    }
    // Larger ASR and accelerator packs are evaluated only after early output screening. A case
    // never installs its model, and a missing language fixture remains explicitly unmeasured.
    VoiceRecognitionModelCatalog.packs.filter { it.model != VoiceRecognitionModel.MOONSHINE_TINY }.forEach { pack ->
        val calibration = if (VoiceRecognitionCpuCalibration.supported(pack)) VoiceRecognitionCpuCalibration.candidates(limit) else emptyList()
        add(
            probe("${pack.model.title} · ${pack.compute.title} · ${if (calibration.isEmpty()) "paced recognition" else "paced recognition and CPU calibration"}", VoiceBenchProbe.RECOGNITION, style = VoiceListeningStyle.NATURAL)
                .copy(recognition = VoiceRecognitionBenchmarkCase.forPack(pack, selectedThreads).copy(calibrationCandidates = calibration))
        )
    }
    playbackOrder.forEach { addCase(it, "first audio and warm synthesis", it) }
    val scenarios = VoiceSpeechPlaybackScenario.entries - VoiceSpeechPlaybackScenario.SUSTAINED
    playbackOrder.forEach { base ->
        addCase(base, "playback, captions and interruption", base.copy(playback = scenarios))
        addCase(base, "current rate and caption calibration", base.copy(rate = selection, playback = listOf(VoiceSpeechPlaybackScenario.STREAMED_SEGMENTS), calibration = VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS))
        listOf(.75f, 1.25f, 1.5f, 2f).forEach { rate ->
            addCase(base, "$rate× streaming playback", base.copy(rate = rate, playback = listOf(VoiceSpeechPlaybackScenario.STREAMED_SEGMENTS)))
        }
        base.engine?.mediaPipeEngine()?.takeUnless { it.isKokoro }?.let { engine ->
            MediaPipeSpeechVoices.ids(engine).forEach { id ->
                addCase(base, "voice $id · PCM readiness", base.copy(mode = VoiceSpeechBenchmarkMode.QUICK, selectedVoiceId = id))
            }
        }
        if (base.engine == VoiceSpeechBenchmarkEngine.POCKET_COMPILED) {
            PocketCompiledSpeechCatalog.VOICES.forEach { id ->
                addCase(base, "voice $id · PCM readiness", base.copy(mode = VoiceSpeechBenchmarkMode.QUICK, selectedVoiceId = id))
            }
        }
    }
    SpeechLanguages.synthesis.forEach { add(probe("Speech, dictionary and word cues · ${it.tag}", VoiceBenchProbe.LANGUAGE, it.tag)) }
    configs.forEach { base ->
        VoiceSpeechBenchmarkWorkload.entries.filterNot { it == base.workload }.forEach { workload ->
            addCase(base, workload.label, base.copy(workload = workload))
        }
        if (base.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO) {
            for (count in 1..limit) {
                addCase(base, "$count CPU threads", base.copy(threads = count, backends = listOf(VoiceSpeechBackend.CPU), mode = VoiceSpeechBenchmarkMode.QUICK, useCalibratedThreads = false))
            }
            listOf("burst", "high_performance").forEach { policy ->
                addCase(base, "Hexagon $policy", base.copy(backends = listOf(VoiceSpeechBackend.QNN_HTP), htp = policy))
            }
        } else {
            val buckets = if (base.engine?.mediaPipeEngine()?.isKokoro == true) MediaPipeKokoroBucket.entries else listOf(MediaPipeKokoroBucket.AUTO)
            for (bucket in buckets) {
                for (streamed in if (base.engine == VoiceSpeechBenchmarkEngine.POCKET_COMPILED) listOf(false) else listOf(false, true)) {
                    if (bucket == base.bucket && streamed == base.incremental) continue
                    val input = if (streamed) "streamed input" else "sealed input"
                    addCase(base, "${bucket.label} · $input", base.copy(bucket = bucket, incremental = streamed))
                }
            }
        }
    }
    configs.forEach { addCase(it, "20 warm repeats", it.copy(mode = VoiceSpeechBenchmarkMode.STABILITY, workload = VoiceSpeechBenchmarkWorkload.INTERLEAVED_UTTERANCES)) }
    for (duration in VoiceSpeechSustainedSeries.TARGETS.sorted()) {
        configs.forEach { addCase(it, "${duration / 60_000}-minute sustained playback per route", it.copy(playback = listOf(VoiceSpeechPlaybackScenario.SUSTAINED), sustainedTargetMs = duration)) }
    }
}

@Serializable
internal enum class VoiceBenchStatus { PENDING, RUNNING, MEASURED, PARTIAL, FAILED, UNAVAILABLE, INTERRUPTED }

@Serializable
internal data class VoiceBenchMetric(val label: String, val value: Double, val unit: String, val samples: Int)

@Serializable
internal data class VoiceBenchResult(val label: String, val status: VoiceBenchStatus = VoiceBenchStatus.PENDING, val reason: String? = null, val measurements: Int = 0, val metrics: List<VoiceBenchMetric> = emptyList(), val recommendedRoute: String? = null, val measuredLatencyCandidate: String? = null)

@Serializable
internal data class VoiceBenchCoverage(
    val plannedStages: Int,
    val statusCounts: Map<String, Int>,
    val stagesWithMeasurements: Int,
    val recordedMeasurements: Int,
    val unavailableReasonCounts: Map<String, Int>,
    val measurementScope: String = "OBSERVATIONS_NOT_PASSED_TESTS_OR_QUALIFIED_ENGINES",
    val completionScope: String = "TERMINAL_STAGE_INCLUDES_UNAVAILABLE_FAILED_AND_INTERRUPTED"
)

@Serializable
internal data class VoiceBenchManifest(
    val schemaVersion: Int = 1,
    val runId: String,
    val startedAtMillis: Long,
    val build: Int,
    val commit: String,
    val androidApi: Int,
    val backendOrderSeed: Long = 0,
    val state: String = "RUNNING",
    val results: List<VoiceBenchResult>,
    val finishedAtMillis: Long? = null,
    val continuationSignature: String? = null,
    val resumedFromRunId: String? = null,
    val reusedStageIndices: List<Int> = emptyList(),
    val scope: String = "On-device, fixed local fixtures. Historical live-session timings are a separate observation window.",
    val unmeasured: List<String> = listOf("Human pronunciation and listening quality", "Physical speaker-to-ear latency", "Provider/network response time", "Real conversation navigation and lock-screen playback", "Accelerator occupancy and kernel dispatch where the SDK exposes no counters")
) {
    val completed: Int get() = results.count { it.status !in setOf(VoiceBenchStatus.PENDING, VoiceBenchStatus.RUNNING) }
    val hasUnfinishedStages: Boolean get() = results.any { it.status in setOf(VoiceBenchStatus.PENDING, VoiceBenchStatus.RUNNING, VoiceBenchStatus.INTERRUPTED) }

    fun coverage() = VoiceBenchCoverage(
        results.size,
        VoiceBenchStatus.entries.associate { status -> status.name to results.count { it.status == status } },
        results.count { it.measurements > 0 },
        results.sumOf { it.measurements },
        results.filter { it.status == VoiceBenchStatus.UNAVAILABLE }.groupingBy { it.reason ?: "REASON_NOT_RECORDED" }.eachCount()
    )

    fun canContinue(signature: String, build: Int, commit: String, api: Int, rows: List<VoiceBenchResult>): Boolean =
        state in setOf("CANCELLED", "INTERRUPTED", "THERMAL_LIMIT") &&
            hasUnfinishedStages &&
            continuationSignature == signature &&
            this.build == build &&
            this.commit == commit &&
            androidApi == api &&
            results.map { it.label } == rows.map { it.label }

    fun reusableStages(rows: List<VoiceBenchResult>): List<Int> = results.indices.filter { index ->
        rows[index].status == VoiceBenchStatus.PENDING &&
            results[index].status in
            setOf(VoiceBenchStatus.MEASURED, VoiceBenchStatus.PARTIAL, VoiceBenchStatus.FAILED)
    }

    fun update(index: Int, result: VoiceBenchResult) = copy(results = results.toMutableList().also { it[index] = result })
    fun stopped(reason: String, now: Long? = null) = copy(
        state = reason,
        finishedAtMillis = now,
        results = results.map { if (it.status == VoiceBenchStatus.RUNNING) it.copy(status = VoiceBenchStatus.INTERRUPTED, reason = reason) else it }
    )
}

internal fun VoiceSpeechBenchmarkReport.suiteResult(label: String): VoiceBenchResult {
    val states = results.map { it.state } + engineResults.map { it.state }
    val playbackObserved = playbackResults.any { it.state == VoiceSpeechPlaybackState.COMPLETE && it.chunks > 0 || it.quality == VoiceSpeechPlaybackQuality.CANCELLATION_VALIDATED }
    val playbackIncomplete = configuration.playback &&
        playbackResults.any {
            it.quality !in setOf(VoiceSpeechPlaybackQuality.PLAYBACK_VALIDATED, VoiceSpeechPlaybackQuality.STREAMING_PLAYBACK_OBSERVED, VoiceSpeechPlaybackQuality.CANCELLATION_VALIDATED)
        }
    val status = when {
        state in setOf(VoiceSpeechBenchmarkRunState.CANCELLED, VoiceSpeechBenchmarkRunState.INTERRUPTED) -> VoiceBenchStatus.INTERRUPTED
        issue == VoiceSpeechBenchmarkIssue.MODEL_UNAVAILABLE || states.isNotEmpty() && states.all { it == VoiceSpeechBenchmarkBackendState.UNSUPPORTED } -> VoiceBenchStatus.UNAVAILABLE
        configuration.playback && playbackResults.isNotEmpty() && playbackResults.all { it.state == VoiceSpeechPlaybackState.UNSUPPORTED } -> VoiceBenchStatus.UNAVAILABLE
        configuration.playback && !playbackObserved -> VoiceBenchStatus.FAILED
        issue != null && completedSamples == 0 -> VoiceBenchStatus.FAILED
        issue != null || playbackIncomplete || states.distinct().size > 1 || VoiceSpeechBenchmarkBackendState.PARTIAL in states -> VoiceBenchStatus.PARTIAL
        state == VoiceSpeechBenchmarkRunState.COMPLETE && completedSamples > 0 && states.all { it == VoiceSpeechBenchmarkBackendState.COMPLETE } -> VoiceBenchStatus.MEASURED
        else -> VoiceBenchStatus.FAILED
    }
    val summary = configuration.backends.mapNotNull { backend ->
        val times = when {
            configuration.playback -> playbackResults.filter { it.requestedBackend == backend && it.state == VoiceSpeechPlaybackState.COMPLETE && it.scenario == VoiceSpeechPlaybackScenario.COLD_START && !it.warmup }.mapNotNull { it.firstPresentedAudioMs }
            configuration.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO -> results.firstOrNull { it.requestedBackend == backend }?.samples.orEmpty().filter { it.validatedAudio && it.fixture == VoiceSpeechBenchmarkFixture.SHORT && it.repeat > 0 }.map { it.firstPcmMs }
            else -> engineResults.firstOrNull { it.requestedBackend == backend }?.samples.orEmpty().filter { it.validatedAudio && it.fixture == VoiceSpeechBenchmarkFixture.SHORT && it.repeat > 0 }.mapNotNull { it.firstPcmMs }
        }
        val median = VoiceSpeechBenchmarkBackendResult.median(times.map(Long::toDouble)) ?: return@mapNotNull null
        val kind = if (configuration.playback) "cold first presented audio" else "warm short phrase to first PCM"
        VoiceBenchMetric("${backend.label} · $kind", median, "ms", times.size)
    }
    val reason = issue?.name ?: when {
        status == VoiceBenchStatus.INTERRUPTED -> outcome.name
        configuration.playback && status == VoiceBenchStatus.UNAVAILABLE -> "PLAYBACK_BACKEND_UNAVAILABLE"
        configuration.playback && !playbackObserved -> "NO_SUCCESSFUL_PLAYBACK_OBSERVATIONS"
        playbackIncomplete -> "PLAYBACK_QUALITY_INCOMPLETE"
        else -> outcome.name
    }
    return VoiceBenchResult(label, status, reason, completedSamples, summary, recommendation()?.label, measuredStreamingCandidate()?.label)
}
