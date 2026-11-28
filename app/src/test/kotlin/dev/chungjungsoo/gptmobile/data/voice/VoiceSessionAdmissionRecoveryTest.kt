package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionAdmissionRecoveryTest {
    @Test fun readOnlyGatewayKeepsTheSynchronousDraftAndExplainsTheRequiredReview() {
        val fixture = Fixture(accepted = false)
        fixture.session.submissionRejection = { "GATEWAY_CONVERSATION_READ_ONLY" }
        fixture.capture()
        fixture.assertRetained("read-only")
        fixture.session.resume()
        assertEquals(VoicePhase.REVIEWING, fixture.session.state.phase)
        assertEquals(1, fixture.submissions)
        assertEquals(1, fixture.audio.captures)
        fixture.session.close()
    }

    @Test fun asynchronousReadOnlyReasonBelongsOnlyToItsSubmittedRequest() {
        val fixture = Fixture(accepted = true)
        fixture.capture()
        fixture.session.admission(VoiceAdmissionStage.FAILED, "GATEWAY_CONVERSATION_READ_ONLY", "another-turn")
        assertEquals(VoicePhase.THINKING, fixture.session.state.phase)
        assertTrue(fixture.session.state.awaitingReply)
        fixture.session.admission(VoiceAdmissionStage.FAILED, "GATEWAY_CONVERSATION_READ_ONLY", "owned-turn")
        fixture.assertRetained("read-only")
        assertTrue(fixture.session.state.notice.orEmpty().contains("pending task"))
        fixture.session.close()
    }

    @Test fun uncertainPriorAdmissionKeepsTheNewWordsWithoutResubmittingEitherTurn() {
        val fixture = Fixture(accepted = true)
        fixture.capture()
        fixture.session.admission(VoiceAdmissionStage.FAILED, "GATEWAY_ADMISSION_UNCONFIRMED", "owned-turn")
        fixture.assertRetained("previous Gateway message")
        fixture.session.tick()
        fixture.session.resume()
        assertEquals(VoicePhase.REVIEWING, fixture.session.state.phase)
        assertEquals(1, fixture.submissions)
        assertEquals(1, fixture.audio.captures)
        fixture.session.close()
    }

    private class Fixture(accepted: Boolean) {
        val audio = Audio()
        var submissions = 0
        val session = VoiceSession(audio, {
            submissions++
            accepted
        }, {}, awaitsAdmission = true, submittedRequestId = { "owned-turn" })

        fun capture() {
            session.access(true, true, true)
            session.resume()
            requireNotNull(audio.listener).final("Keep my original question")
        }

        fun assertRetained(explanation: String) {
            assertEquals(VoicePhase.ERROR, session.state.phase)
            assertEquals("Keep my original question", session.state.transcript)
            assertTrue(session.state.hasDraft)
            assertFalse(session.state.awaitingReply)
            assertTrue(session.state.notice.orEmpty().contains(explanation))
            assertEquals(1, submissions)
            assertEquals(1, audio.captures)
        }
    }

    private class Audio : VoiceAudio {
        var listener: VoiceAudio.Listener? = null
        var captures = 0
        override fun listen(listener: VoiceAudio.Listener) {
            captures++
            this.listener = listener
            listener.ready()
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = error("Admission does not authorize speech")
        override fun stop() = Unit
        override fun close() = Unit
    }
}
