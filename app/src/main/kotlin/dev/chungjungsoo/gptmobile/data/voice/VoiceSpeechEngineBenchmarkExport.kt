package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** SDK callback measurements are kept separate from native provider and token-duration proof. */
internal fun speechEngineBenchmarkJson(result: VoiceSpeechEngineBenchmarkResult, includeSamples: Boolean = true): JsonObject = buildJsonObject {
    put("requestedBackend", result.requestedBackend.name)
    put("state", result.state.name)
    nullable("sessionPrepareMs", result.sessionPrepareMs)
    nullable("preparationThermalBefore", result.preparationThermalBefore)
    nullable("failureCode", result.failureCode?.takeIf { it.matches(CODE) })
    nullable("failedFixture", result.failedFixture?.name)
    nullable("failedRepeat", result.failedRepeat)
    nullable("failedAttemptMs", result.failedAttemptMs)
    nullable("cancellationMs", result.cancellationMs)
    nullable("nativeRetirementMs", result.nativeRetirementMs)
    put("nativeRetirementScope", "native_handle_close_call_duration")
    put("cleanupState", result.cleanupState)
    put("requestedDelegate", result.requestedDelegate)
    put("observedProvider", JsonNull)
    put("providerEvidenceStatus", "NOT_EXPOSED_BY_SDK")
    nullable("timeoutScope", result.timeoutScope?.takeIf { it in TIMEOUT_SCOPES })
    nullable("timeoutLimitMs", result.timeoutLimitMs)
    nullable("timeoutElapsedMs", result.timeoutElapsedMs)
    put("measuredCostMs", result.measuredCostMs)
    put("qualityScope", "STRUCTURAL_PCM_ONLY_SEMANTIC_AND_PERCEPTUAL_QUALITY_NOT_MEASURED")
    put("samples", buildJsonArray { result.samples.takeIf { includeSamples }.orEmpty().forEach { add(engineSampleJson(it)) } })
    put("partialSample", result.partialSample?.let(::engineSampleJson) ?: JsonNull)
    put(
        "fixtureStatistics",
        buildJsonArray {
            VoiceSpeechBenchmarkFixture.entries.forEach { fixture ->
                val samples = result.samples.filter { it.fixture == fixture && it.validatedAudio }
                val warm = samples.filter { it.warm }
                val times = warm.map { it.generationMs }.sorted()
                add(
                    buildJsonObject {
                        put("fixture", fixture.name)
                        put("sampleCount", samples.size)
                        put("warmSampleCount", warm.size)
                        number("medianWarmMs", median(times.map { it.toDouble() }))
                        number("medianFirstPcmMs", median(warm.mapNotNull { it.firstPcmMs?.toDouble() }))
                        number("medianWarmRtf", median(warm.mapNotNull { it.realTimeFactor }))
                        nullable("p95WarmMs", times.takeIf { it.size >= VoiceSpeechBenchmarkBackendResult.MIN_PERCENTILE_SAMPLES }?.let { it[(kotlin.math.ceil(it.size * .95).toInt() - 1).coerceAtLeast(0)] })
                        put("p95Availability", if (times.size >= VoiceSpeechBenchmarkBackendResult.MIN_PERCENTILE_SAMPLES) "MEASURED" else "INSUFFICIENT_SAMPLES")
                    }
                )
            }
        }
    )
}

internal fun engineSampleJson(sample: VoiceSpeechEngineBenchmarkSample): JsonObject = buildJsonObject {
    put("fixture", sample.fixture.name)
    put("repeat", sample.repeat)
    put("warm", sample.warm)
    put("inputBytes", sample.inputBytes)
    put("inputSha256", sample.inputSha256)
    put("generationMs", sample.generationMs)
    nullable("firstPcmMs", sample.firstPcmMs)
    nullable("lastPcmMs", sample.lastPcmMs)
    nullable("prepareToFirstPcmMs", sample.prepareToFirstPcmMs)
    put("audioFrames", sample.audioFrames)
    number("audioMs", sample.audioMs)
    number("realTimeFactor", sample.realTimeFactor)
    put("pcmChunks", sample.pcmChunks)
    put("streamCalls", sample.streamCalls)
    nullable("maximumChunkGapMs", sample.maximumChunkGapMs)
    put("validatedAudio", sample.validatedAudio)
    nullable("thermalBefore", sample.thermalBefore)
    nullable("thermalAfter", sample.thermalAfter)
    number("audioRms", sample.audioRms)
    number("audioPeak", sample.audioPeak)
    put("clippedSamples", sample.clippedSamples)
    put("nonSilentSamples", sample.nonSilentSamples)
    put("omittedPcmReceipts", sample.omittedPcmReceipts)
    put(
        "pcmTimeline",
        buildJsonArray {
            sample.pcmTimeline.forEach { receipt ->
                add(
                    buildJsonObject {
                        put("index", receipt.index)
                        put("receivedAtMs", receipt.receivedAtMs)
                        put("frames", receipt.frames)
                        put("streamCall", receipt.streamCall)
                    }
                )
            }
        }
    )
}

