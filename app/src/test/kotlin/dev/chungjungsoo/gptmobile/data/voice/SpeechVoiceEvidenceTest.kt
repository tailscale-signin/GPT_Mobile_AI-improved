package dev.chungjungsoo.gptmobile.data.voice

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechVoiceEvidenceTest {
    @Test fun receiptsSurviveReopeningOnlyForTheActualBuildModelVoiceAndConfiguredDelegate() {
        val values = mutableMapOf<String, Boolean>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean(any(), any()) } answers {
            values[firstArg()] = secondArg()
            editor
        }
        val preferences = mockk<SharedPreferences>()
        every { preferences.edit() } returns editor
        every { preferences.getBoolean(any(), false) } answers { values[firstArg()] == true }
        val model = MediaPipeSpeechCatalog.model(MediaPipeSpeechEngine.KOKORO)
        val cpu = VoiceSpeechBackend.LITERT_KOKORO_CPU
        val first = SpeechVoiceEvidence(preferences, "first-build")
        assertFalse(first.has(model, cpu, "jm_kumo"))
        first.record(model, cpu, "jm_kumo")
        val reopened = SpeechVoiceEvidence(preferences, "first-build")
        assertTrue(reopened.has(model, cpu, "jm_kumo"))
        assertFalse(reopened.has(model, cpu, "jf_alpha"))
        assertFalse(reopened.has(model, VoiceSpeechBackend.LITERT_KOKORO_GPU, "jm_kumo"))
        assertFalse(reopened.has(MediaPipeSpeechCatalog.model(MediaPipeSpeechEngine.KOKORO_GPU), cpu, "jm_kumo"))
        assertFalse(SpeechVoiceEvidence(preferences, "next-build").has(model, cpu, "jm_kumo"))
        val gpu = VoiceSpeechBackend.LITERT_KOKORO_GPU
        val oldRuntime = SpeechVoiceEvidence(preferences, "first-build", "old-install")
        val updated = SpeechVoiceEvidence(preferences, "first-build", "new-install")
        oldRuntime.record(model, gpu, "ff_siwis")
        assertTrue(oldRuntime.has(model, gpu, "ff_siwis"))
        assertFalse(updated.has(model, gpu, "ff_siwis"))
        assertTrue(updated.has(model, cpu, "jm_kumo"))
        every { preferences.getBoolean(any(), false) } throws ClassCastException("malformed evidence")
        assertFalse(reopened.has(model, cpu, "jm_kumo"))
    }
}
