package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionTest {
    @Test fun `terminal trimming seals the reply and lets audio drain before the next microphone turn`() {
        audio.streamsReplies = true
        start()
        audio.listeners.last().final("Tell me more")
        session.streamReply("run", "The complete answer. ")
        val stoppedBeforeCompletion = audio.stopped
        session.reply("run", "The complete answer.")
        assertEquals(stoppedBeforeCompletion, audio.stopped)
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        assertEquals(1, audio.sealed)
        assertEquals(1, audio.listeners.size)
        session.reply("run", "The complete answer.")
        assertEquals(1, audio.sealed)
        audio.done?.invoke()
        assertEquals(2, audio.listeners.size)
    }

    private class Audio : VoiceAudio {
        val listeners = mutableListOf<VoiceAudio.Listener>()
        val spoken = mutableListOf<String>()
        var done: (() -> Unit)? = null
        var audioError: ((String) -> Unit)? = null
        var stopped = 0
        var closed = false
        override var streamsReplies = false
        var opened = 0
        var sealed = 0
        override fun openReply(complete: () -> Unit, error: (String) -> Unit): VoiceReplyPlayback? {
            if (!streamsReplies) return null
            opened++
            done = complete
            audioError = error
            return object : VoiceReplyPlayback {
                override fun enqueue(text: String) {
                    spoken += text
                }
                override fun finish() {
                    sealed++
                }
            }
        }
        override fun listen(listener: VoiceAudio.Listener) {
            listeners += listener
            listener.ready()
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            spoken += text
            done = complete
            audioError = error
        }
        override fun stop() {
            stopped++
        }
        override fun close() {
            closed = true
        }
    }
    private val audio = Audio()
    private val prompts = mutableListOf<String>()
    private val session = VoiceSession(audio, {
        prompts += it
        true
    }, {}).apply { autoContinue = true }
    private fun start() {
        session.access(true, true, true)
        session.resume()
    }

    @Test fun `admission checking and queuing preserve a turn until its accepted run replies`() {
        val drafts = mutableListOf<String>()
        val pending = VoiceSession(audio, { true }, { if (it.hasDraft) drafts += it.transcript }, awaitsAdmission = true)
        pending.access(true, true, true)
        pending.resume()
        audio.listeners.last().final("Keep my exact words")
        assertTrue(pending.state.hasDraft)
        pending.admission(VoiceAdmissionStage.CHECKING)
        pending.pause()
        pending.resume()
        assertEquals(VoicePhase.THINKING, pending.state.phase)
        assertEquals(1, audio.listeners.size)
        pending.admission(VoiceAdmissionStage.QUEUED)
        assertTrue(pending.state.awaitingReply)
        assertEquals(false, pending.state.hasDraft)
        pending.admission(VoiceAdmissionStage.ACCEPTED)
        pending.reply("accepted", "The final answer")
        assertEquals("The final answer", pending.state.reply)
        assertEquals(listOf("Keep my exact words"), drafts.distinct())
    }

    @Test fun `rejected admission keeps dictated text for an explicit retry`() {
        val pending = VoiceSession(audio, { true }, {}, awaitsAdmission = true)
        pending.access(true, true, true)
        pending.resume()
        audio.listeners.last().final("Retain this draft")
        pending.admission(VoiceAdmissionStage.FAILED, "SELECTED_MODEL_NOT_LOADED")
        assertEquals(VoicePhase.ERROR, pending.state.phase)
        assertTrue(pending.state.hasDraft)
        assertEquals(false, pending.state.awaitingReply)
        pending.resume()
        assertEquals(VoicePhase.REVIEWING, pending.state.phase)
        assertEquals("Retain this draft", pending.state.transcript)
    }

    @Test fun `stopping streamed speech waits for final text before restarting the microphone`() {
        audio.streamsReplies = true
        start()
        audio.listeners.last().final("Explain this")
        session.streamReply("accepted", "This is the first clause, ")
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        session.resume()
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        val lateCompletion = requireNotNull(audio.done)
        session.stopSpeaking()
        lateCompletion()
        assertEquals(VoicePhase.THINKING, session.state.phase)
        assertEquals(1, audio.listeners.size)
        val final = "This is the first clause, followed by the final answer."
        session.reply("accepted", final)
        assertEquals(final, session.state.reply)
        assertEquals(1, audio.opened)
        assertEquals(VoicePhase.LISTENING, session.state.phase)
        assertEquals(2, audio.listeners.size)
    }

    @Test fun `audio failure does not cut off final streamed captions or restart microphone`() {
        audio.streamsReplies = true
        start()
        audio.listeners.last().final("Explain this")
        session.streamReply("accepted", "This is the first clause, ")
        val lateCompletion = requireNotNull(audio.done)
        requireNotNull(audio.audioError)("Audio device disconnected")
        assertTrue(session.state.awaitingReply)
        lateCompletion()
        session.streamReply("accepted", "This is the first clause, followed by more text.")
        session.reply("accepted", "This is the first clause, followed by more text. Complete answer.")
        assertEquals("This is the first clause, followed by more text. Complete answer.", session.state.reply)
        assertEquals(false, session.state.awaitingReply)
        assertEquals(1, audio.listeners.size)
    }

    @Test fun `permission denial exposes app permission repair`() {
        session.permissionDenied()
        assertEquals(VoiceRecoveryAction.MICROPHONE_PERMISSION, session.state.recoveryAction)
        assertEquals(VoicePhase.ERROR, session.state.phase)
        assertTrue(audio.listeners.isEmpty())
    }

    @Test fun `disabled permissionless or background sessions cannot listen`() {
        for (flags in listOf(Triple(false, true, true), Triple(true, false, true), Triple(true, true, false))) {
            session.access(flags.first, flags.second, flags.third)
            session.resume()
        }
        assertTrue(audio.listeners.isEmpty())
    }

    @Test fun `stream playback starts at a stable first clause and enqueues the rest in one reply`() {
        audio.streamsReplies = true
        start()
        audio.listeners.last().final("Tell me more")
        session.streamReply("run", "Here is the short answer, ")
        assertEquals(listOf("Here is the short answer, "), audio.spoken)
        session.streamReply("run", "Here is the short answer, with more detail. ")
        session.reply("run", "Here is the short answer, with more detail. The last sentence.")
        assertEquals(1, audio.opened)
        assertEquals(1, audio.sealed)
        assertEquals(listOf("Here is the short answer, ", "with more detail. ", "The last sentence."), audio.spoken)
        assertEquals(1, audio.listeners.size)
        audio.done?.invoke()
        assertEquals(2, audio.listeners.size)
    }

    @Test fun `interrupt fences late callbacks from the prior reply stream`() {
        audio.streamsReplies = true
        start()
        audio.listeners.last().final("Tell me more")
        session.streamReply("run", "Here is the short answer, ")
        val stale = audio.done
        session.interrupt()
        stale?.invoke()
        assertEquals(2, audio.listeners.size)
        assertEquals(VoicePhase.LISTENING, session.state.phase)
    }

    @Test fun `partial results never submit and final callbacks submit once`() {
        start()
        val listener = audio.listeners.last()
        listener.partial("Draft speech")
        assertTrue(prompts.isEmpty())
        listener.final("  Hello  ")
        listener.final("Duplicate")
        assertEquals(listOf("Hello"), prompts)
        assertEquals(VoicePhase.THINKING, session.state.phase)
    }

    @Test fun `speech never overlaps microphone and completion starts next turn`() {
        start()
        audio.listeners.last().final("Hello")
        assertEquals(1, audio.listeners.size)
        session.reply("run-1", "Welcome")
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        assertEquals(listOf("Welcome"), audio.spoken)
        audio.done?.invoke()
        assertEquals(2, audio.listeners.size)
        assertEquals(VoicePhase.LISTENING, session.state.phase)
        session.reply("run-1", "Welcome")
        assertEquals(1, audio.spoken.size)
    }

    @Test fun `mute fences late recognition and unmute starts fresh capture`() {
        start()
        val old = audio.listeners.last()
        session.mute()
        old.final("Late private audio")
        assertTrue(prompts.isEmpty())
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        session.mute()
        assertEquals(2, audio.listeners.size)
    }

    @Test fun `microphone mute still permits the pending assistant response`() {
        start()
        audio.listeners.last().final("Hello")
        session.mute()
        session.reply("run", "Reply")
        assertEquals(listOf("Reply"), audio.spoken)
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        audio.done?.invoke()
        assertEquals(1, audio.listeners.size)
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        session.mute()
        assertEquals(2, audio.listeners.size)
    }

    @Test fun `stopping the microphone keeps partial speech for explicit review`() {
        start()
        val capture = audio.listeners.single()
        capture.partial("Keep this draft")
        session.mute()
        capture.final("Ignore this late result")
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        assertTrue(session.state.hasDraft)
        assertTrue(prompts.isEmpty())

        session.mute()
        assertEquals(VoicePhase.REVIEWING, session.state.phase)
        assertEquals("Keep this draft", session.state.transcript)
        assertEquals(1, audio.listeners.size)
        assertTrue(prompts.isEmpty())
        session.sendDraft()
        assertEquals(listOf("Keep this draft"), prompts)
    }

    @Test fun `muting during playback preserves speech and prevents automatic capture`() {
        start()
        audio.listeners.last().final("Hello")
        session.reply("run", "Reply")
        val stopped = audio.stopped
        session.mute()
        assertEquals(stopped, audio.stopped)
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        audio.done?.invoke()
        assertEquals(1, audio.listeners.size)
    }

    @Test fun `background stops playback and ignores late completion`() {
        start()
        audio.listeners.last().final("Hello")
        session.reply("run", "Reply")
        val callback = audio.done
        session.access(true, true, false)
        callback?.invoke()
        assertEquals(1, audio.listeners.size)
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        session.access(true, true, true)
        assertEquals(1, audio.listeners.size)
    }

    @Test fun `reply arriving while backgrounded stays silent`() {
        start()
        audio.listeners.last().final("Hello")
        session.access(true, true, false)
        session.reply("run", "Reply")
        assertTrue(audio.spoken.isEmpty())
        assertEquals("Reply", session.state.reply)
    }

    @Test fun `returning to foreground needs an explicit resume before audio restarts`() {
        start()
        audio.listeners.last().final("Hello")
        session.access(true, true, false)
        session.access(true, true, true)
        session.reply("run", "Reply")
        assertTrue(audio.spoken.isEmpty())
        assertEquals(1, audio.listeners.size)
        session.resume()
        assertEquals(2, audio.listeners.size)
    }

    @Test fun `speaker off keeps captions and restarts listening`() {
        start()
        session.speaker()
        audio.listeners.last().final("Hello")
        session.reply("run", "Reply")
        assertTrue(audio.spoken.isEmpty())
        assertEquals("Reply", session.state.reply)
        assertEquals(VoicePhase.LISTENING, session.state.phase)
    }

    @Test fun `interrupt invalidates old speech completion and close prevents resurrection`() {
        start()
        audio.listeners.last().final("Hello")
        session.reply("run", "Reply")
        val old = audio.done
        session.stopSpeaking()
        assertEquals(2, audio.listeners.size)
        old?.invoke()
        assertEquals(2, audio.listeners.size)
        val listener = audio.listeners.last()
        session.close()
        listener.final("Late")
        old?.invoke()
        session.mute()
        session.speaker()
        session.mute()
        session.resume()
        assertEquals(listOf("Hello"), prompts)
        assertEquals(2, audio.listeners.size)
        assertTrue(audio.closed)
        assertEquals(VoicePhase.CLOSED, session.state.phase)
    }

    @Test fun `failed dispatch and recognition error never retry automatically`() {
        val rejecting = VoiceSession(audio, { false }, {})
        rejecting.access(true, true, true)
        rejecting.resume()
        audio.listeners.last().final("Hello")
        assertEquals(VoicePhase.ERROR, rejecting.state.phase)
        assertEquals(1, audio.listeners.size)
        rejecting.discardDraft()
        rejecting.resume()
        audio.listeners.last().error("Network unavailable")
        assertEquals(2, audio.listeners.size)
        assertEquals(VoicePhase.ERROR, rejecting.state.phase)
    }

    @Test fun `chunking preserves all text and unicode boundaries`() {
        val original = "ab😀cd".repeat(2000)
        val chunks = speechChunks(original, 7)
        assertEquals(original, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 7 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
}
