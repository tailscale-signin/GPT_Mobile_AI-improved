package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

/** Fixed fixtures/labels and measured numbers; no speech, PCM, transcript, serial or raw exception. */
internal fun voiceSpeechBenchmarkExport(report: VoiceSpeechBenchmarkReport): String =
    Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), speechBenchmarkJson(report))

internal fun speechBenchmarkJson(report: VoiceSpeechBenchmarkReport, local: Boolean = false): JsonObject = buildJsonObject {
    put("schemaVersion", 1)
    put("fixtureVersion", 1)
    put("measurement", "silent_phrase_synthesis_on_this_device")
    put("coldScope", "fresh_engine_session_and_first_use_of_each_phrase_shape_os_file_cache_not_cleared")
    put("firstPcmBasis", "complete_phrase_returned_by_native_generate")
    put("kernelProfileScope", "first_inference_of_exact_graph_shape_not_each_warm_repeat")
    put("playbackLatencyMs", JsonNull)
    put("acousticLatencyMs", JsonNull)
    put("audioUnderruns", JsonNull)
    put("batteryEnergy", JsonNull)
    put("startedAtMillis", report.startedAtMillis)
    put("finishedAtMillis", report.finishedAtMillis?.let(::JsonPrimitive) ?: JsonNull)
    put("state", report.state.name)
    put("issue", report.issue?.name?.let(::JsonPrimitive) ?: JsonNull)
    put("recommendedBackend", report.recommendation()?.name?.let(::JsonPrimitive) ?: JsonNull)
    put(
        "configuration",
        buildJsonObject {
            val value = report.configuration
            put("mode", value.mode.name)
            put("backends", buildJsonArray { value.backends.forEach { add(JsonPrimitive(it.name)) } })
            put("voice", value.voice.name)
            put("threads", value.threads)
            put("speechRate", value.speechRate.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonNull)
            put("modelSha256", value.modelSha256.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let(::JsonPrimitive) ?: JsonNull)
            put("runtimeId", value.runtimeId.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,96}")) }?.let(::JsonPrimitive) ?: JsonNull)
            put("appBuild", value.appBuild)
            put("sdk", value.sdk)
            if (local) put("localDeviceKey", value.deviceKey.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let(::JsonPrimitive) ?: JsonNull)
        }
    )
    put(
        "results",
        buildJsonArray {
            for (result in report.results) {
                add(
                    buildJsonObject {
                        put("requestedBackend", result.requestedBackend.name)
                        put("state", result.state.name)
                        put("sessionPrepareMs", result.sessionPrepareMs?.takeIf { it in 0..3_600_000L }?.let(::JsonPrimitive) ?: JsonNull)
                        put("failure", result.failure?.name?.let(::JsonPrimitive) ?: JsonNull)
                        put("failureStage", result.failureStage?.name?.let(::JsonPrimitive) ?: JsonNull)
                        put("nativeCode", result.nativeCode?.let(::JsonPrimitive) ?: JsonNull)
                        put("failureEffectiveProvider", provider(result.failureEffectiveProvider))
                        put("failureAcceleratorKernels", result.failureAcceleratorKernels?.let(::JsonPrimitive) ?: JsonNull)
                        put("failureCpuKernels", result.failureCpuKernels?.let(::JsonPrimitive) ?: JsonNull)
                        put("verifiedRequestedBackend", result.verified)
                        put("warmSampleCount", result.warmSamples.size)
                        put("medianWarmMs", result.medianMs?.let(::JsonPrimitive) ?: JsonNull)
                        put("p95WarmMs", result.p95Ms?.let(::JsonPrimitive) ?: JsonNull)
                        put("medianWarmRealTimeFactor", number(result.medianRtf))
                        put(
                            "samples",
                            buildJsonArray {
                                for (sample in result.samples) {
                                    add(
                                        buildJsonObject {
                                            put("fixture", sample.fixture.name)
                                            put("repeat", sample.repeat)
                                            put("warm", sample.warm)
                                            put("generationMs", sample.generationMs)
                                            put("firstPcmMs", sample.firstPcmMs)
                                            put("prepareToFirstPcmMs", sample.prepareToFirstPcmMs?.let(::JsonPrimitive) ?: JsonNull)
                                            put("audioMs", number(sample.audioMs))
                                            put("realTimeFactor", number(sample.realTimeFactor))
                                            put("effectiveProvider", provider(sample.effectiveProvider))
                                            put("acceleratorKernels", sample.acceleratorKernels)
                                            put("cpuKernels", sample.cpuKernels)
                                            put("acceleratorMicros", sample.acceleratorMicros)
                                            put("cpuMicros", sample.cpuMicros)
                                            put("graphPrepareMs", sample.graphPrepareMs)
                                            put("tokenCount", sample.tokenCount)
                                            put("validatedAudio", sample.validatedAudio)
                                            put("thermalBefore", sample.thermalBefore?.takeIf { it in 0..6 }?.let(::JsonPrimitive) ?: JsonNull)
                                            put("thermalAfter", sample.thermalAfter?.takeIf { it in 0..6 }?.let(::JsonPrimitive) ?: JsonNull)
                                            put("hardwareType", sample.hardwareType?.let(::JsonPrimitive) ?: JsonNull)
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
}

private fun provider(value: String?): JsonElement = value?.takeIf { it in setOf("cpu", "qnn-gpu", "qnn-htp") }?.let(::JsonPrimitive) ?: JsonNull
private fun number(value: Double?): JsonElement = value?.takeIf { it.isFinite() && it >= 0 }?.let(::JsonPrimitive) ?: JsonNull

/** Bounded, strict read of app-owned history. A corrupt run never becomes a passing receipt. */
internal fun speechBenchmarkReportFromJson(value: JsonObject): VoiceSpeechBenchmarkReport? = runCatching {
    require(value.getValue("schemaVersion").jsonPrimitive.int == 1)
    val config = value.getValue("configuration").jsonObject
    val configuration = VoiceSpeechBenchmarkConfiguration(
        VoiceSpeechBenchmarkMode.valueOf(config.string("mode")),
        config.getValue("backends").jsonArray.also { require(it.size in 1..3) }.map { VoiceSpeechBackend.valueOf(it.jsonPrimitive.content) },
        KokoroVoice.valueOf(config.string("voice")), config.getValue("threads").jsonPrimitive.int,
        config.getValue("speechRate").jsonPrimitive.float,
        config.string("modelSha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) },
        config.string("runtimeId").also { require(it.matches(Regex("[A-Za-z0-9._-]{1,96}"))) },
        config.getValue("appBuild").jsonPrimitive.int.also { require(it > 0) },
        config.getValue("sdk").jsonPrimitive.int.also { require(it in 1..100) },
        config.string("localDeviceKey").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    )
    val results = value.getValue("results").jsonArray.also { require(it.size == configuration.backends.size) }.map { raw ->
        val result = raw.jsonObject
        VoiceSpeechBenchmarkBackendResult(
            requestedBackend = VoiceSpeechBackend.valueOf(result.string("requestedBackend")),
            state = VoiceSpeechBenchmarkBackendState.valueOf(result.string("state")),
            sessionPrepareMs = result.optionalLong("sessionPrepareMs")?.also { require(it in 0..3_600_000L) },
            samples = result.getValue("samples").jsonArray.also { require(it.size <= 18) }.map { sample ->
                val row = sample.jsonObject
                VoiceSpeechBenchmarkSample(
                    VoiceSpeechBenchmarkFixture.valueOf(row.string("fixture")),
                    row.getValue("repeat").jsonPrimitive.int.also { require(it in 0..configuration.mode.warmRepeats) },
                    row.boundedLong("generationMs"), row.boundedLong("firstPcmMs"), row.optionalLong("prepareToFirstPcmMs")?.also { require(it in 0..3_600_000L) },
                    row.getValue("audioMs").jsonPrimitive.double.also { require(it.isFinite() && it > 0 && it <= 300_000) },
                    row.string("effectiveProvider").also { require(it in setOf("cpu", "qnn-gpu", "qnn-htp")) },
                    row.boundedLong("acceleratorKernels").toInt(), row.boundedLong("cpuKernels").toInt(),
                    row.boundedLong("acceleratorMicros", 3_600_000_000), row.boundedLong("cpuMicros", 3_600_000_000),
                    row.boundedLong("graphPrepareMs"), row.boundedLong("tokenCount", 511).toInt(),
                    row.getValue("validatedAudio").jsonPrimitive.boolean,
                    row.optionalLong("thermalBefore")?.toInt()?.also { require(it in 0..6) },
                    row.optionalLong("thermalAfter")?.toInt()?.also { require(it in 0..6) },
                    row.optionalLong("hardwareType")?.toInt()
                )
            },
            failure = result.optionalString("failure")?.let(VoiceSpeechFailureCode::valueOf),
            failureStage = result.optionalString("failureStage")?.let(VoiceSpeechStage::valueOf),
            nativeCode = result.optionalLong("nativeCode")?.toInt(),
            failureEffectiveProvider = result.optionalString("failureEffectiveProvider")?.also { require(it in setOf("cpu", "qnn-gpu", "qnn-htp")) },
            failureAcceleratorKernels = result.optionalLong("failureAcceleratorKernels")?.toInt(),
            failureCpuKernels = result.optionalLong("failureCpuKernels")?.toInt()
        )
    }
    require(results.map { it.requestedBackend } == configuration.backends)
    require(results.all { row -> row.samples.map { it.fixture to it.repeat }.distinct().size == row.samples.size })
    VoiceSpeechBenchmarkReport(
        value.getValue("startedAtMillis").jsonPrimitive.long,
        configuration,
        VoiceSpeechBenchmarkRunState.valueOf(value.string("state")),
        results,
        value.optionalLong("finishedAtMillis"),
        value.optionalString("issue")?.let(VoiceSpeechBenchmarkIssue::valueOf)
    )
}.getOrNull()

private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.optionalString(key: String) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
private fun JsonObject.optionalLong(key: String) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.long
private fun JsonObject.boundedLong(key: String, maximum: Long = 3_600_000) = getValue(key).jsonPrimitive.long.also { require(it in 0..maximum) }
