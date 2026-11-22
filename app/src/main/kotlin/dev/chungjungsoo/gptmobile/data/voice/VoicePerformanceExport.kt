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
    val document = buildJsonObject {
        put("schemaVersion", 1)
        put("scope", "current_app_process")
        put("seriesWindow", "since_last_performance_reset")
        put("latestSynthesisWindow", "most_recent_synthesis_may_precede_series_reset")
        put("sampleWindow", "bounded_recent_samples_per_stage_provider_and_warm_state")
        put("timingUnit", "milliseconds")
        put("pairedSpeedComparison", false)
        put("acousticSpeakerLatency", JsonNull)
        put("batteryEnergy", JsonNull)
        put("captureQueueMs", snapshot.captureQueueMs?.let(::JsonPrimitive) ?: JsonNull)
        put("thermalStatus", snapshot.thermalStatus?.let(::JsonPrimitive) ?: JsonNull)
        put("processMemoryKb", snapshot.processMemoryKb?.let(::JsonPrimitive) ?: JsonNull)
        put("observedFrameSamples", snapshot.sampledFrames)
        put(
            "series",
            buildJsonArray {
                for (row in snapshot.rows) {
                    if (row.metric !in VoicePerformanceHistory.METRICS || row.engine !in VoicePerformanceHistory.ENGINES || row.count !in 1..256 || row.p50Ms !in 0..86_400_000L || row.p95Ms !in row.p50Ms..86_400_000L) continue
                    add(
                        buildJsonObject {
                            put("metric", row.metric)
                            put("observedProvider", row.engine)
                            put("warm", row.warm?.let(::JsonPrimitive) ?: JsonNull)
                            put("samples", row.count)
                            put("p50Ms", row.p50Ms)
                            put("p95Ms", row.p95Ms)
                        }
                    )
                }
            }
        )
        put(
            "latestSynthesis",
            latest?.takeIf { it.generatedAudioMs > 0 }?.let { sample ->
                buildJsonObject {
                    put("observedProvider", sample.executionProvider.takeIf { it in setOf("cpu", "qnn-gpu", "qnn-htp", "gateway-cuda") }?.let(::JsonPrimitive) ?: JsonNull)
                    put("route", sample.route.takeIf { it in setOf("speaker", "earpiece", "bluetooth", "usb", "wired", "other") }?.let(::JsonPrimitive) ?: JsonNull)
                    put("clockBasis", sample.clockBasis.takeIf { it in setOf("audio_timestamp", "playback_head") }?.let(::JsonPrimitive) ?: JsonNull)
                    put("warm", sample.warm)
                    put("synthesisMs", sample.synthesisMs)
                    put("generatedAudioMs", sample.generatedAudioMs)
                    put("bufferedAudioMs", sample.bufferedAudioMs)
                    put("threads", sample.threads)
                    put("underruns", sample.underruns)
                    put("captionOffsetMs", sample.captionOffsetMs)
                    put("bufferReserveMs", sample.bufferMs)
                    put("productionRatio", sample.productionRatio?.takeIf { it.isFinite() && it >= 0 }?.let(::JsonPrimitive) ?: JsonNull)
                    put("firstAudioMs", sample.firstAudioMs?.let(::JsonPrimitive) ?: JsonNull)
                    put("cancellationMs", sample.cancellationMs?.let(::JsonPrimitive) ?: JsonNull)
                    put(
                        "executionEvidence",
                        sample.executionEvidence?.let { evidence ->
                            buildJsonObject {
                                put("selectedBackend", evidence.selectedBackend.name)
                                put("effectiveProvider", evidence.effectiveProvider.takeIf { it in setOf("cpu", "qnn-gpu", "qnn-htp", "gateway-cuda") }?.let(::JsonPrimitive) ?: JsonNull)
                                put("stage", evidence.stage.name)
                                put("failure", evidence.failure?.name?.let(::JsonPrimitive) ?: JsonNull)
                                put("audioTimingValidated", evidence.verifiedAudio)
                                put("acceleratorKernelEvents", evidence.acceleratorKernels)
                                put("cpuKernelEvents", evidence.cpuKernels)
                                put("profileScope", "first_inference_of_exact_graph_shape")
                                put("prepareMs", evidence.prepareMs)
                                put("synthesisMs", evidence.synthesisMs)
                            }
                        } ?: JsonNull
                    )
                }
            } ?: JsonNull
        )
    }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), document)
}
