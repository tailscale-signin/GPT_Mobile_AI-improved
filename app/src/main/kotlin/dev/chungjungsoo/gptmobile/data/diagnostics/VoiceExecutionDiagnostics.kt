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
    const val FALLBACK_CAUSES_FEATURE = "VOICE_FALLBACK_CAUSES_V1"
    private val systemFailures = setOf(
        "INITIALIZATION_FAILED", "CATALOG_UNAVAILABLE", "OFFLINE_VOICE_UNAVAILABLE", "LANGUAGE_UNAVAILABLE", "VOICE_UNAVAILABLE",
        "SERVICE_FAILED", "NETWORK_FAILED", "NETWORK_TIMEOUT", "INVALID_REQUEST", "VOICE_NOT_INSTALLED"
    )
    val playbackFailures = setOf(
        "AUDIO_FOCUS_DENIED", "AUDIO_FOCUS_LOST", "AUDIO_FOCUS_TIMEOUT", "AUDIO_FOCUS_RESUME_FAILED",
        "TIMEOUT", "MEMORY_PRESSURE", "TEXT_QUEUE_FULL", "SYNTHESIS_FAILED", "ENGINE_FAILED", "AUDIO_FAILED",
        "PRONUNCIATION_UNAVAILABLE", "LEXICON_UNAVAILABLE"
    ) + systemFailures
    private val stages = setOf("PREPARATION", "SYNTHESIS", "PLAYBACK", "INITIALIZATION", "CATALOG", "AUDIO_FOCUS")
    val causeHashes = setOf("voice_primary_cause_fingerprint", "voice_fallback_cause_fingerprint")
    val fallbackCauseFields = causeHashes + setOf(
        "voice_primary_failure_stage",
        "voice_fallback_failure_stage",
        "voice_fallback_terminal_failure",
        "voice_failure_requested_backend",
        "voice_fallback_source_observation",
        "voice_system_error_code"
    )
    private val requestedBackends = setOf("AUTO", "CPU", "QNN_GPU", "QNN_HTP", "GATEWAY_CUDA", "LITERT_KOKORO_CPU", "LITERT_KOKORO_GPU", "LITERT_KOKORO_NPU", "LITERT_KOKORO_GPU_OPTIMIZED", "LITERT_KOKORO_SM8750", "PHONON_CPU", "POCKET_CPU")
    val enums = mapOf(
        "voice_selected_backend" to requestedBackends,
        "voice_effective_provider" to setOf("SYSTEM", "KOKORO_CPU", "QNN_GPU", "QNN_HTP", "GATEWAY_CUDA"),
        "voice_fallback_source" to setOf("KOKORO", "KOKORO_CPU", "QNN_GPU", "QNN_HTP", "GATEWAY_CUDA", "LITERT_KOKORO", "PHONON", "POCKET"),
        "voice_fallback_target" to setOf("SYSTEM", "KOKORO_CPU"),
        "voice_fallback_outcome" to setOf("STARTED", "SUCCEEDED", "FAILED"),
        "voice_fallback_failure" to playbackFailures,
        "voice_primary_failure_stage" to stages,
        "voice_fallback_failure_stage" to stages,
        "voice_fallback_terminal_failure" to playbackFailures,
        "voice_failure_requested_backend" to requestedBackends,
        "voice_fallback_source_observation" to setOf("INITIALIZED", "NOT_INITIALIZED")
    )
    val fields = enums.keys + fallbackCauseFields + "voice_fallback_count"

    fun requiresFallbackCauseSupport(values: Map<String, JsonElement>, omitted: Collection<String>): Boolean =
        (values.keys + omitted).any { it in fallbackCauseFields } ||
            listOf("voice_playback_failure", "voice_fallback_failure").any { (values[it] as? JsonPrimitive)?.contentOrNull in systemFailures }

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
        if (key in causeHashes) return primitive.takeIf { it.isString && it.content.matches(Regex("[a-f0-9]{64}")) }
        if (key == "voice_system_error_code") return primitive.takeUnless { it.isString }?.longOrNull?.takeIf { it in -9L..-1L }?.let(::JsonPrimitive)
        enums[key]?.let { choices ->
            return primitive.takeIf { it.isString }?.contentOrNull?.takeIf { it in choices }?.let(::JsonPrimitive)
        }
        if (key != "voice_fallback_count" || primitive.isString) return null
        return primitive.longOrNull?.takeIf { it in 0L..100L }?.let(::JsonPrimitive)
    }
}
