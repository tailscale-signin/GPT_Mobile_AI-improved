package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VoicePresentationSignalsTest {
    @Test fun meterAndCursorUpdatesDoNotChangeConversationPresentation() {
        val state = VoiceState(phase = VoicePhase.SPEAKING, reply = "A retained answer", level = .1f, spokenRange = 0..0)
        val next = state.copy(level = .9f, spokenRange = 2..9, playbackCaption = "A retained answer")
        assertEquals(state.presentationState(), next.presentationState())
        assertNotEquals(state, next)
        assertNotEquals(state.presentationState(), next.copy(phase = VoicePhase.PAUSED).presentationState())
    }

    @Test fun liveMeterAndPlaybackCursorDoNotRecheckpointOrPublishTheScreen() {
        val audio = SignalAudio()
        val checkpoints = mutableListOf<VoiceState>()
        val session = VoiceSession(audio, { true }, checkpoints::add)
        session.access(true, true, true)
        session.resume()
        val listening = session.presentationStates.value
        val changes = checkpoints.size
        repeat(40) { audio.listener.level(it / 40f) }
        assertSame(listening, session.presentationStates.value)
        assertEquals(changes, checkpoints.size)
        assertEquals(VoiceInputLevel(.975f, true), session.inputLevels.value)

        audio.listener.final("Please speak")
        session.reply("run", "A retained answer")
        val speaking = session.presentationStates.value
        val playbackChanges = checkpoints.size
        requireNotNull(audio.spokenRange).invoke(0, "A retained answer", 2..9)
        assertSame(speaking, session.presentationStates.value)
        assertEquals(playbackChanges, checkpoints.size)
        assertEquals(2..9, session.captionCursors.value.range)
        assertEquals(2..9, session.states.value.spokenRange)
        session.pause()
        assertEquals(null, session.captionCursors.value.range)
    }

    @Test fun doneSpeakingAndHandsFreeCallbacksRespectSessionAccess() {
        val audio = SignalAudio()
        val session = VoiceSession(audio, { true }, {})
        session.configureInterruption(true)
        session.configureInterruption(true)
        assertEquals(1, audio.interruptionConfigurations)
        session.finishListening()
        assertEquals(0, audio.finished)
        session.access(true, true, true)
        session.resume()
        session.finishListening()
        assertEquals(1, audio.finished)
        audio.listener.final("Please speak")
        session.reply("run", "A retained answer")
        requireNotNull(audio.interrupted).invoke()
        assertEquals(VoicePhase.LISTENING, session.state.phase)
        assertEquals(2, audio.captures)
        session.access(true, true, false)
        session.finishListening()
        requireNotNull(audio.interrupted).invoke()
        assertEquals(1, audio.finished)
        assertEquals(2, audio.captures)
        assertEquals(VoicePhase.PAUSED, session.state.phase)
    }

    @Test fun adaptiveSpeechFlushesStableWordsDuringAProviderPauseWithoutDuplicates() {
        var now = 0L
        val audio = SignalAudio()
        val session = VoiceSession(audio, { true }, {}, now = { now }).apply { performanceMode = VoicePerformanceMode.AUTO }
        session.access(true, true, true)
        session.resume()
        audio.listener.final("Please speak")
        session.streamReply("run", "Two words ")
        assertEquals(emptyList<String>(), audio.spoken)
        now = 250
        session.flushSpeech()
        assertEquals(listOf("Two words "), audio.spoken)
        now = 500
        session.flushSpeech()
        assertEquals(listOf("Two words "), audio.spoken)
    }

    private class SignalAudio : VoiceAudio {
        lateinit var listener: VoiceAudio.Listener
        var spokenRange: ((Int, String, IntRange) -> Unit)? = null
        var interrupted: (() -> Unit)? = null
        var interruptionConfigurations = 0
        var finished = 0
        var captures = 0
        val spoken = mutableListOf<String>()
        override fun listen(listener: VoiceAudio.Listener) {
            this.listener = listener
            captures++
            listener.ready()
        }
        override fun finishListening() {
            finished++
        }
        override fun configureInterruption(enabled: Boolean, interrupted: (() -> Unit)?) {
            interruptionConfigurations++
            this.interrupted = interrupted
        }
        override fun bindSpokenRange(spoken: ((Int, String, IntRange) -> Unit)?) {
            spokenRange = spoken
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            spoken += text
        }
        override fun stop() = Unit
        override fun close() = Unit
    }
}
