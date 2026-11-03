package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecoveryTest {
    private class Audio : VoiceAudio {
        val attempts = mutableListOf<VoiceAudio.Listener>()
        var complete: (() -> Unit)? = null
        override fun listen(listener: VoiceAudio.Listener) {
            attempts += listener
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            this.complete = complete
        }
        override fun stop() = Unit
        override fun close() = Unit
    }

    @Test fun rapidStartsAndLateCallbacksCannotCreateAnotherTurn() {
        val audio = Audio()
        val sent = mutableListOf<String>()
        val session = VoiceSession(audio, {
            sent += it
            true
        }, {})
        session.access(true, true, true)
        repeat(100) { session.resume() }
        assertEquals(1, audio.attempts.size)
        audio.attempts[0].ending()
        repeat(100) { session.resume() }
        assertEquals(1, audio.attempts.size)
        audio.attempts[0].final("hello")
        audio.attempts[0].final("duplicate")
        assertEquals(listOf("hello"), sent)
        session.reply("reply", "answer")
        audio.complete?.invoke()
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        assertEquals(1, audio.attempts.size)
    }

    @Test fun cooldownCannotBeBypassedByUnmuteResumeOrForeground() {
        var now = 0L
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {}, { now })
        session.access(true, true, true)
        session.resume()
        audio.attempts[0].failure("busy", 10_000)
        repeat(100) {
            session.resume()
            session.mute()
            session.mute()
            session.access(true, true, true)
        }
        assertEquals(1, audio.attempts.size)
        now = 10_000
        session.tick()
        assertEquals(1, audio.attempts.size)
        session.resume()
        assertEquals(2, audio.attempts.size)
        session.close()
        audio.attempts.last().ready()
        assertEquals(VoicePhase.CLOSED, session.state.phase)
    }

    @Test fun throttleEscalatesAndResetsOnlyAfterSuccess() {
        var now = 0L
        val gate = RecognitionStartGate { now }
        gate.failed(true)
        assertEquals(10_000L, gate.remainingMillis())
        now += 10_000
        assertTrue(gate.acquire())
        gate.failed(true)
        assertEquals(30_000L, gate.remainingMillis())
        now += 30_000
        gate.failed(true)
        assertEquals(60_000L, gate.remainingMillis())
        assertFalse(gate.acquire())
        now += 60_000
        gate.succeeded()
        gate.failed(true)
        assertEquals(10_000L, gate.remainingMillis())
    }

    @Test fun editBeforeSendRequiresOneExplicitSubmissionAndKeepsDraftOnError() {
        val audio = Audio()
        val sent = mutableListOf<String>()
        val session = VoiceSession(audio, {
            sent += it
            true
        }, {}).apply { editBeforeSend = true }
        session.access(true, true, true)
        session.resume()
        audio.attempts[0].partial("draft")
        audio.attempts[0].final("final draft")
        assertTrue(sent.isEmpty())
        session.sendDraft("edited")
        session.sendDraft("duplicate")
        assertEquals(listOf("edited"), sent)
        session.reply("run", "", failed = true)
        assertEquals("edited", session.state.transcript)
        assertEquals(VoicePhase.ERROR, session.state.phase)
    }

    @Test fun streamingSpeechWaitsForFinalModelAndNeverReplaysChunks() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {})
        session.access(true, true, true)
        session.resume()
        audio.attempts[0].final("question")
        session.streamReply("run", "First sentence. Tail")
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        val first = audio.complete
        first?.invoke()
        assertEquals(VoicePhase.THINKING, session.state.phase)
        session.streamReply("run", "First sentence. Tail")
        session.reply("run", "First sentence. Tail done")
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        first?.invoke()
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        audio.complete?.invoke()
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        assertEquals(1, audio.attempts.size)
    }

    @Test fun interruptFencesSpeechAndStartsOnlyOneFreshCapture() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {})
        session.access(true, true, true)
        session.resume()
        audio.attempts[0].final("question")
        session.streamReply("old", "Speaking. Tail")
        val late = audio.complete
        session.interrupt()
        late?.invoke()
        session.reply("old", "Late answer")
        assertEquals(2, audio.attempts.size)
        assertEquals(VoicePhase.STARTING, session.state.phase)
    }

    @Test fun restoredUnsentDraftDoesNotReopenMicrophone() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {})
        session.restore("draft", "reply", review = true, pending = false)
        session.access(true, true, true)
        session.resume()
        assertTrue(audio.attempts.isEmpty())
        assertEquals("draft", session.state.transcript)
        assertTrue(session.state.hasDraft)
    }
}
