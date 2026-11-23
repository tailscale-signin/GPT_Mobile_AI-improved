package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Numeric observations and fixed labels only. No transcript, audio, model prompt or device identifier. */
internal fun voicePerformanceExport(snapshot: VoicePerformanceSnapshot, latest: VoiceSynthesisFeedback? = null): String {
    val rows = snapshot.rows.filter {
        it.metric in VoicePerformanceHistory.METRICS &&
            it.engine in VoicePerformanceHistory.ENGINES &&
            it.count in 1..256 &&
            it.observedSamples >= it.count &&
            it.p50Ms in 0..86_400_000L &&
            it.p95Ms in it.p50Ms..86_400_000L
    }
    val synthesis = latest?.takeIf { it.generatedAudioMs > 0 }
    val document = buildJsonObject {
        put("schemaVersion", 3)
        put("scope", "current_app_process")
        put("seriesWindow", "since_last_performance_reset")
        put("latestSynthesisWindow", "most_recent_synthesis_may_precede_series_reset")
        put("sampleWindow", "bounded_recent_samples_per_stage_provider_and_warm_state")
        put("sampleCapacity", snapshot.sampleCapacity.coerceIn(8, 256))
        put("percentileScope", "retained_recent_samples_only")
        put("observedSamplesScope", "since_last_performance_reset")
        put("timingUnit", "milliseconds")
        put("pairedSpeedComparison", false)
        put("acousticSpeakerLatency", JsonNull)
        put("batteryEnergy", JsonNull)
        put("cpuUtilization", JsonNull)
        put("gpuUtilization", JsonNull)
        put("npuUtilization", JsonNull)
        put(
            "availability",
            buildJsonObject {
                put("speechStageTimings", if (rows.any { it.metric !in VoicePerformanceHistory.FRAME_METRICS }) "OBSERVED" else "NO_RECORDED_SAMPLES")
                put("latestSynthesis", if (synthesis != null) "OBSERVED" else "NO_RECORDED_SAMPLE")
                put("graphicsGpuFrameDuration", if (rows.any { it.metric == "gpu_duration" }) "OBSERVED" else "NO_RECORDED_SAMPLES")
                put("endToEndConversationLatency", "NOT_MEASURED")
                put("cpuGpuNpuUtilization", "NOT_MEASURED")
                put("acousticSpeakerLatency", "NOT_MEASURED")
                put("batteryEnergy", "NOT_MEASURED")
            }
        )
        put("gpuFrameDurationScope", "android_graphics_frame_metrics_not_speech_accelerator_execution")
        put("captureQueueMs", snapshot.captureQueueMs?.let(::JsonPrimitive) ?: JsonNull)
        put("thermalStatus", snapshot.thermalStatus?.let(::JsonPrimitive) ?: JsonNull)
        put("processMemoryKb", snapshot.processMemoryKb?.let(::JsonPrimitive) ?: JsonNull)
        put("observedFrameSamples", snapshot.sampledFrames)
        put("frameSampleCountScope", "frame_duration_and_frame_interval_observations_since_last_reset")
        put("droppedFrameMetricCallbacks", snapshot.droppedFrameSamples)
        put(
            "series",
            buildJsonArray {
                for (row in rows) {
                    add(
                        buildJsonObject {
                            put("metric", row.metric)
                            val sdkFamily = row.engine.takeIf { it in MEDIAPIPE_FAMILIES }
                            put("observedProvider", row.engine.takeIf { sdkFamily == null }?.let(::JsonPrimitive) ?: JsonNull)
                            put("modelFamily", sdkFamily?.let(::JsonPrimitive) ?: JsonNull)
                            put("warm", row.warm?.let(::JsonPrimitive) ?: JsonNull)
                            put("samples", row.count)
                            put("observedSamples", row.observedSamples)
                            put("evictedSamples", row.observedSamples - row.count)
                            put("p50Ms", row.p50Ms)
                            put("p95Ms", row.p95Ms)
                        }
                    )
                }
            }
        )
        put(
            "latestSynthesis",
            synthesis?.let { sample ->
                buildJsonObject {
                    val sdk = mediaPipeExecution(sample.executionProvider)
                    val nativeThreads = sample.executionProvider in setOf("cpu", "qnn-gpu", "qnn-htp")
                    put("observedProvider", sample.executionProvider.takeIf { it in setOf("cpu", "qnn-gpu", "qnn-htp", "gateway-cuda") }?.let(::JsonPrimitive) ?: JsonNull)
                    put("modelFamily", sdk?.first?.let(::JsonPrimitive) ?: JsonNull)
                    put("requestedDelegate", sdk?.second?.let(::JsonPrimitive) ?: JsonNull)
                    put("route", sample.route.takeIf { it in setOf("speaker", "earpiece", "bluetooth", "usb", "wired", "other") }?.let(::JsonPrimitive) ?: JsonNull)
                    put("clockBasis", sample.clockBasis.takeIf { it in setOf("audio_timestamp", "playback_head") }?.let(::JsonPrimitive) ?: JsonNull)
                    put("warm", sample.warm)
                    put("synthesisMs", sample.synthesisMs)
                    put("generatedAudioMs", sample.generatedAudioMs)
                    put("bufferedAudioMs", sample.bufferedAudioMs)
                    put("threads", sample.threads.takeIf { nativeThreads }?.let(::JsonPrimitive) ?: JsonNull)
                    put(
                        "threadsScope",
                        when {
                            nativeThreads -> "configured_native_cpu_threads"
                            sdk != null -> "not_exposed_by_sdk"
                            sample.executionProvider == "gateway-cuda" -> "managed_by_gateway"
                            else -> "unknown"
                        }
                    )
                    put("underruns", sample.underruns)
                    put("captionOffsetMs", sample.captionOffsetMs)
                    put("bufferReserveMs", sample.bufferMs)
                    put("productionRatio", sample.productionRatio?.takeIf { it.isFinite() && it >= 0 }?.let(::JsonPrimitive) ?: JsonNull)
                    put("firstAudioMs", sample.firstAudioMs?.let(::JsonPrimitive) ?: JsonNull)
                    put("firstAudioScope", "playback_operation_start_to_first_observed_presented_audio")
                    put("cancellationMs", sample.cancellationMs?.let(::JsonPrimitive) ?: JsonNull)
                    put(
                        "executionEvidence",
                        sample.executionEvidence?.let(::performanceExecutionJson) ?: JsonNull
                    )
                }
            } ?: JsonNull
        )
    }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), document)
}

