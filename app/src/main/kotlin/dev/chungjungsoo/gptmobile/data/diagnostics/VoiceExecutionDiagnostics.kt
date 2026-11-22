package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Closed execution observations; selected hardware alone never proves the provider that ran. */
internal object VoiceExecutionDiagnostics {
    const val FEATURE = "VOICE_EXECUTION_EVIDENCE_V1"
    val playbackFailures = setOf(
        "AUDIO_FOCUS_DENIED", "AUDIO_FOCUS_LOST", "AUDIO_FOCUS_TIMEOUT", "AUDIO_FOCUS_RESUME_FAILED",
        "TIMEOUT", "MEMORY_PRESSURE", "TEXT_QUEUE_FULL", "SYNTHESIS_FAILED", "ENGINE_FAILED", "AUDIO_FAILED",
        "PRONUNCIATION_UNAVAILABLE", "LEXICON_UNAVAILABLE"
    )
    val enums = mapOf(
        "voice_selected_backend" to setOf("AUTO", "CPU", "QNN_GPU", "QNN_HTP", "GATEWAY_CUDA"),
        "voice_effective_provider" to setOf("SYSTEM", "KOKORO_CPU", "QNN_GPU", "QNN_HTP", "GATEWAY_CUDA"),
        "voice_fallback_source" to setOf("KOKORO", "KOKORO_CPU", "QNN_GPU", "QNN_HTP"),
        "voice_fallback_target" to setOf("SYSTEM", "KOKORO_CPU"),
        "voice_fallback_outcome" to setOf("STARTED", "SUCCEEDED", "FAILED"),
        "voice_fallback_failure" to playbackFailures
    )
    val fields = enums.keys + "voice_fallback_count"

    fun validValue(key: String, value: JsonElement): JsonElement? {
        val primitive = value as? JsonPrimitive ?: return null
        enums[key]?.let { choices ->
            return primitive.takeIf { it.isString }?.contentOrNull?.takeIf { it in choices }?.let(::JsonPrimitive)
        }
        if (key != "voice_fallback_count" || primitive.isString) return null
        return primitive.longOrNull?.takeIf { it in 0L..100L }?.let(::JsonPrimitive)
    }
}
