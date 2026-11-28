package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptionAlignmentPreferenceTest {
    @Test fun hiddenCaptionsStayDisabledThroughDeferredCreationWarmupAndVoiceChanges() {
        val configurations = mutableListOf<Boolean>()
        val spoken = mutableListOf<Pair<KokoroVoice, Boolean>>()
        var alignmentEnabled = true
        var creations = 0
        val output = DeferredKokoroOutput {
            creations++
            object : KokoroOutput {
                override fun configureCaptionAlignment(enabled: Boolean) {
                    alignmentEnabled = enabled
                    configurations += enabled
                }
                override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) {
                    spoken += voice to alignmentEnabled
                    complete()
                }
                override fun stop() = Unit
                override fun close() = Unit
            }
        }
        output.bindSpokenRange { _, _, _ -> }
        output.bindTimedSpokenRange { _, _, _, _ -> }
        output.configureCaptionAlignment(false)
        assertEquals(0, creations)
        assertTrue(configurations.isEmpty())
        output.prewarm(KokoroVoice.HEART)
        output.speak("Hello.", KokoroVoice.HEART, {}, { message, _ -> error(message) })
        output.speak("Hello.", KokoroVoice.ADAM, {}, { message, _ -> error(message) })
        assertEquals(listOf(false), configurations)
        assertEquals(listOf(KokoroVoice.HEART to false, KokoroVoice.ADAM to false), spoken)
        output.configureCaptionAlignment(true)
        output.speak("Hello.", KokoroVoice.HEART, {}, { message, _ -> error(message) })
        assertEquals(listOf(false, true), configurations)
        assertEquals(KokoroVoice.HEART to true, spoken.last())
        assertEquals(1, creations)
        output.close()
    }

    @Test fun defaultCaptionPolicyRemainsAvailableToPlaybackBenchmarksAndClosedOwnersIgnoreEdits() {
        val configurations = mutableListOf<Boolean>()
        val output = DeferredKokoroOutput {
            object : KokoroOutput {
                override fun configureCaptionAlignment(enabled: Boolean) {
                    configurations += enabled
                }
                override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) = Unit
                override fun stop() = Unit
                override fun close() = Unit
            }
        }
        output.prewarm(KokoroVoice.HEART)
        assertEquals(listOf(true), configurations)
        output.close()
        output.configureCaptionAlignment(false)
        assertEquals(listOf(true), configurations)
    }
}
