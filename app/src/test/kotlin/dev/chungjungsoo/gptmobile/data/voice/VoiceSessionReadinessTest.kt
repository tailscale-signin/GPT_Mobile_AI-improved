package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionReadinessTest {
    private class Audio : VoiceAudio {
        val listeners = mutableListOf<VoiceAudio.Listener>()
        val completions = mutableListOf<() -> Unit>()
        val spoken = mutableListOf<String>()
        val stops = mutableListOf<String>()
        val interruptionSettings = mutableListOf<Boolean>()
        var started: (() -> Unit)? = null
        var interrupted: (() -> Unit)? = null
        override val reportsPlaybackStart = true
        override fun bindPlaybackStarted(started: (() -> Unit)?) {
            this.started = started
        }
        override fun configureInterruption(enabled: Boolean, interrupted: (() -> Unit)?) {
            interruptionSettings += enabled
            this.interrupted = interrupted
        }
        override fun listen(listener: VoiceAudio.Listener) {
            listeners += listener
            listener.ready()
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            spoken += text
            completions += complete
        }
        override fun stop() = stop("unspecified")
        override fun stop(reason: String) {
            stops += reason
        }
        override fun close() = Unit
    }

    private class Fixture {
        val audio = Audio()
        val submitted = mutableListOf<String>()
        val session = VoiceSession(audio, {
            submitted += it
            true
        }, {}).apply { access(true, true, true) }

        fun capture() {
            session.listenForConversation()
            audio.listeners.last().final("Translate this sentence.")
        }

        fun acceptedReply(audible: Boolean = true) {
            capture()
            assertTrue(session.beginMessage("accepted-reply"))
            session.reply("accepted-reply", "Bonjour.")
            assertEquals(VoicePhase.PREPARING, session.state.phase)
            // The foreground playback service owns this exact admitted reply.
            session.backgroundPlayback = true
            if (audible) requireNotNull(audio.started).invoke()
        }
    }

    @Test fun restoringPreferencesKeepsTheAdmittedQueueAndDoesNotChangeForegroundPolicy() {
        for (audible in listOf(false, true)) {
            val f = Fixture()
            f.acceptedReply(audible)
            f.session.access(true, true, false)
            val phase = f.session.state.phase
            val presentation = f.session.state.captionPresentationId
            val stops = f.audio.stops.size
            f.session.access(true, true, true, readyForNewWork = false)
            assertEquals(phase, f.session.state.phase)
            assertEquals(presentation, f.session.state.captionPresentationId)
            assertEquals(stops, f.audio.stops.size)
            assertEquals(1, f.audio.spoken.size)
            assertTrue(f.session.state.awaitingReply)
            assertFalse(f.session.beginMessage("another-reply", manual = true))
            assertNull(f.session.playbackPreparationOwner())

            // An existing background caller must preserve the pending readiness value.
            f.session.access(true, false, false)
            assertEquals(phase, f.session.state.phase)
            assertEquals(stops, f.audio.stops.size)
            f.session.access(true, true, true)
            assertFalse(f.session.beginMessage("still-not-ready", manual = true))
            f.session.access(true, true, true, readyForNewWork = true)
            assertEquals(phase, f.session.state.phase)
            assertEquals(stops, f.audio.stops.size)
            f.session.close()
        }
    }

    @Test fun acceptedCompletionAndLateCallbacksCannotStartListeningUntilReady() {
        for (autoContinue in listOf(false, true)) {
            val f = Fixture()
            f.acceptedReply(audible = false)
            f.session.autoContinue = autoContinue
            val started = requireNotNull(f.audio.started)
            val complete = f.audio.completions.single()
            f.session.access(true, true, true, readyForNewWork = false)
            started()
            assertEquals(VoicePhase.SPEAKING, f.session.state.phase)
            complete()
            assertEquals(VoicePhase.PAUSED, f.session.state.phase)
            assertFalse(f.session.state.awaitingReply)
            complete()
            started()
            f.session.tick()
            f.session.resume()
            f.session.listenForConversation()
            assertEquals(1, f.audio.listeners.size)
            assertEquals(1, f.submitted.size)
            assertNull(f.session.playbackPreparationOwner())

            f.session.access(true, true, true, readyForNewWork = true)
            assertEquals(1, f.audio.listeners.size)
            f.session.listenForConversation()
            assertEquals(2, f.audio.listeners.size)
            f.session.close()
        }
    }

    @Test fun theSameStreamingOwnerCanReceiveItsRemainingWordsDuringHydration() {
        val f = Fixture()
        f.capture()
        assertTrue(f.session.beginMessage("accepted-reply"))
        val first = "Here is the first complete translated sentence with enough words to speak. "
        f.session.streamReply("accepted-reply", first)
        f.session.backgroundPlayback = true
        var delivered = 0
        while (delivered < f.audio.completions.size) f.audio.completions[delivered++]()
        assertEquals(VoicePhase.THINKING, f.session.state.phase)
        assertTrue(f.session.state.awaitingReply)
        val stops = f.audio.stops.size
        f.session.access(true, true, true, readyForNewWork = false)
        assertEquals(VoicePhase.THINKING, f.session.state.phase)
        assertEquals(stops, f.audio.stops.size)
        assertFalse(f.session.beginMessage("different-owner"))
        val final = first + "This is the final translated sentence."
        f.session.reply("accepted-reply", final)
        while (delivered < f.audio.completions.size) f.audio.completions[delivered++]()
        assertEquals(final, f.session.state.reply)
        assertEquals(VoicePhase.PAUSED, f.session.state.phase)
        assertEquals(1, f.audio.listeners.size)
        assertEquals(1, f.submitted.size)
        f.session.close()
    }

    @Test fun anUnreadyScreenCannotPrepareOrSubmitRestoredTextOrReplay() {
        val f = Fixture()
        var preparations = 0
        f.session.prepareDraft = { _, _ -> preparations++ }
        f.session.access(true, true, true, readyForNewWork = false)
        f.session.restore("Saved source text", "Saved reply", review = true, pending = false)
        f.session.sendDraft()
        assertEquals(0, preparations)
        assertTrue(f.submitted.isEmpty())
        assertTrue(f.session.state.hasDraft)
        f.session.discardDraft()
        assertNull(f.session.playbackPreparationOwner())
        assertFalse(f.session.beginMessage("manual-replay", manual = true))
        f.session.replay()
        f.session.listenForConversation()
        assertTrue(f.audio.spoken.isEmpty())
        assertTrue(f.audio.listeners.isEmpty())
        f.session.access(true, false, false)
        f.session.access(true, true, true)
        f.session.listenForConversation()
        assertTrue(f.audio.listeners.isEmpty())
        f.session.access(true, true, true, readyForNewWork = true)
        assertTrue(f.audio.listeners.isEmpty())
        f.session.listenForConversation()
        assertEquals(1, f.audio.listeners.size)
        f.session.close()
    }

    @Test fun readinessLossRetiresDraftAndReplayPreparationsBeforeReadinessReturns() {
        val f = Fixture()
        var completion: ((VoiceDraftPreparation) -> Unit)? = null
        var commits = 0
        f.session.prepareDraft = { _, complete -> completion = complete }
        f.capture()
        assertEquals(VoicePhase.FINALIZING, f.session.state.phase)
        f.session.access(true, true, true, readyForNewWork = false)
        assertEquals(VoicePhase.PAUSED, f.session.state.phase)
        assertTrue(f.session.state.hasDraft)
        f.session.access(true, true, true, readyForNewWork = true)
        requireNotNull(completion).invoke(VoiceDraftPreparation(commit = { commits++ }))
        assertEquals(0, commits)
        assertTrue(f.submitted.isEmpty())
        f.session.discardDraft()
        val replay = f.session.playbackPreparationOwner()
        assertNotNull(replay)
        assertTrue(f.session.ownsPlaybackPreparation(replay))
        f.session.access(true, true, true, readyForNewWork = false)
        f.session.access(true, true, true, readyForNewWork = true)
        assertFalse(f.session.ownsPlaybackPreparation(replay))
        f.session.close()
    }

    @Test fun readinessDoesNotPreserveUnownedOutputOrAnUnadmittedRequest() {
        val unowned = Fixture()
        unowned.acceptedReply()
        unowned.session.backgroundPlayback = false
        unowned.session.access(true, true, true, readyForNewWork = false)
        assertEquals(VoicePhase.PAUSED, unowned.session.state.phase)
        assertEquals("screen_not_ready", unowned.audio.stops.last())
        unowned.session.close()

        val pending = Fixture()
        pending.capture()
        pending.session.backgroundPlayback = true
        pending.session.access(true, true, true, readyForNewWork = false)
        assertEquals(VoicePhase.PAUSED, pending.session.state.phase)
        pending.session.reply("not-yet-admitted", "No new output may start.")
        assertTrue(pending.audio.spoken.isEmpty())
        pending.session.close()
    }

    @Test fun actualRevocationStillStopsRetainedAudioAndFencesItsCallbacks() {
        val revoke: List<(VoiceSession) -> Unit> = listOf(
            { it.access(false, true, true, readyForNewWork = false) },
            { it.holdAudio(true) },
            {
                it.backgroundPlayback = false
                it.access(true, true, false, readyForNewWork = false)
            },
            VoiceSession::stopTurn,
            VoiceSession::close
        )
        for (stop in revoke) {
            val f = Fixture()
            f.acceptedReply()
            val complete = f.audio.completions.single()
            val stops = f.audio.stops.size
            f.session.access(true, true, true, readyForNewWork = false)
            stop(f.session)
            assertTrue(f.session.state.phase in setOf(VoicePhase.PAUSED, VoicePhase.CLOSED))
            assertTrue(f.audio.stops.size > stops)
            complete()
            assertEquals(1, f.audio.listeners.size)
            assertEquals(1, f.audio.spoken.size)
            assertEquals(1, f.submitted.size)
            f.session.close()
        }
    }

    @Test fun permissionLossStillRevokesCaptureAndPendingDraftPreparation() {
        for (prepare in listOf(false, true)) {
            val f = Fixture()
            var completion: ((VoiceDraftPreparation) -> Unit)? = null
            var commits = 0
            f.session.prepareDraft = { _, complete -> completion = complete }
            f.session.listenForConversation()
            if (prepare) f.audio.listeners.last().final("Captured source text")
            f.session.access(true, false, true, readyForNewWork = true)
            assertEquals(VoicePhase.PAUSED, f.session.state.phase)
            assertEquals("permission_changed", f.audio.stops.last())
            completion?.invoke(VoiceDraftPreparation(commit = { commits++ }))
            f.audio.listeners.last().final("Late recognition")
            assertEquals(0, commits)
            assertTrue(f.submitted.isEmpty())
            f.session.close()
        }
    }

    @Test fun microphoneInterruptionMonitoringFollowsReadinessAndCaptureAuthorization() {
        val f = Fixture()
        f.acceptedReply()
        f.session.configureInterruption(true)
        assertTrue(f.audio.interruptionSettings.last())
        val oldInterruption = requireNotNull(f.audio.interrupted)
        f.session.access(true, true, false)
        assertFalse(f.audio.interruptionSettings.last())
        f.session.access(true, true, true, readyForNewWork = false)
        assertFalse(f.audio.interruptionSettings.last())
        oldInterruption()
        assertEquals(VoicePhase.SPEAKING, f.session.state.phase)
        assertEquals(1, f.audio.listeners.size)
        f.session.access(true, true, true, readyForNewWork = true)
        assertTrue(f.audio.interruptionSettings.last())
        f.session.access(true, false, true)
        assertFalse(f.audio.interruptionSettings.last())
        assertEquals(VoicePhase.SPEAKING, f.session.state.phase)
        f.session.access(true, true, true)
        assertTrue(f.audio.interruptionSettings.last())
        f.session.mute()
        assertFalse(f.audio.interruptionSettings.last())
        f.session.mute()
        assertTrue(f.audio.interruptionSettings.last())
        f.session.holdAudio(true)
        assertFalse(f.audio.interruptionSettings.last())
        assertEquals(VoicePhase.PAUSED, f.session.state.phase)
        f.session.close()
    }
}
