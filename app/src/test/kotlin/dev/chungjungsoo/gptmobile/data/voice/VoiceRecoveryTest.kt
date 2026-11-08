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

    @Test fun serviceRepairKeepsItsSpecificActionAcrossPauseAndNeverAutomaticallyRetries() {
        var now = 0L
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {}, { now }).apply { autoContinue = true }
        session.access(true, true, true)
        session.resume()
        audio.attempts.single().repairRequired("Review the speech service", 5000, VoiceRecoveryAction.SPEECH_SERVICE)
        assertEquals(VoiceRecoveryAction.SPEECH_SERVICE, session.state.recoveryAction)
        session.access(true, true, false)
        session.access(true, true, true)
        assertEquals(VoiceRecoveryAction.SPEECH_SERVICE, session.state.recoveryAction)
        now = 5000
        session.tick()
        assertEquals(1, audio.attempts.size)
        session.resume()
        assertEquals(2, audio.attempts.size)
        assertEquals(null, session.state.recoveryAction)
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

    @Test fun failedRecognitionPreservesPartialDraftAndAllowsReviewDuringCooldown() {
        var now = 0L
        val audio = Audio()
        val sent = mutableListOf<String>()
        val session = VoiceSession(audio, {
            sent += it
            true
        }, {}, { now }).apply { autoContinue = true }
        session.access(true, true, true)
        session.resume()
        val capture = audio.attempts.single()
        capture.ready()
        capture.partial("Keep these spoken words")
        capture.retryableFailure("Network interrupted", 30_000)
        assertEquals(VoicePhase.ERROR, session.state.phase)
        assertTrue(session.state.hasDraft)
        session.resume()
        assertEquals(VoicePhase.REVIEWING, session.state.phase)
        assertEquals("Keep these spoken words", session.state.transcript)
        assertEquals(1, audio.attempts.size)
        now = 30_000
        session.tick()
        assertEquals(1, audio.attempts.size)
        assertTrue(sent.isEmpty())
        session.editDraft("Corrected spoken words")
        session.sendDraft()
        assertEquals(listOf("Corrected spoken words"), sent)
        capture.final("Late discarded result")
        assertEquals(listOf("Corrected spoken words"), sent)
    }

    @Test fun partialSpeechAfterTerminalErrorSurvivesSessionRecreationWithoutAutomaticSend() {
        val audio = Audio()
        val session = VoiceSession(audio, { error("Must not send partial speech") }, {})
        session.access(true, true, true)
        session.resume()
        audio.attempts.single().partial("Interrupted draft")
        audio.attempts.single().error("Speech service disconnected")
        val saved = session.state
        session.close()
        val replacement = Audio()
        val restored = VoiceSession(replacement, { error("Must review restored draft") }, {})
        restored.restore(saved.transcript, saved.reply, saved.hasDraft, saved.awaitingReply)
        restored.access(true, true, true)
        restored.resume()
        assertEquals(VoicePhase.REVIEWING, restored.state.phase)
        assertEquals("Interrupted draft", restored.state.transcript)
        assertTrue(replacement.attempts.isEmpty())
    }

    @Test fun throttleEscalatesAndResetsOnlyAfterSuccess() {
        var now = 0L
        val gate = RecognitionStartGate { now }
        gate.failed(true)
        assertEquals(1_000L, gate.remainingMillis())
        now += 1_000
        assertTrue(gate.acquire())
        gate.failed(true)
        assertEquals(2_500L, gate.remainingMillis())
        now += 2_500
        gate.failed(true)
        assertEquals(5_000L, gate.remainingMillis())
        assertFalse(gate.acquire())
        now += 5_000
        gate.succeeded()
        gate.failed(true)
        assertEquals(1_000L, gate.remainingMillis())
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

    @Test fun editedDraftSurvivesPauseAndSessionRecreation() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {}).apply { editBeforeSend = true }
        session.access(true, true, true)
        session.resume()
        audio.attempts.single().final("recognized")
        session.editDraft("corrected draft")
        session.pause()
        val saved = session.state
        session.close()
        val sent = mutableListOf<String>()
        val restoredAudio = Audio()
        val restored = VoiceSession(restoredAudio, {
            sent += it
            true
        }, {})
        restored.restore(saved.transcript, saved.reply, saved.hasDraft, saved.awaitingReply)
        restored.access(true, true, true)
        restored.resume()
        assertTrue(restoredAudio.attempts.isEmpty())
        restored.sendDraft()
        assertEquals(listOf("corrected draft"), sent)
    }

    @Test fun backgroundedPartialCaptureBecomesReviewableDraft() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {})
        session.access(true, true, true)
        session.resume()
        audio.attempts.single().partial("unfinished words")
        session.access(true, true, false)
        assertTrue(session.state.hasDraft)
        session.access(true, true, true)
        session.resume()
        assertEquals(VoicePhase.REVIEWING, session.state.phase)
        assertEquals("unfinished words", session.state.transcript)
        assertEquals(1, audio.attempts.size)
    }

    @Test fun pausingNewCaptureDoesNotRestoreAlreadySentTranscript() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {})
        session.access(true, true, true)
        session.resume()
        audio.attempts.single().final("already sent")
        session.reply("run", "answer")
        audio.complete?.invoke()
        session.resume()
        session.pause()
        assertFalse(session.state.hasDraft)
        assertEquals("", session.state.transcript)
    }

    @Test fun emptyFinalResultDoesNotDiscardCapturedSpeechOrSendItAutomatically() {
        val audio = Audio()
        val sent = mutableListOf<String>()
        val session = VoiceSession(audio, {
            sent += it
            true
        }, {})
        session.access(true, true, true)
        session.autoContinue = true
        session.editBeforeSend = false
        session.resume()
        audio.attempts.last().ready()
        audio.attempts.last().partial("Please keep this unfinished request")
        audio.attempts.last().final("")
        assertEquals(VoicePhase.ERROR, session.state.phase)
        assertTrue(session.state.hasDraft)
        assertEquals("Please keep this unfinished request", session.state.transcript)
        assertTrue(sent.isEmpty())
        session.resume()
        assertEquals(VoicePhase.REVIEWING, session.state.phase)
    }
}
