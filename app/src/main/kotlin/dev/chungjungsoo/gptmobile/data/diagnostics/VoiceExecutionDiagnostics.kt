package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Closed execution observations; selected hardware alone never proves the provider that ran. */
internal object VoiceExecutionDiagnostics {
    const val FEATURE = "VOICE_EXECUTION_EVIDENCE_V1"
    const val STREAMING_BACKEND_FEATURE = "VOICE_STREAMING_BACKEND_EVIDENCE_V1"
    const val STREAMING_PACK_FEATURE = "VOICE_STREAMING_PACK_EVIDENCE_V1"
    const val STREAMING_NPU_FEATURE = "VOICE_STREAMING_NPU_EVIDENCE_V1"
    val playbackFailures = setOf(
        "AUDIO_FOCUS_DENIED", "AUDIO_FOCUS_LOST", "AUDIO_FOCUS_TIMEOUT", "AUDIO_FOCUS_RESUME_FAILED",
        "TIMEOUT", "MEMORY_PRESSURE", "TEXT_QUEUE_FULL", "SYNTHESIS_FAILED", "ENGINE_FAILED", "AUDIO_FAILED",
        "PRONUNCIATION_UNAVAILABLE", "LEXICON_UNAVAILABLE"
    )
    val enums = mapOf(
        "voice_selected_backend" to setOf("AUTO", "CPU", "QNN_GPU", "QNN_HTP", "GATEWAY_CUDA", "LITERT_KOKORO_CPU", "LITERT_KOKORO_GPU", "LITERT_KOKORO_NPU", "LITERT_KOKORO_GPU_OPTIMIZED", "LITERT_KOKORO_SM8750", "PHONON_CPU", "POCKET_CPU"),
        "voice_effective_provider" to setOf("SYSTEM", "KOKORO_CPU", "QNN_GPU", "QNN_HTP", "GATEWAY_CUDA"),
        "voice_fallback_source" to setOf("KOKORO", "KOKORO_CPU", "QNN_GPU", "QNN_HTP", "GATEWAY_CUDA", "LITERT_KOKORO", "PHONON", "POCKET"),
        "voice_fallback_target" to setOf("SYSTEM", "KOKORO_CPU"),
        "voice_fallback_outcome" to setOf("STARTED", "SUCCEEDED", "FAILED"),
        "voice_fallback_failure" to playbackFailures
    )
    val fields = enums.keys + "voice_fallback_count"

    private val streamingBackendValues = mapOf(
        "voice_engine" to setOf("MEDIAPIPE"),
        "voice_selected_backend" to setOf("LITERT_KOKORO_CPU", "LITERT_KOKORO_GPU", "LITERT_KOKORO_NPU", "LITERT_KOKORO_GPU_OPTIMIZED", "LITERT_KOKORO_SM8750", "PHONON_CPU", "POCKET_CPU"),
        "voice_fallback_source" to setOf("LITERT_KOKORO", "PHONON", "POCKET")
    )

    /** Existing field names alone do not require support for newly introduced enum values. */
    fun requiresStreamingBackendSupport(values: Map<String, JsonElement>): Boolean =
        streamingBackendValues.any { (field, choices) ->
            (values[field] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull in choices
        }

    fun validValue(key: String, value: JsonElement): JsonElement? {
        val primitive = value as? JsonPrimitive ?: return null
        enums[key]?.let { choices ->
            return primitive.takeIf { it.isString }?.contentOrNull?.takeIf { it in choices }?.let(::JsonPrimitive)
        }
        if (key != "voice_fallback_count" || primitive.isString) return null
        return primitive.longOrNull?.takeIf { it in 0L..100L }?.let(::JsonPrimitive)
    }
}
