package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDraftPreparationTest {
    private class Audio : VoiceAudio {
        val listeners = mutableListOf<VoiceAudio.Listener>()
        var completed: (() -> Unit)? = null
        override fun listen(listener: VoiceAudio.Listener) {
            listeners += listener
            listener.ready()
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            completed = complete
        }
        override fun stop() = Unit
        override fun close() = Unit
    }

    private class Fixture(awaitsAdmission: Boolean = false) {
        val audio = Audio()
        val sent = mutableListOf<String>()
        val inputs = mutableListOf<String>()
        val callbacks = mutableListOf<(VoiceDraftPreparation) -> Unit>()
        val phases = mutableListOf<VoicePhase>()
        var commits = 0
        var selected: VoiceTranslationTarget? = null
        val submittedTargets = mutableListOf<VoiceTranslationTarget?>()
        val session = VoiceSession(audio, {
            sent += it
            submittedTargets += selected
            true
        }, { phases += it.phase }, awaitsAdmission = awaitsAdmission, submittedRequestId = { "prepared-request" }).apply {
            access(true, true, true)
            prepareDraft = { text, complete ->
                inputs += text
                callbacks += complete
            }
        }

        fun capture(text: String = "What time is it?", review: Boolean = false) {
            session.editBeforeSend = review
            session.listenForConversation()
            audio.listeners.last().final(text)
        }

        fun prepared(index: Int = callbacks.lastIndex, detected: String = "en") {
            val target = requireNotNull(VoiceTranslationPair().resolve(detected))
            callbacks[index](
                VoiceDraftPreparation(commit = {
                    commits++
                    selected = target
                })
            )
        }

        fun assertDraft(text: String = "What time is it?") {
            assertEquals(text, session.state.transcript)
            assertTrue(session.state.hasDraft)
            assertFalse(session.state.awaitingReply)
            assertTrue(sent.isEmpty())
            assertEquals(0, commits)
        }
    }

    @Test fun automaticAndReviewedQuestionsWaitForOnePreparedDirectionBeforeSubmittingLiteralWords() {
        for (review in listOf(false, true)) {
            for ((text, language, target) in listOf(Triple("What time is it?", "en", "fr-FR"), Triple("Quelle heure est-il ?", "fr", "en-US"))) {
                val f = Fixture()
                f.session.translationOnly = true
                f.capture(text, review)
                if (review) f.session.sendDraft()
                assertEquals(VoicePhase.FINALIZING, f.session.state.phase)
                assertEquals(VoiceMicrophoneAction.STOP, f.session.state.microphoneAction)
                assertEquals(listOf(text), f.inputs)
                f.assertDraft(text)
                f.session.sendDraft()
                assertEquals(1, f.callbacks.size)
                f.prepared(detected = language)
                assertEquals(listOf(text), f.sent)
                assertEquals(target, f.submittedTargets.single()?.targetLanguage)
                assertEquals(text, f.session.state.translationPrompt?.text)
                assertEquals(VoicePhase.THINKING, f.session.state.phase)
                assertEquals(1, f.commits)
                f.session.close()
            }
        }
    }

    @Test fun absentAndSynchronousPreparationKeepImmediateSubmissionBehavior() {
        for (synchronous in listOf(false, true)) {
            val f = Fixture()
            f.session.prepareDraft = if (synchronous) {
                { _, complete -> complete(VoiceDraftPreparation(commit = { f.commits++ })) }
            } else {
                null
            }
            f.capture()
            assertEquals(listOf("What time is it?"), f.sent)
            assertEquals(VoicePhase.THINKING, f.session.state.phase)
            assertEquals(if (synchronous) 1 else 0, f.commits)
            f.session.close()
        }
    }

    @Test fun ambiguousPreparationKeepsAnActionableReviewWithoutAnErrorPhaseOrAutomaticSend() {
        val f = Fixture()
        f.capture()
        f.callbacks.single()(VoiceDraftPreparation(rejection = "Choose English or French as the spoken language, then send again."))
        assertEquals(VoicePhase.REVIEWING, f.session.state.phase)
        assertTrue(f.session.state.notice.orEmpty().contains("Choose English or French"))
        f.assertDraft()
        f.session.tick()
        f.session.resume()
        f.prepared()
        f.assertDraft()
        assertFalse(VoicePhase.ERROR in f.phases)
        assertEquals(1, f.audio.listeners.size)
        f.session.close()
    }

    @Test fun preparationAndCommitExceptionsReturnToReviewAndNeverStrandFinalizing() {
        for (duringCommit in listOf(false, true)) {
            val f = Fixture()
            f.session.prepareDraft = { _, complete ->
                if (duringCommit) {
                    complete(VoiceDraftPreparation(commit = { throw IllegalStateException("synthetic unavailable factory") }))
                } else {
                    throw IllegalStateException("synthetic unavailable detector")
                }
            }
            f.capture()
            assertEquals(VoicePhase.REVIEWING, f.session.state.phase)
            assertTrue(f.session.state.notice.orEmpty().contains("send again"))
            assertFalse(f.session.state.notice.orEmpty().contains("synthetic"))
            f.assertDraft()
            assertFalse(VoicePhase.ERROR in f.phases)
            f.session.close()
        }
    }

    @Test fun cancellationAndAccessChangesFenceLateCompletionBeforeAnyCommit() {
        for (action in listOf("stop", "background", "background_playback", "disabled", "permission", "benchmark", "close", "replay")) {
            val f = Fixture()
            f.capture()
            when (action) {
                "stop" -> f.session.pause()
                "background" -> f.session.access(true, true, false)
                "background_playback" -> {
                    f.session.backgroundPlayback = true
                    f.session.access(true, true, false)
                    f.session.access(true, true, true)
                }
                "disabled" -> f.session.access(false, true, true)
                "permission" -> f.session.access(true, false, true)
                "benchmark" -> f.session.holdAudio(true)
                "close" -> f.session.close()
                "replay" -> f.session.beginMessage("another-reply", manual = true)
            }
            f.prepared()
            assertEquals(action, 0, f.commits)
            assertTrue(action, f.sent.isEmpty())
            assertEquals(action, "What time is it?", f.session.state.transcript)
            if (action !in setOf("close", "replay")) f.assertDraft()
            f.session.close()
        }
    }

    @Test fun suspendedReplayCannotReviveAfterPauseBackgroundOrBenchmarkReturns() {
        for (action in listOf("settings", "pair", "background", "background_playback", "benchmark", "stop", "capture")) {
            val f = Fixture()
            f.session.pause("translation_replay_preparing")
            val owner = f.session.playbackPreparationOwner()
            assertTrue(action, f.session.ownsPlaybackPreparation(owner))
            when (action) {
                "settings" -> f.session.pause("settings_opened")
                "pair" -> f.session.pause("translation_pair_changed")
                "background", "background_playback" -> {
                    f.session.backgroundPlayback = action == "background_playback"
                    f.session.access(true, true, false)
                    f.session.access(true, true, true)
                }
                "benchmark" -> {
                    f.session.holdAudio(true)
                    f.session.holdAudio(false)
                }
                "stop" -> f.session.stopTurn()
                "capture" -> f.session.listenForConversation()
            }
            assertFalse(action, f.session.ownsPlaybackPreparation(owner))
            f.session.close()
        }
    }

    @Test fun editingWhilePreparationRunsCancelsItAndOnlyTheNewDraftCanCommit() {
        val f = Fixture()
        f.capture()
        f.session.editDraft("Where is the station?")
        assertEquals(VoicePhase.REVIEWING, f.session.state.phase)
        f.prepared(0)
        f.assertDraft("Where is the station?")
        f.session.sendDraft()
        f.prepared(0)
        f.assertDraft("Where is the station?")
        f.prepared(1)
        assertEquals(listOf("What time is it?", "Where is the station?"), f.inputs)
        assertEquals(listOf("Where is the station?"), f.sent)
        assertEquals(1, f.commits)
        f.session.close()
    }

    @Test fun discardedDraftAndReplacedCaptureNeverInheritAnOldPreparedDirection() {
        val f = Fixture()
        f.capture()
        f.session.discardDraft()
        f.prepared(0)
        assertEquals(VoicePhase.PAUSED, f.session.state.phase)
        assertFalse(f.session.state.hasDraft)
        f.capture("Bonjour ?")
        f.prepared(0)
        f.assertDraft("Bonjour ?")
        f.prepared(1, "fr")
        assertEquals(listOf("Bonjour ?"), f.sent)
        assertEquals("en-US", f.submittedTargets.single()?.targetLanguage)
        f.session.close()
    }

    @Test fun duplicateSuccessAndRejectionCallbacksCannotCommitOrDispatchTwice() {
        for (rejected in listOf(false, true)) {
            val f = Fixture()
            f.capture()
            if (rejected) f.callbacks.single()(VoiceDraftPreparation(rejection = "Choose the spoken language.")) else f.prepared()
            f.prepared()
            f.prepared()
            assertEquals(if (rejected) 0 else 1, f.commits)
            assertEquals(if (rejected) 0 else 1, f.sent.size)
            assertEquals(if (rejected) VoicePhase.REVIEWING else VoicePhase.THINKING, f.session.state.phase)
            f.session.close()
        }
    }

    @Test fun preparedSubmissionRetainsTheExistingAdmissionIdentityAndRejectedDraft() {
        val f = Fixture(awaitsAdmission = true)
        f.capture()
        f.prepared()
        assertEquals("prepared-request", f.session.state.submittedDraftRequestId)
        assertTrue(f.session.state.hasDraft)
        f.session.admission(VoiceAdmissionStage.ACCEPTED, requestId = "another-request")
        assertTrue(f.session.state.hasDraft)
        f.session.admission(VoiceAdmissionStage.FAILED, "PROFILE_UNAVAILABLE", "prepared-request")
        assertTrue(f.session.state.hasDraft)
        assertFalse(f.session.state.awaitingReply)
        assertEquals("What time is it?", f.session.state.transcript)
        f.session.resume()
        assertEquals(VoicePhase.REVIEWING, f.session.state.phase)
        f.session.sendDraft()
        f.prepared(0)
        assertEquals(VoicePhase.FINALIZING, f.session.state.phase)
        assertEquals(1, f.sent.size)
        f.prepared(1)
        assertEquals(2, f.sent.size)
        assertEquals(2, f.commits)
        f.session.close()
    }

    @Test fun commitThatLosesOwnershipCannotSubmitAndKeepsTheDraft() {
        val f = Fixture()
        f.capture()
        f.callbacks.single()(VoiceDraftPreparation(commit = { f.session.pause() }))
        assertEquals(VoicePhase.PAUSED, f.session.state.phase)
        f.assertDraft()
        f.session.close()
    }

    @Test fun aStopFromTheThinkingTransitionStillPreventsThePreparedSubmission() {
        val audio = Audio()
        val sent = mutableListOf<String>()
        var prepared: ((VoiceDraftPreparation) -> Unit)? = null
        lateinit var session: VoiceSession
        session = VoiceSession(audio, {
            sent += it
            true
        }, { if (it.phase == VoicePhase.THINKING) session.pause() })
        session.access(true, true, true)
        session.prepareDraft = { _, complete -> prepared = complete }
        session.listenForConversation()
        audio.listeners.single().final("Keep this question?")
        requireNotNull(prepared)(VoiceDraftPreparation())
        assertTrue(sent.isEmpty())
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        assertTrue(session.state.hasDraft)
        assertFalse(session.state.awaitingReply)
        session.close()
    }

    @Test fun restoredReviewUsesPreparationAndCompletedSpeechCanListenForTheNextTurn() {
        val f = Fixture()
        f.session.restore("What time is it?", "", review = true, pending = false)
        f.session.sendDraft()
        f.assertDraft()
        f.prepared()
        assertEquals(listOf("What time is it?"), f.sent)
        f.session.reply("restored", "Quelle heure est-il ?")
        requireNotNull(f.audio.completed).invoke()
        assertEquals(VoicePhase.PAUSED, f.session.state.phase)
        assertTrue(f.audio.listeners.isEmpty())
        f.session.close()

        val conversation = Fixture()
        conversation.capture()
        conversation.prepared()
        conversation.session.reply("first", "Quelle heure est-il ?")
        requireNotNull(conversation.audio.completed).invoke()
        assertEquals(2, conversation.audio.listeners.size)
        conversation.audio.listeners.last().final("Où est la gare ?")
        assertEquals(VoicePhase.FINALIZING, conversation.session.state.phase)
        assertEquals(1, conversation.sent.size)
        conversation.prepared(detected = "fr")
        assertEquals("en-US", conversation.submittedTargets.last()?.targetLanguage)
        assertEquals(2, conversation.sent.size)
        assertNull(conversation.session.state.notice)
        conversation.session.close()
    }
}
