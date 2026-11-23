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

internal fun speechBenchmarkJson(report: VoiceSpeechBenchmarkReport, local: Boolean = false): JsonObject = buildJsonObject {
    put("schemaVersion", 2)
    put("fixtureVersion", report.configuration.fixtureVersion)
    nullable("playbackFixture", "LONG".takeIf { report.configuration.playback })
    nullable("playbackPacing", "BALANCED_NATURAL".takeIf { report.configuration.playback })
    put("measurement", if (report.configuration.playback) "production_pipeline_playback_on_this_device" else "planned_phrase_synthesis_on_this_device")
    put("coldScope", "fresh_engine_session_os_file_cache_not_cleared")
    put("warmScope", "repeated_workload_each_chunk_records_actual_shape_cache_use")
    put("firstPcmBasis", "first_complete_native_chunk_of_fixture")
    put("kernelProfileScope", "identified_first_shape_inference_reused_profiles_are_not_new_measurements")
    put("summaryKernelScope", "last_chunk_profile_see_chunks_for_individual_shapes")
    put("medianConvention", "average_middle_pair_for_even_samples")
    put("percentileConvention", "nearest_rank_small_sample_descriptive_only")
    put("playbackCancellationBasis", "audio_stop_acknowledgement_before_native_retirement")
    put("captionValidationScope", "production_caption_word_coverage_and_clock_consistency_not_acoustic_alignment")
    put("orderingProtocol", "recorded_cyclic_rotation_across_runs_not_randomized_within_run")
    put("plannedBackendOrder", buildJsonArray { report.configuration.plannedBackendOrder.forEach { add(JsonPrimitive(it.name)) } })
    put("playbackLatencyMs", JsonNull)
    put("acousticLatencyMs", JsonNull)
    put("audioUnderruns", JsonNull)
    put("batteryEnergy", JsonNull)
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
            if (local) nullable("localDeviceKey", c.deviceKey.takeIf { it.matches(SHA256) })
        }
    )
    put(
        "results",
        buildJsonArray {
            for (r in report.results) {
                add(
                    buildJsonObject {
                        put("requestedBackend", r.requestedBackend.name)
                        put("state", r.state.name)
                        nullable("sessionPrepareMs", r.sessionPrepareMs)
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
                        put("incompleteChunks", buildJsonArray { r.incompleteChunks.forEach { add(chunkJson(it)) } })
                        put("executionVerified", r.executionVerified)
                        put("verifiedRequestedBackend", r.verified)
                        put("qualifiedComparison", r in report.qualifiedResults())
                        put("measuredCostMs", r.measuredCostMs)
                        put("warmSampleCount", r.warmSamples.size)
                        put("medianWarmMs", number(r.medianMs))
                        nullable("p95WarmMs", r.p95Ms)
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
                                for (s in r.samples) {
                                    add(
                                        buildJsonObject {
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
                                    )
                                }
                            }
                        )
                    }
                )
            }
        }
    )
    put("playbackResults", buildJsonArray { report.playbackResults.forEach { add(playbackJson(it)) } })
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
    put("audioRms", number(o.audioRms))
    put("audioPeak", number(o.audioPeak))
    nullable("clippedSamples", o.clippedSamples)
    nullable("nonSilentSamples", o.nonSilentSamples)
}

private fun readFailureObservation(o: JsonObject) = VoiceSpeechBenchmarkFailureObservation(
    profileAvailable = o.optionalBoolean("profileAvailable"), profileId = o.optionalBoundedLong("profileId", Long.MAX_VALUE),
    profileReused = o.optionalBoolean("profileReused"), acceleratorMicros = o.optionalBoundedLong("acceleratorMicros", 3_600_000_000) ?: 0,
    cpuMicros = o.optionalBoundedLong("cpuMicros", 3_600_000_000) ?: 0, graphPrepareMs = o.boundedLong("graphPrepareMs"),
    currentPrepareMs = o.optionalBoundedLong("currentPrepareMs"), synthesisMs = o.boundedLong("synthesisMs"), tokenCount = o.boundedLong("tokenCount", 511).toInt(),
    hardwareType = o.optionalLong("hardwareType")?.also { require(it in -1..1000) }?.toInt(), cacheHit = o.optionalBoolean("cacheHit"),
    cachedShapeCount = o.optionalBoundedLong("cachedShapeCount", 32)?.toInt(), cacheLimit = o.optionalBoundedLong("cacheLimit", 32)?.toInt(),
    cacheEstimatedBytes = o.optionalBoundedLong("cacheEstimatedBytes", 16L * 1024 * 1024 * 1024),
    audioRms = o.optionalDouble("audioRms", 1_000_000.0), audioPeak = o.optionalDouble("audioPeak", 1_000_000.0),
    clippedSamples = o.optionalBoundedLong("clippedSamples", 720_000), nonSilentSamples = o.optionalBoundedLong("nonSilentSamples", 720_000)
)

private fun playbackJson(r: VoiceSpeechPlaybackResult) = buildJsonObject {
    put("requestedBackend", r.requestedBackend.name)
    put("state", r.state.name)
    nullable("firstPcmMs", r.firstPcmMs)
    nullable("firstQueuedPcmMs", r.firstQueuedPcmMs)
    nullable("firstPresentedAudioMs", r.firstPresentedAudioMs)
    put("elapsedMs", r.elapsedMs)
    put("totalSynthesisMs", r.totalSynthesisMs)
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
    nullable("thermalBefore", r.thermalBefore)
    nullable("thermalAfter", r.thermalAfter)
    nullable("failure", r.failure?.name)
    nullable("failureStage", r.failureStage?.name)
}

