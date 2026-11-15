package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVoiceDiagnosticClassificationTest {
    @Test fun unavailablePronunciationAndLexiconAreActionablePlaybackFailures() {
        for (reason in listOf("pronunciation_unavailable", "lexicon_unavailable")) {
            val failure = classifyDiagnostic("I", "VoiceTiming", "kokoro_terminal · reason=$reason · knownWords=4 · unknownWords=1")
            assertEquals("voice_playback_failure", failure.category)
            assertTrue(failure.actionable)
        }
        assertFalse(classifyDiagnostic("I", "VoiceTiming", "kokoro_terminal · reason=complete · unknownWords=0").actionable)
    }

    @Test fun documentedSherpaHeaderAndCompleteTypedDumpsAreConfigurationRatherThanFailures() {
        // k2-fsa/sherpa-onnx jni/offline-tts.cc emits config:\n%s through LOGE.
        val records = listOf(
            "config:",
            "OfflineTtsConfig(model=OfflineTtsModelConfig(kokoro=OfflineTtsKokoroModelConfig(model=\"model.onnx\", voices=\"voices.bin\")))",
            "config:\nOfflineTtsConfig(model=OfflineTtsModelConfig(provider=\"cpu\"))",
            "OfflineTtsKokoroModelConfig(model=\"path with (balanced).onnx\")"
        )
        for (record in records) {
            val result = classifyDiagnostic("E", "Android", "eventId=record-1 · monoNs=42 · E/sherpa-onnx(123): $record")
            assertEquals("native_voice_configuration", result.category)
            assertFalse(result.actionable)
        }
    }

    @Test fun configurationNamesCannotDowngradeRealFailuresOrUnrecognizedFragments() {
        for (record in listOf(
            "Errors found in config!",
            "Failed to load OfflineTtsConfig(model=missing)",
            "OfflineTtsConfig(model=missing) failed validation",
            "OfflineTtsConfig(model=missing)\nFailed to allocate 4096 bytes",
            "OfflineTtsConfig(model=OfflineTtsModelConfig(",
            "config: model validation failed",
            "Unsupported native configuration header"
        )) {
            assertTrue(record, classifyDiagnostic("E", "Android", "E/sherpa-onnx(123): $record").actionable)
        }
        assertTrue(classifyDiagnostic("E", "Android", "E/OtherNative(123): OfflineTtsConfig(model=\"cpu\")").actionable)
        assertEquals("critical", classifyDiagnostic("F", "Android", "F/sherpa-onnx(123): OfflineTtsConfig(model=\"cpu\")").severity)
        assertEquals("critical", classifyDiagnostic("E", "AppCrash", "OfflineTtsConfig(model=\"cpu\")").severity)
    }

    @Test fun typedMoonshineCaptureFailureCleanupAndCancellationRemainDistinct() {
        val failure = classifyDiagnostic("W", "VoiceEngine", "MOONSHINE_FAILURE · failureCode=MICROPHONE_CAPTURE_FAILED · cancelled=false")
        assertEquals("voice_capture_or_recognition_failure", failure.category)
        assertTrue(failure.actionable)
        for (record in listOf("MOONSHINE_RELEASED · failureCode=NONE", "MOONSHINE_DECODER_CLOSE · cancelled=false", "MOONSHINE_FAILURE · failureCode=NONE · cancelled=true")) {
            assertEquals("native_voice_cleanup", classifyDiagnostic("I", "VoiceEngine", record).category)
            assertFalse(classifyDiagnostic("I", "VoiceEngine", record).actionable)
        }
        assertEquals("native_transcription_success", classifyDiagnostic("E", "Android", "E/MoonshineJni(123): Transcription error: 0").category)
        assertEquals("native_operation_failure", classifyDiagnostic("E", "Android", "E/MoonshineJni(123): Transcription error: -4").category)
    }
}
