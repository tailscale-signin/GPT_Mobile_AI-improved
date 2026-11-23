package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceExecutionDiagnosticsTest {
    @Test fun selectedBackendAndObservedProviderHaveDifferentClosedVocabularies() {
        assertEquals(JsonPrimitive("AUTO"), VoiceExecutionDiagnostics.validValue("voice_selected_backend", JsonPrimitive("AUTO")))
        assertNull(VoiceExecutionDiagnostics.validValue("voice_effective_provider", JsonPrimitive("AUTO")))
        assertNull(VoiceExecutionDiagnostics.validValue("voice_effective_provider", JsonPrimitive("CPU")))
        assertEquals(JsonPrimitive("KOKORO_CPU"), VoiceExecutionDiagnostics.validValue("voice_effective_provider", JsonPrimitive("KOKORO_CPU")))
        assertEquals(JsonPrimitive("KOKORO"), VoiceExecutionDiagnostics.validValue("voice_fallback_source", JsonPrimitive("KOKORO")))
        assertNull(VoiceExecutionDiagnostics.validValue("voice_effective_provider", JsonPrimitive("KOKORO")))
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
