package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceConversationListeningTest {
    private class Audio(override val streamsReplies: Boolean = false) : VoiceAudio {
        val listeners = mutableListOf<VoiceAudio.Listener>()
        val completed = mutableListOf<() -> Unit>()
        val failures = mutableListOf<(String) -> Unit>()
        val spoken = mutableListOf<String>()
        var sealed = false
        override fun listen(listener: VoiceAudio.Listener) {
            listeners += listener
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            spoken += text
            completed += complete
            failures += error
        }
        override fun openReply(complete: () -> Unit, error: (String) -> Unit): VoiceReplyPlayback {
            completed += complete
            failures += error
            return object : VoiceReplyPlayback {
                override fun enqueue(text: String) {
                    spoken += text
                }
                override fun finish() {
                    sealed = true
                }
            }
        }
        override fun stop() = Unit
        override fun close() = Unit
    }

    private class Fixture(streaming: Boolean = false, cueAvailable: Boolean = true) {
        val audio = Audio(streaming)
        var cues = 0
        var submissions = 0
        var now = 0L
        val session = VoiceSession(audio, {
            submissions++
            true
        }, {}, { now }).apply {
            microphoneFeedback = {
                cues++
                cueAvailable
            }
            access(true, true, true)
        }
        fun capture() {
            session.listenForConversation()
            audio.listeners.last().ready()
            audio.listeners.last().final("My next question")
        }
        fun answer() {
            session.reply("reply", "The complete spoken answer.")
        }
    }

    @Test fun completedSpeechStartsTheNextCaptureOnceAndEachReadyCaptureOwnsOneCue() {
        for (streaming in listOf(false, true)) {
            val f = Fixture(streaming)
            f.session.listenForConversation()
            assertEquals(VoicePhase.STARTING, f.session.state.phase)
            assertEquals(0, f.cues)
            f.audio.listeners.single().ready()
            f.audio.listeners.single().ready()
            assertEquals(1, f.cues)
            f.audio.listeners.single().final("My next question")
            f.answer()
            assertEquals(1, f.audio.listeners.size)
            assertEquals(VoicePhase.SPEAKING, f.session.state.phase)
            if (streaming) assertTrue(f.audio.sealed)

            val completed = f.audio.completed.single()
            completed()
            assertEquals(2, f.audio.listeners.size)
            assertEquals(VoicePhase.STARTING, f.session.state.phase)
            assertEquals(1, f.cues)
            f.audio.listeners.last().ready()
            assertEquals(2, f.cues)
            completed()
            f.audio.listeners.first().ready()
            assertEquals(2, f.audio.listeners.size)
            assertEquals(2, f.cues)
            assertEquals(1, f.submissions)
            assertFalse(f.session.autoContinue)
            f.session.close()
        }
    }

    @Test fun streamingPhrasesAndOpeningAcknowledgmentsCannotReopenTheMicrophoneEarly() {
        val f = Fixture()
        f.capture()
        f.session.beginMessage("reply")
        f.session.offerOpening("reply", VoiceThinkingCue("I am checking that."))
        f.audio.completed.single()()
        assertEquals(1, f.audio.listeners.size)
        assertEquals(VoicePhase.THINKING, f.session.state.phase)
        val partial = "Here is the first complete sentence with enough words to speak. "
        f.session.streamReply("reply", partial)
        var completion = 1
        while (completion < f.audio.completed.size) f.audio.completed[completion++]()
        assertEquals(1, f.audio.listeners.size)
        assertEquals(VoicePhase.THINKING, f.session.state.phase)
        f.session.reply("reply", partial + "This is the final sentence.")
        assertEquals(1, f.audio.listeners.size)
        while (completion < f.audio.completed.size) f.audio.completed[completion++]()
        assertEquals(2, f.audio.listeners.size)
        assertEquals(VoicePhase.STARTING, f.session.state.phase)
        f.session.close()
    }

    @Test fun stoppingPausingLosingAccessOrSupersedingSpeechFencesItsLateCompletion() {
        val stops: List<(VoiceSession) -> Unit> = listOf(
            VoiceSession::stopTurn,
            VoiceSession::stopSpeaking,
            { it.pause() },
            { it.access(true, true, false) },
            { it.access(false, true, true) },
            { it.access(true, false, true) },
            { it.holdAudio(true) },
            { it.supersedeMessage("reply") },
            VoiceSession::close
        )
        for (stop in stops) {
            val f = Fixture(streaming = true)
            f.capture()
            f.answer()
            val old = f.audio.completed.single()
            stop(f.session)
            old()
            assertEquals(1, f.audio.listeners.size)
            assertEquals(1, f.cues)
            assertEquals(1, f.submissions)
            f.session.close()
        }
    }

    @Test fun muteAndChangedReadinessPreventAutomaticCaptureAfterSpeech() {
        for (mute in listOf(false, true)) {
            val f = Fixture()
            f.capture()
            f.answer()
            if (mute) f.session.mute() else f.session.automaticListeningAvailable = { false }
            f.audio.completed.single()()
            assertEquals(1, f.audio.listeners.size)
            assertEquals(VoicePhase.PAUSED, f.session.state.phase)
            assertEquals(1, f.cues)
            f.session.close()
        }
    }

    @Test fun playbackFailuresSilentAnswersAndRecognitionErrorsDoNotBecomeAutomaticRetries() {
        val playback = Fixture()
        playback.capture()
        playback.answer()
        val old = playback.audio.completed.single()
        playback.audio.failures.single()("Output unavailable")
        old()
        assertEquals(1, playback.audio.listeners.size)
        assertEquals(VoicePhase.ERROR, playback.session.state.phase)
        playback.session.close()

        val silent = Fixture()
        silent.capture()
        silent.session.speaker()
        silent.answer()
        assertEquals(1, silent.audio.listeners.size)
        assertTrue(silent.audio.spoken.isEmpty())
        assertEquals(VoicePhase.PAUSED, silent.session.state.phase)
        silent.session.close()

        val recognition = Fixture()
        recognition.session.listenForConversation()
        recognition.audio.listeners.single().ready()
        recognition.audio.listeners.single().retryableFailure("Busy", 500)
        recognition.now = 1000
        recognition.session.tick()
        assertEquals(1, recognition.audio.listeners.size)
        assertEquals(0, recognition.submissions)
        assertEquals(VoicePhase.ERROR, recognition.session.state.phase)
        recognition.session.close()
    }

    @Test fun pendingCueCannotOutliveStopAndAnUnavailableCueNeverBlocksCapture() {
        val f = Fixture(cueAvailable = false)
        f.session.listenForConversation()
        val stale = f.audio.listeners.single()
        f.session.stopTurn()
        stale.ready()
        assertEquals(0, f.cues)
        assertEquals(VoicePhase.PAUSED, f.session.state.phase)
        f.session.listenForConversation()
        f.audio.listeners.last().ready()
        f.audio.listeners.last().ready()
        assertEquals(1, f.cues)
        assertEquals(VoicePhase.LISTENING, f.session.state.phase)
        stale.ready()
        assertEquals(1, f.cues)
        f.session.close()
    }

    @Test fun disabledSoundHasNoCueAndAnExplicitNewListenRearmsTheConversationAfterStop() {
        val f = Fixture()
        f.session.microphoneFeedback = null
        f.capture()
        f.answer()
        f.session.stopTurn()
        f.audio.completed.single()()
        assertEquals(1, f.audio.listeners.size)
        f.capture()
        f.session.reply("next", "The next answer.")
        f.audio.completed.last()()
        assertEquals(3, f.audio.listeners.size)
        assertEquals(0, f.cues)
        f.session.close()
    }
}