private val MEDIAPIPE_FAMILIES = setOf("MEDIAPIPE", "MEDIAPIPE_KOKORO", "MEDIAPIPE_PHONON", "MEDIAPIPE_POCKET")

private fun mediaPipeExecution(provider: String): Pair<String, String>? = when (provider) {
    "mediapipe-kokoro-cpu-requested" -> "MEDIAPIPE_KOKORO" to "CPU"
    "mediapipe-kokoro-gpu-requested" -> "MEDIAPIPE_KOKORO" to "GPU"
    "mediapipe-phonon-cpu-requested" -> "MEDIAPIPE_PHONON" to "CPU"
    "mediapipe-pocket-cpu-requested" -> "MEDIAPIPE_POCKET" to "CPU"
    else -> null
}

private fun performanceExecutionJson(evidence: VoiceSpeechExecutionEvidence) = buildJsonObject {
    fun number(name: String, value: Long?, maximum: Long = Long.MAX_VALUE) {
        put(name, value?.takeIf { it in 0..maximum }?.let(::JsonPrimitive) ?: JsonNull)
    }
    fun flag(name: String, value: Boolean?) {
        put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    }
    fun amplitude(name: String, value: Double?) {
        put(name, value?.takeIf { it.isFinite() && it in 0.0..1_000_000.0 }?.let(::JsonPrimitive) ?: JsonNull)
    }
    val profiled = evidence.profileAvailable == true
    put("selectedBackend", evidence.selectedBackend.name)
    put("effectiveProvider", evidence.effectiveProvider.takeIf { it in setOf("cpu", "qnn-gpu", "qnn-htp", "gateway-cuda") }?.let(::JsonPrimitive) ?: JsonNull)
    put("stage", evidence.stage.name)
    put("failure", evidence.failure?.name?.let(::JsonPrimitive) ?: JsonNull)
    put("audioTimingValidated", evidence.verifiedAudio)
    flag("profileAvailable", evidence.profileAvailable)
    put(
        "profileScope",
        when (evidence.profileAvailable) {
            true -> "first_inference_of_exact_graph_shape"
            false -> "not_collected"
            null -> "unknown"
        }
    )
    number("profileId", evidence.profileId?.takeIf { profiled && it > 0 })
    flag("profileReused", evidence.profileReused?.takeIf { profiled })
    number("acceleratorKernelEvents", evidence.acceleratorKernels.toLong().takeIf { profiled })
    number("cpuKernelEvents", evidence.cpuKernels.toLong().takeIf { profiled })
    number("acceleratorMicros", evidence.acceleratorMicros.takeIf { profiled }, 3_600_000_000)
    number("cpuMicros", evidence.cpuMicros.takeIf { profiled }, 3_600_000_000)
    put("prepareScope", "initial_prepare_of_exact_graph_shape")
    number("prepareMs", evidence.prepareMs, 3_600_000)
    number("currentPrepareMs", evidence.currentPrepareMs, 3_600_000)
    number("synthesisMs", evidence.synthesisMs, 3_600_000)
    number("tokenCount", evidence.tokenCount.toLong(), 511)
    flag("graphPreparedThisCall", evidence.graphPreparedThisCall)
    flag("cacheHit", evidence.cacheHit)
    number("cachedShapeCount", evidence.cachedShapeCount?.toLong(), 32)
    number("cacheLimit", evidence.cacheLimit?.toLong(), 32)
    number("cacheEstimatedBytes", evidence.cacheEstimatedBytes, 16L * 1024 * 1024 * 1024)
    number("cacheBudgetBytes", evidence.cacheBudgetBytes, 16L * 1024 * 1024 * 1024)
    number("activeShapeEstimatedBytes", evidence.activeShapeEstimatedBytes, 16L * 1024 * 1024 * 1024)
    number("availableMemoryBytes", evidence.availableMemoryBytes, 256L * 1024 * 1024 * 1024)
    put("cacheAdmission", evidence.cacheAdmission?.takeIf { it in setOf("CPU_SINGLE", "MEMORY_UNKNOWN", "MEMORY_PRESSURE", "BOUNDED_TWO_SHAPES") }?.let(::JsonPrimitive) ?: JsonNull)
    amplitude("audioRms", evidence.audioRms)
    amplitude("audioPeak", evidence.audioPeak)
    number("clippedSamples", evidence.clippedSamples, 720_000)
    number("nonSilentSamples", evidence.nonSilentSamples, 720_000)
}
