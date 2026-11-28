package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun streamingComparisonsJson(report: VoiceSpeechBenchmarkReport): JsonArray = buildJsonArray {
    report.streamingComparisons().forEach { row ->
        add(
            buildJsonObject {
                put("requestedBackend", row.backend.name)
                put("modelSha256", report.configuration.modelSha256)
                put("runtimeId", report.configuration.runtimeId)
                nullable("pcmProcessingId", report.configuration.pcmProcessingId)
                nullable("voiceId", report.configuration.engineVoiceId)
                put("speechRate", report.configuration.speechRate)
                nullable("routeBucket", row.route)
                nullable("outputRouteIdentity", row.outputRouteIdentity)
                put("comparisonScope", "SAME_MODEL_VOICE_RATE_RUNTIME_PROCESSING_FIXTURES_DEVICE_AND_OUTPUT_ROUTE")
                put("processorExecutionEvidence", "NOT_EXPOSED_BY_SDK")
                put("samples", row.samples)
                nullable("medianWarmFirstPresentedMs", row.medianFirstPresentedMs)
                nullable("p95WarmFirstPresentedMs", row.p95FirstPresentedMs)
                nullable("activePlaybackUnderruns", row.activeUnderruns)
                nullable("maximumCancellationMs", row.maximumCancellationMs)
                nullable("captionManifestCoverage", row.captionCoverage)
                put("captionCoverageScope", "TEXT_BOUNDARIES_OBSERVED_NOT_ACOUSTIC_WORD_ACCURACY")
                put("cueProvenance", row.cueProvenance)
                nullable("nativeTimingWords", row.nativeTimingWords)
                nullable("estimatedTimingWords", row.estimatedTimingWords)
                nullable("asrAlignmentTimingWords", row.asrAlignmentTimingWords)
                put("captionAlignmentStatuses", buildJsonArray { row.captionAlignmentStatuses.sorted().forEach { add(JsonPrimitive(it)) } })
                put("asrAlignmentScope", "ASR_DERIVED_AUDIO_TIMING_EXACT_SOURCE_MATCH_NOT_INDEPENDENT_ACOUSTIC_VALIDATION")
                nullable("timingRevisions", row.timingRevisions)
                put("eligibleForLatencyComparison", row.eligibleForLatencyComparison)
                put("exclusions", buildJsonArray { row.exclusions.forEach { add(JsonPrimitive(it)) } })
                put("independentQualityStatus", row.independentQualityStatus)
                put("qualifiedForAutomaticSelection", row.qualifiedForAutomaticSelection)
            }
        )
    }
}

internal fun independentSpeechQualityJson(value: VoiceIndependentSpeechQuality): JsonObject = buildJsonObject {
    put("backend", value.backend.name)
    put("modelSha256", value.modelSha256)
    put("runtimeId", value.runtimeId)
    put("voiceId", value.voiceId)
    put("speechRate", value.speechRate)
    put("pcmProcessingId", value.pcmProcessingId)
    put("route", value.route)
    nullable("outputRouteIdentity", value.outputRouteIdentity)
    put("fixtureHashes", buildJsonArray { value.fixtureHashes.sorted().forEach { add(JsonPrimitive(it)) } })
    nullable("pitchDeviationPercent", value.pitchDeviationPercent)
    put("pitchPairs", value.pitchPairs)
    nullable("wordTimingP95Ms", value.wordTimingP95Ms)
    put("alignedWords", value.alignedWords)
    nullable("pronunciationPassRate", value.pronunciationPassRate)
    put("ratedWords", value.ratedWords)
    put("scope", "INDEPENDENT_REFERENCE_OBSERVATIONS_NOT_SDK_TIMESTAMPS_OR_INFERRED_QUALITY")
}

internal fun readIndependentSpeechQuality(values: JsonElement?, configuration: VoiceSpeechBenchmarkConfiguration): List<VoiceIndependentSpeechQuality> =
    values?.jsonArray.orEmpty().also { require(it.size <= configuration.backends.size) }.map { raw ->
        val value = raw.jsonObject
        val quality = VoiceIndependentSpeechQuality(
            backend = VoiceSpeechBackend.valueOf(value.string("backend")),
            modelSha256 = value.string("modelSha256"),
            runtimeId = value.string("runtimeId"),
            voiceId = value.string("voiceId"),
            speechRate = value.getValue("speechRate").jsonPrimitive.float,
            route = value.string("route"),
            fixtureHashes = value.getValue("fixtureHashes").jsonArray.also { require(it.size in 1..32) }.map { it.jsonPrimitive.content }.toSet(),
            pitchDeviationPercent = value.optionalDouble("pitchDeviationPercent"),
            pitchPairs = value.getValue("pitchPairs").jsonPrimitive.int,
            wordTimingP95Ms = value.optionalDouble("wordTimingP95Ms"),
            alignedWords = value.getValue("alignedWords").jsonPrimitive.int,
            pronunciationPassRate = value.optionalDouble("pronunciationPassRate"),
            ratedWords = value.getValue("ratedWords").jsonPrimitive.int,
            pcmProcessingId = value.string("pcmProcessingId"),
            outputRouteIdentity = value["outputRouteIdentity"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        )
        require(quality.backend in configuration.backends && quality.modelSha256 == configuration.modelSha256 && quality.runtimeId == configuration.runtimeId)
        require(quality.voiceId == configuration.engineVoiceId && quality.speechRate.toBits() == configuration.speechRate.toBits())
        require(quality.pcmProcessingId == configuration.pcmProcessingId)
        quality
    }.also { rows -> require(rows.map { it.backend }.distinct().size == rows.size) }

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.optionalDouble(key: String): Double? = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.double
private fun JsonObjectBuilder.nullable(key: String, value: String?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)
private fun JsonObjectBuilder.nullable(key: String, value: Number?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)
