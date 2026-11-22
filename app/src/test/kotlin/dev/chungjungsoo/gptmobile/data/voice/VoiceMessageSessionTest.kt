package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.presentation.ui.voice.VoiceCaption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMessageSessionTest {
    private class Audio(override val reportsPlaybackStart: Boolean = false) : VoiceAudio {
        val spoken = mutableListOf<String>()
        val completed = mutableListOf<() -> Unit>()
        var captures = 0
        var stops = 0
        var listener: VoiceAudio.Listener? = null
        var spokenRange: ((Int, String, IntRange) -> Unit)? = null
        var playbackStarted: (() -> Unit)? = null
        override fun bindPlaybackStarted(started: (() -> Unit)?) {
            playbackStarted = started
        }
        override fun bindSpokenRange(spoken: ((Int, String, IntRange) -> Unit)?) {
            spokenRange = spoken
        }
        override fun listen(listener: VoiceAudio.Listener) {
            captures++
            this.listener = listener
            listener.ready()
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            spoken += text
            completed += complete
        }
        override fun stop() {
            stops++
        }
        override fun close() = Unit
    }

    @Test fun captionPresentationSurvivesPreviewFirstAudioChunksAndPauseButNotAnExplicitReplay() {
        val audio = Audio(reportsPlaybackStart = true)
        val session = VoiceSession(audio, { error("Typed playback must not send a prompt") }, {})
        session.access(true, false, true)
        session.beginMessage("reply")
        val owner = session.presentationStates.value.captionPresentationId
        assertTrue(owner > 0)
        val opening = "The first complete sentence. "
        session.streamReply("reply", opening)
        assertEquals(VoicePhase.PREPARING, session.state.phase)
        assertEquals(owner, session.presentationStates.value.captionPresentationId)
        requireNotNull(audio.playbackStarted).invoke()
        requireNotNull(audio.spokenRange).invoke(0, opening, 10..17)
        assertEquals(owner, session.presentationStates.value.captionPresentationId)
        session.reply("reply", opening + "The ending.")
        audio.completed.first().invoke()
        requireNotNull(audio.playbackStarted).invoke()
        assertEquals(owner, session.presentationStates.value.captionPresentationId)
        session.pause()
        assertEquals(owner, session.presentationStates.value.captionPresentationId)

        // Observe only the final snapshot, as a StateFlow/Compose collector may do.
        session.beginMessage("reply", manual = true)
        session.reply("reply", opening + "The ending.")
        val replay = session.presentationStates.value
        assertEquals(VoicePhase.PREPARING, replay.phase)
        assertTrue("An identical replay still needs a new reader owner", replay.captionPresentationId != owner)
        val replayOwner = replay.captionPresentationId
        session.beginMessage("next")
        session.streamReply("next", opening)
        assertTrue("Batched replacement cannot retain the previous reader", session.presentationStates.value.captionPresentationId != replayOwner)
    }

    @Test fun completedOneWordReplyRemainsVisibleWhileItsAudioIsStillDraining() {
        val audio = Audio()
        val session = VoiceSession(audio, { error("Manual replay must not send a prompt") }, {})
        session.access(true, false, true)
        assertTrue(session.beginMessage("reply", manual = true))
        session.reply("reply", "Yes.")

        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        assertTrue(session.state.awaitingReply)
        assertTrue(session.state.replyComplete)
        val caption = requireNotNull(VoiceCaption.from(session.state))
        assertEquals("Yes.", caption.text)
        assertTrue(caption.live)
        assertNull(caption.highlightRange(session.state.spokenRange))

        requireNotNull(audio.spokenRange).invoke(0, "Yes.", 0..2)
        assertEquals(0..2, VoiceCaption.from(session.state)?.highlightRange(session.state.spokenRange))
        audio.completed.single().invoke()
        assertFalse(session.state.awaitingReply)
        assertEquals("Yes.", VoiceCaption.from(session.state)?.text)

        assertTrue(session.beginMessage("next", manual = true))
        assertFalse(session.state.replyComplete)
        assertNull(VoiceCaption.from(session.state))
    }

    @Test fun finalWordCallbackMapsIntoTheCompleteCaptionBeforePlaybackFinishes() {
        val audio = Audio()
        val session = VoiceSession(audio, { error("Manual playback must not send a prompt") }, {})
        session.access(true, false, true)
        session.beginMessage("reply", manual = true)
        val opening = "First complete sentence. "
        session.streamReply("reply", opening)
        assertEquals(listOf(opening), audio.spoken)
        assertFalse(session.state.replyComplete)
        requireNotNull(audio.spokenRange).invoke(0, opening, 0..4)

        val ending = "Final word."
        session.reply("reply", opening + ending)
        assertTrue(session.state.replyComplete)
        assertTrue(session.state.awaitingReply)
        assertEquals(opening + ending, VoiceCaption.from(session.state)?.text)
        audio.completed.first().invoke()
        assertEquals(listOf(opening, ending), audio.spoken)

        requireNotNull(audio.spokenRange).invoke(0, ending, 6..9)
        val expected = opening.length + 6..opening.length + 9
        val caption = requireNotNull(VoiceCaption.from(session.state))
        assertTrue(session.state.awaitingReply)
        assertEquals(expected, session.state.spokenRange)
        assertEquals(expected, caption.highlightRange(session.state.spokenRange))
        assertEquals("word", caption.text.substring(requireNotNull(caption.highlightRange(session.state.spokenRange))))
    }

    @Test fun manualAndAutomaticOutputDoNotRequireRecordingPermission() {
        for (manual in listOf(false, true)) {
            val audio = Audio()
            val session = VoiceSession(audio, { true }, {})
            session.access(true, false, true)
            assertTrue(session.beginMessage("reply", manual))
            session.reply("reply", "Here is the answer.")
            assertEquals("Here is the answer.", audio.spoken.joinToString(""))
            assertEquals(0, audio.captures)
            audio.completed.last()()
            session.resume()
            assertEquals(0, audio.captures)
        }
    }

    @Test fun manualReplayWorksWhileAutomaticReadAloudIsOff() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {})
        session.access(true, false, true)
        session.speaker()
        session.beginMessage("automatic")
        session.reply("automatic", "Do not autoplay this.")
        assertTrue(audio.spoken.isEmpty())
        session.beginMessage("manual", manual = true)
        session.reply("manual", "Read this explicit selection.")
        assertFalse(session.state.speaker)
        assertEquals("Read this explicit selection.", audio.spoken.joinToString(""))
    }

    @Test fun stopBeforeTheFirstPhraseSuppressesAllLaterChunksAndFinalPersistence() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {})
        session.access(true, false, true)
        session.beginMessage("reply")
        session.stopSpeaking()
        session.streamReply("reply", "Here are four words ")
        session.reply("reply", "Here are four words and the ending.")
        assertTrue(audio.spoken.isEmpty())
        assertFalse(session.state.awaitingReply)
    }

    @Test fun selectingAnotherMessageFencesCompletionFromTheEarlierMessage() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {})
        session.access(true, false, true)
        session.beginMessage("first", manual = true)
        session.reply("first", "The first reply.")
        val staleCompletion = audio.completed.last()
        session.beginMessage("second", manual = true)
        session.reply("second", "The second reply.")
        staleCompletion()
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        assertEquals("The second reply.", session.state.reply)
        assertTrue(session.state.awaitingReply)
        audio.completed.last()()
        assertFalse(session.state.awaitingReply)
    }

    @Test fun selectingAnotherMessageRejectsLateTerminalRepliesFromTheEarlierRun() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {})
        session.access(true, false, true)
        session.beginMessage("first")
        session.beginMessage("second", manual = true)
        session.reply("second", "The selected reply.")
        session.reply("first", "A stale terminal reply.")
        session.reply("first", "A stale failure.", failed = true)
        assertEquals(listOf("The selected reply."), audio.spoken)
        assertEquals("The selected reply.", session.state.reply)
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
    }

    @Test fun explicitReplayOfTheSameGenerationRestartsOnlyWhenRequested() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {})
        session.access(true, false, true)
        session.beginMessage("reply")
        val opening = "Here are eight complete words before the answer "
        session.streamReply("reply", opening)
        session.stopSpeaking()
        session.beginMessage("reply", manual = true)
        session.reply("reply", "Here is the complete response.")
        assertEquals(listOf(opening, "Here is the complete response."), audio.spoken)
    }

    @Test fun microphoneRevocationLeavesOutputPlayingButBackgroundingStopsIt() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {})
        session.access(true, true, true)
        session.beginMessage("reply", manual = true)
        session.reply("reply", "Here is the reply.")
        val before = audio.stops
        session.access(true, false, true)
        assertEquals(before, audio.stops)
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        session.access(true, false, false)
        assertTrue(audio.stops > before)
        assertEquals(VoicePhase.PAUSED, session.state.phase)
    }

    @Test fun capturedReplyStillUpdatesCaptionsAndFinishesWhenAutomaticReadAloudIsOff() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {}).apply { autoContinue = true }
        val playback = VoiceMessagePlayback()
        playback.observe(emptyList(), autoplay = false)
        session.access(true, true, true)
        session.speaker()
        session.resume()
        audio.listener!!.final("Tell me the answer")
        assertTrue(session.state.awaitingReply)
        playback.observe(listOf(VoiceMessageReply(VoiceMessageIdentity(1, "run"), "Here is the complete answer.", complete = true)), autoplay = false)
        val next = requireNotNull(playback.next())
        session.beginMessage(next.identity.key, readAloud = next.readAloud)
        session.reply(next.identity.key, next.text)
        assertEquals(next.text, session.state.reply)
        assertTrue(audio.spoken.isEmpty())
        assertFalse(session.state.awaitingReply)
        assertEquals(2, audio.captures)
    }

    @Test fun replyFirstReceivedWhileBackgroundedCompletesSilentlyAfterReturning() {
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {})
        val playback = VoiceMessagePlayback()
        playback.observe(emptyList(), autoplay = true)
        session.access(true, true, true)
        session.resume()
        audio.listener!!.final("Tell me the answer")
        session.access(true, true, false)
        val final = VoiceMessageReply(VoiceMessageIdentity(1, "run"), "Here is the complete answer.", complete = true)
        playback.observe(listOf(final), autoplay = false)
        session.access(true, true, true)
        playback.observe(listOf(final), autoplay = true)
        val next = requireNotNull(playback.next())
        session.beginMessage(next.identity.key, readAloud = next.readAloud)
        session.reply(next.identity.key, next.text)
        assertEquals(final.text, session.state.reply)
        assertTrue(audio.spoken.isEmpty())
        assertFalse(session.state.awaitingReply)
    }

    @Test fun pausingCompletedAudioClearsWaitingAndRejectsItsLateDrainCallback() {
        for (manual in listOf(false, true)) {
            val audio = Audio()
            val session = VoiceSession(audio, { true }, {})
            session.access(true, false, true)
            session.beginMessage("reply", manual = manual)
            session.reply("reply", "Here is the complete answer.")
            val lateCompletion = audio.completed.last()
            assertTrue(session.state.awaitingReply)
            session.access(true, false, false)
            assertFalse(session.state.awaitingReply)
            session.access(true, false, true)
            session.resume()
            lateCompletion()
            assertEquals(VoicePhase.PAUSED, session.state.phase)
            assertEquals("Here is the complete answer.", session.state.reply)
            assertFalse(session.state.awaitingReply)
        }
    }
}
