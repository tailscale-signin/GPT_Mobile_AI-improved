package dev.chungjungsoo.gptmobile.data.voice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAdmissionPlaybackTest {
    private class Audio : VoiceAudio {
        var listener: VoiceAudio.Listener? = null
        val completions = mutableListOf<() -> Unit>()
        val spoken = mutableListOf<String>()
        var captures = 0
        var stops = 0
        override fun listen(listener: VoiceAudio.Listener) {
            captures++
            this.listener = listener
            listener.ready()
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            spoken += text
            completions += complete
        }
        override fun stop() {
            stops++
        }
        override fun close() = Unit
    }

    private class Fixture {
        val audio = Audio()
        var time = 100L
        var submissions = 0
        var requestId: String? = null
        var admissionStage: VoiceAdmissionStage? = null
        var composer = ""
        val trace = VoiceDiagnosticTrace({ time }, { 1_000 + time }, { true }, {})
        val session = VoiceSession(
            audio,
            submit = {
                requestId = "request-${++submissions}"
                admissionStage = VoiceAdmissionStage.CHECKING
                true
            },
            changed = { if (it.shouldCheckpointDraft(requestId, admissionStage)) composer = it.transcript },
            now = { time },
            awaitsAdmission = true,
            diagnosticTrace = trace,
            submittedRequestId = { requestId }
        )
        fun capture(text: String = "Keep this captured prompt") {
            session.access(true, true, true)
            session.resume()
            requireNotNull(audio.listener).final(text)
        }
        fun replay() {
            time += 100
            session.beginMessage("historical", manual = true)
            session.reply("historical", "Read an earlier response.")
        }
        fun admission(stage: VoiceAdmissionStage, reason: String? = null, id: String? = requestId) {
            time += 100
            if (id == requestId) {
                admissionStage = stage
                if (stage in setOf(VoiceAdmissionStage.QUEUED, VoiceAdmissionStage.ACCEPTED) && composer == session.state.transcript) composer = ""
            }
            session.admission(stage, reason, id)
        }
    }

    @After fun clearDiagnostics() = VoiceDiagnosticTrace.clearAll()

    @Test fun lateAcceptanceClearsOnlyTheSubmittedDraftWhileReplayKeepsAudioOwnership() {
        val fixture = Fixture()
        fixture.capture()
        fixture.replay()
        val stops = fixture.audio.stops
        fixture.admission(VoiceAdmissionStage.ACCEPTED)
        assertFalse(fixture.session.state.hasDraft)
        assertTrue(fixture.session.state.awaitingReply)
        assertEquals(VoicePhase.SPEAKING, fixture.session.state.phase)
        assertEquals("Read an earlier response.", fixture.session.state.reply)
        assertEquals(stops, fixture.audio.stops)
        assertEquals(1, fixture.audio.captures)
        assertFalse("voice_transcript_to_request_ms" in fixture.trace.incidentFields())
        fixture.audio.completions.last()()
        fixture.session.resume()
        assertEquals(VoicePhase.LISTENING, fixture.session.state.phase)
        assertEquals(1, fixture.submissions)
    }

    @Test fun acceptedDraftCannotRemainSendableIfReplayFinishedBeforeAdmissionArrived() {
        val fixture = Fixture()
        fixture.capture()
        fixture.replay()
        fixture.audio.completions.last()()
        fixture.session.resume()
        assertEquals(VoicePhase.THINKING, fixture.session.state.phase)
        fixture.session.sendDraft()
        assertEquals(1, fixture.submissions)
        fixture.admission(VoiceAdmissionStage.ACCEPTED)
        assertFalse(fixture.session.state.hasDraft)
        assertEquals(VoicePhase.PAUSED, fixture.session.state.phase)
        fixture.session.sendDraft()
        assertEquals(1, fixture.submissions)
        assertEquals(1, fixture.audio.captures)
    }

    @Test fun queuedRejectionRestoresTheSubmittedDraftWithoutFailingUnrelatedReplay() {
        val fixture = Fixture()
        fixture.capture()
        fixture.replay()
        val stops = fixture.audio.stops
        fixture.admission(VoiceAdmissionStage.QUEUED)
        assertFalse(fixture.session.state.hasDraft)
        fixture.admission(VoiceAdmissionStage.CHECKING)
        assertFalse(fixture.session.state.hasDraft)
        fixture.admission(VoiceAdmissionStage.FAILED, "QUEUED_TURN_REMOVED")
        assertTrue(fixture.session.state.hasDraft)
        assertTrue(fixture.session.state.awaitingReply)
        assertEquals(VoicePhase.SPEAKING, fixture.session.state.phase)
        assertEquals(stops, fixture.audio.stops)
        assertEquals(1, fixture.audio.captures)
        assertTrue(fixture.session.state.notice.orEmpty().contains("QUEUED_TURN_REMOVED"))
        assertFalse("voice_reply_failed" in fixture.trace.incidentFields())
        assertFalse("voice_transcript_to_request_ms" in fixture.trace.incidentFields())
        fixture.audio.completions.last()()
        fixture.session.resume()
        assertEquals(VoicePhase.REVIEWING, fixture.session.state.phase)
        assertEquals("Keep this captured prompt", fixture.session.state.transcript)
        assertEquals(1, fixture.submissions)
    }

    @Test fun acceptedOrRejectedAdmissionIsRetiredAndLateEventsCannotRecreateItsDraft() {
        for (terminal in listOf(VoiceAdmissionStage.ACCEPTED, VoiceAdmissionStage.FAILED)) {
            val fixture = Fixture()
            fixture.capture()
            fixture.replay()
            fixture.admission(terminal)
            val afterTerminal = fixture.session.state
            for (late in VoiceAdmissionStage.entries) {
                fixture.admission(late, "STALE_RESULT")
                assertEquals(afterTerminal, fixture.session.state)
            }
        }
    }

    @Test fun admissionFromAnotherRequestCannotChangeTheNewCapturedDraft() {
        val fixture = Fixture()
        fixture.capture()
        val originalRequest = fixture.requestId
        fixture.replay()
        fixture.audio.completions.last()()
        fixture.session.discardDraft()
        fixture.capture("A replacement captured prompt")
        val replacement = fixture.session.state
        for (stale in VoiceAdmissionStage.entries) {
            fixture.admission(stale, "STALE_RESULT", originalRequest)
            assertEquals(replacement, fixture.session.state)
        }
        fixture.admission(VoiceAdmissionStage.ACCEPTED)
        assertFalse(fixture.session.state.hasDraft)
        assertEquals("A replacement captured prompt", fixture.session.state.transcript)
    }

    @Test fun discardedOrReplacedDraftsRetireTheirEarlierAdmissionOwnership() {
        for (discard in listOf(false, true)) {
            val fixture = Fixture()
            fixture.capture()
            fixture.replay()
            fixture.audio.completions.last()()
            fixture.session.discardDraft()
            if (!discard) {
                fixture.session.restore("A replacement draft", "", review = true, pending = false)
                fixture.session.editDraft("A newly edited draft")
            }
            val replacement = fixture.session.state
            fixture.admission(VoiceAdmissionStage.ACCEPTED)
            fixture.admission(VoiceAdmissionStage.FAILED, "STALE_RESULT")
            assertEquals(replacement, fixture.session.state)
        }
    }

    @Test fun restoringTheActualSubmittedIdentityWaitsSilentlyAndAcceptanceCannotReofferIt() {
        for (queued in listOf(false, true)) {
            val original = Fixture()
            original.capture()
            if (queued) original.admission(VoiceAdmissionStage.QUEUED)
            val saved = original.session.state
            val restored = Fixture()
            restored.requestId = saved.submittedDraftRequestId
            restored.session.restore(saved.transcript, saved.reply, saved.hasDraft, pending = false, pendingRequestId = saved.submittedDraftRequestId)
            restored.session.access(true, true, true)
            restored.session.resume()
            assertEquals(VoicePhase.THINKING, restored.session.state.phase)
            assertEquals(0, restored.audio.captures)
            assertTrue(restored.audio.spoken.isEmpty())
            restored.admission(VoiceAdmissionStage.ACCEPTED)
            assertFalse(restored.session.state.hasDraft)
            assertNull(restored.session.state.submittedDraftRequestId)
            assertEquals(VoicePhase.PAUSED, restored.session.state.phase)
            restored.session.sendDraft()
            assertEquals(0, restored.submissions)
            assertEquals(0, restored.audio.captures)
            assertTrue(restored.audio.spoken.isEmpty())
            assertFalse("voice_transcript_to_request_ms" in restored.trace.incidentFields())
        }
    }

    @Test fun restoredQueuedRejectionKeepsTheExactDraftAvailableWithoutRestartingAudio() {
        val original = Fixture()
        original.capture()
        original.admission(VoiceAdmissionStage.QUEUED)
        val saved = original.session.state
        val restored = Fixture()
        restored.requestId = saved.submittedDraftRequestId
        restored.session.restore(saved.transcript, saved.reply, saved.hasDraft, pending = false, pendingRequestId = saved.submittedDraftRequestId)
        restored.session.access(true, true, true)
        restored.admission(VoiceAdmissionStage.CHECKING)
        assertFalse(restored.session.state.hasDraft)
        restored.admission(VoiceAdmissionStage.FAILED, "QUEUED_TURN_REMOVED")
        assertTrue(restored.session.state.hasDraft)
        assertEquals(saved.transcript, restored.session.state.transcript)
        assertEquals(VoicePhase.REVIEWING, restored.session.state.phase)
        assertNull(restored.session.state.submittedDraftRequestId)
        assertEquals(0, restored.audio.captures)
        assertTrue(restored.audio.spoken.isEmpty())
        assertFalse("voice_reply_failed" in restored.trace.incidentFields())
    }

    @Test fun restorationNeverInfersSubmissionOwnershipFromMatchingTextOrTheCurrentRequest() {
        val fixture = Fixture()
        fixture.requestId = "some-existing-request"
        fixture.session.restore("Keep this captured prompt", "", review = true, pending = false)
        fixture.admission(VoiceAdmissionStage.ACCEPTED)
        assertTrue(fixture.session.state.hasDraft)
        assertEquals(VoicePhase.REVIEWING, fixture.session.state.phase)
        assertNull(fixture.session.state.submittedDraftRequestId)

        fixture.session.discardDraft()
        fixture.session.restore("An older submitted draft", "", review = true, pending = false, pendingRequestId = "old-request")
        fixture.session.discardDraft()
        fixture.session.restore("A newer draft", "", review = true, pending = false)
        fixture.admission(VoiceAdmissionStage.ACCEPTED, id = "old-request")
        fixture.admission(VoiceAdmissionStage.FAILED, "STALE_RESULT", "old-request")
        assertEquals("A newer draft", fixture.session.state.transcript)
        assertTrue(fixture.session.state.hasDraft)
        assertEquals(VoicePhase.REVIEWING, fixture.session.state.phase)
    }

    @Test fun restoringAnAlreadyAcceptedSubmissionCannotRepopulateTheClearedComposer() {
        val original = Fixture()
        original.capture()
        val saved = original.session.state
        val restored = Fixture()
        restored.requestId = saved.submittedDraftRequestId
        restored.admissionStage = VoiceAdmissionStage.ACCEPTED
        restored.session.restore(saved.transcript, saved.reply, saved.hasDraft, pending = false, pendingRequestId = saved.submittedDraftRequestId)
        assertEquals("", restored.composer)
        restored.admission(VoiceAdmissionStage.ACCEPTED)
        assertFalse(restored.session.state.hasDraft)
        assertEquals("", restored.composer)
        assertTrue(restored.audio.spoken.isEmpty())

        // A user-created draft with the same words owns no submitted identity and remains editable.
        restored.session.restore(saved.transcript, "", review = true, pending = false)
        assertEquals(saved.transcript, restored.composer)
        assertTrue(restored.session.state.hasDraft)
    }

    @Test fun rejectionWhileBackgroundedReleasesItsOriginalCaptureWaitAndPreservesReview() {
        val fixture = Fixture()
        fixture.capture()
        fixture.session.access(true, true, false)
        fixture.admission(VoiceAdmissionStage.FAILED, "SELECTED_MODEL_NOT_LOADED")
        assertFalse(fixture.session.state.awaitingReply)
        assertTrue(fixture.session.state.hasDraft)
        fixture.session.access(true, true, true)
        fixture.session.resume()
        assertEquals(VoicePhase.REVIEWING, fixture.session.state.phase)
        assertEquals("Keep this captured prompt", fixture.session.state.transcript)
        assertEquals(1, fixture.audio.captures)
        assertTrue(fixture.audio.spoken.isEmpty())
    }

    @Test fun rejectionStillFailsItsOwnWaitingTurnAndClosedSessionsIgnoreLateAdmission() {
        val fixture = Fixture()
        fixture.capture()
        fixture.admission(VoiceAdmissionStage.FAILED, "SELECTED_MODEL_NOT_LOADED")
        assertEquals(VoicePhase.ERROR, fixture.session.state.phase)
        assertTrue(fixture.session.state.hasDraft)
        assertFalse(fixture.session.state.awaitingReply)
        fixture.session.close()
        val closed = fixture.session.state
        fixture.admission(VoiceAdmissionStage.ACCEPTED)
        assertEquals(closed, fixture.session.state)
        assertNull(fixture.session.state.spokenRange)
    }
}
