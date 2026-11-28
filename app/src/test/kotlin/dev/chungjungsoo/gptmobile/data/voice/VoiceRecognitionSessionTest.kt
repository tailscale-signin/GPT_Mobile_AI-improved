package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognitionSessionTest {
    private class Audio : VoiceAudio {
        val listeners = mutableListOf<VoiceAudio.Listener>()
        override fun listen(listener: VoiceAudio.Listener) {
            listeners += listener
            listener.ready()
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    private fun transcript(revision: Long, final: Boolean = false, stream: Long = 7, text: String = "hello world") = VoiceRecognitionTranscript.fromTokens(
        text, listOf("hello", "▁world"), listOf(0.25, 0.5), 0.0, stream, revision, final, 16_000, 16_000
    )

    private fun start(audio: Audio): VoiceSession = VoiceSession(audio, { true }, {}).apply {
        editBeforeSend = true
        access(true, true, true)
        resume()
    }

    @Test fun timedRevisionsReachTheInputCaptionWithoutBecomingPlaybackIntervals() {
        val audio = Audio()
        val session = start(audio)
        val listener = audio.listeners.single()
        listener.partialTranscript(transcript(2))
        assertEquals("hello world", session.state.transcript)
        assertEquals(4_000L, session.state.recognitionTranscript?.timings?.first()?.startFrame)
        assertNull(session.state.recognitionTranscript?.timings?.first()?.endFrame)
        assertNull(session.state.spokenRange)
        assertNull(session.state.playbackCaption)
        listener.partialTranscript(transcript(1, text = "stale words"))
        listener.partialTranscript(transcript(3, stream = 8, text = "foreign words"))
        assertEquals("hello world", session.state.transcript)
        listener.finalTranscript(transcript(4, final = true))
        assertEquals(VoicePhase.REVIEWING, session.state.phase)
        assertTrue(requireNotNull(session.state.recognitionTranscript).isFinal)
        assertEquals(8_000L, session.state.recognitionTranscript?.timings?.last()?.startFrame)
        listener.partialTranscript(transcript(5, text = "late words"))
        assertEquals("hello world", session.state.transcript)
    }

    @Test fun cancellationFencesTimedCallbacksAndTheNextCaptureGetsAnIndependentStream() {
        val audio = Audio()
        val session = start(audio)
        val previous = audio.listeners.single()
        previous.partialTranscript(transcript(1))
        session.pause()
        previous.finalTranscript(transcript(2, final = true))
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        session.discardDraft()
        session.resume()
        assertNull(session.state.recognitionTranscript)
        val next = audio.listeners.last()
        previous.partialTranscript(transcript(3))
        assertEquals("", session.state.transcript)
        next.partialTranscript(transcript(1, stream = 9))
        assertEquals(9L, session.state.recognitionTranscript?.streamId)
    }

    @Test fun wrongCallbackTypeCannotPoisonTheRevisionLedger() {
        val audio = Audio()
        val session = start(audio)
        val listener = audio.listeners.single()
        listener.partialTranscript(transcript(10, final = true))
        listener.finalTranscript(transcript(11, final = false))
        listener.partialTranscript(transcript(1))
        assertEquals(1L, session.state.recognitionTranscript?.revision)
        assertEquals(VoicePhase.LISTENING, session.state.phase)
    }

    @Test fun editingRecognizedWordsInvalidatesTheirTimingsWithoutChangingTheDraft() {
        val audio = Audio()
        val session = start(audio)
        audio.listeners.single().finalTranscript(transcript(1, final = true))
        session.editDraft("hello replacement")
        assertEquals("hello replacement", session.state.transcript)
        assertTrue(requireNotNull(session.state.recognitionTranscript).timings.isEmpty())
        assertEquals(VoiceRecognitionTimingIssue.SOURCE_MISMATCH, session.state.recognitionTranscript?.timingIssue)
        assertTrue(session.state.hasDraft)
    }

    @Test fun untimedCallbacksCannotInheritAStaleWordTimeline() {
        val audio = Audio()
        val session = start(audio)
        val listener = audio.listeners.single()
        listener.partialTranscript(transcript(1))
        listener.partial("plain correction")
        assertNull(session.state.recognitionTranscript)
        listener.final("plain correction")
        assertEquals("plain correction", session.state.transcript)
        assertNull(session.state.recognitionTranscript)
    }

    @Test fun terminalTrimmingPreservesInputFrameCoordinates() {
        val audio = Audio()
        val session = start(audio)
        val timed = transcript(1, final = true, text = "  hello world  ")
        audio.listeners.single().finalTranscript(timed)
        val result = requireNotNull(session.state.recognitionTranscript)
        assertEquals("hello world", result.text)
        assertEquals(0, result.timings.first().startChar)
        assertEquals(4_000L, result.timings.first().startFrame)
        assertFalse(result.timings.isEmpty())
    }

    @Test fun asynchronousTranslationPreparationRetainsTheExactCapturedTimeline() {
        val audio = Audio()
        val sent = mutableListOf<String>()
        var complete: ((VoiceDraftPreparation) -> Unit)? = null
        val session = VoiceSession(audio, {
            sent += it
            true
        }, {}).apply {
            translationOnly = true
            prepareDraft = { _, callback -> complete = callback }
            access(true, true, true)
            resume()
        }
        audio.listeners.single().finalTranscript(transcript(1, final = true))
        assertEquals(VoicePhase.FINALIZING, session.state.phase)
        assertTrue(sent.isEmpty())
        val timing = requireNotNull(session.state.recognitionTranscript)
        val source = requireNotNull(session.state.translationPrompt)
        requireNotNull(complete)(VoiceDraftPreparation())
        assertEquals(VoicePhase.THINKING, session.state.phase)
        assertEquals(listOf("hello world"), sent)
        assertEquals(timing, session.state.recognitionTranscript)
        assertEquals(source, session.state.translationPrompt)
        assertEquals(4_000L, timing.timings.first().startFrame)
        assertNull(timing.timings.first().endFrame)
        assertNull(session.state.spokenRange)
        assertNull(session.state.playbackCaption)
        session.close()
    }

    @Test fun editingDuringTranslationPreparationFencesTheOldCommitAndInvalidatesItsTimings() {
        val audio = Audio()
        val sent = mutableListOf<String>()
        val callbacks = mutableListOf<(VoiceDraftPreparation) -> Unit>()
        var commits = 0
        val session = VoiceSession(audio, {
            sent += it
            true
        }, {}).apply {
            translationOnly = true
            prepareDraft = { _, callback -> callbacks += callback }
            access(true, true, true)
            resume()
        }
        audio.listeners.single().finalTranscript(transcript(1, final = true))
        val capture = requireNotNull(session.state.translationPrompt).captureId
        session.editDraft("hello replacement")
        assertEquals(VoicePhase.REVIEWING, session.state.phase)
        assertTrue(session.state.hasDraft)
        assertEquals("hello replacement", session.state.recognitionTranscript?.text)
        assertTrue(requireNotNull(session.state.recognitionTranscript).timings.isEmpty())
        assertEquals(VoiceRecognitionTimingIssue.SOURCE_MISMATCH, session.state.recognitionTranscript?.timingIssue)
        callbacks[0](VoiceDraftPreparation(commit = { commits++ }))
        assertEquals(0, commits)
        assertTrue(sent.isEmpty())
        session.sendDraft()
        callbacks[0](VoiceDraftPreparation(commit = { commits++ }))
        assertEquals(0, commits)
        callbacks[1](VoiceDraftPreparation(commit = { commits++ }))
        assertEquals(1, commits)
        assertEquals(listOf("hello replacement"), sent)
        assertEquals(VoiceTranslationPrompt(capture, "hello replacement"), session.state.translationPrompt)
        assertTrue(requireNotNull(session.state.recognitionTranscript).timings.isEmpty())
        session.close()
    }

    @Test fun explicitSendTextCannotCarryTimingForDifferentRecognizedWords() {
        for (prepare in listOf(false, true)) {
            val audio = Audio()
            val sent = mutableListOf<String>()
            var complete: ((VoiceDraftPreparation) -> Unit)? = null
            val session = VoiceSession(audio, {
                sent += it
                true
            }, {}).apply {
                translationOnly = true
                editBeforeSend = true
                if (prepare) prepareDraft = { _, callback -> complete = callback }
                access(true, true, true)
                resume()
            }
            audio.listeners.single().finalTranscript(transcript(1, final = true))
            session.sendDraft("different supplied words")
            assertEquals("different supplied words", session.state.transcript)
            assertEquals(session.state.transcript, session.state.recognitionTranscript?.text)
            assertTrue(requireNotNull(session.state.recognitionTranscript).timings.isEmpty())
            assertEquals(VoiceRecognitionTimingIssue.SOURCE_MISMATCH, session.state.recognitionTranscript?.timingIssue)
            if (prepare) {
                assertEquals(VoicePhase.FINALIZING, session.state.phase)
                requireNotNull(complete)(VoiceDraftPreparation())
            }
            assertEquals(listOf("different supplied words"), sent)
            assertEquals(VoicePhase.THINKING, session.state.phase)
            assertEquals(session.state.transcript, session.state.recognitionTranscript?.text)
            session.close()
        }
    }
}