/** Legacy runs remain readable, but carry their old protocol and cannot qualify current settings. */
internal fun speechBenchmarkReportFromJson(value: JsonObject): VoiceSpeechBenchmarkReport? = runCatching {
    val schema = value.getValue("schemaVersion").jsonPrimitive.int.also { require(it in 1..2) }
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
        playback = c.optionalBoolean("playback") ?: false
    )
    val results = value.getValue("results").jsonArray.also { require(it.size == configuration.backends.size) }.map { raw ->
        val r = raw.jsonObject
        VoiceSpeechBenchmarkBackendResult(
            requestedBackend = VoiceSpeechBackend.valueOf(r.string("requestedBackend")),
            state = VoiceSpeechBenchmarkBackendState.valueOf(r.string("state")),
            sessionPrepareMs = r.optionalBoundedLong("sessionPrepareMs"),
            samples = r.getValue("samples").jsonArray.also { require(it.size <= 18) }.map { sample ->
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
            failureObservation = r["failureObservation"]?.takeUnless { it == JsonNull }?.jsonObject?.let(::readFailureObservation)
        ).also { row ->
            require(row.incompleteChunks.isEmpty() || (row.incompleteFixture != null && row.incompleteRepeat != null))
            require(row.state != VoiceSpeechBenchmarkBackendState.COMPLETE || (row.failure == null && row.incompleteChunks.isEmpty()))
        }
    }
    require(results.map { it.requestedBackend } == configuration.backends)
    require(results.all { row -> row.samples.map { it.fixture to it.repeat }.distinct().size == row.samples.size })
    val playback = value["playbackResults"]?.jsonArray.orEmpty().also { require(it.size <= 3) }.map { readPlayback(it.jsonObject) }
    require(playback.map { it.requestedBackend }.distinct().size == playback.size && playback.all { it.requestedBackend in configuration.backends })
    require(configuration.playback || playback.isEmpty())
    VoiceSpeechBenchmarkReport(
        startedAtMillis = value.getValue("startedAtMillis").jsonPrimitive.long.also { require(it >= 0) },
        configuration = configuration,
        state = VoiceSpeechBenchmarkRunState.valueOf(value.string("state")),
        results = results,
        finishedAtMillis = value.optionalLong("finishedAtMillis")?.also { require(it >= 0) },
        issue = value.optionalString("issue")?.let(VoiceSpeechBenchmarkIssue::valueOf),
        playbackResults = playback
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
        discoverySummary = c.optionalDiscovery("discoverySummary")
    ).also { row ->
        require(row.profileAvailable != false || (row.acceleratorKernels == 0 && row.cpuKernels == 0 && row.acceleratorMicros == 0L && row.cpuMicros == 0L))
        require(row.cacheHit != true || row.currentPrepareMs == 0L)
    }
}

private fun readPlayback(r: JsonObject) = VoiceSpeechPlaybackResult(
    requestedBackend = VoiceSpeechBackend.valueOf(r.string("requestedBackend")), state = VoiceSpeechPlaybackState.valueOf(r.string("state")),
    firstPcmMs = r.optionalBoundedLong("firstPcmMs"), firstQueuedPcmMs = r.optionalBoundedLong("firstQueuedPcmMs"), firstPresentedAudioMs = r.optionalBoundedLong("firstPresentedAudioMs"),
    elapsedMs = r.boundedLong("elapsedMs"), totalSynthesisMs = r.boundedLong("totalSynthesisMs"), totalAudioMs = r.boundedLong("totalAudioMs"),
    chunks = r.boundedLong("chunks", 1000).toInt(), underruns = r.optionalBoundedLong("underruns")?.toInt(),
    bufferRestarts = r.boundedLong("bufferRestarts").toInt(), longestRefillMs = r.boundedLong("longestRefillMs"), cancellationMs = r.optionalBoundedLong("cancellationMs"),
    captionObservations = r.boundedLong("captionObservations").toInt(), captionClockRegressions = r.boundedLong("captionClockRegressions").toInt(), captionTimingViolations = r.boundedLong("captionTimingViolations").toInt(),
    clockBasis = r.optionalString("clockBasis")?.also { require(it in CLOCKS) }, route = r.optionalString("route")?.also { require(it in ROUTES) },
    effectiveProvider = r.optionalProvider("effectiveProvider"), acceleratorExecutionObserved = r.getValue("acceleratorExecutionObserved").jsonPrimitive.boolean,
    thermalBefore = r.thermal("thermalBefore"), thermalAfter = r.thermal("thermalAfter"),
    failure = r.optionalString("failure")?.let(VoiceSpeechFailureCode::valueOf), failureStage = r.optionalString("failureStage")?.let(VoiceSpeechStage::valueOf),
    validatedChunks = r.optionalBoundedLong("validatedChunks", 1000)?.toInt() ?: 0,
    captionWordsExpected = r.optionalBoundedLong("captionWordsExpected", 256)?.toInt() ?: 0,
    captionWordsObserved = r.optionalBoundedLong("captionWordsObserved", 256)?.toInt() ?: 0
)

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
private fun JsonObject.positiveDouble(key: String, maximum: Double) = getValue(key).jsonPrimitive.double.also { require(it.isFinite() && it > 0 && it <= maximum) }
private fun JsonObject.optionalDouble(key: String, maximum: Double) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.double?.also { require(it.isFinite() && it in 0.0..maximum) }
