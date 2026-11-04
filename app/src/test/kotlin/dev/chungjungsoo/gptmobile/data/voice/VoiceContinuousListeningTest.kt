package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceContinuousListeningTest {
    private class Audio : VoiceAudio {
        val listeners = mutableListOf<VoiceAudio.Listener>()
        var playback: (() -> Unit)? = null
        override fun listen(listener: VoiceAudio.Listener) {
            listeners += listener
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            playback = complete
        }
        override fun stop() = Unit
        override fun close() = Unit
    }

    @Test fun silenceRestartsOnlyAfterCooldownAndMuteFencesTheRestart() {
        var time = 0L
        val audio = Audio()
        val session = VoiceSession(audio, { error("Silence must not send a message") }, {}, { time }).apply { autoContinue = true }
        session.access(true, true, true)
        session.resume()
        audio.listeners.last().final("")
        time = 499
        session.tick()
        assertEquals(1, audio.listeners.size)
        time = 500
        session.tick()
        assertEquals(2, audio.listeners.size)
        audio.listeners.last().final("")
        session.mute()
        time = 2000
        session.tick()
        assertEquals(2, audio.listeners.size)
        assertTrue(session.state.muted)
    }

    @Test fun transientFailureHasBoundedAutomaticRetriesAndCannotListenInBackground() {
        var time = 0L
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {}, { time }).apply { autoContinue = true }
        session.access(true, true, true)
        session.resume()
        repeat(4) {
            audio.listeners.last().retryableFailure("busy", 1000)
            time += 1000
            session.tick()
        }
        assertEquals(4, audio.listeners.size)
        session.resume()
        audio.listeners.last().retryableFailure("busy", 1000)
        session.access(true, true, false)
        time += 2000
        session.tick()
        assertEquals(5, audio.listeners.size)
    }

    @Test fun repeatedSilenceHasBoundedAutomaticRetries() {
        var time = 0L
        val audio = Audio()
        val session = VoiceSession(audio, { error("Silence must not send a message") }, {}, { time }).apply { autoContinue = true }
        session.access(true, true, true)
        session.resume()
        repeat(4) {
            audio.listeners.last().final("")
            time += 500
            session.tick()
        }
        assertEquals(4, audio.listeners.size)
        assertEquals(VoicePhase.ERROR, session.state.phase)
    }

    @Test fun captionsOnlyReplyReturnsToListeningWithoutSpeakerPlayback() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {}).apply { autoContinue = true }
        session.access(true, true, true)
        session.resume()
        session.speaker()
        audio.listeners.last().final("Hello")
        session.streamReply("run", "Answer", complete = true)
        assertEquals(2, audio.listeners.size)
        assertEquals(VoicePhase.STARTING, session.state.phase)
        assertEquals(null, audio.playback)
    }
}