internal fun readSpeechEngineBenchmark(value: JsonObject, configuration: VoiceSpeechBenchmarkConfiguration): VoiceSpeechEngineBenchmarkResult {
    require(configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO)
    val backend = VoiceSpeechBackend.valueOf(value.string("requestedBackend")).also { require(it in configuration.backends) }
    val samples = value.getValue("samples").jsonArray.also { require(it.size <= VoiceSpeechBenchmarkFixture.entries.size * (configuration.mode.warmRepeats + 1)) }
        .map { readEngineSample(it.jsonObject, configuration) }
    require(samples.map { it.fixture to it.repeat }.distinct().size == samples.size)
    val partial = value["partialSample"]?.takeUnless { it == JsonNull }?.jsonObject?.let { readEngineSample(it, configuration) }
    require(partial == null || samples.none { it.fixture == partial.fixture && it.repeat == partial.repeat })
    require(value.optionalString("observedProvider") == null)
    require(value.string("providerEvidenceStatus") == "NOT_EXPOSED_BY_SDK")
    require(value.string("requestedDelegate") == requestedSpeechDelegate(backend))
    return VoiceSpeechEngineBenchmarkResult(
        requestedBackend = backend,
        state = VoiceSpeechBenchmarkBackendState.valueOf(value.string("state")),
        sessionPrepareMs = value.optionalTime("sessionPrepareMs"),
        preparationThermalBefore = value.optionalLong("preparationThermalBefore", 6)?.toInt(),
        samples = samples,
        partialSample = partial,
        failureCode = value.optionalString("failureCode")?.also { require(it.matches(CODE)) },
        failedFixture = value.optionalString("failedFixture")?.let(VoiceSpeechBenchmarkFixture::valueOf),
        failedRepeat = value.optionalLong("failedRepeat", configuration.mode.warmRepeats.toLong())?.toInt(),
        failedAttemptMs = value.optionalTime("failedAttemptMs"),
        cancellationMs = value.optionalTime("cancellationMs"),
        nativeRetirementMs = value.optionalTime("nativeRetirementMs"),
        cleanupState = value.string("cleanupState").also { require(it in CLEANUP_STATES) },
        requestedDelegate = requestedSpeechDelegate(backend),
        observedProvider = null,
        providerEvidenceStatus = "NOT_EXPOSED_BY_SDK",
        timeoutScope = value.optionalString("timeoutScope")?.also { require(it in TIMEOUT_SCOPES) },
        timeoutLimitMs = value.optionalTime("timeoutLimitMs"),
        timeoutElapsedMs = value.optionalTime("timeoutElapsedMs")
    ).also { result ->
        require(result.state != VoiceSpeechBenchmarkBackendState.COMPLETE || (result.failureCode == null && partial == null))
    }
}

