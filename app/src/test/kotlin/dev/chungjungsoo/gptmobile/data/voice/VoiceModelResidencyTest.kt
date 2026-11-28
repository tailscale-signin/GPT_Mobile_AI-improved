package dev.chungjungsoo.gptmobile.data.voice

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelResidencyTest {
    @Test fun compiledPocketReopensItsCpuTransformerWhenThreadsChange() {
        for (backend in listOf(VoiceSpeechBackend.POCKET_GPU, VoiceSpeechBackend.POCKET_NPU)) {
            val factory = factory(backend, nativeAssets = false)
            val warm = KokoroVoiceOutput.ModelKey("unused-native-lexicon", 2, factory)
            assertTrue(warm.sameResident(KokoroVoiceOutput.ModelKey("another-unused-lexicon", 2, factory)))
            assertFalse(warm.sameResident(KokoroVoiceOutput.ModelKey("unused-native-lexicon", 4, factory)))
        }
    }

    @Test fun mediaPipeResidencyKeepsItsExistingIndependenceFromNativeThreadAndLexiconSettings() {
        for (backend in listOf(VoiceSpeechBackend.POCKET_CPU, VoiceSpeechBackend.LITERT_KOKORO_CPU, VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_NPU)) {
            val factory = factory(backend, nativeAssets = false)
            val warm = KokoroVoiceOutput.ModelKey("lexicon-a", 2, factory)
            assertTrue(warm.sameResident(KokoroVoiceOutput.ModelKey("lexicon-b", 4, factory)))
        }
    }

    @Test fun nativeResidencyStillRequiresMatchingLexiconThreadsAndRuntimeIdentity() {
        val factory = factory(VoiceSpeechBackend.CPU, nativeAssets = true)
        val warm = KokoroVoiceOutput.ModelKey("lexicon-a", 2, factory)
        assertTrue(warm.sameResident(KokoroVoiceOutput.ModelKey("lexicon-a", 2, factory)))
        assertFalse(warm.sameResident(KokoroVoiceOutput.ModelKey("lexicon-b", 2, factory)))
        assertFalse(warm.sameResident(KokoroVoiceOutput.ModelKey("lexicon-a", 4, factory)))
        assertFalse(warm.sameResident(KokoroVoiceOutput.ModelKey("lexicon-a", 2, factory(VoiceSpeechBackend.CPU, nativeAssets = true, revision = "updated"))))
    }

    private fun factory(backend: VoiceSpeechBackend, nativeAssets: Boolean, revision: String = "current") = object : VoicePhraseEngineFactory {
        override val selectedBackend = backend
        override val requiresKokoroAssets = nativeAssets
        override val identity = "${backend.name}:$revision"
        override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine = error("Residency comparison must not open a native engine")
    }
}
