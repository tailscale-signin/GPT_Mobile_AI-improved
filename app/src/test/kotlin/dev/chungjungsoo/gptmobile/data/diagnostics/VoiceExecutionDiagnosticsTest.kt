package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceExecutionDiagnosticsTest {
    @Test fun streamingCapabilityIsRequiredOnlyForNewClosedValuesInTheirOwnFields() {
        val additions = mapOf(
            "voice_engine" to setOf("MEDIAPIPE"),
            "voice_selected_backend" to setOf("LITERT_KOKORO_CPU", "LITERT_KOKORO_GPU", "PHONON_CPU", "POCKET_CPU"),
            "voice_fallback_source" to setOf("LITERT_KOKORO", "PHONON", "POCKET")
        )
        for ((field, choices) in additions) {
            for (value in choices) {
                assertTrue(VoiceExecutionDiagnostics.requiresStreamingBackendSupport(mapOf(field to JsonPrimitive(value))))
                for (unrelated in listOf("voice_effective_provider", "voice_fallback_target", "private_value")) {
                    assertFalse(VoiceExecutionDiagnostics.requiresStreamingBackendSupport(mapOf(unrelated to JsonPrimitive(value))))
                }
            }
            for (invalid in listOf(JsonPrimitive("private speech or request-id"), JsonPrimitive(1), JsonPrimitive(true), JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()))) {
                assertFalse(VoiceExecutionDiagnostics.requiresStreamingBackendSupport(mapOf(field to invalid)))
            }
        }
        val legacy = VoiceExecutionDiagnostics.enums + ("voice_engine" to setOf("SYSTEM", "LOCAL", "KOKORO"))
        for ((field, choices) in legacy) {
            for (value in choices - additions[field].orEmpty()) {
                assertFalse(VoiceExecutionDiagnostics.requiresStreamingBackendSupport(mapOf(field to JsonPrimitive(value))))
            }
        }
        assertFalse(VoiceExecutionDiagnostics.requiresStreamingBackendSupport(emptyMap()))
    }

    @Test fun selectedBackendAndObservedProviderHaveDifferentClosedVocabularies() {
        assertEquals(JsonPrimitive("AUTO"), VoiceExecutionDiagnostics.validValue("voice_selected_backend", JsonPrimitive("AUTO")))
        assertNull(VoiceExecutionDiagnostics.validValue("voice_effective_provider", JsonPrimitive("AUTO")))
        assertNull(VoiceExecutionDiagnostics.validValue("voice_effective_provider", JsonPrimitive("CPU")))
        assertEquals(JsonPrimitive("KOKORO_CPU"), VoiceExecutionDiagnostics.validValue("voice_effective_provider", JsonPrimitive("KOKORO_CPU")))
        assertEquals(JsonPrimitive("KOKORO"), VoiceExecutionDiagnostics.validValue("voice_fallback_source", JsonPrimitive("KOKORO")))
        assertNull(VoiceExecutionDiagnostics.validValue("voice_effective_provider", JsonPrimitive("KOKORO")))
        for (selected in listOf("LITERT_KOKORO_CPU", "LITERT_KOKORO_GPU", "PHONON_CPU", "POCKET_CPU")) {
            assertEquals(JsonPrimitive(selected), VoiceExecutionDiagnostics.validValue("voice_selected_backend", JsonPrimitive(selected)))
            assertNull(VoiceExecutionDiagnostics.validValue("voice_effective_provider", JsonPrimitive(selected)))
        }
    }

    @Test fun fallbackFailureAndOutcomeAreIndependentClosedObservations() {
        for ((field, choices) in VoiceExecutionDiagnostics.enums) {
            for (choice in choices) assertEquals(JsonPrimitive(choice), VoiceExecutionDiagnostics.validValue(field, JsonPrimitive(choice)))
            for (privateValue in listOf(JsonPrimitive("private speech or request-id"), JsonPrimitive(1), JsonPrimitive(true), JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()))) {
                assertNull(VoiceExecutionDiagnostics.validValue(field, privateValue))
            }
        }
        assertNull(VoiceExecutionDiagnostics.validValue("voice_fallback_target", JsonPrimitive("QNN_HTP")))
        assertNull(VoiceExecutionDiagnostics.validValue("voice_provider_request_id", JsonPrimitive("private-id")))
    }

    @Test fun gatewayFallbackRetainsTheRemoteSourceWithoutExpandingRecoveryTargets() {
        for (field in listOf("voice_selected_backend", "voice_effective_provider", "voice_fallback_source")) {
            assertEquals(JsonPrimitive("GATEWAY_CUDA"), VoiceExecutionDiagnostics.validValue(field, JsonPrimitive("GATEWAY_CUDA")))
        }
        assertNull(VoiceExecutionDiagnostics.validValue("voice_fallback_target", JsonPrimitive("GATEWAY_CUDA")))
        for (unclosed in listOf("gateway-cuda", "CUDA", "https://private.gateway.example/voice?request=private-id")) {
            assertNull(VoiceExecutionDiagnostics.validValue("voice_fallback_source", JsonPrimitive(unclosed)))
        }
    }

    @Test fun fallbackCountRetainsOnlyBoundedTypedIntegers() {
        for (count in listOf(0, 1, 100)) assertEquals(JsonPrimitive(count), VoiceExecutionDiagnostics.validValue("voice_fallback_count", JsonPrimitive(count)))
        for (invalid in listOf(JsonPrimitive(-1), JsonPrimitive(101), JsonPrimitive(Long.MAX_VALUE), JsonPrimitive("1"), JsonPrimitive(1.5), JsonPrimitive(false), JsonNull)) {
            assertNull(VoiceExecutionDiagnostics.validValue("voice_fallback_count", invalid))
        }
    }
}
