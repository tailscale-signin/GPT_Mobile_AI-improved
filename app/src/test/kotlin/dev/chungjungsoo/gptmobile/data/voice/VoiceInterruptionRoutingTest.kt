package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInterruptionRoutingTest {
    @Test fun recognitionWaitsForTheMonitorLeaseAndCancelledHandoffsCannotRestartIt() {
        val input = Input().apply { deferRelease = true }
        val audio = VoicePluginAudio(SystemAudio(), input) {}
        val listener = object : VoiceAudio.Listener {
            override fun ready() = Unit
            override fun level(value: Float) = Unit
            override fun partial(text: String) = Unit
            override fun final(text: String) = Unit
            override fun error(message: String) = Unit
        }
        audio.listen(listener)
        assertEquals(0, input.listening)
        val cancelled = input.released.single()
        audio.stop()
        cancelled()
        assertEquals(0, input.listening)
        audio.listen(listener)
        input.released.last()()
        assertEquals(1, input.listening)
        audio.close()
    }

    @Test fun systemRecognitionDoesNotCaptureAnUnusablePreRoll() {
        val input = Input()
        val output = Output()
        val audio = VoicePluginAudio(SystemAudio(), input, output) {}.apply {
            kokoroVoice = KokoroVoice.HEART
            recognitionBackend = VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE
        }
        audio.configureInterruption(true) {}
        audio.speak("Reply", {}, {})
        requireNotNull(output.started)()
        assertEquals(VoiceInterruptionStatus.UNAVAILABLE, audio.interruptionState.value)
        assertEquals(0, input.started.size)
        audio.close()
    }

    @Test fun monitorStartsAtActualPlaybackAndCompletionRetiresLateAudioCallbacks() {
        val input = Input()
        val output = Output()
        val audio = VoicePluginAudio(SystemAudio(), input, output) {}.apply { kokoroVoice = KokoroVoice.HEART }
        var interruptions = 0
        audio.configureInterruption(true) { interruptions++ }
        audio.speak("First reply", {}, {})
        assertEquals(0, input.started.size)
        val started = requireNotNull(output.started)
        started()
        started()
        assertEquals(1, input.started.size)
        val staleDetection = input.started.single()
        requireNotNull(output.completed)()
        staleDetection()
        started()
        assertEquals(0, interruptions)
        assertEquals(1, input.started.size)
        audio.close()
    }

    @Test fun togglingHandsFreeFencesTheRetiredMonitorWithinTheSameReply() {
        val input = Input()
        val output = Output()
        val audio = VoicePluginAudio(SystemAudio(), input, output) {}.apply { kokoroVoice = KokoroVoice.HEART }
        var interruptions = 0
        audio.configureInterruption(true) { interruptions++ }
        audio.speak("Reply", {}, {})
        requireNotNull(output.started)()
        val staleDetection = input.started.single()
        audio.configureInterruption(false) { interruptions++ }
        audio.configureInterruption(true) { interruptions++ }
        assertEquals(2, input.started.size)
        staleDetection()
        assertEquals(0, interruptions)
        input.started.last()()
        input.started.last()()
        assertEquals(1, interruptions)
        audio.close()
    }

    private class Input :
        VoiceRecognitionInput,
        VoiceInterruptionInput {
        override var languageTag = "en-US"
        override val interruptionState = MutableStateFlow(VoiceInterruptionStatus.OFF)
        val started = mutableListOf<() -> Unit>()
        val released = mutableListOf<() -> Unit>()
        var deferRelease = false
        var listening = 0
        override fun startInterruptionMonitoring(outputLevel: () -> Float?, interrupted: () -> Unit) {
            started += interrupted
        }
        override fun stopInterruptionMonitoring() = Unit
        override fun afterInterruptionStopped(released: () -> Unit) {
            if (deferRelease) this.released += released else released()
        }
        override fun listen(listener: VoiceAudio.Listener) {
            listening++
        }
        override fun stop() = Unit
        override fun close() = Unit
    }

    private class Output : KokoroOutput {
        var started: (() -> Unit)? = null
        var completed: (() -> Unit)? = null
        override val reportsPlaybackStart get() = true
        override fun bindPlaybackStarted(started: (() -> Unit)?) {
            this.started = started
        }
        override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) {
            completed = complete
        }
        override fun stop() = Unit
        override fun close() = Unit
    }

    private class SystemAudio : VoiceAudio {
        override fun listen(listener: VoiceAudio.Listener) = Unit
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }
}