private fun readEngineSample(value: JsonObject, configuration: VoiceSpeechBenchmarkConfiguration): VoiceSpeechEngineBenchmarkSample {
    val frames = value.long("audioFrames", 24_000L * 960)
    val chunks = value.long("pcmChunks", 16_384).toInt()
    val calls = value.long("streamCalls", VoiceSpeechWorkload.MAX_CHUNKS.toLong()).toInt()
    val generationMs = value.long("generationMs", MAX_TIME)
    val timeline = value.getValue("pcmTimeline").jsonArray.also { require(it.size <= 128) }.mapIndexed { index, raw ->
        val receipt = raw.jsonObject
        VoiceSpeechEnginePcmReceipt(
            receipt.long("index", 127).toInt().also { require(it == index) },
            receipt.long("receivedAtMs", generationMs),
            receipt.long("frames", 24_000L * 960).toInt().also { require(it > 0) },
            receipt.long("streamCall", (calls - 1L).coerceAtLeast(0)).toInt()
        )
    }
    val omitted = value.long("omittedPcmReceipts", 16_384).toInt()
    require(timeline.size + omitted == chunks)
    require(timeline.zipWithNext().all { (first, second) -> first.receivedAtMs <= second.receivedAtMs && first.streamCall <= second.streamCall })
    require(timeline.sumOf { it.frames.toLong() } <= frames)
    require(omitted != 0 || timeline.sumOf { it.frames.toLong() } == frames)
    val first = value.optionalLong("firstPcmMs", generationMs)
    val last = value.optionalLong("lastPcmMs", generationMs)
    require((chunks == 0 && first == null && last == null && frames == 0L) || (chunks > 0 && calls > 0 && first != null && last != null && first <= last))
    require(timeline.isEmpty() || first == timeline.first().receivedAtMs)
    return VoiceSpeechEngineBenchmarkSample(
        fixture = VoiceSpeechBenchmarkFixture.valueOf(value.string("fixture")),
        repeat = value.long("repeat", configuration.mode.warmRepeats.toLong()).toInt(),
        inputBytes = value.long("inputBytes", 1_000_000).toInt(),
        inputSha256 = value.string("inputSha256").also { require(it.matches(SHA256)) },
        generationMs = generationMs, firstPcmMs = first, lastPcmMs = last,
        prepareToFirstPcmMs = value.optionalTime("prepareToFirstPcmMs"),
        audioFrames = frames, pcmChunks = chunks, streamCalls = calls,
        maximumChunkGapMs = value.optionalLong("maximumChunkGapMs", generationMs),
        validatedAudio = value.getValue("validatedAudio").jsonPrimitive.boolean,
        thermalBefore = value.optionalLong("thermalBefore", 6)?.toInt(), thermalAfter = value.optionalLong("thermalAfter", 6)?.toInt(),
        audioRms = value.optionalDouble("audioRms"), audioPeak = value.optionalDouble("audioPeak"),
        clippedSamples = value.long("clippedSamples", frames), nonSilentSamples = value.long("nonSilentSamples", frames),
        pcmTimeline = timeline, omittedPcmReceipts = omitted
    ).also { require(!it.validatedAudio || (frames > 0 && it.nonSilentSamples > 0 && it.audioRms != null && it.audioPeak != null)) }
}

private const val MAX_TIME = 86_400_000L
private val CODE = Regex("[A-Z][A-Z0-9_]{0,79}")
private val SHA256 = Regex("[0-9a-f]{64}")
private val CLEANUP_STATES = setOf("NOT_OBSERVED", "PENDING", "RETIRED", "FAILED")
private val TIMEOUT_SCOPES = setOf("PREPARATION", "CHUNK", "BACKEND", "preparation", "chunk", "backend")
private fun JsonObjectBuilder.nullable(key: String, value: String?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)
private fun JsonObjectBuilder.nullable(key: String, value: Long?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)
private fun JsonObjectBuilder.nullable(key: String, value: Int?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)
private fun JsonObjectBuilder.number(key: String, value: Double?) = put(key, value?.takeIf { it.isFinite() && it >= 0 }?.let(::JsonPrimitive) ?: JsonNull)
private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.optionalString(key: String) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
private fun JsonObject.long(key: String, maximum: Long) = getValue(key).jsonPrimitive.long.also { require(it in 0..maximum) }
private fun JsonObject.optionalLong(key: String, maximum: Long) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.long?.also { require(it in 0..maximum) }
private fun JsonObject.optionalTime(key: String) = optionalLong(key, MAX_TIME)
private fun JsonObject.optionalDouble(key: String) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.double?.also { require(it.isFinite() && it in 0.0..1_000_000.0) }
private fun median(values: List<Double>): Double? = values.sorted().takeIf { it.isNotEmpty() }?.let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
