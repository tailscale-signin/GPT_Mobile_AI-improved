package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGenerationSupersessionTest {
    private class Audio(override val streamsReplies: Boolean) : VoiceAudio {
        class Output(val complete: () -> Unit, val error: (String) -> Unit) {
            val text = StringBuilder()
        }
        val outputs = mutableListOf<Output>()
        val stops = mutableListOf<String>()
        var captures = 0
        override fun listen(listener: VoiceAudio.Listener) {
            captures++
            listener.ready()
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            outputs += Output(complete, error).also { it.text.append(text) }
        }
        override fun openReply(complete: () -> Unit, error: (String) -> Unit): VoiceReplyPlayback? {
            if (!streamsReplies) return null
            val output = Output(complete, error).also { outputs += it }
            return object : VoiceReplyPlayback {
                override fun enqueue(text: String) {
                    output.text.append(text)
                }
                override fun finish() = Unit
            }
        }
        override fun stop() = stop("audio_stop")
        override fun stop(reason: String) {
            stops += reason
        }
        override fun close() = Unit
    }

    @Test fun replacementRetiresCompletedButDrainingAudioWithoutFailureOrMicrophoneRestart() {
        for (streams in listOf(false, true)) exerciseReplacement(streams, completed = true)
    }

    @Test fun replacementRetiresStreamingAudioAndFencesItsRemainingCallbacks() {
        for (streams in listOf(false, true)) exerciseReplacement(streams, completed = false)
    }

    @Test fun regeneratingAManuallyReadLatestRevisionRetiresItsAudioAndFencesCallbacks() {
        for (streams in listOf(false, true)) {
            val audio = Audio(streams)
            val session = VoiceSession(audio, { true }, {}).apply { autoContinue = true }
            val playback = VoiceMessagePlayback()
            val original = VoiceMessageReply(VoiceMessageIdentity(1, "old"), "The old response is complete.", complete = true)
            session.access(true, true, true)
            playback.observe(listOf(original), autoplay = true)
            playback.manual(original.identity)
            session.beginMessage(original.identity.key, manual = true)
            session.reply(original.identity.key, original.text)
            val oldOutput = audio.outputs.single()

            val replacement = VoiceMessageReply(VoiceMessageIdentity(1, "new"), "The regenerated response is complete.", complete = true)
            playback.observe(listOf(replacement), autoplay = true)
            val retired = requireNotNull(playback.retireSuperseded())
            assertEquals(original.identity, retired)
            session.supersedeMessage(retired.key)
            assertEquals(VoicePhase.PAUSED, session.state.phase)
            assertNull(session.state.notice)
            assertEquals(0, audio.captures)
            requireNotNull(playback.next()).let { session.beginMessage(it.identity.key) }
            requireNotNull(playback.update()).let { session.reply(it.identity.key, it.text) }
            val current = session.state
            oldOutput.complete()
            oldOutput.error("Retired manual replay")
            session.reply(original.identity.key, original.text)
            assertEquals(current, session.state)
            assertEquals(VoicePhase.SPEAKING, session.state.phase)
            assertEquals(replacement.text, session.state.reply)
            assertEquals(0, audio.captures)
        }
    }

    @Test fun anExplicitHistoricalRevisionKeepsPlayingWhenTheLatestGenerationChanges() {
        for (streams in listOf(false, true)) {
            val audio = Audio(streams)
            val session = VoiceSession(audio, { true }, {})
            val playback = VoiceMessagePlayback()
            val original = VoiceMessageReply(VoiceMessageIdentity(1, "latest"), "The latest response.", complete = true)
            val historical = VoiceMessageIdentity(1, "historical", revision = 0)
            session.access(true, true, true)
            playback.observe(listOf(original), autoplay = true)
            playback.manual(historical)
            session.beginMessage(historical.key, manual = true)
            session.reply(historical.key, "The explicitly selected historical response.")
            val historicalOutput = audio.outputs.single()
            val historicalState = session.state
            val stops = audio.stops.size

            val replacement = VoiceMessageReply(VoiceMessageIdentity(1, "new"), "The regenerated response is complete.", complete = true)
            playback.observe(listOf(replacement), autoplay = true)
            assertNull(playback.retireSuperseded())
            assertNull(playback.next())
            assertEquals(historicalState, session.state)
            assertEquals(stops, audio.stops.size)
            historicalOutput.complete()
            playback.finish()
            requireNotNull(playback.next()).let { session.beginMessage(it.identity.key) }
            requireNotNull(playback.update()).let { session.reply(it.identity.key, it.text) }
            val current = session.state
            historicalOutput.complete()
            historicalOutput.error("Retired historical replay")
            assertEquals(current, session.state)
            assertEquals(replacement.text, session.state.reply)
            assertEquals(0, audio.captures)
        }
    }

    private fun exerciseReplacement(streams: Boolean, completed: Boolean) {
        val audio = Audio(streams)
        val session = VoiceSession(audio, { true }, {}).apply { autoContinue = true }
        val playback = VoiceMessagePlayback()
        session.access(true, true, true)
        playback.observe(emptyList(), autoplay = true)
        val original = VoiceMessageReply(VoiceMessageIdentity(1, "old"), "These are four words ", complete = completed)
        playback.observe(listOf(original), autoplay = true)
        requireNotNull(playback.next()).let { session.beginMessage(it.identity.key, diagnosticRunId = it.identity.runId) }
        requireNotNull(playback.update()).let {
            if (it.complete) session.reply(it.identity.key, it.text) else session.streamReply(it.identity.key, it.text)
        }
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        assertTrue(session.state.awaitingReply)
        val oldOutput = audio.outputs.single()

        val replacement = VoiceMessageReply(VoiceMessageIdentity(1, "new"), "The replacement response is ready.", complete = true)
        playback.observe(listOf(replacement), autoplay = true)
        session.supersedeMessage(requireNotNull(playback.retireSuperseded()).key)
        assertEquals("message_superseded", audio.stops.last())
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        assertFalse(session.state.awaitingReply)
        assertNull(session.state.notice)
        assertEquals(0, audio.captures)
        oldOutput.complete()
        oldOutput.error("A stale failure")
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        assertNull(session.state.notice)

        requireNotNull(playback.next()).let { session.beginMessage(it.identity.key, diagnosticRunId = it.identity.runId) }
        requireNotNull(playback.update()).let { session.reply(it.identity.key, it.text) }
        val replacementState = session.state
        assertEquals(VoicePhase.SPEAKING, replacementState.phase)
        assertTrue(replacementState.awaitingReply)
        assertEquals(replacement.text, audio.outputs.last().text.toString())
        oldOutput.complete()
        oldOutput.error("A stale failure")
        session.streamReply(original.identity.key, original.text + "from the retired generation.")
        session.reply(original.identity.key, original.text + "from the retired generation.")
        session.supersedeMessage(original.identity.key)
        assertEquals(replacementState, session.state)
        assertEquals(0, audio.captures)
        assertEquals(2, audio.outputs.size)
    }
}
