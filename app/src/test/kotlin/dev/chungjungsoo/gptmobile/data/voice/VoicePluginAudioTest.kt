package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePluginAudioTest {
    private class SystemAudio : VoiceAudio {
        var starts = 0
        var stops = 0
        var closes = 0
        val spoken = mutableListOf<String>()
        var cooldown = 0L
        var listener: VoiceAudio.Listener? = null
        override fun retryAfterMillis() = cooldown
        override fun listen(listener: VoiceAudio.Listener) {
            starts++
            this.listener = listener
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            spoken += text
        }
        override fun stop() {
            stops++
        }
        override fun close() {
            closes++
        }
    }

    private class LocalInput : VoiceRecognitionInput {
        override var languageTag = "en-CA"
        var starts = 0
        var stops = 0
        var closes = 0
        var failure: String? = null
        override fun listen(listener: VoiceAudio.Listener) {
            starts++
            failure?.let(listener::error) ?: listener.ready()
        }
        override fun stop() {
            stops++
        }
        override fun close() {
            closes++
        }
    }

    private val system = SystemAudio()
    private val local = LocalInput()
    private var retired = 0
    private val audio = VoicePluginAudio(system, local) { retired++ }
    private val listener = object : VoiceAudio.Listener {
        override fun ready() = Unit
        override fun level(value: Float) = Unit
        override fun partial(text: String) = Unit
        override fun final(text: String) = Unit
        override fun error(message: String) = Unit
    }

    @Test fun localFailureNeverStartsSystemRecognition() {
        local.failure = "Model is missing"
        audio.listen(listener)
        assertEquals(1, local.starts)
        assertEquals(0, system.starts)
        assertEquals(1, retired)
    }

    @Test fun localSelectionIgnoresSystemQuotaWithoutResettingIt() {
        system.cooldown = 60_000
        assertEquals(0, audio.retryAfterMillis())
        audio.listen(listener)
        assertEquals(1, local.starts)
        audio.recognitionBackend = VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE
        assertEquals(60_000, audio.retryAfterMillis())
        assertEquals(60_000, system.cooldown)
    }

    @Test fun selectingSystemIsExplicitAndStopsLocalCapture() {
        audio.recognitionBackend = VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE
        audio.listen(listener)
        assertEquals(1, system.starts)
        assertEquals(0, local.starts)
        assertTrue(local.stops > 0)
    }

    @Test fun spokenReplyStopsLocalInputAndUsesExistingOutput() {
        audio.listen(listener)
        val stopped = local.stops
        audio.speak("A reply from the selected profile", {}, {})
        assertEquals(stopped + 1, local.stops)
        assertEquals(listOf("A reply from the selected profile"), system.spoken)
        assertEquals(0, system.starts)
    }

    @Test fun closeIsTerminalAndReleasesBothEnginesOnce() {
        audio.close()
        audio.close()
        audio.listen(listener)
        audio.recognitionBackend = VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE
        audio.speak("Must not play", {}, {})
        assertEquals(1, local.closes)
        assertEquals(1, system.closes)
        assertEquals(0, local.starts)
        assertEquals(0, system.starts)
        assertTrue(system.spoken.isEmpty())
    }

    @Test fun explicitEngineChangePreservesDraftAndFencesOldCallbacks() {
        audio.recognitionBackend = VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE
        val submitted = mutableListOf<String>()
        val session = VoiceSession(audio, {
            submitted += it
            true
        }, {}).apply { autoContinue = true }
        session.access(true, true, true)
        session.resume()
        val old = requireNotNull(system.listener)
        old.ready()
        old.partial("Keep my words")
        system.cooldown = 60_000
        old.repairRequired("Android is limited", 60_000, VoiceRecoveryAction.SPEECH_SERVICE)
        session.recognitionConfigurationChanged()
        audio.recognitionBackend = VoiceRecognitionBackend.LOCAL_MOONSHINE
        local.failure = "No model installed"
        session.resume()
        assertEquals(VoicePhase.REVIEWING, session.state.phase)
        assertEquals("Keep my words", session.state.transcript)
        assertEquals(0, local.starts)
        assertEquals(null, session.state.recoveryAction)
        old.final("Must never be sent")
        assertTrue(submitted.isEmpty())
        session.sendDraft()
        assertEquals(listOf("Keep my words"), submitted)
    }

    @Test fun sharedMicrophoneOwnershipRejectsOtherEnginesAndStaleRelease() {
        val first = "local-${System.nanoTime()}"
        val second = "system-${System.nanoTime()}"
        try {
            assertTrue(VoiceMicrophoneLease.acquire(first))
            assertFalse(VoiceMicrophoneLease.acquire(first))
            assertFalse(VoiceMicrophoneLease.acquire(second))
            VoiceMicrophoneLease.release(second)
            assertFalse(VoiceMicrophoneLease.acquire(second))
            VoiceMicrophoneLease.release(first)
            assertTrue(VoiceMicrophoneLease.acquire(second))
        } finally {
            VoiceMicrophoneLease.release(first)
            VoiceMicrophoneLease.release(second)
        }
    }

    @Test fun missingOrUnknownSavedBackendUsesLocalWithoutSilentSystemFallback() {
        assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, VoiceRecognitionBackend.fromSaved(null))
        assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, VoiceRecognitionBackend.fromSaved("uninstalled"))
        assertEquals(VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE, VoiceRecognitionBackend.fromSaved("ANDROID_SPEECH_SERVICE"))
    }
}
