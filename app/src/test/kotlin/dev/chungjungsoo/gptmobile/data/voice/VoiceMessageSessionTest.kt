package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMessageSessionTest {
    private class Audio : VoiceAudio {
        val spoken = mutableListOf<String>()
        val completed = mutableListOf<() -> Unit>()
        var captures = 0
        var stops = 0
        var listener: VoiceAudio.Listener? = null
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
        session.streamReply("reply", "Here are four words ")
        session.stopSpeaking()
        session.beginMessage("reply", manual = true)
        session.reply("reply", "Here is the complete response.")
        assertEquals(listOf("Here are four words ", "Here is the complete response."), audio.spoken)
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
